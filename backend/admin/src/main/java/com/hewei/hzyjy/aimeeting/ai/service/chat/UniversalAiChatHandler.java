package com.hewei.hzyjy.aimeeting.ai.service.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.aimeeting.ai.api.io.resp.AiChatStreamRespDTO;
import com.hewei.hzyjy.aimeeting.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiPropertiesDO;
import com.hewei.hzyjy.aimeeting.ai.enums.AiPropritiesType;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.llm.LlmProperties;
import com.hewei.hzyjy.aimeeting.toolkit.llm.AIContentAccumulator;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 普通聊天处理器，基于 Spring AI 实现。
 *
 * <p>重构后只服务 DeepSeek 直连：密钥、地址与模型固定取 {@code ai-meeting.llm.*}，
 * 不再读取 {@code ai_properties} 表的旧供应商配置。非 deepseek 类型由
 * {@code AiChatHandlerFactory} 直接拒绝。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UniversalAiChatHandler implements AiChatHandler {

    /**
     * 服务端统一大模型配置。普通聊天（DeepSeek）的密钥/地址/模型固定取自此配置，
     * 不再读取 {@code ai_properties} 表的旧密钥，避免两处密钥不一致导致 401。
     */
    private final LlmProperties llmProperties;

    @Override
    public String getType() {
        return "universal";
    }

    public boolean supports(String type) {
        return AiPropritiesType.isSupported(type);
    }

    @Override
    public void streamToSink(AiPropertiesDO aiProperties, String userMessage, List<AiMessageHistoryRespDTO> historyMessages,
                             FluxSink<String> sink, AIContentAccumulator accumulator) throws Exception {
        ChatClient chatClient = createChatClient(aiProperties);
        List<Message> messages = buildMessages(aiProperties, userMessage, historyMessages);

        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] streamError = new Throwable[1];

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .subscribe(
                        chatResponse -> {
                            try {
                                Generation generation = chatResponse.getResult();
                                if (generation != null) {
                                    String content = generation.getOutput().getText();
                                    if (StrUtil.isNotEmpty(content)) {
                                        AiChatStreamRespDTO resp = AiChatStreamRespDTO.builder()
                                                .type("content")
                                                .content(content)
                                                .build();
                                        sink.next(JSON.toJSONString(resp));
                                        accumulator.appendSimpleContent(content);
                                    }

                                    String reasoning = null;
                                    try {
                                        java.lang.reflect.Method getReasoningContent = generation.getOutput().getClass().getMethod("getReasoningContent");
                                        Object reasoningVal = getReasoningContent.invoke(generation.getOutput());
                                        if (reasoningVal != null) {
                                            reasoning = reasoningVal.toString();
                                        }
                                    } catch (Exception ignore) {
                                    }

                                    if (reasoning == null) {
                                        Object reasoningObj = generation.getOutput().getMetadata().get("reasoningContent");
                                        if (reasoningObj != null) {
                                            reasoning = reasoningObj.toString();
                                        }
                                    }

                                    if (StrUtil.isNotEmpty(reasoning)) {
                                        AiChatStreamRespDTO resp = AiChatStreamRespDTO.builder()
                                                .type("reasoning_content")
                                                .content(reasoning)
                                                .build();
                                        sink.next(JSON.toJSONString(resp));
                                        accumulator.appendReasoningChunk(reasoning.getBytes());
                                    }
                                }
                            } catch (Exception e) {
                                log.error("流式响应处理错误", e);
                                streamError[0] = e;
                                sink.error(e);
                                latch.countDown();
                            }
                        },
                        error -> {
                            log.error("流式响应发生错误", error);
                            streamError[0] = error;
                            sink.error(error);
                            latch.countDown();
                        },
                        latch::countDown
                );

        if (!latch.await(5, TimeUnit.MINUTES)) {
            throw new RuntimeException("AI 响应超时");
        }

        if (streamError[0] != null) {
            throw new RuntimeException(streamError[0]);
        }
    }

    private ChatClient createChatClient(AiPropertiesDO aiProperties) {
        String baseUrl = aiProperties.getApiUrl();
        String apiKey = aiProperties.getApiKey();
        String modelName = aiProperties.getModelName();

        if (AiPropritiesType.DEEPSEEK.getType().equalsIgnoreCase(aiProperties.getAiType())) {
            // 普通聊天固定使用服务端统一 DeepSeek 配置（ai-meeting.llm.*），
            // 不再读 ai_properties 表的旧密钥，避免与面试链路密钥不一致导致 401。
            baseUrl = StrUtil.blankToDefault(llmProperties.getBaseUrl(), baseUrl);
            apiKey = llmProperties.getApiKey();
            modelName = StrUtil.blankToDefault(llmProperties.getModel(), modelName);
        }

        if (StrUtil.isBlank(baseUrl)) {
            baseUrl = AiPropritiesType.DEEPSEEK.getDefaultBaseUrl();
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new ClientException("AI API Key 未配置");
        }

        RestClient.Builder restClientBuilder = RestClient.builder()
                .defaultHeaders(headers -> {
                    if (StrUtil.isNotBlank(aiProperties.getProjectId())) {
                        headers.add("OpenAI-Project", aiProperties.getProjectId());
                    }
                    if (StrUtil.isNotBlank(aiProperties.getOrganizationId())) {
                        headers.add("OpenAI-Organization", aiProperties.getOrganizationId());
                    }
                });

        if (AiPropritiesType.DEEPSEEK.getType().equalsIgnoreCase(aiProperties.getAiType())) {
            DeepSeekApi deepSeekApi = new DeepSeekApi(
                    baseUrl,
                    new SimpleApiKey(apiKey),
                    new LinkedMultiValueMap<>(),
                    "/chat/completions",
                    "/beta",
                    restClientBuilder,
                    WebClient.builder(),
                    new DefaultResponseErrorHandler()
            );

            DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
                    .model(modelName);

            if (aiProperties.getMaxTokens() != null) {
                optionsBuilder.maxTokens(aiProperties.getMaxTokens());
            }

            DeepSeekChatOptions options = optionsBuilder.build();
            ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

            DeepSeekChatModel chatModel = new DeepSeekChatModel(
                    deepSeekApi,
                    options,
                    toolCallingManager,
                    RetryTemplate.defaultInstance(),
                    ObservationRegistry.NOOP
            );
            return ChatClient.builder(chatModel)
                    .defaultOptions(options)
                    .build();
        }

        OpenAiApi openAiApi = new OpenAiApi(
                baseUrl,
                new SimpleApiKey(apiKey),
                new LinkedMultiValueMap<>(),
                "/chat/completions",
                "/embeddings",
                restClientBuilder,
                WebClient.builder(),
                new DefaultResponseErrorHandler()
        );

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(modelName);

        if (aiProperties.getMaxTokens() != null) {
            optionsBuilder.maxTokens(aiProperties.getMaxTokens());
        }

        OpenAiChatOptions options = optionsBuilder.build();
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        OpenAiChatModel chatModel = new OpenAiChatModel(
                openAiApi,
                options,
                toolCallingManager,
                RetryTemplate.defaultInstance(),
                ObservationRegistry.NOOP
        );

        return ChatClient.builder(chatModel)
                .defaultOptions(options)
                .build();
    }

    private List<Message> buildMessages(AiPropertiesDO aiProperties, String userMessage, List<AiMessageHistoryRespDTO> historyMessages) {
        List<Message> messages = new ArrayList<>();

        if (StrUtil.isNotBlank(aiProperties.getSystemPrompt())) {
            messages.add(new SystemMessage(aiProperties.getSystemPrompt()));
        }

        if (CollUtil.isNotEmpty(historyMessages)) {
            for (AiMessageHistoryRespDTO history : historyMessages) {
                String content = history.getMessageContent();
                if (history.getMessageType() != null && history.getMessageType() == 1) {
                    messages.add(new UserMessage(content));
                } else {
                    messages.add(new AssistantMessage(content));
                }
            }
        }

        messages.add(new UserMessage(userMessage));
        return messages;
    }
}
