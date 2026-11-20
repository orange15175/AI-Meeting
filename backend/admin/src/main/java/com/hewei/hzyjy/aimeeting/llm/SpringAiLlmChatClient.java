package com.hewei.hzyjy.aimeeting.llm;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 Spring AI 的直连实现，DeepSeek 走官方适配，其余供应商按 OpenAI 兼容协议接入。
 *
 * <p>客户端在首次调用时构建并复用：Spring AI 的 ChatModel 内部持有 HTTP 客户端与连接池，
 * 每次请求都重建既浪费连接也无法复用底层资源。模型名/温度/长度按场景在调用时通过
 * {@link ChatOptions} 覆盖，因此一个客户端即可服务全部场景。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiLlmChatClient implements LlmChatClient {

    private static final String PROVIDER_DEEPSEEK = "deepseek";

    private final LlmProperties properties;

    private volatile ChatClient chatClient;

    @PostConstruct
    void logConfiguration() {
        log.info("LLM configured, provider={}, baseUrl={}, defaultModel={}, apiKeyPresent={}",
                properties.getProvider(),
                properties.getBaseUrl(),
                properties.getModel(),
                properties.isConfigured());
    }

    @Override
    public String complete(LlmRequest request) {
        long start = System.currentTimeMillis();
        String content = client()
                .prompt()
                .messages(buildMessages(request))
                .options(buildOptions(request))
                .call()
                .content();
        log.debug("LLM completion finished, scene={}, model={}, costMs={}, length={}",
                request.getScene(),
                properties.resolveModel(request.getScene()),
                System.currentTimeMillis() - start,
                content == null ? 0 : content.length());
        return content == null ? "" : content;
    }

    @Override
    public void stream(LlmRequest request, Consumer<String> onDelta) {
        client()
                .prompt()
                .messages(buildMessages(request))
                .options(buildOptions(request))
                .stream()
                .content()
                .doOnNext(delta -> {
                    if (StrUtil.isNotEmpty(delta)) {
                        onDelta.accept(delta);
                    }
                })
                .blockLast(properties.resolveTimeout(request.getScene()));
    }

    @Override
    public String describe() {
        return properties.getProvider() + "/" + properties.getModel();
    }

    private List<Message> buildMessages(LlmRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.hasSystemPrompt()) {
            messages.add(new SystemMessage(request.getSystemPrompt()));
        }
        if (request.hasHistory()) {
            for (LlmMessage history : request.getHistory()) {
                if (StrUtil.isBlank(history.content())) {
                    continue;
                }
                messages.add(history.role() == LlmMessage.Role.USER
                        ? new UserMessage(history.content())
                        : new AssistantMessage(history.content()));
            }
        }
        messages.add(new UserMessage(StrUtil.blankToDefault(request.getUserPrompt(), "")));
        return messages;
    }

    private ChatOptions buildOptions(LlmRequest request) {
        String scene = request.getScene();
        String model = properties.resolveModel(scene);
        Double temperature = request.getTemperature() == null
                ? properties.resolveTemperature(scene)
                : request.getTemperature();
        Integer maxTokens = request.getMaxTokens() == null
                ? properties.resolveMaxTokens(scene)
                : request.getMaxTokens();

        if (isDeepSeek()) {
            return DeepSeekChatOptions.builder()
                    .model(model)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();
        }
        return OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    private ChatClient client() {
        ChatClient current = chatClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (chatClient == null) {
                chatClient = ChatClient.builder(buildChatModel()).build();
            }
            return chatClient;
        }
    }

    private ChatModel buildChatModel() {
        if (!properties.isConfigured()) {
            throw new ClientException("大模型 API Key 未配置，请设置环境变量 LLM_API_KEY 或 application-local.yaml");
        }
        RestClient.Builder restClientBuilder = RestClient.builder();
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(1).build();
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        if (isDeepSeek()) {
            DeepSeekApi api = new DeepSeekApi(
                    properties.getBaseUrl(),
                    new SimpleApiKey(properties.getApiKey()),
                    new LinkedMultiValueMap<>(),
                    "/chat/completions",
                    "/beta",
                    restClientBuilder,
                    WebClient.builder(),
                    new DefaultResponseErrorHandler()
            );
            DeepSeekChatOptions defaultOptions = DeepSeekChatOptions.builder()
                    .model(properties.getModel())
                    .build();
            return new DeepSeekChatModel(
                    api,
                    defaultOptions,
                    toolCallingManager,
                    retryTemplate,
                    ObservationRegistry.NOOP
            );
        }

        OpenAiApi api = new OpenAiApi(
                properties.getBaseUrl(),
                new SimpleApiKey(properties.getApiKey()),
                new LinkedMultiValueMap<>(),
                "/chat/completions",
                "/embeddings",
                restClientBuilder,
                WebClient.builder(),
                new DefaultResponseErrorHandler()
        );
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .build();
        return new OpenAiChatModel(
                api,
                defaultOptions,
                toolCallingManager,
                retryTemplate,
                ObservationRegistry.NOOP
        );
    }

    private boolean isDeepSeek() {
        return PROVIDER_DEEPSEEK.equalsIgnoreCase(StrUtil.trimToEmpty(properties.getProvider()));
    }
}
