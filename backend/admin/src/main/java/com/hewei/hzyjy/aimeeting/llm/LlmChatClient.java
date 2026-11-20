package com.hewei.hzyjy.aimeeting.llm;

import java.util.function.Consumer;

/**
 * 直连大模型客户端。上层只依赖这个接口，便于替换供应商或在测试中打桩。
 */
public interface LlmChatClient {

    /**
     * 一次性拿完整回复。
     */
    String complete(LlmRequest request);

    /**
     * 流式拿增量片段，回调按到达顺序逐段触发。
     */
    void stream(LlmRequest request, Consumer<String> onDelta);

    /**
     * 当前生效的供应商/模型描述，用于启动日志与排障。
     */
    String describe();
}
