package com.hewei.hzyjy.aimeeting.llm;

/**
 * 多轮对话中的一条历史消息。
 */
public record LlmMessage(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }
}
