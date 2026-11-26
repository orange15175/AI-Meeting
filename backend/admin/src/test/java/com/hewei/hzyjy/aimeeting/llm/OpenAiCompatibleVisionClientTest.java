package com.hewei.hzyjy.aimeeting.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉调用的失败提示：必须能指出该改哪个配置，而不是只丢一句 I/O error。
 */
class OpenAiCompatibleVisionClientTest {

    private final VisionProperties properties = new VisionProperties();

    private final OpenAiCompatibleVisionClient client =
            new OpenAiCompatibleVisionClient(properties, new ObjectMapper());

    @Test
    @DisplayName("域名无法解析时指向 VISION_BASE_URL")
    void reportsUnknownHost() {
        String message = client.describeFailure(new UnknownHostException("cn-beijing.maas.qianwenapi.com"));

        assertTrue(message.contains("无法解析"), message);
        assertTrue(message.contains("VISION_BASE_URL"), message);
        assertTrue(message.contains("dashscope.aliyuncs.com"), message);
    }

    @Test
    @DisplayName("Spring 包装后的 ResourceAccessException 仍能识别底层原因")
    void unwrapsResourceAccessException() {
        ResourceAccessException wrapped =
                new ResourceAccessException("I/O error on POST request", new UnknownHostException("bad-host"));

        String message = client.describeFailure(wrapped);

        assertTrue(message.contains("无法解析"), message);
    }

    @Test
    @DisplayName("连接被拒绝时提示检查网络或代理")
    void reportsConnectFailure() {
        String message = client.describeFailure(new ConnectException("Connection refused"));

        assertTrue(message.contains("无法连接"), message);
        assertTrue(message.contains("代理"), message);
    }

    @Test
    @DisplayName("超时提示带上当前超时秒数，便于直接调参")
    void reportsTimeout() {
        properties.setTimeoutSeconds(45);

        String message = client.describeFailure(new SocketTimeoutException("Read timed out"));

        assertTrue(message.contains("超时"), message);
        assertTrue(message.contains("45 秒"), message);
        assertTrue(message.contains("VISION_TIMEOUT_SECONDS"), message);
    }

    @Test
    @DisplayName("未识别的异常保留原始消息，不做二次包装")
    void keepsOriginalMessageAsFallback() {
        String message = client.describeFailure(new IllegalStateException("upstream 500"));

        assertTrue(message.contains("upstream 500"), message);
    }
}
