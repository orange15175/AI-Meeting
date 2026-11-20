package com.hewei.hzyjy.aimeeting.interview.flow.answer;

import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewProgressCommit;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
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
         * 追加追问题目（与游标推进同一事务）。
         */
        private String followUpQuestion;
        private Integer followUpMainIndex;
        private Integer followUpIndex;
    }

    @Transactional(rollbackFor = Exception.class)
    public CommitResult commit(CommitCommand command) {
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
            log.warn("Discard late answer result, sessionId={}, submissionId={}",
                    command.getSessionId(), command.getSubmissionId());
            return CommitResult.TOKEN_LOST;
        }

        if (command.getFollowUpQuestion() != null) {
            questionStore.appendFollowUp(
                    command.getSessionId(),
                    command.getFollowUpMainIndex(),
                    command.getFollowUpIndex(),
                    command.getFollowUpQuestion());
        }

        boolean advanced = sessionStore.commitProgress(InterviewProgressCommit.builder()
                .sessionId(command.getSessionId())
                .expectedVersion(command.getExpectedVersion())
                .status(command.getNextStatus())
                .flowStatus(command.getNextFlowStatus())
                .currentQuestionNo(command.getNextQuestionNo())
                .followUpCount(command.getFollowUpCount())
                .activeSubmissionId(null)
                .build());
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
