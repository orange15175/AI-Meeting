package com.hewei.hzyjy.aimeeting.interview.flow.answer;

import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewProgressCommit;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import io.micrometer.core.instrument.Metrics;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 答题结果落库：把「提交终态 + 追问题 + 会话游标」放在同一个短事务里。
 *
 * <p>事务内只做数据库写入，不调用模型。任一条件不满足即整体回滚，
 * 保证不会出现"题号推进了但分数没入账"或反之的中间态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewAnswerCommitService {

    private final InterviewSubmissionStore submissionStore;
    private final InterviewQuestionStore questionStore;
    private final InterviewSessionStore sessionStore;

    public enum CommitResult {
        SUCCEEDED,
        /**
         * attemptToken 已失效（被新尝试接管），本次结果必须丢弃。
         */
        TOKEN_LOST,
        /**
         * 会话版本已被其他执行者推进，本次结果必须丢弃。
         */
        VERSION_CONFLICT
    }

    @Data
    @Builder
    public static class CommitCommand {
        private String sessionId;
        private Integer expectedVersion;
        private String submissionId;
        private String attemptToken;
        private int score;
        private String feedback;
        private String missingPointsJson;
        private String responseJson;
        private boolean finished;
        private String stage;
        private int durationMs;

        private String nextStatus;
        private String nextFlowStatus;
        private String nextQuestionNo;
        private Integer followUpCount;

        /**
         * 本次已作答的题号：会话游标条件更新要求库里仍停在这一题，防止锁过期期间的重复推进。
         */
        private String answeredQuestionNo;

        /**
         * 追加追问题目（与游标推进同一事务）。
         */
        private String followUpQuestion;
        private Integer followUpMainIndex;
        private Integer followUpIndex;
    }

    /**
     * 【关键节点：答题事务】attemptToken 校验当前执行权，version 校验会话进度。
     * 令牌失效则丢弃迟到结果；版本冲突抛异常，让已写评分和追问题随事务一起回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommitResult commit(CommitCommand command) {
        // 事务第 1 步：按 submissionId + attemptToken + PROCESSING 条件写入评分与响应快照，先确认本次仍有执行权。
        boolean marked = submissionStore.markSucceeded(
                command.getSubmissionId(),
                command.getAttemptToken(),
                command.getScore(),
                command.getFeedback(),
                command.getMissingPointsJson(),
                command.getResponseJson(),
                command.isFinished(),
                command.getStage(),
                command.getDurationMs());
        if (!marked) {
            // 0 行有多种原因（被接管 / 状态已变 / 截止已过），补一次读把原因写进日志与指标，便于排障。
            String reason = submissionStore.describeLostReason(
                    command.getSubmissionId(), command.getAttemptToken());
            log.warn("Discard late answer result, sessionId={}, submissionId={}, reason={}",
                    command.getSessionId(), command.getSubmissionId(), reason);
            Metrics.counter("answer_commit_rejected_total", "reason", reason).increment();
            return CommitResult.TOKEN_LOST;
        }

        // 事务第 2 步：确实有追问文案时插入追问题行；它与本轮评分及游标更新一起成功或回滚。
        if (command.getFollowUpQuestion() != null) {
            questionStore.appendFollowUp(
                    command.getSessionId(),
                    command.getFollowUpMainIndex(),
                    command.getFollowUpIndex(),
                    command.getFollowUpQuestion());
        }

        // 事务第 3 步：使用模型调用前的 expectedVersion 更新会话；版本不匹配必须抛异常，回滚前两步的写入。
        // 额外要求"库里仍停在本次已答的题号、状态仍是 IN_PROGRESS"：锁过期后可能已有另一笔推进了游标，
        // 只看 version 的话在极端并发下仍有覆盖窗口。这里用答题专用方法，避免影响出题与结束流程。
        boolean advanced = sessionStore.advanceAnswerProgress(InterviewProgressCommit.builder()
                .sessionId(command.getSessionId())
                .expectedVersion(command.getExpectedVersion())
                .status(command.getNextStatus())
                .flowStatus(command.getNextFlowStatus())
                .currentQuestionNo(command.getNextQuestionNo())
                .followUpCount(command.getFollowUpCount())
                .activeSubmissionId(null)
                .build(), command.getAnsweredQuestionNo());
        if (!advanced) {
            log.warn("Session version conflict, rollback answer commit, sessionId={}, expectedVersion={}",
                    command.getSessionId(), command.getExpectedVersion());
            throw new VersionConflictException();
        }
        return CommitResult.SUCCEEDED;
    }

    public static class VersionConflictException extends RuntimeException {
        public VersionConflictException() {
            super("session progress version changed");
        }
    }
}
