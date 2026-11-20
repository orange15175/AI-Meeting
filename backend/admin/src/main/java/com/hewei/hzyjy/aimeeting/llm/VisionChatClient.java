package com.hewei.hzyjy.aimeeting.llm;

import java.util.List;

/**
 * 视觉模型客户端：接受提示词与若干图片，返回识别文本。
 *
 * <p>与 {@link LlmChatClient} 分离，二者地址/密钥/模型各自独立。</p>
 */
public interface VisionChatClient {

    /**
     * 是否已配置密钥（未配置时应用照常启动，仅视觉功能不可用）。
     */
    boolean isConfigured();

    /**
     * 识别图片并返回文本。未配置密钥时抛 ClientException。
     */
    String recognize(String prompt, List<VisionImage> images);

    /**
     * 当前生效的模型描述，用于启动日志与排障。
     */
    String describe();
}
