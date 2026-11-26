package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 本场出题的两个可选项：题量与难度倾向。
 *
 * <p>入参用字符串接收再在这里解析，而不是直接绑成 {@code int} / 枚举：
 * 表单没传时 Spring 绑基本类型会拿到 0 或抛类型转换异常，拿不到"未传即默认"的兼容语义；
 * 枚举同理，非法值会在参数绑定阶段就变成 400，前端拿不到可读提示。</p>
 *
 * @param questionCount 本场主问题数量，已落在 [min, max] 内
 * @param difficulty    整场难度倾向，未传时为 {@link InterviewDifficulty#AUTO}
 */
public record InterviewPreparationOptions(int questionCount, InterviewDifficulty difficulty) {

    /**
     * 解析结果：{@code errorMessage} 非空表示入参不可用，调用方必须直接拒绝，不能再消耗一次模型调用。
     */
    public record ParseResult(InterviewPreparationOptions options, String errorMessage) {

        public boolean ok() {
            return errorMessage == null;
        }
    }

    /**
     * 解析并校验入参。空值走默认，非法值给出可直接展示给用户的文案。
     *
     * @param rawCount     原始题量入参（可空）
     * @param rawDifficulty 原始难度入参（可空）
     * @param defaultCount 未指定题量时的配置默认值
     * @param min          允许的最小题量
     * @param max          允许的最大题量
     */
    public static ParseResult parse(String rawCount, String rawDifficulty,
                                    int defaultCount, int min, int max) {
        int lower = Math.max(1, min);
        int upper = Math.max(lower, max);

        Integer count = parseCount(rawCount, lower, upper);
        if (count == null) {
            return new ParseResult(null,
                    "题目数量需为 " + lower + " 到 " + upper + " 之间的整数");
        }
        if (rawCount == null || StrUtil.isBlank(rawCount)) {
            // 未传时用配置默认值，但仍夹进合法区间：配置写错不应该把请求打回。
            count = Math.min(Math.max(defaultCount, lower), upper);
        }

        InterviewDifficulty difficulty = InterviewDifficulty.from(rawDifficulty);
        if (difficulty == null) {
            return new ParseResult(null, "难度只能是 " + InterviewDifficulty.labels());
        }
        return new ParseResult(new InterviewPreparationOptions(count, difficulty), null);
    }

    // 空串返回 -1 的哨兵，交给调用方走默认值；非数字或越界返回 null 表示非法。
    private static Integer parseCount(String rawCount, int lower, int upper) {
        if (StrUtil.isBlank(rawCount)) {
            return -1;
        }
        String value = rawCount.trim();
        if (!NumberUtil.isInteger(value)) {
            return null;
        }
        int count = Integer.parseInt(value);
        return count >= lower && count <= upper ? count : null;
    }
}
