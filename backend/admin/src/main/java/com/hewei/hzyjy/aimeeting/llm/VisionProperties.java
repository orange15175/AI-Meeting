package com.hewei.hzyjy.aimeeting.llm;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 视觉模型配置：用于图片简历、扫描版 PDF 的 OCR 与内容理解。
 *
 * <p>与文本模型 {@link LlmProperties} 完全隔离：地址、模型、密钥各自独立，
 * 走 OpenAI 兼容的 Chat Completions 多模态协议。默认指向千问 MaaS 的
 * qwen3.8-omni-flash 端点，密钥留空由使用者通过 {@code VISION_API_KEY} 注入。</p>
 *
 * <p>未配置密钥时 {@link #isConfigured()} 返回 false，应用照常启动，
 * 所有既有文本链路不受影响；只有真正需要视觉识别的功能才会提示未配置。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-meeting.vision")
public class VisionProperties {

    /**
     * OpenAI 兼容端点，需包含 /v1 前缀。
     *
     * <p>这里的字段默认值必须与 application.yaml 保持一致：Spring 绑定无论如何都会覆盖它，
     * 但一旦有人删掉 yaml 里的 base-url（或脱离 Spring 直接 new 本类做单测），
     * 字段默认值就会变成实际生效值。旧的 cn-beijing.maas.qianwenapi.com 已无法解析，
     * 保留它只会制造"看起来配了、实际 DNS 失败"的故障。</p>
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 视觉模型名。 */
    private String model = "qwen3.8-omni-flash";

    /** 密钥留空，由 VISION_API_KEY 注入。 */
    private String apiKey;

    private Double temperature = 0.1;

    private Integer maxTokens = 4096;

    private Integer timeoutSeconds = 120;

    /** 扫描版 PDF 最多渲染送模型的页数，控制成本。 */
    private Integer maxImagePages = 6;

    /** 渲染 DPI，越高越清晰但 base64 越大。 */
    private Integer renderDpi = 150;

    public boolean isConfigured() {
        return StrUtil.isNotBlank(apiKey);
    }

    public Duration resolveTimeout() {
        Integer seconds = timeoutSeconds == null ? 120 : timeoutSeconds;
        return Duration.ofSeconds(seconds);
    }
}
