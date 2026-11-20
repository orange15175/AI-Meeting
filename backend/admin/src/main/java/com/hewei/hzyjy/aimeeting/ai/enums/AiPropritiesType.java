package com.hewei.hzyjy.aimeeting.ai.enums;

import lombok.Getter;

/**
 * 普通聊天模型类型枚举。
 *
 * <p>重构后普通聊天固定直连 DeepSeek，密钥、地址与模型统一由
 * {@code ai-meeting.llm.*} 提供（见 LlmProperties）。星火/豆包/OpenAI 等
 * 供应商入口已下线，不再保留兼容映射；未知类型一律归为 {@link #OTHER} 并拒绝调用。
 */
@Getter
public enum AiPropritiesType {

    DEEPSEEK(4, "deepseek", "DeepSeek", "https://api.deepseek.com"),
    OTHER(99, "other", "其他", "");

    private final Integer code;
    private final String type;
    private final String desc;
    private final String defaultBaseUrl;

    AiPropritiesType(Integer code, String type, String desc, String defaultBaseUrl) {
        this.code = code;
        this.type = type;
        this.desc = desc;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    /**
     * 根据 code 获取类型。
     */
    public static AiPropritiesType getByCode(Integer code) {
        for (AiPropritiesType type : AiPropritiesType.values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return OTHER;
    }

    /**
     * 根据 type 字符串获取类型。旧数据里的 generalv3.5（星火）等已下线类型不再兼容，
     * 统一返回 {@link #OTHER}，避免误把已退役配置当成可用模型。
     */
    public static AiPropritiesType getByType(String type) {
        if (type == null) {
            return OTHER;
        }
        for (AiPropritiesType t : AiPropritiesType.values()) {
            if (t.getType().equalsIgnoreCase(type)) {
                return t;
            }
        }
        return OTHER;
    }

    /**
     * 检查是否支持该类型（当前仅 DeepSeek）。
     */
    public static boolean isSupported(String type) {
        return DEEPSEEK.getType().equalsIgnoreCase(type);
    }
}
