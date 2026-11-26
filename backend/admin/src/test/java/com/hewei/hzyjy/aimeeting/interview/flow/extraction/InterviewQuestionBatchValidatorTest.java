package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出题校验器：只覆盖规则本身，不启动 Spring、不连模型。
 */
class InterviewQuestionBatchValidatorTest {

    private static final String RESUME = """
            张三，三年 Java 后端经验。
            负责订单与库存模块，使用 Redis 缓存商品库存，并参与交易链路重构。
            """;

    private static final String JD = "岗位要求：熟悉 Redis 与高并发交易系统，了解消息队列。";

    private final InterviewQuestionBatchValidator validator = new InterviewQuestionBatchValidator();

    @Test
    @DisplayName("完整合规的批次通过，题号顺序与数组一致")
    void acceptsValidBatch() {
        String json = batch(
                question("你在订单项目中如何避免并发扣减库存时出现超卖？", "PROJECT", "MEDIUM", "库存并发",
                        "RESUME", "负责订单与库存模块", "", points("说明风险", "给出一致性方案", "说明适用条件"))
                        + "," + question("解释一下 Redis 缓存与数据库不一致的常见处理方式。", "FUNDAMENTAL", "EASY", "缓存一致性",
                        "NONE", "", "", points("说明不一致来源", "给出处理方案")),
                "{}",
                suggestions3(),
                75);

        ValidationResult result = validator.validate(json, RESUME, null, 2);

        assertThat(result.accepted()).isTrue();
        assertThat(result.criticalDetails()).isEmpty();
        assertThat(result.batch().questions()).hasSize(2);
        assertThat(result.batch().questions().get(0).category()).isEqualTo("PROJECT");
        assertThat(result.batch().questions().get(1).source()).isEqualTo("NONE");
        assertThat(result.batch().resumeScore()).isEqualTo(75);
        assertThat(result.batch().interviewType()).isEqualTo("Java 后端");
    }

    @Test
    @DisplayName("题数与要求不一致时阻断，原因是 count")
    void rejectsWrongCount() {
        String json = batch(
                question("只有一道题？", "FUNDAMENTAL", "EASY", "基础", "NONE", "", "", points("要点一", "要点二")),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, null, 3);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("count");
        assertThat(result.criticalDetails()).anyMatch(detail -> detail.contains("数量为 1"));
    }

    @Test
    @DisplayName("题干不是字符串时阻断，不做对象转字符串的兜底")
    void rejectsNonStringContent() {
        String json = batch(
                """
                {"content":{"text":"对象形式"},"category":"FUNDAMENTAL","difficulty":"EASY","topic":"基础","source":"NONE","evidence":"","requirement":"","scoringPoints":["要点一","要点二"]}
                """,
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("content");
    }

    @Test
    @DisplayName("评分要点含数字时阻断，不静默转字符串")
    void rejectsNumericScoringPoint() {
        String json = batch(
                question("解释一下幂等设计的基本思路。", "FUNDAMENTAL", "MEDIUM", "幂等", "NONE", "", "",
                        "[1,\"要点二\"]"),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("scoring_points");
    }

    @Test
    @DisplayName("规范化后完全重复的题干被拒绝")
    void rejectsDuplicateContent() {
        String content = "解释一下 Redis 缓存与数据库的一致性处理。";
        String json = batch(
                question(content, "FUNDAMENTAL", "EASY", "缓存", "NONE", "", "", points("要点一", "要点二"))
                        + "," + question(content + " ", "FUNDAMENTAL", "EASY", "缓存", "NONE", "", "", points("要点一", "要点二")),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, null, 2);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("duplicate");
    }

    @Test
    @DisplayName("PROJECT 题缺少可核实证据时阻断")
    void rejectsProjectWithoutEvidence() {
        String json = batch(
                question("你在库存模块里怎么做的？", "PROJECT", "MEDIUM", "库存", "RESUME", "负责支付对账系统", "",
                        points("要点一", "要点二")),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("evidence");
        assertThat(result.issues())
                .anyMatch(issue -> issue.code() == ValidationIssue.IssueCode.EVIDENCE
                        && issue.critical()
                        && "PROJECT".equals(issue.category()));
    }

    @Test
    @DisplayName("非 PROJECT 题证据核实失败时降级为 NONE，但仍可落库")
    void downgradesUnverifiedCitation() {
        String json = batch(
                question("如果让你用 Redis 缓存库存，如何避免与数据库不一致？", "JOB", "MEDIUM", "缓存一致性",
                        "JD", "熟悉 Kafka 流处理", "", points("说明不一致来源", "给出兜底方案")),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, JD, 1);

        assertThat(result.accepted()).isTrue();
        assertThat(result.batch().questions().get(0).source()).isEqualTo("NONE");
        assertThat(result.batch().questions().get(0).evidence()).isEmpty();
        assertThat(result.issues())
                .anyMatch(issue -> issue.code() == ValidationIssue.IssueCode.EVIDENCE && !issue.critical());
    }

    @Test
    @DisplayName("无 JD 时引用 JD 属于阻断错误")
    void rejectsJdCitationWithoutJd() {
        String json = batch(
                question("岗位要求里提到的消息队列，你会如何选型？", "JOB", "MEDIUM", "消息队列",
                        "JD", "了解消息队列", "", points("要点一", "要点二")),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("evidence");
    }

    @Test
    @DisplayName("无 JD 时填写 requirement 会被清空，但没有阻断问题")
    void clearsRequirementWithoutJd() {
        String json = batch(
                question("解释一下索引失效的常见原因。", "FUNDAMENTAL", "EASY", "索引", "NONE", "", "熟悉 MySQL",
                        points("要点一", "要点二")),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isTrue();
        assertThat(result.batch().questions().get(0).requirement()).isEmpty();
    }

    @Test
    @DisplayName("OCR 换行与全角标点差异不影响证据匹配")
    void matchesEvidenceAcrossOcrNoise() {
        String resumeWithBreak = "负责订单与库存\n模块，使用 Redis 缓存商品库存。";
        String json = batch(
                question("你在订单项目中如何避免超卖？", "PROJECT", "MEDIUM", "库存并发",
                        "RESUME", "负责订单与库存模块，", "", points("要点一", "要点二")),
                "{}", suggestions3(), 60);

        ValidationResult result = validator.validate(json, resumeWithBreak, null, 1);

        assertThat(result.accepted()).isTrue();
        assertThat(result.batch().questions().get(0).source()).isEqualTo("RESUME");
    }

    @Test
    @DisplayName("resumeScore 为数字字符串时阻断，类型必须严格")
    void rejectsStringResumeScore() {
        String json = batch(
                question("解释一下 CAP。", "FUNDAMENTAL", "EASY", "分布式", "NONE", "", "", points("要点一", "要点二")),
                "{}", suggestions3(), 75).replace("\"resumeScore\":75", "\"resumeScore\":\"75\"");

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("resume_score");
    }

    @Test
    @DisplayName("建议条数不足 3 条时阻断")
    void rejectsTooFewSuggestions() {
        String json = batch(
                question("解释一下索引结构。", "FUNDAMENTAL", "EASY", "索引", "NONE", "", "", points("要点一", "要点二")),
                "{}", "[\"只有一条建议\"]", 60);

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("suggestions");
    }

    @Test
    @DisplayName("被 Markdown 代码块包裹的输出仍可解析")
    void acceptsFencedJson() {
        String json = "```json\n" + batch(
                question("解释一下索引结构。", "FUNDAMENTAL", "EASY", "索引", "NONE", "", "", points("要点一", "要点二")),
                "{}", suggestions3(), 60) + "\n```";

        ValidationResult result = validator.validate(json, RESUME, null, 1);

        assertThat(result.accepted()).isTrue();
    }

    @Test
    @DisplayName("完全无法解析时给出 root 原因")
    void rejectsNonJson() {
        ValidationResult result = validator.validate("抱歉，我无法完成该任务。", RESUME, null, 1);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonTag()).isEqualTo("root");
    }

    // ===== 测试数据构造 =====

    private static String batch(String questions, String jdContext, String suggestions, int score) {
        return "{\"questions\":[" + questions + "],\"jdContext\":" + jdContext
                + ",\"suggestions\":" + suggestions
                + ",\"type\":\"Java 后端\",\"resumeScore\":" + score + ",\"resumeSummary\":\"三年 Java 后端经验\"}";
    }

    private static String question(String content,
                                   String category,
                                   String difficulty,
                                   String topic,
                                   String source,
                                   String evidence,
                                   String requirement,
                                   String pointsJson) {
        return "{\"content\":\"" + content + "\",\"category\":\"" + category + "\",\"difficulty\":\"" + difficulty
                + "\",\"topic\":\"" + topic + "\",\"source\":\"" + source + "\",\"evidence\":\"" + evidence
                + "\",\"requirement\":\"" + requirement + "\",\"scoringPoints\":" + pointsJson + "}";
    }

    private static String points(String... values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(values[i]).append('"');
        }
        return builder.append(']').toString();
    }

    private static String suggestions3() {
        return "[\"说明承担的具体职责\",\"补充关键设计及原因\",\"描述可核实的结果\"]";
    }
}
