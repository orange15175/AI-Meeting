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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
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

    /**
     * 建连超时：与"模型生成要多久"无关，只防 DNS/握手卡死，固定短值即可。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final LlmProperties properties;

    private volatile ChatClient chatClient;

    @PostConstruct
    void logConfiguration() {
        // 把实际生效的读超时打出来：出题超时问题排查时，第一条要确认的就是"配置有没有进到客户端"。
        log.info("LLM configured, provider={}, baseUrl={}, defaultModel={}, apiKeyPresent={}, readTimeoutSeconds={}",
                properties.getProvider(),
                properties.getBaseUrl(),
                properties.getModel(),
                properties.isConfigured(),
                properties.resolveMaxTimeout().toSeconds());
    }

    @Override
    // 同步模型调用：取得复用客户端，组装 system/history/user 消息和场景参数，等待返回正文；结构化业务校验由上层负责。
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

    // 消息组装顺序为系统消息 → 有效历史消息 → 本次用户消息；空历史项跳过，不能把全部消息混成一个角色。
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

    // 请求参数按场景解析模型、温度和输出上限；DeepSeek 使用专用 Options，其余走 OpenAI 兼容 Options。
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

    // 首次调用时懒加载客户端并缓存；双重检查配合同步块，避免并发首次请求重复创建底层连接资源。
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

    // 【关键节点：供应商适配】先检查 API Key，按 provider 选择 DeepSeek 或 OpenAI 兼容实现；此处 SDK 重试设为 1 次尝试。
    private ChatModel buildChatModel() {
        if (!properties.isConfigured()) {
            throw new ClientException("大模型 API Key 未配置，请设置环境变量 LLM_API_KEY 或 application-local.yaml");
        }
        Duration readTimeout = properties.resolveMaxTimeout();
        RestClient.Builder restClientBuilder = restClientBuilder(readTimeout);
        WebClient.Builder webClientBuilder = webClientBuilder(readTimeout);
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
                    webClientBuilder,
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
                webClientBuilder,
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

    /**
     * 同步调用用的 RestClient。
     *
     * <p>{@code RestClient.builder()} 不传工厂时，由 {@code ClientHttpRequestFactories} 按 classpath
     * 自行挑选（本项目会选中 Reactor 实现），超时值完全不受 {@code ai-meeting.llm.timeout-seconds}
     * 控制——出题配了 180 秒，实际仍在十几秒处被连接层掐断，对外表现为 AI_UNAVAILABLE。
     * 这里显式指定 JDK HttpClient 与读超时，让配置真正生效。</p>
     */
    private RestClient.Builder restClientBuilder(Duration readTimeout) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * 流式调用用的 WebClient。reactor-netty 的 responseTimeout 管的是"等首字节"，
     * 与读超时语义一致，这里保持同一个预算，避免两条路径行为不一致。
     */
    private WebClient.Builder webClientBuilder(Duration readTimeout) {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create().responseTimeout(readTimeout)));
    }

    private boolean isDeepSeek() {
        return PROVIDER_DEEPSEEK.equalsIgnoreCase(StrUtil.trimToEmpty(properties.getProvider()));
    }
}
