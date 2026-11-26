package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import java.util.List;
import java.util.Locale;

/**
 * 校验结果：既给出可落库的批次，也给出问题清单。
 *
 * <p>{@link #accepted()} 为 true 时批次可直接转换落库；为 false 时应发起一次修复调用，
 * 修复后仍不通过则整次出题失败，不能挑一部分题目凑数。</p>
 */
public record ValidationResult(InterviewQuestionBatch batch, List<ValidationIssue> issues) {

    public ValidationResult {
        batch = batch == null ? InterviewQuestionBatch.empty() : batch;
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean accepted() {
        return issues.stream().noneMatch(ValidationIssue::critical);
    }

    /**
     * 只取阻断性问题：降级项不进入修复提示词，避免把一次可用的结果再送去重写。
     */
    public List<String> criticalDetails() {
        return issues.stream()
                .filter(ValidationIssue::critical)
                .map(issue -> "[" + issue.code().name().toLowerCase(Locale.ROOT) + "] " + issue.detail())
                .toList();
    }

    /**
     * 首个阻断问题的类型，用作指标 reason；全部通过时返回 none。
     */
    public String reasonTag() {
        return issues.stream()
                .filter(ValidationIssue::critical)
                .map(issue -> issue.code().name().toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse("none");
    }
}
