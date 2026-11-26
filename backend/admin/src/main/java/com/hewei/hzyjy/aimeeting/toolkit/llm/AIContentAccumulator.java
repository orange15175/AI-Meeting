package com.hewei.hzyjy.aimeeting.toolkit.llm;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 流式回复累积器：把增量片段拼成完整正文与推理内容，供落库使用。
 *
 * <p>P2 之后所有调用都直连模型，拿到的是纯文本增量，不再需要解析星辰/Coze
 * 的 SSE 包装格式，因此这里只保留最小能力。</p>
 */
@Component
public class AIContentAccumulator {

    private final StringBuilder contentBuilder = new StringBuilder();

    private final StringBuilder reasoningBuilder = new StringBuilder();

    public void appendSimpleContent(String content) {
        if (content != null) {
            contentBuilder.append(content);
        }
    }

    public void appendReasoningChunk(byte[] chunk) {
        if (chunk != null) {
            reasoningBuilder.append(new String(chunk, StandardCharsets.UTF_8));
        }
    }

    public void appendReasoningChunk(String reasoning) {
        if (reasoning != null) {
            reasoningBuilder.append(reasoning);
        }
    }

    public String getFullContent() {
        return contentBuilder.toString();
    }

    public String getFullReasoningContent() {
        return reasoningBuilder.toString();
    }

}
