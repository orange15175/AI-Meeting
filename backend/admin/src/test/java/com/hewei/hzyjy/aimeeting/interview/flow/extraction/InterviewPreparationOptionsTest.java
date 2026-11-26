package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题量与难度入参的解析规则：未传走默认、非法直接拒绝、配置默认值不把请求打回。
 */
class InterviewPreparationOptionsTest {

    private static final int DEFAULT_COUNT = 10;
    private static final int MIN = 5;
    private static final int MAX = 20;

    @Test
    @DisplayName("两个参数都不传时使用配置默认值与智能匹配")
    void usesDefaultsWhenAbsent() {
        InterviewPreparationOptions.ParseResult result =
                InterviewPreparationOptions.parse(null, null, DEFAULT_COUNT, MIN, MAX);

        assertThat(result.ok()).isTrue();
        assertThat(result.options().questionCount()).isEqualTo(DEFAULT_COUNT);
        assertThat(result.options().difficulty()).isEqualTo(InterviewDifficulty.AUTO);
    }

    @Test
    @DisplayName("题量为空白串时同样走默认值，不当作 0 题")
    void treatsBlankCountAsAbsent() {
        InterviewPreparationOptions.ParseResult result =
                InterviewPreparationOptions.parse("   ", "", DEFAULT_COUNT, MIN, MAX);

        assertThat(result.ok()).isTrue();
        assertThat(result.options().questionCount()).isEqualTo(DEFAULT_COUNT);
        assertThat(result.options().difficulty()).isEqualTo(InterviewDifficulty.AUTO);
    }

    @Test
    @DisplayName("难度接受中文标签与忽略大小写的枚举名")
    void acceptsLabelAndEnumName() {
        assertThat(parseDifficulty("挑战")).isEqualTo(InterviewDifficulty.CHALLENGE);
        assertThat(parseDifficulty("基础")).isEqualTo(InterviewDifficulty.BASIC);
        assertThat(parseDifficulty("advanced")).isEqualTo(InterviewDifficulty.ADVANCED);
        assertThat(parseDifficulty("AUTO")).isEqualTo(InterviewDifficulty.AUTO);
    }

    @Test
    @DisplayName("题量为非数字时拒绝，并给出区间提示")
    void rejectsNonNumericCount() {
        InterviewPreparationOptions.ParseResult result =
                InterviewPreparationOptions.parse("十道", null, DEFAULT_COUNT, MIN, MAX);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("5 到 20");
    }

    @Test
    @DisplayName("题量越界时拒绝，不静默夹到边界")
    void rejectsCountOutOfRange() {
        assertThat(InterviewPreparationOptions.parse("4", null, DEFAULT_COUNT, MIN, MAX).ok()).isFalse();
        assertThat(InterviewPreparationOptions.parse("21", null, DEFAULT_COUNT, MIN, MAX).ok()).isFalse();
        assertThat(InterviewPreparationOptions.parse("5", null, DEFAULT_COUNT, MIN, MAX).ok()).isTrue();
        assertThat(InterviewPreparationOptions.parse("20", null, DEFAULT_COUNT, MIN, MAX).ok()).isTrue();
    }

    @Test
    @DisplayName("难度无法识别时拒绝，提示列出全部可选项")
    void rejectsUnknownDifficulty() {
        InterviewPreparationOptions.ParseResult result =
                InterviewPreparationOptions.parse("10", "地狱", DEFAULT_COUNT, MIN, MAX);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("智能匹配", "基础", "进阶", "挑战");
    }

    @Test
    @DisplayName("配置默认值越界时夹进合法区间，而不是让所有未传题量的请求失败")
    void clampsMisconfiguredDefault() {
        InterviewPreparationOptions.ParseResult result =
                InterviewPreparationOptions.parse(null, null, 50, MIN, MAX);

        assertThat(result.ok()).isTrue();
        assertThat(result.options().questionCount()).isEqualTo(MAX);
    }

    @Test
    @DisplayName("区间配置颠倒时仍可解析：上限被抬到不小于下限")
    void survivesInvertedRange() {
        InterviewPreparationOptions.ParseResult result =
                InterviewPreparationOptions.parse(null, null, DEFAULT_COUNT, 20, 5);

        assertThat(result.ok()).isTrue();
        assertThat(result.options().questionCount()).isEqualTo(20);
    }

    private InterviewDifficulty parseDifficulty(String raw) {
        InterviewPreparationOptions.ParseResult result =
                InterviewPreparationOptions.parse("10", raw, DEFAULT_COUNT, MIN, MAX);
        assertThat(result.ok()).as(result.errorMessage()).isTrue();
        return result.options().difficulty();
    }
}
