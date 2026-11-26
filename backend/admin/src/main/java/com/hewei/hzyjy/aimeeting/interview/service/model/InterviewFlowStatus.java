package com.hewei.hzyjy.aimeeting.interview.service.model;

/**
 * 面试流程阶段。以真实状态表达，不把"处理中"写成失败。
 */
public enum InterviewFlowStatus {

    INIT,
    ASKING,
    // 声明的评分阶段；当前流水线处理中主要使用提交行的 PROCESSING，不保证会话持久化到此枚举值。
    EVALUATING,
    FOLLOW_UP,
    COMPLETED;

    public static InterviewFlowStatus from(String value) {
        if (value == null || value.isBlank()) {
            return INIT;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return INIT;
        }
    }

    public boolean isTerminal() {
        return this == COMPLETED;
    }
}
