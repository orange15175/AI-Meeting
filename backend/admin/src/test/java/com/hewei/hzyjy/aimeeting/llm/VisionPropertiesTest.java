package com.hewei.hzyjy.aimeeting.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionPropertiesTest {

    @Test
    void defaultsPointToPresetVisionEndpoint() {
        VisionProperties properties = new VisionProperties();

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                properties.getBaseUrl());
        assertEquals("qwen3.8-omni-flash", properties.getModel());
    }

    @Test
    void notConfiguredWhenApiKeyBlank() {
        VisionProperties properties = new VisionProperties();

        assertFalse(properties.isConfigured());

        properties.setApiKey("   ");
        assertFalse(properties.isConfigured());
    }

    @Test
    void configuredWhenApiKeyPresent() {
        VisionProperties properties = new VisionProperties();
        properties.setApiKey("sk-test");

        assertTrue(properties.isConfigured());
    }

    @Test
    void resolveTimeoutFallsBackWhenNull() {
        VisionProperties properties = new VisionProperties();
        properties.setTimeoutSeconds(null);

        assertEquals(Duration.ofSeconds(120), properties.resolveTimeout());
    }
}
