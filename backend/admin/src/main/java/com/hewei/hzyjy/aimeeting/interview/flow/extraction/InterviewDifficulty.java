package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 整场面试的难度倾向。
 *
 * <p>它只影响提示词里的一句倾向说明，不改变校验规则：无论选哪个难度，
 * 题目结构与证据约束都一致，区别只在"从多深开始问"。这样难度不会成为绕过校验的后门。</p>
 *
 * <p>枚举名落库与传输，中文标签只用于前端展示，提示词文案只用于拼模板——三者分开，
 * 避免以后改文案时把历史数据的枚举值一起改掉。</p>
 */
public enum InterviewDifficulty {

    /**
     * 智能匹配：由模型按简历信息决定起点，缺省值。
     */
    AUTO("智能匹配", "智能匹配（按简历可见的经历、职责和技术信息确定起点；信息不足时从基础到中等）"),

    /**
     * 基础：概念与常见应用，适合转岗、校招或首次准备。
     */
    BASIC("基础", "基础（侧重概念与常见应用，少量进阶题用于区分）"),

    /**
     * 进阶：原理、设计与排障。
     */
    ADVANCED("进阶", "进阶（侧重原理、设计与线上排障）"),

    /**
     * 挑战：复杂场景、边界与取舍。
     */
    CHALLENGE("挑战", "挑战（侧重复杂场景、边界条件与方案取舍）");

    private final String label;
    private final String promptText;

    InterviewDifficulty(String label, String promptText) {
        this.label = label;
        this.promptText = promptText;
    }

    public String label() {
        return label;
    }

    public String promptText() {
        return promptText;
    }

    /**
     * 解析入参：接受枚举名或中文标签，大小写与首尾空白不敏感。
     *
     * <p>返回 null 表示无法识别，由调用方给出可展示的错误，而不是在这里抛异常——
     * 这是用户输入而不是程序不变量。</p>
     */
    public static InterviewDifficulty from(String raw) {
        String value = StrUtil.trimToNull(raw);
        if (value == null) {
            return AUTO;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        for (InterviewDifficulty difficulty : values()) {
            if (difficulty.name().equals(upper) || difficulty.label.equals(value)) {
                return difficulty;
            }
        }
        return null;
    }

    /**
     * 全部可选项的中文标签，用于拼错误提示，避免提示里漏掉新增难度。
     */
    public static String labels() {
        StringBuilder builder = new StringBuilder();
        for (InterviewDifficulty difficulty : values()) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(difficulty.label);
        }
        return builder.toString();
    }
}
