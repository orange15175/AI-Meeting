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
 * <p>结构：会话锁串行化入口 → MySQL 提交行做幂等并登记执行权租约 → 心跳续期 →
 * 模型评分（不持事务）→ 单事务提交「提交终态 + 追问题 + 会话游标」。
 * 所有"处理中/已完成/旧题/忙"的判定都来自数据库当前状态，不依赖 Redis 去重集合或内存版本比较。</p>
 *
 * <p>会话锁只降低并发冲突概率，<b>不承担正确性</b>：锁是固定租期会自然过期，
 * 真正决定"谁能提交"的是提交行上的 attemptToken、租约条件和会话 version。</p>
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
    private final InterviewAnswerHeartbeatService heartbeatService;
    private final InterviewResponseParser interviewResponseParser;

    /**
     * 【面试主线 4/6】每次回答的入口：校验当前题 → 会话锁 → 持锁重读 → MySQL claim → 评分与追问 → 提交。
     * REPLAY/CONFLICT/BUSY 等分支均在通过当前题检查后处理；已推进的旧题会先被拒绝，不能理解为任意旧请求均可回放。
     */
    public InterviewAnswerRespDTO execute(String sessionId, InterviewAnswerReqDTO requestParam) {
        // 第 1 段：入口兜底校验会话 ID、题号和回答；无效请求在获取锁、登记提交和调用模型前就返回。
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
        // 【关键节点：提交身份】requestId 表示请求身份，inputHash 表示题号与回答内容，用于区分重试和冲突。
        String inputHash = DigestUtil.sha256Hex(questionNo + "|" + requestParam.getAnswerContent().trim());

        // 第 2 段：锁前快速检查当前游标，尽早拒绝旧题；这个快照不能替代下面持锁后的再次检查。
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
        // 只有 NEW/RETRY 分支会赋值；failing 分支保持 null，finally 中的关闭为空操作。
        InterviewAnswerHeartbeatService.Heartbeat heartbeat = null;
        try {
            // 【关键节点：答题互斥】先串行化同一会话；数据库唯一键、令牌和 version 仍是最终提交保护。
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

            // 【关键节点：幂等登记】先占用题目的提交行；只有 NEW/RETRY 分支继续产生模型调用成本。
            InterviewSubmissionStore.ClaimResult claim = submissionStore.claim(
                    sessionId,
                    currentQuestionNo,
                    requestId,
                    inputHash,
                    requestParam.getAnswerContent(),
                    IdUtil.fastSimpleUUID(),
                    IdUtil.fastSimpleUUID());
            switch (claim.getStatus()) {
                // 同请求且同输入已有成功结果：走快照回放，不再评分；注意只有通过当前题检查后才会进入该分支。
                case REPLAY -> {
                    Metrics.counter("idempotency_replay_hit_total").increment();
                    return replay(claim.getSubmission(), currentQuestionNo, sessionId, response);
                }
                // 请求 ID 已用于另一份输入，不能把它当成同一次重试。
                case CONFLICT -> {
                    return response.fail(CONFLICT_MESSAGE);
                }
                case ALREADY_ANSWERED -> {
                    Metrics.counter("stale_question_reject_total").increment();
                    return response.fail(STALE_QUESTION_MESSAGE);
                }
                // 提交行仍在处理中，当前请求只返回忙；避免重复消耗模型调用。
                case BUSY -> {
                    return response.fail(PROCESSING_MESSAGE);
                }
                default -> {
                    // NEW / RETRY 继续调模型。
                }
            }
            // 【关键节点：执行权租约】claim 事务已提交，此刻起用独立调度线程每 20 秒刷新存活时间；
            // 这是"允许别人接管"的判据，与锁无关。心跳启动失败只降级为固定超时，不阻断答题。
            heartbeat = heartbeatService.start(
                    claim.getSubmission().getSubmissionId(), claim.getSubmission().getAttemptToken());
            return processAnswer(session, flow, question, claim.getSubmission(), requestParam, response, heartbeat);
        // 中断时恢复线程中断标记；版本冲突表示其他执行者已推进进度，返回处理中提示而非继续覆盖。
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
            // 先停心跳再放锁。心跳与 markSucceeded 更新同一行，两者都是毫秒级单行 UPDATE，竞争只会让某一方
            // 多等几毫秒，不会让事务失败；真正要避免的是锁释放后仍有后台任务在动这一行，以及任务泄漏。
            if (heartbeat != null) {
                heartbeat.close();
            }
            // 所有退出路径都释放本次会话锁；释放服务会检查锁是否仍由当前线程持有。
            sessionLockService.release(sessionLock);
        }
    }

    // 第 3 段：只有拿到 NEW/RETRY 执行权才进入；评分 → 输出校验 → 追问规则/文案 → 状态规划 → 短事务提交。
    private InterviewAnswerRespDTO processAnswer(
            InterviewSession session,
            InterviewFlowState flow,
            InterviewQuestionItem question,
            InterviewAnswerSubmission submission,
            InterviewAnswerReqDTO requestParam,
            InterviewAnswerRespDTO response,
            InterviewAnswerHeartbeatService.Heartbeat heartbeat) {

        String sessionId = session.getSessionId();
        Map<String, Object> evaluation;
        long start = System.currentTimeMillis();
        try {
            // 【关键节点：先评分】评分失败只记录提交失败，不提前推进题号；模型等待期间不持有提交事务。
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

        // 【关键节点：评分有效性】返回空对象或缺少有效 score 都视为失败，提交行标为 FAILED，当前题保持不变。
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

        // 【关键节点：执行权探测】评分是最贵的一步，刚拿到结果就先确认"这份结果还算数吗"。
        // 探测只读心跳线程已经得出的结论（纯内存），不再额外发 SQL；它只用于止损，不承担正确性。
        if (!heartbeat.alive()) {
            Metrics.counter("answer_pipeline_fail_total", "reason", "execution_right_lost_before_follow_up").increment();
            return response.fail(PROCESSING_MESSAGE);
        }

        // 把模型评分结果拆成规则输入：是否建议追问、备选追问题干及遗漏点；这些值还不是最终路由。
        boolean aiSuggestedFollowUp = interviewResponseParser.asBoolean(evaluation.get("follow_up_needed"));
        String followUpHint = sanitizeFollowUpQuestion(
                interviewResponseParser.asString(evaluation.get("follow_up_question")));
        List<String> missingPoints = interviewResponseParser.asStringList(evaluation.get("missing_points"));

        // 【关键节点：追问决策】将分数、遗漏点和模型建议交给 LiteFlow；决策与追问文案生成是两个步骤。
        InterviewFollowUpRuleDecision ruleDecision = decideFollowUp(
                session, flow, submission, question, score, aiSuggestedFollowUp, followUpHint, missingPoints);
        boolean needFollowUp = ruleDecision != null
                ? ruleDecision.isNeedFollowUp()
                : Boolean.TRUE.equals(aiSuggestedFollowUp);
        int maxFollowUp = ruleDecision != null && ruleDecision.getResolvedMaxFollowUp() > 0
                ? ruleDecision.getResolvedMaxFollowUp()
                : resolveMaxFollowUp(flow);

        // 只有规则要求追问才生成文案；如果生成和备选都不可用，后续按“不追问”规划下一步。
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

        // 追问模型同样是一次长耗时调用，返回后再确认一次执行权，避免用已失效的令牌去提交注定失败的事务。
        if (!heartbeat.alive()) {
            Metrics.counter("answer_pipeline_fail_total", "reason", "execution_right_lost_before_commit").increment();
            return response.fail(PROCESSING_MESSAGE);
        }

        int totalQuestions = flow.getTotalQuestions() == null ? 0 : flow.getTotalQuestions();
        // 【关键节点：下一步规划】只计算 FOLLOW_UP / NEXT_MAIN / FINISH，数据库游标尚未改变。
        InterviewNextStep step = flowStateMachine.plan(
                flow, followUpQuestion != null, followUpQuestion, totalQuestions);

        // 组装响应并序列化为回放快照，再一次性提交。
        InterviewAnswerRespDTO committed = buildResponse(sessionId, question.getQuestionNo(), question.getContent(),
                step, followUpQuestion, score, interviewResponseParser.asString(evaluation.get("feedback")));
        String responseJson = JSON.toJSONString(committed);

        // 【关键节点：答题提交】评分、追问题、响应快照、游标在同一事务落库后，客户端才能切换到下一题。
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
                        .answeredQuestionNo(submission.getQuestionNo())
                        .followUpCount(step.getKind() == InterviewNextStep.Kind.FOLLOW_UP
                                ? step.getFollowUpIndex() : 0)
                        .followUpQuestion(followUpQuestion)
                        .followUpMainIndex(step.getKind() == InterviewNextStep.Kind.FOLLOW_UP
                                ? step.getMainIndex() : null)
                        .followUpIndex(step.getKind() == InterviewNextStep.Kind.FOLLOW_UP
                                ? step.getFollowUpIndex() : null)
                        .build());

        // 【关键节点：迟到结果】执行令牌已被新尝试接管时，丢弃本次模型结果；不能向前端返回虚假的切题成功。
        if (result == InterviewAnswerCommitService.CommitResult.TOKEN_LOST) {
            Metrics.counter("answer_pipeline_fail_total", "reason", "attempt_token_lost").increment();
            return response.fail(PROCESSING_MESSAGE);
        }

        // 事务成功后重新读取主问题均分，使当前返回的总分包含本次成功提交。
        committed.setTotalScore(submissionStore.averageMainScore(sessionId));
        return committed;
    }

    // 组装本轮评分与下一题的响应快照；这里尚未落库，外层事务提交成功后才能把它当作最终响应。
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

    // 优先还原已存 responseJson；快照损坏时回退为已保存评分，均分仍读数据库最新值。
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

    // 失败更新同样携带 attemptToken，避免旧执行者把新尝试的状态改成 FAILED；诊断更新失败仅记录日志。
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
        // 把本题题号、追问次数、评分和模型建议组装成 LiteFlow 上下文；规则服务不负责生成题干或推进数据库。
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
            // 规则异常上抛到这里时返回 null，调用方回退到模型追问建议；这与规则服务内部 failOpen 的处理要合起来看。
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

    // 辅助评分依据解析：历史考察点为空或 JSON 损坏时返回空列表，由评分提示词采用通用标准。
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

    // 优先采用前端传入的请求 ID；缺省时由会话、题号和回答生成稳定摘要，兼容未传 ID 的调用方。
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

    // 统一题号格式，例如 003 → 3、03-F01 → 3-F1，用于比较请求题号和数据库游标。
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
