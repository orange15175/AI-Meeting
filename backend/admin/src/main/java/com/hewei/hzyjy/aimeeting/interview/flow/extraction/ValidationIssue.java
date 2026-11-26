package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

/**
 * 一条校验结论。
 *
 * <p>{@code critical=true} 表示必须修复才能落库；{@code critical=false} 表示已经在内存里
 * 做了可解释的降级（例如证据无法核实就清空 evidence 并把 source 置为 NONE），题目仍可使用。
 * 这个区分很重要：不是所有"不完美"都值得再花一次模型调用。</p>
 *
 * @param code     问题类型，同时用于指标 reason 标签
 * @param detail   面向日志与修复提示词的具体描述，不含简历原文
 * @param category 关联题目的分类，证据类问题用于指标维度
 * @param critical 是否阻断落库
 */
public record ValidationIssue(IssueCode code, String detail, String category, boolean critical) {

    public static ValidationIssue critical(IssueCode code, String detail) {
        return new ValidationIssue(code, detail, null, true);
    }

    public static ValidationIssue critical(IssueCode code, String detail, String category) {
        return new ValidationIssue(code, detail, category, true);
    }

    public static ValidationIssue soft(IssueCode code, String detail, String category) {
        return new ValidationIssue(code, detail, category, false);
    }

    /**
     * 问题类型。名称即指标 reason 标签，新增时注意基数可控。
     */
    public enum IssueCode {
        ROOT,
        COUNT,
        STRUCTURE,
        CONTENT,
        ENUM,
        SCORING_POINTS,
        EVIDENCE,
        DUPLICATE,
        SUGGESTIONS,
        RESUME_SCORE,
        RESUME_SUMMARY
    }
}
