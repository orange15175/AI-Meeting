package com.hewei.hzyjy.aimeeting.interview.shared;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.AiCallGuardService;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.aimeeting.llm.LlmChatClient;
import com.hewei.hzyjy.aimeeting.llm.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 面试文本 AI 的统一调用入口：串联熔断/并发隔离/超时保护，最后落到 {@link LlmChatClient}。
 *
 * <p>P3 起不再做分布式 single-flight：同一题的重复请求由 MySQL 提交行
 * 的唯一键与 attemptToken 拦截，不需要在模型层再复用一次的"任务中心"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewAiInvoker {

    private final LlmChatClient llmChatClient;
    private final AiCallGuardService aiCallGuardService;

    /**
     * 【关键节点：文本模型入口】出题、评分和追问都经过此处；callKey 用于调用标识，不能当成结果缓存命中。
     * 本入口保护文本模型 complete；材料 OCR 由 InterviewMaterialExtractor 使用独立视觉客户端。
     */
    public String call(LlmRequest request, String stage, String callKey) throws Exception {
        String safeStage = StrUtil.blankToDefault(stage, InterviewAiGuardStage.INTERVIEW_EVALUATION);
        String key = StrUtil.blankToDefault(callKey, safeStage + "|no-key");
        String content = aiCallGuardService.execute(safeStage, key, () -> llmChatClient.complete(request));
        if (StrUtil.isBlank(content)) {
            log.warn("LLM returned blank content, stage={}, scene={}, key={}",
                    safeStage, request.getScene(), key);
        }
        return content;
    }

    public String buildCallKey(
            String stage,
            String sessionId,
            String questionNumber,
            String answerContent) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeQuestionNumber = StrUtil.blankToDefault(StrUtil.trimToEmpty(questionNumber), "-");
        String safeAnswerHash = StrUtil.isBlank(answerContent)
                ? "-"
                : DigestUtil.sha256Hex(answerContent.trim()).substring(0, 16);
        return safeStage + "|" + safeSessionId + "|" + safeQuestionNumber + "|" + safeAnswerHash;
    }

    public String buildCallKey(String stage, String sessionId, String businessKey) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeBusinessKey = StrUtil.blankToDefault(StrUtil.trimToEmpty(businessKey), "-");
        return safeStage + "|" + safeSessionId + "|" + safeBusinessKey;
    }
}
