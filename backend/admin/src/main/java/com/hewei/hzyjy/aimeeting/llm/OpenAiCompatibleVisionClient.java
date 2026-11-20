package com.hewei.hzyjy.aimeeting.llm;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视觉模型客户端：走 OpenAI 兼容的 Chat Completions 多模态协议。
 *
 * <p>不复用 {@link SpringAiLlmChatClient} 的原因：视觉模型的地址、密钥、模型名
 * 与文本模型彼此独立，且 Spring AI 的 Media 构造在不同版本间不稳定；这里用
 * RestClient 直接拼多模态 content（text + image_url），对千问 MaaS 的
 * compatible-mode 端点透明可用。</p>
 *
 * <p>密钥留空时不构建任何 HTTP 客户端，{@link #isConfigured()} 为 false；
 * 只有真正调用 {@link #recognize} 时才抛配置提示，不影响应用启动和文本链路。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleVisionClient implements VisionChatClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final VisionProperties properties;
    private final ObjectMapper objectMapper;

    private volatile RestClient restClient;

    @PostConstruct
    void logConfiguration() {
        log.info("Vision model configured, baseUrl={}, model={}, apiKeyPresent={}",
                properties.getBaseUrl(), properties.getModel(), properties.isConfigured());
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public String describe() {
        return properties.getModel();
    }

    @Override
    public String recognize(String prompt, List<VisionImage> images) {
        if (!isConfigured()) {
            throw new ClientException("视觉模型未配置，请设置环境变量 VISION_API_KEY");
        }
        if (images == null || images.isEmpty()) {
            throw new ClientException("视觉识别需要至少一张图片");
        }
        long start = System.currentTimeMillis();
        try {
            String response = client().post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequestBody(prompt, images))
                    .retrieve()
                    .body(String.class);
            String content = extractContent(response);
            log.debug("Vision recognize finished, model={}, costMs={}, images={}, length={}",
                    properties.getModel(), System.currentTimeMillis() - start, images.size(),
                    content == null ? 0 : content.length());
            return content == null ? "" : content;
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("Vision recognize failed, model={}", properties.getModel(), e);
            throw new ClientException("视觉模型调用失败: " + e.getMessage());
        }
    }

    private String buildRequestBody(String prompt, List<VisionImage> images) {
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", StrUtil.blankToDefault(prompt, "请识别图片内容"));
        content.add(textPart);
        for (VisionImage image : images) {
            Map<String, Object> imagePart = new LinkedHashMap<>();
            imagePart.put("type", "image_url");
            Map<String, Object> imageUrl = new LinkedHashMap<>();
            imageUrl.put("url", "data:" + image.mimeType() + ";base64," + image.base64Data());
            imagePart.put("image_url", imageUrl);
            content.add(imagePart);
        }

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", List.of(userMessage));
        body.put("temperature", properties.getTemperature() == null ? 0.1 : properties.getTemperature());
        body.put("max_tokens", properties.getMaxTokens() == null ? 4096 : properties.getMaxTokens());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ClientException("视觉请求序列化失败: " + e.getMessage());
        }
    }

    /**
     * 兼容两种返回：content 为字符串，或为 [{type:text,text:...}] 数组。
     */
    private String extractContent(String response) {
        if (StrUtil.isBlank(response)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : content) {
                    JsonNode text = part.path("text");
                    if (text.isTextual()) {
                        sb.append(text.asText());
                    }
                }
                return sb.toString();
            }
            return "";
        } catch (Exception e) {
            log.warn("Failed to parse vision response: {}", e.getMessage());
            return "";
        }
    }

    private RestClient client() {
        RestClient current = restClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (restClient == null) {
                restClient = buildClient();
            }
            return restClient;
        }
    }

    private RestClient buildClient() {
        Duration timeout = properties.resolveTimeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .requestFactory(factory)
                .build();
    }
}
