package com.hewei.hzyjy.aimeeting.llm;

/**
 * 送入视觉模型的一张图片：MIME 类型 + 纯 base64 数据（不含 data: 前缀）。
 */
public record VisionImage(String mimeType, String base64Data) {
}
