package com.hewei.hzyjy.aimeeting.llm;

import cn.hutool.core.util.StrUtil;

import java.util.Collections;
import java.util.List;

/**
 * 一次模型调用的最小描述：场景（用于取配置与埋点）+ 系统提示词 + 历史消息 + 本轮用户输入。
 */
public final class LlmRequest {

    private final String scene;

    private final String systemPrompt;

    private final List<LlmMessage> history;

    private final String userPrompt;

    private final Double temperature;

    private final Integer maxTokens;

    private LlmRequest(String scene,
                       String systemPrompt,
                       List<LlmMessage> history,
                       String userPrompt,
                       Double temperature,
                       Integer maxTokens) {
        this.scene = scene;
        this.systemPrompt = systemPrompt;
        this.history = history == null ? Collections.emptyList() : List.copyOf(history);
        this.userPrompt = userPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public static LlmRequest of(String scene, String systemPrompt, String userPrompt) {
        return new LlmRequest(scene, systemPrompt, null, userPrompt, null, null);
    }

    public LlmRequest withHistory(List<LlmMessage> values) {
        return new LlmRequest(scene, systemPrompt, values, userPrompt, temperature, maxTokens);
    }

    public LlmRequest withTemperature(Double value) {
        return new LlmRequest(scene, systemPrompt, history, userPrompt, value, maxTokens);
    }

    public LlmRequest withMaxTokens(Integer value) {
        return new LlmRequest(scene, systemPrompt, history, userPrompt, temperature, value);
    }

    public String getScene() {
        return scene;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<LlmMessage> getHistory() {
        return history;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public boolean hasSystemPrompt() {
        return StrUtil.isNotBlank(systemPrompt);
    }

    public boolean hasHistory() {
        return history != null && !history.isEmpty();
    }
}
