package com.hewei.hzyjy.aimeeting.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewJobContextReader;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewResumeContextReader;
import com.hewei.hzyjy.aimeeting.llm.LlmRequest;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptLibrary;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptTemplate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 追问生成：直连模型判定是否值得深挖，值得则产出一句针对性追问。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpService {

    private static final String KEY_ASK_TO_USER = "ask_to_user";
    private static final String KEY_END_INTERVIEW = "end_interview";

    private final InterviewResumeContextReader resumeContextReader;
    private final InterviewJobContextReader jobContextReader;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;
    private final PromptLibrary promptLibrary;

    // 追问文案用例：检查上限 → 生成候选题号 → 调模型 → 使用评分提示兜底；返回结果本身不会插入题库。
    public FollowUpQuestionResult generateFollowUpQuestion(
            String sessionId,
            String requestId,
            String currentQuestionNumber,
            String currentQuestion,
            String answerContent,
            String fallbackFollowUpQuestion,
            Integer currentFollowUpCount,
            Integer maxFollowUp) {

        int safeCurrentFollowUpCount = Math.max(0, currentFollowUpCount == null ? 0 : currentFollowUpCount);
        int safeMaxFollowUp = maxFollowUp == null || maxFollowUp <= 0 ? 2 : maxFollowUp;
        // 【关键节点：追问上限】生成前再次检查轮数；后续状态机还会按会话上限检查实际题号推进。
        if (safeCurrentFollowUpCount >= safeMaxFollowUp) {
            return FollowUpQuestionResult.empty();
        }

        // 题号只用于表达本主问题的第几次追问，例如 3-F1；实际下一题题号还要经过状态机规划。
        String mainQuestionNumber = resolveMainQuestionNumber(currentQuestionNumber);
        String questionNumber = buildFollowUpQuestionNumber(mainQuestionNumber, safeCurrentFollowUpCount + 1);
        if (StrUtil.isBlank(questionNumber)) {
            return FollowUpQuestionResult.empty();
        }

        String generatedQuestion = null;
        try {
            generatedQuestion = generateByModel(
                    sessionId, requestId, currentQuestion, answerContent, safeCurrentFollowUpCount, safeMaxFollowUp);
        } catch (InterviewAiGuardException ex) {
            log.warn("Follow-up fast-failed, sessionId={}, code={}", sessionId, ex.getErrorCode());
        } catch (Exception ex) {
            log.warn("Follow-up generation failed, sessionId={}", sessionId, ex);
        }

        // 【关键节点：追问降级】生成模型不可用时尝试评分模型给出的追问提示；两者都为空则跳过追问，保留评分。
        String questionContent = StrUtil.isNotBlank(generatedQuestion)
                ? generatedQuestion
                : sanitizeFollowUpQuestion(fallbackFollowUpQuestion);
        if (StrUtil.isBlank(questionContent)) {
            return FollowUpQuestionResult.empty();
        }
        return new FollowUpQuestionResult(questionNumber, questionContent, safeCurrentFollowUpCount + 1);
    }

    private String generateByModel(
            String sessionId,
            String requestId,
            String currentQuestion,
            String answerContent,
            int currentFollowUpCount,
            int maxFollowUp) throws Exception {

        if (StrUtil.isBlank(currentQuestion) || StrUtil.isBlank(answerContent)) {
            return null;
        }

        // 构造追问提示词：关注当前回答和剩余追问次数，简历背景与岗位要求分别限长注入。
        PromptTemplate prompt = promptLibrary.get(PromptLibrary.INTERVIEW_FOLLOW_UP);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("question", currentQuestion);
        variables.put("answer", answerContent);
        variables.put("followUpCount", currentFollowUpCount);
        variables.put("maxFollowUp", maxFollowUp);
        variables.put("resumeContext", resumeContextReader.readAsText(sessionId, 1000));
        variables.put("jobContext", jobContextReader.readAsText(sessionId, 800));

        LlmRequest request = LlmRequest.of(
                InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                prompt.renderSystem(),
                prompt.renderUser(variables)
        );
        String callKey = interviewAiInvoker.buildCallKey(
                InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                sessionId,
                currentQuestion,
                answerContent
        );

        String response = interviewAiInvoker.call(request, InterviewAiGuardStage.INTERVIEW_FOLLOWUP, callKey);
        Map<String, Object> result = interviewResponseParser.extractStructuredResult(
                response, KEY_ASK_TO_USER, KEY_END_INTERVIEW);
        if (result == null || result.isEmpty()) {
            log.warn("Follow-up response is not valid JSON, sessionId={}, requestId={}, prompt={}",
                    sessionId, requestId, prompt.versionLabel());
            return null;
        }
        // 模型的 end_interview 在这里仅表示本次不产出追问；不直接结束整场面试，外层仍可能使用备选追问。
        if (interviewResponseParser.asBoolean(result.get(KEY_END_INTERVIEW))) {
            log.info("Follow-up decided to end, sessionId={}, requestId={}", sessionId, requestId);
            return null;
        }
        return sanitizeFollowUpQuestion(interviewResponseParser.asString(result.get(KEY_ASK_TO_USER)));
    }

    // 清理 none/null 等占位符，补问号并限制题干长度；这是文案规范化，不是对事实或难度的验证。
    private String sanitizeFollowUpQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)) {
            return null;
        }
        if (!normalized.endsWith("?") && !normalized.endsWith("？")) {
            normalized = normalized + "？";
        }
        return clip(normalized, 100);
    }

    private String resolveMainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        int separatorIndex = normalized.indexOf("-F");
        if (separatorIndex > 0) {
            return normalized.substring(0, separatorIndex);
        }
        return normalized;
    }

    private String buildFollowUpQuestionNumber(String mainQuestionNumber, int followUpCount) {
        if (StrUtil.isBlank(mainQuestionNumber) || followUpCount <= 0) {
            return null;
        }
        return mainQuestionNumber + "-F" + followUpCount;
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    @Getter
    public static final class FollowUpQuestionResult {
        private final String questionNumber;
        private final String questionContent;
        private final Integer followUpCount;

        private FollowUpQuestionResult(String questionNumber, String questionContent, Integer followUpCount) {
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
            this.followUpCount = followUpCount;
        }

        public static FollowUpQuestionResult empty() {
            return new FollowUpQuestionResult(null, null, 0);
        }

        public boolean hasQuestion() {
            return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent);
        }
    }
}
