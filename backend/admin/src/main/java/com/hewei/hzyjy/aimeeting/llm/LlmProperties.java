package com.hewei.hzyjy.aimeeting.llm;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 直连大模型的统一配置。
 *
 * <p>P2 之前面试链路通过星辰 Agent 工作流调用模型，模型名、密钥、提示词都存放在
 * {@code agent_properties} 表里；改造后密钥与地址收敛到本配置，提示词收敛到
 * {@code classpath:prompts} 目录，业务代码不再感知供应商。</p>
 *
 * <p>供应商只需满足 OpenAI Chat Completions 协议即可接入：DeepSeek 走官方端点，
 * Qwen 走 DashScope 的 compatible-mode 端点，二者仅 base-url / model 不同。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-meeting.llm")
public class LlmProperties {

    /**
     * 供应商标识：deepseek 走 DeepSeek SDK 适配，其余按 OpenAI 兼容协议处理。
     */
    private String provider = "deepseek";

    private String baseUrl = "https://api.deepseek.com";

    private String apiKey;

    private String model = "deepseek-chat";

    private Double temperature = 0.3;

    private Integer maxTokens = 4096;

    private Integer timeoutSeconds = 120;

    /**
     * 分场景覆盖，key 为场景名（如 interview-answer-evaluation）。
     */
    private Map<String, SceneOptions> scenes = new LinkedHashMap<>();

    public String resolveModel(String scene) {
        SceneOptions options = sceneOptions(scene);
        return StrUtil.blankToDefault(options == null ? null : options.getModel(), model);
    }

    public Double resolveTemperature(String scene) {
        SceneOptions options = sceneOptions(scene);
        Double value = options == null ? null : options.getTemperature();
        return value == null ? temperature : value;
    }

    public Integer resolveMaxTokens(String scene) {
        SceneOptions options = sceneOptions(scene);
        Integer value = options == null ? null : options.getMaxTokens();
        return value == null ? maxTokens : value;
    }

    public Duration resolveTimeout(String scene) {
        SceneOptions options = sceneOptions(scene);
        Integer value = options == null ? null : options.getTimeoutSeconds();
        return Duration.ofSeconds(value == null ? timeoutSeconds : value);
    }

    /**
     * 所有场景里最大的超时预算。
     *
     * <p>ChatModel 内部持有共享的 HTTP 客户端（连接池），无法按场景切换超时，
     * 因此客户端层取"最宽的那一档"，真正的按场景收紧交给 Resilience4j 的 TimeLimiter。
     * 如果这里只取全局 {@code timeoutSeconds}，出题场景配的 180 秒会被悄悄压回 120 秒。</p>
     */
    public Duration resolveMaxTimeout() {
        int max = timeoutSeconds == null ? 120 : timeoutSeconds;
        if (scenes != null) {
            for (SceneOptions options : scenes.values()) {
                if (options != null && options.getTimeoutSeconds() != null) {
                    max = Math.max(max, options.getTimeoutSeconds());
                }
            }
        }
        return Duration.ofSeconds(max);
    }

    public boolean isConfigured() {
        return StrUtil.isNotBlank(apiKey);
    }

    private SceneOptions sceneOptions(String scene) {
        if (StrUtil.isBlank(scene) || scenes == null) {
            return null;
        }
        return scenes.get(scene);
    }

    @Data
    public static class SceneOptions {

        private String model;

        private Double temperature;

        private Integer maxTokens;

        private Integer timeoutSeconds;
    }
}
