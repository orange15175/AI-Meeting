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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单题评分：把题目、回答、考察要点与候选人背景套进版本化提示词，直连模型拿到结构化评分。
 *
 * <p>考察要点只在这里进入提示词，不随接口下发，避免前端提前看到"标准答案"。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewEvaluationService {

    private static final String KEY_SCORE = "score";
    private static final String KEY_LOGIC_OK = "logic_ok";
    private static final String KEY_MISSING_POINTS = "missing_points";
    private static final String KEY_FEEDBACK = "feedback";
    private static final String KEY_FOLLOW_UP_NEEDED = "follow_up_needed";
    private static final String KEY_FOLLOW_UP_QUESTION = "follow_up_question";

    private final InterviewResumeContextReader resumeContextReader;
    private final InterviewJobContextReader jobContextReader;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;
    private final PromptLibrary promptLibrary;

    public Map<String, Object> evaluateAnswer(
            String sessionId,
            String requestId,
            String questionNumber,
            String questionContent,
            String answerContent,
            List<String> scoringPoints) {

        if (StrUtil.isBlank(questionContent) || StrUtil.isBlank(answerContent)) {
            log.warn("Skip evaluation because question or answer is blank, sessionId={}, questionNumber={}",
                    sessionId, questionNumber);
            return null;
        }

        PromptTemplate prompt = promptLibrary.get(PromptLibrary.INTERVIEW_ANSWER_EVALUATION);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("question", questionContent);
        variables.put("answer", answerContent);
        variables.put("scoringPoints", scoringPoints == null || scoringPoints.isEmpty()
                ? "（未提供，请按岗位通用标准评价）"
                : String.join("；", scoringPoints));
        variables.put("resumeContext", resumeContextReader.readAsText(sessionId, 1500));
        variables.put("jobContext", jobContextReader.readAsText(sessionId, 1000));

        LlmRequest request = LlmRequest.of(
                InterviewAiGuardStage.INTERVIEW_EVALUATION,
                prompt.renderSystem(),
                prompt.renderUser(variables)
        );

        String callKey = interviewAiInvoker.buildCallKey(
                InterviewAiGuardStage.INTERVIEW_EVALUATION,
                sessionId,
                questionNumber,
                answerContent
        );

        long start = System.currentTimeMillis();
        String aiResponse;
        try {
            aiResponse = interviewAiInvoker.call(request, InterviewAiGuardStage.INTERVIEW_EVALUATION, callKey);
        } catch (InterviewAiGuardException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Evaluation call failed, sessionId={}, requestId={}", sessionId, requestId, ex);
            return null;
        }

        Map<String, Object> parsed = interviewResponseParser.parseEvaluationResult(aiResponse);
        if (parsed == null || parsed.isEmpty()) {
            log.warn("Evaluation response is not valid JSON, sessionId={}, requestId={}, prompt={}, length={}",
                    sessionId, requestId, prompt.versionLabel(), aiResponse == null ? 0 : aiResponse.length());
            return null;
        }

        Map<String, Object> normalized = normalize(parsed);
        fillDefaults(normalized);
        log.info("Evaluation finished, sessionId={}, questionNumber={}, prompt={}, score={}, costMs={}",
                sessionId,
                questionNumber,
                prompt.versionLabel(),
                normalized.get(KEY_SCORE),
                System.currentTimeMillis() - start);
        return normalized;
    }

    private Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>(raw);

        Integer score = interviewResponseParser.parseScoreFromResponse(normalized, KEY_SCORE);
        if (score != null) {
            normalized.put(KEY_SCORE, score);
        } else {
            normalized.remove(KEY_SCORE);
        }

        if (normalized.containsKey(KEY_LOGIC_OK)) {
            normalized.put(KEY_LOGIC_OK, interviewResponseParser.asBoolean(normalized.get(KEY_LOGIC_OK)));
        }
        if (normalized.containsKey(KEY_MISSING_POINTS)) {
            List<String> missingPoints = interviewResponseParser.asStringList(normalized.get(KEY_MISSING_POINTS));
            normalized.put(KEY_MISSING_POINTS, missingPoints == null ? Collections.emptyList() : missingPoints);
        }
        if (normalized.containsKey(KEY_FEEDBACK)) {
            String feedback = interviewResponseParser.asString(normalized.get(KEY_FEEDBACK));
            normalized.put(KEY_FEEDBACK, feedback == null ? "" : feedback);
        }
        if (normalized.containsKey(KEY_FOLLOW_UP_NEEDED)) {
            normalized.put(KEY_FOLLOW_UP_NEEDED,
                    interviewResponseParser.asBoolean(normalized.get(KEY_FOLLOW_UP_NEEDED)));
        }
        if (normalized.containsKey(KEY_FOLLOW_UP_QUESTION)) {
            String question = interviewResponseParser.asString(normalized.get(KEY_FOLLOW_UP_QUESTION));
            normalized.put(KEY_FOLLOW_UP_QUESTION, question == null ? "" : question);
        }
        return normalized;
    }

    private void fillDefaults(Map<String, Object> result) {
        if (!result.containsKey(KEY_MISSING_POINTS)) {
            result.put(KEY_MISSING_POINTS, Collections.emptyList());
        }
        if (!result.containsKey(KEY_FEEDBACK)) {
            result.put(KEY_FEEDBACK, "");
        }
        if (!result.containsKey(KEY_FOLLOW_UP_QUESTION)) {
            result.put(KEY_FOLLOW_UP_QUESTION, "");
        }
        if (!result.containsKey(KEY_FOLLOW_UP_NEEDED)) {
            boolean inferred = Boolean.FALSE.equals(result.get(KEY_LOGIC_OK))
                    || !interviewResponseParser.asStringList(result.get(KEY_MISSING_POINTS)).isEmpty()
                    || StrUtil.isNotBlank(interviewResponseParser.asString(result.get(KEY_FOLLOW_UP_QUESTION)));
            result.put(KEY_FOLLOW_UP_NEEDED, inferred);
        }
    }
}
