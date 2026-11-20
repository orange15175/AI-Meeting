package com.hewei.hzyjy.aimeeting.interview.dao.store;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewAnswerSubmission;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewAnswerSubmissionMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 回答提交存取：幂等登记、条件完成与分数聚合。
 *
 * <p>幂等不再依赖 Redis 去重集合：
 * 唯一键 (session_id, question_no) 保证每题只有一行有效提交，
 * (session_id, request_id) 索引支撑同次重试回放原结果。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSubmissionStore {

    /**
     * 处理中的提交超过该时长视为孤儿，允许新的执行者接管。
     * 必须大于模型最大调用预算（出题 180s 之外的评分 20s + 重试）。
     */
    private static final Duration PROCESSING_BUDGET = Duration.ofMinutes(5);

    private final InterviewAnswerSubmissionMapper submissionMapper;

    public enum ClaimStatus {
        /**
         * 首次登记，可以继续调模型。
         */
        NEW,
        /**
         * 上次失败或孤儿接管，可以重试。
         */
        RETRY,
        /**
         * 同一次请求已完成，直接回放原响应。
         */
        REPLAY,
        /**
         * 同 requestId 但输入不同，拒绝。
         */
        CONFLICT,
        /**
         * 已有成功提交，且不是同一次请求（旧题重复提交）。
         */
        ALREADY_ANSWERED,
        /**
         * 同一题正在处理中。
         */
        BUSY
    }

    @Data
    @Builder
    public static class ClaimResult {
        private ClaimStatus status;
        private InterviewAnswerSubmission submission;
    }

    public InterviewAnswerSubmission findByQuestion(String sessionId, String questionNo) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNo)) {
            return null;
        }
        return submissionMapper.selectOne(Wrappers.lambdaQuery(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSessionId, sessionId)
                .eq(InterviewAnswerSubmission::getQuestionNo, questionNo.trim())
                .eq(InterviewAnswerSubmission::getDelFlag, 0));
    }

    /**
     * 登记一次提交。返回 REPLAY 时调用方应直接回放 {@code submission.responseJson}。
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimResult claim(
            String sessionId,
            String questionNo,
            String requestId,
            String inputHash,
            String answerContent,
            String submissionId,
            String attemptToken) {
        InterviewAnswerSubmission existing = findByQuestion(sessionId, questionNo);
        if (existing == null) {
            InterviewAnswerSubmission fresh = new InterviewAnswerSubmission();
            fresh.setSubmissionId(submissionId);
            fresh.setSessionId(sessionId);
            fresh.setQuestionNo(questionNo);
            fresh.setRequestId(requestId);
            fresh.setInputHash(inputHash);
            fresh.setAnswerContent(answerContent);
            fresh.setSkipped(0);
            fresh.setStatus(InterviewAnswerSubmission.STATUS_PROCESSING);
            fresh.setAttemptToken(attemptToken);
            fresh.setAttemptCount(1);
            fresh.setFinished(0);
            fresh.setCreateTime(new Date());
            fresh.setUpdateTime(new Date());
            fresh.setDelFlag(0);
            try {
                submissionMapper.insert(fresh);
                return ClaimResult.builder().status(ClaimStatus.NEW).submission(fresh).build();
            } catch (DuplicateKeyException duplicateKeyException) {
                // 并发下另一个执行者先插入了同一题，退化为按已有行处理。
                existing = findByQuestion(sessionId, questionNo);
                if (existing == null) {
                    throw duplicateKeyException;
                }
                log.info("Submission claim hit concurrent insert, sessionId={}, questionNo={}", sessionId, questionNo);
            }
        }
        return settleExisting(existing, requestId, inputHash, answerContent, attemptToken);
    }

    private ClaimResult settleExisting(
            InterviewAnswerSubmission existing,
            String requestId,
            String inputHash,
            String answerContent,
            String attemptToken) {
        String status = existing.getStatus();
        if (InterviewAnswerSubmission.STATUS_SUCCEEDED.equals(status)) {
            if (Objects.equals(existing.getRequestId(), requestId)) {
                if (Objects.equals(existing.getInputHash(), inputHash)) {
                    return ClaimResult.builder().status(ClaimStatus.REPLAY).submission(existing).build();
                }
                return ClaimResult.builder().status(ClaimStatus.CONFLICT).submission(existing).build();
            }
            return ClaimResult.builder().status(ClaimStatus.ALREADY_ANSWERED).submission(existing).build();
        }

        if (InterviewAnswerSubmission.STATUS_PROCESSING.equals(status)) {
            boolean sameAttempt = Objects.equals(existing.getRequestId(), requestId)
                    && Objects.equals(existing.getAttemptToken(), attemptToken);
            boolean stale = isStale(existing);
            if (!stale) {
                return ClaimResult.builder().status(ClaimStatus.BUSY).submission(existing).build();
            }
            if (sameAttempt) {
                return ClaimResult.builder().status(ClaimStatus.BUSY).submission(existing).build();
            }
            log.warn("Take over orphan submission, sessionId={}, questionNo={}, attemptToken={}",
                    existing.getSessionId(), existing.getQuestionNo(), existing.getAttemptToken());
        }

        // FAILED 或超时的 PROCESSING：复用同一行重试，递增尝试次数并换令牌。
        int nextAttempt = existing.getAttemptCount() == null ? 2 : existing.getAttemptCount() + 1;
        int updated = submissionMapper.update(null, Wrappers.lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getId, existing.getId())
                .eq(InterviewAnswerSubmission::getStatus, existing.getStatus())
                .eq(existing.getAttemptToken() != null,
                        InterviewAnswerSubmission::getAttemptToken, existing.getAttemptToken())
                .set(InterviewAnswerSubmission::getRequestId, requestId)
                .set(InterviewAnswerSubmission::getInputHash, inputHash)
                .set(InterviewAnswerSubmission::getAnswerContent, answerContent)
                .set(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_PROCESSING)
                .set(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .set(InterviewAnswerSubmission::getAttemptCount, nextAttempt)
                .set(InterviewAnswerSubmission::getStage, null)
                .set(InterviewAnswerSubmission::getErrorMessage, null)
                .set(InterviewAnswerSubmission::getUpdateTime, new Date()));
        if (updated != 1) {
            return ClaimResult.builder().status(ClaimStatus.BUSY).submission(findByQuestion(
                    existing.getSessionId(), existing.getQuestionNo())).build();
        }
        existing.setRequestId(requestId);
        existing.setInputHash(inputHash);
        existing.setStatus(InterviewAnswerSubmission.STATUS_PROCESSING);
        existing.setAttemptToken(attemptToken);
        existing.setAttemptCount(nextAttempt);
        return ClaimResult.builder().status(ClaimStatus.RETRY).submission(existing).build();
    }

    private boolean isStale(InterviewAnswerSubmission submission) {
        Date updatedAt = submission.getUpdateTime();
        if (updatedAt == null) {
            return true;
        }
        return System.currentTimeMillis() - updatedAt.getTime() > PROCESSING_BUDGET.toMillis();
    }

    /**
     * 条件完成：只有仍持有同一 attemptToken 的执行者能落库，迟到结果会被拒绝。
     */
    public boolean markSucceeded(
            String submissionId,
            String attemptToken,
            int score,
            String feedback,
            String missingPointsJson,
            String responseJson,
            boolean finished,
            String stage,
            int durationMs) {
        return submissionMapper.update(null, Wrappers.lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSubmissionId, submissionId)
                .eq(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .eq(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_PROCESSING)
                .set(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_SUCCEEDED)
                .set(InterviewAnswerSubmission::getScore, score)
                .set(InterviewAnswerSubmission::getFeedback, feedback)
                .set(InterviewAnswerSubmission::getMissingPoints, missingPointsJson)
                .set(InterviewAnswerSubmission::getResponseJson, responseJson)
                .set(InterviewAnswerSubmission::getFinished, finished ? 1 : 0)
                .set(InterviewAnswerSubmission::getStage, stage)
                .set(InterviewAnswerSubmission::getDurationMs, durationMs)
                .set(InterviewAnswerSubmission::getErrorMessage, null)
                .set(InterviewAnswerSubmission::getUpdateTime, new Date())) == 1;
    }

    public boolean markFailed(String submissionId, String attemptToken, String stage, String errorMessage) {
        return submissionMapper.update(null, Wrappers.lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSubmissionId, submissionId)
                .eq(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .eq(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_PROCESSING)
                .set(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_FAILED)
                .set(InterviewAnswerSubmission::getStage, stage)
                .set(InterviewAnswerSubmission::getErrorMessage, StrUtil.maxLength(errorMessage, 480))
                .set(InterviewAnswerSubmission::getUpdateTime, new Date())) == 1;
    }

    /**
     * 主问题平均分；无有效评分时返回 null，不写 0。
     */
    public Integer averageMainScore(String sessionId) {
        return submissionMapper.averageMainScore(sessionId);
    }

    public int countSucceededMain(String sessionId) {
        return submissionMapper.countSucceededMain(sessionId);
    }

    public List<InterviewAnswerSubmission> listByQuestionOrder(String sessionId) {
        return submissionMapper.listByQuestionOrder(sessionId);
    }

    /**
     * 重试出题时作废本轮问答。
     */
    public int deleteBySession(String sessionId) {
        return submissionMapper.delete(Wrappers.lambdaQuery(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSessionId, sessionId));
    }
}
