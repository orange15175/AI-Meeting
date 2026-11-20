package com.hewei.hzyjy.aimeeting.interview.flow.answer;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.aimeeting.interview.application.flow.InterviewFlowStateMachine;
import com.hewei.hzyjy.aimeeting.interview.application.flow.InterviewNextStep;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleContext;
import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleDecision;
import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleService;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewAnswerSubmission;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewQuestionItem;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowStatus;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewResponseParser;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 答题主流水线。
 *
 * <p>P3 结构：会话锁串行化入口 → MySQL 提交行做幂等 → 模型评分（不持事务）→
 * 单事务提交「提交终态 + 追问题 + 会话游标」。所有"处理中/已完成/旧题/忙"的判定
 * 都来自数据库当前状态，不再依赖 Redis 去重集合或内存版本比较。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewAnswerPipeline {

    private static final String PROCESSING_MESSAGE = "current request is processing, please retry later";
    private static final String CONFLICT_MESSAGE = "requestId is reused with different input, please use a new requestId";
    private static final String STALE_QUESTION_MESSAGE = "stale question number, please refresh current question";
    private static final String BUSY_MESSAGE = "another answer is being processed for this session, please retry later";

    private final InterviewSessionStore sessionStore;
    private final InterviewQuestionStore questionStore;
    private final InterviewSubmissionStore submissionStore;
    private final InterviewEvaluationService interviewEvaluationService;
    private final InterviewFollowUpService interviewFollowUpService;
    private final InterviewFollowUpRuleService interviewFollowUpRuleService;
    private final InterviewFlowStateMachine flowStateMachine;
    private final InterviewAnswerCommitService commitService;
    private final InterviewSessionLockService sessionLockService;
    private final InterviewResponseParser interviewResponseParser;

    public InterviewAnswerRespDTO execute(String sessionId, InterviewAnswerReqDTO requestParam) {
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();
        if (StrUtil.isBlank(sessionId)) {
            return response.fail("sessionId cannot be empty");
        }
        if (requestParam == null) {
            return response.fail("request body cannot be empty");
        }
        if (StrUtil.isBlank(requestParam.getQuestionNumber())) {
            return response.fail("question number cannot be empty");
        }
        if (StrUtil.isBlank(requestParam.getAnswerContent())) {
            return response.fail("answer content cannot be empty");
        }

        String questionNo = normalizeQuestionNumber(requestParam.getQuestionNumber());
        String requestId = normalizeRequestId(sessionId, questionNo, requestParam.getAnswerContent(),
                requestParam.getRequestId());
        requestParam.setRequestId(requestId);
        String inputHash = DigestUtil.sha256Hex(questionNo + "|" + requestParam.getAnswerContent().trim());

        InterviewSession session = sessionStore.findBySessionId(sessionId);
        if (session == null) {
            return response.fail("interview session does not exist");
        }
        InterviewFlowState flow = flowStateMachine.toFlow(session);
        if (flowStateMachine.isCompleted(flow)) {
            return finishResponse(sessionId, response);
        }
        String currentQuestionNo = flowStateMachine.currentQuestionNumber(flow);
        if (StrUtil.isBlank(currentQuestionNo)) {
            return response.fail("interview questions are not prepared yet");
        }
        if (!isSameQuestion(questionNo, currentQuestionNo)) {
            Metrics.counter("stale_question_reject_total").increment();
            return response.fail(STALE_QUESTION_MESSAGE);
        }

        RLock sessionLock = null;
        try {
            sessionLock = sessionLockService.acquire(sessionId);
            if (sessionLock == null) {
                Metrics.counter("session_lock_contention_total").increment();
                return response.fail(BUSY_MESSAGE);
            }

            // 持锁后重读会话：锁等待期间游标可能已被上一笔提交推进。
            session = sessionStore.findBySessionId(sessionId);
            if (session == null) {
                return response.fail("interview session does not exist");
            }
            flow = flowStateMachine.toFlow(session);
            if (flowStateMachine.isCompleted(flow)) {
                return finishResponse(sessionId, response);
            }
            currentQuestionNo = flowStateMachine.currentQuestionNumber(flow);
            if (!isSameQuestion(questionNo, currentQuestionNo)) {
                Metrics.counter("stale_question_reject_total").increment();
                return response.fail(STALE_QUESTION_MESSAGE);
            }

            InterviewQuestionItem question = questionStore.findByNo(sessionId, currentQuestionNo);
            if (question == null) {
                return response.fail("question does not exist or expired");
            }

            InterviewSubmissionStore.ClaimResult claim = submissionStore.claim(
                    sessionId,
                    currentQuestionNo,
                    requestId,
                    inputHash,
                    requestParam.getAnswerContent(),
                    IdUtil.fastSimpleUUID(),
                    IdUtil.fastSimpleUUID());
            switch (claim.getStatus()) {
                case REPLAY -> {
                    Metrics.counter("idempotency_replay_hit_total").increment();
                    return replay(claim.getSubmission(), currentQuestionNo, sessionId, response);
                }
                case CONFLICT -> {
                    return response.fail(CONFLICT_MESSAGE);
                }
                case ALREADY_ANSWERED -> {
                    Metrics.counter("stale_question_reject_total").increment();
                    return response.fail(STALE_QUESTION_MESSAGE);
                }
                case BUSY -> {
                    return response.fail(PROCESSING_MESSAGE);
                }
                default -> {
                    // NEW / RETRY 继续调模型。
                }
            }
            return processAnswer(session, flow, question, claim.getSubmission(), requestParam, response);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Metrics.counter("answer_pipeline_fail_total", "reason", "interrupted").increment();
            return response.fail("interview answer request interrupted");
        } catch (InterviewAnswerCommitService.VersionConflictException ex) {
            Metrics.counter("answer_pipeline_fail_total", "reason", "session_version_conflict").increment();
            return response.fail(PROCESSING_MESSAGE);
        } catch (Exception ex) {
            log.error("Interview answer pipeline failed, sessionId={}", sessionId, ex);
            Metrics.counter("answer_pipeline_fail_total", "reason", "unexpected_exception").increment();
            return response.fail("failed to process answer: " + ex.getMessage());
        } finally {
            sessionLockService.release(sessionLock);
        }
    }

    private InterviewAnswerRespDTO processAnswer(
            InterviewSession session,
            InterviewFlowState flow,
            InterviewQuestionItem question,
            InterviewAnswerSubmission submission,
            InterviewAnswerReqDTO requestParam,
            InterviewAnswerRespDTO response) {

        String sessionId = session.getSessionId();
        Map<String, Object> evaluation;
        long start = System.currentTimeMillis();
        try {
            evaluation = interviewEvaluationService.evaluateAnswer(
                    sessionId,
                    submission.getRequestId(),
                    submission.getQuestionNo(),
                    question.getContent(),
                    requestParam.getAnswerContent(),
                    parseScoringPoints(question.getPointsJson()));
        } catch (InterviewAiGuardException guardException) {
            markFailed(submission, "evaluation", guardException.getMessage());
            Metrics.counter("answer_pipeline_fail_total",
                    "reason", "ai_guard_" + guardException.getErrorCode().name().toLowerCase()).increment();
            return response.fail(guardException.getMessage());
        } catch (Exception ex) {
            markFailed(submission, "evaluation", ex.getMessage());
            Metrics.counter("answer_pipeline_fail_total", "reason", "evaluation_error").increment();
            return response.fail("failed to evaluate answer");
        }

        if (evaluation == null || evaluation.isEmpty()) {
            markFailed(submission, "evaluation", "evaluation parse failed");
            Metrics.counter("answer_pipeline_fail_total", "reason", "evaluation_parse_failed").increment();
            return response.fail("failed to parse evaluation result");
        }
        Integer score = interviewResponseParser.parseScoreFromResponse(evaluation, "score");
        if (score == null) {
            markFailed(submission, "evaluation", "score missing");
            Metrics.counter("answer_pipeline_fail_total", "reason", "evaluation_score_missing").increment();
            return response.fail("score missing in evaluation result");
        }
        int durationMs = (int) (System.currentTimeMillis() - start);

        boolean aiSuggestedFollowUp = interviewResponseParser.asBoolean(evaluation.get("follow_up_needed"));
        String followUpHint = sanitizeFollowUpQuestion(
                interviewResponseParser.asString(evaluation.get("follow_up_question")));
        List<String> missingPoints = interviewResponseParser.asStringList(evaluation.get("missing_points"));

        InterviewFollowUpRuleDecision ruleDecision = decideFollowUp(
                session, flow, submission, question, score, aiSuggestedFollowUp, followUpHint, missingPoints);
        boolean needFollowUp = ruleDecision != null
                ? ruleDecision.isNeedFollowUp()
                : Boolean.TRUE.equals(aiSuggestedFollowUp);
        int maxFollowUp = ruleDecision != null && ruleDecision.getResolvedMaxFollowUp() > 0
                ? ruleDecision.getResolvedMaxFollowUp()
                : resolveMaxFollowUp(flow);

        String followUpQuestion = null;
        if (needFollowUp) {
            InterviewFollowUpService.FollowUpQuestionResult followUpResult =
                    interviewFollowUpService.generateFollowUpQuestion(
                            sessionId,
                            submission.getRequestId(),
                            submission.getQuestionNo(),
                            question.getContent(),
                            requestParam.getAnswerContent(),
                            followUpHint,
                            flowStateMachine.followUpIndexOf(submission.getQuestionNo()),
                            maxFollowUp);
            if (followUpResult.hasQuestion()) {
                followUpQuestion = followUpResult.getQuestionContent();
            } else {
                // 追问生成失败不回滚已完成的评分：按计划跳过追问并记录原因。
                log.info("Skip follow-up because generation produced nothing, sessionId={}, questionNo={}",
                        sessionId, submission.getQuestionNo());
                Metrics.counter("answer_follow_up_skipped_total").increment();
            }
        }

        int totalQuestions = flow.getTotalQuestions() == null ? 0 : flow.getTotalQuestions();
        InterviewNextStep step = flowStateMachine.plan(
                flow, followUpQuestion != null, followUpQuestion, totalQuestions);

        // 组装响应并序列化为回放快照，再一次性提交。
        InterviewAnswerRespDTO committed = buildResponse(sessionId, question.getQuestionNo(), question.getContent(),
                step, followUpQuestion, score, interviewResponseParser.asString(evaluation.get("feedback")));
        String responseJson = JSON.toJSONString(committed);

        InterviewAnswerCommitService.CommitResult result = commitService.commit(
                InterviewAnswerCommitService.CommitCommand.builder()
                        .sessionId(sessionId)
                        .expectedVersion(flow.getVersion())
                        .submissionId(submission.getSubmissionId())
                        .attemptToken(submission.getAttemptToken())
                        .score(score)
                        .feedback(interviewResponseParser.asString(evaluation.get("feedback")))
                        .missingPointsJson(missingPoints == null || missingPoints.isEmpty()
                                ? null
                                : JSON.toJSONString(missingPoints))
                        .responseJson(responseJson)
                        .finished(step.isFinished())
                        .stage("evaluated")
                        .durationMs(durationMs)
                        .nextStatus(step.isFinished()
                                ? InterviewSessionStatus.FINISHED.name()
                                : InterviewSessionStatus.IN_PROGRESS.name())
                        .nextFlowStatus(step.isFinished()
                                ? InterviewFlowStatus.COMPLETED.name()
                                : (step.getKind() == InterviewNextStep.Kind.FOLLOW_UP
                                    ? InterviewFlowStatus.FOLLOW_UP.name()
                                    : InterviewFlowStatus.ASKING.name()))
                        .nextQuestionNo(step.getQuestionNo())
                        .followUpCount(step.getKind() == InterviewNextStep.Kind.FOLLOW_UP
                                ? step.getFollowUpIndex() : 0)
                        .followUpQuestion(followUpQuestion)
                        .followUpMainIndex(step.getKind() == InterviewNextStep.Kind.FOLLOW_UP
                                ? step.getMainIndex() : null)
                        .followUpIndex(step.getKind() == InterviewNextStep.Kind.FOLLOW_UP
                                ? step.getFollowUpIndex() : null)
                        .build());

        if (result == InterviewAnswerCommitService.CommitResult.TOKEN_LOST) {
            Metrics.counter("answer_pipeline_fail_total", "reason", "attempt_token_lost").increment();
            return response.fail(PROCESSING_MESSAGE);
        }

        committed.setTotalScore(submissionStore.averageMainScore(sessionId));
        return committed;
    }

    private InterviewAnswerRespDTO buildResponse(
            String sessionId,
            String answeredQuestionNo,
            String answeredQuestionContent,
            InterviewNextStep step,
            String followUpQuestionContent,
            int score,
            String feedback) {
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();
        response.withCurrentQuestion(answeredQuestionNo, answeredQuestionContent);
        response.withEvaluation(score, feedback, submissionStore.averageMainScore(sessionId));
        if (step.isFinished()) {
            response.finish().success();
            return response;
        }
        if (step.getKind() == InterviewNextStep.Kind.FOLLOW_UP) {
            // 追问题在提交事务里才落库，这里必须用刚生成的内容，不能回查。
            response.withNextQuestion(step.getQuestionNo(), followUpQuestionContent, true,
                    step.getFollowUpIndex()).success();
            return response;
        }
        String nextContent = resolveQuestionContent(sessionId, step.getQuestionNo());
        response.withNextQuestion(step.getQuestionNo(), nextContent, false, 0).success();
        return response;
    }

    private InterviewAnswerRespDTO replay(
            InterviewAnswerSubmission submission,
            String questionNo,
            String sessionId,
            InterviewAnswerRespDTO response) {
        if (StrUtil.isNotBlank(submission.getResponseJson())) {
            try {
                InterviewAnswerRespDTO replay = JSON.parseObject(
                        submission.getResponseJson(), InterviewAnswerRespDTO.class);
                if (replay != null) {
                    replay.setTotalScore(submissionStore.averageMainScore(sessionId));
                    return replay;
                }
            } catch (Exception ex) {
                log.warn("Failed to parse replay response, sessionId={}, questionNo={}", sessionId, questionNo, ex);
            }
        }
        // 快照缺失时至少返回已存分数，避免客户端重复提交。
        response.withCurrentQuestion(questionNo, resolveQuestionContent(sessionId, questionNo));
        response.withEvaluation(submission.getScore(), submission.getFeedback(),
                submissionStore.averageMainScore(sessionId));
        return response.success();
    }

    private InterviewAnswerRespDTO finishResponse(String sessionId, InterviewAnswerRespDTO response) {
        response.setTotalScore(submissionStore.averageMainScore(sessionId));
        return response.finish().success();
    }

    private void markFailed(InterviewAnswerSubmission submission, String stage, String message) {
        try {
            submissionStore.markFailed(submission.getSubmissionId(), submission.getAttemptToken(), stage, message);
        } catch (Exception ex) {
            log.warn("Failed to mark submission failed, submissionId={}", submission.getSubmissionId(), ex);
        }
    }

    private InterviewFollowUpRuleDecision decideFollowUp(
            InterviewSession session,
            InterviewFlowState flow,
            InterviewAnswerSubmission submission,
            InterviewQuestionItem question,
            int score,
            boolean aiSuggestedFollowUp,
            String followUpHint,
            List<String> missingPoints) {
        InterviewFollowUpRuleContext context = new InterviewFollowUpRuleContext();
        context.setSessionId(session.getSessionId());
        context.setRequestId(submission.getRequestId());
        context.setQuestionNumber(submission.getQuestionNo());
        context.setInterviewType(session.getInterviewType());
        context.setFollowUpQuestion(flowStateMachine.isFollowUp(submission.getQuestionNo()));
        context.setFollowUpCount(flowStateMachine.followUpIndexOf(submission.getQuestionNo()));
        context.setMaxFollowUp(resolveMaxFollowUp(flow));
        context.setScore(score);
        context.setFollowUpNeededFromAi(aiSuggestedFollowUp);
        context.setMissingPoints(missingPoints);
        context.setFollowUpQuestionHint(followUpHint);
        context.setInterviewCompleted(false);
        try {
            InterviewFollowUpRuleDecision decision = interviewFollowUpRuleService.decide(context);
            log.info("Follow-up rule decision, sessionId={}, questionNo={}, needFollowUp={}, reason={}",
                    session.getSessionId(), question.getQuestionNo(),
                    decision == null ? null : decision.isNeedFollowUp(),
                    decision == null ? null : decision.getReasonCode());
            return decision;
        } catch (Exception ex) {
            log.warn("Follow-up rule evaluation failed, fallback to model decision, sessionId={}",
                    session.getSessionId(), ex);
            return null;
        }
    }

    private String resolveQuestionContent(String sessionId, String questionNo) {
        InterviewQuestionItem item = questionStore.findByNo(sessionId, questionNo);
        return item == null ? null : item.getContent();
    }

    private int resolveMaxFollowUp(InterviewFlowState flow) {
        if (flow == null || flow.getMaxFollowUp() == null || flow.getMaxFollowUp() <= 0) {
            return 2;
        }
        return flow.getMaxFollowUp();
    }

    private List<String> parseScoringPoints(String pointsJson) {
        if (StrUtil.isBlank(pointsJson)) {
            return List.of();
        }
        try {
            List<String> points = JSON.parseArray(pointsJson, String.class);
            return points == null ? List.of() : points;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String normalizeRequestId(String sessionId, String questionNo, String answer, String requestId) {
        if (StrUtil.isNotBlank(requestId)) {
            return requestId.trim();
        }
        String seed = sessionId + "|" + questionNo + "|" + answer;
        return "auto-" + DigestUtil.sha256Hex(seed).substring(0, 32);
    }

    private boolean isSameQuestion(String requested, String current) {
        return Objects.equals(normalizeQuestionNumber(requested), normalizeQuestionNumber(current));
    }

    private String normalizeQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim().toUpperCase();
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        if (normalized.matches("\\d+-F\\d+")) {
            String[] parts = normalized.split("-F");
            try {
                return Integer.parseInt(parts[0]) + "-F" + Integer.parseInt(parts[1]);
            } catch (Exception ex) {
                return normalized;
            }
        }
        return normalized;
    }

    private String sanitizeFollowUpQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }
}
