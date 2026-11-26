package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 出题结果的严格校验：只读原始 JSON 类型，不做"对象转字符串"这类静默兜底。
 *
 * <p>为什么不用 {@code asStringList}：那个方法会把对象、数字一起 {@code String.valueOf}，
 * 再过滤空项，结果既掩盖了模型的结构错误，又让题目与按题号索引的考察点错位。
 * 这里改成"类型不对就是错误"，把问题暴露给修复流程，而不是带病入库。</p>
 *
 * <p>校验分两级：{@link ValidationIssue#critical()} 阻断落库、触发一次修复；
 * 非阻断项在内存里做可解释的降级（证据无法核实就清空并置 NONE），不额外消耗模型调用。</p>
 */
@Component
public class InterviewQuestionBatchValidator {

    private static final int MAX_CONTENT_LENGTH = 300;
    private static final int MAX_SCORING_POINT_LENGTH = 200;
    private static final int MAX_SUMMARY_LENGTH = 200;

    /**
     * 证据短于这个长度就不算可核实引用，避免"Java"这类两三个字造成假命中。
     */
    private static final int MIN_EVIDENCE_LENGTH = 4;

    /**
     * 证据匹配时忽略的标点：覆盖中英文常见标点，NFKC 之后全角标点已折叠为半角，两套都列上。
     */
    private static final String IGNORED_PUNCTUATION = ",.;:!?()[]{}<>\"'`~-_/\\|·、。，；：！？（）【】《》〈〉「」『』“”‘’—…";

    private static final Set<String> CATEGORIES = Set.of("PROJECT", "JOB", "FUNDAMENTAL");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Set<String> SOURCES = Set.of("RESUME", "JD", "NONE");

    private static final String DEFAULT_INTERVIEW_TYPE = "综合技术";

    /**
     * 校验一次模型输出。
     *
     * @param rawJson       模型原始文本（允许被 Markdown 代码块包裹）
     * @param resumeText    简历文本，用于证据回查
     * @param jdText        岗位要求文本，未提供时为 null 或空
     * @param expectedCount 本次要求的题目数量
     */
    public ValidationResult validate(String rawJson, String resumeText, String jdText, int expectedCount) {
        List<ValidationIssue> issues = new ArrayList<>();
        JSONObject root = parseRoot(rawJson);
        if (root == null) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.ROOT, "根节点不是合法 JSON 对象"));
            return new ValidationResult(InterviewQuestionBatch.empty(), issues);
        }
        if (expectedCount <= 0) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.COUNT, "题量配置非法：" + expectedCount));
        }

        boolean jdPresent = StrUtil.isNotBlank(jdText);
        List<InterviewQuestionDraft> questions = readQuestions(
                root.get("questions"), expectedCount, resumeText, jdText, jdPresent, issues);
        Map<String, Object> jdContext = readJdContext(root.get("jdContext"), jdPresent, issues);
        List<String> suggestions = readSuggestions(root.get("suggestions"), issues);
        String interviewType = readInterviewType(root.get("type"), issues);
        Integer resumeScore = readResumeScore(root.get("resumeScore"), issues);
        String resumeSummary = readResumeSummary(root.get("resumeSummary"), issues);

        return new ValidationResult(
                new InterviewQuestionBatch(questions, jdContext, suggestions, interviewType, resumeScore, resumeSummary),
                issues);
    }

    // ===== 题目数组 =====

    private List<InterviewQuestionDraft> readQuestions(Object raw,
                                                       int expectedCount,
                                                       String resumeText,
                                                       String jdText,
                                                       boolean jdPresent,
                                                       List<ValidationIssue> issues) {
        if (!(raw instanceof JSONArray array)) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.STRUCTURE, "questions 必须是数组"));
            return List.of();
        }
        // 先判数量：模型少给或多给题都不允许"凑合着用"，数量不符本身就送修复。
        if (array.size() != expectedCount) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.COUNT,
                    "questions 数量为 " + array.size() + "，要求 " + expectedCount));
        }

        String normalizedResume = normalizeForMatch(resumeText);
        String normalizedJd = normalizeForMatch(jdText);
        Set<String> seenContents = new HashSet<>();
        List<InterviewQuestionDraft> questions = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            int index = i + 1;
            if (!(array.get(i) instanceof JSONObject item)) {
                issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.STRUCTURE,
                        "第 " + index + " 题不是 JSON 对象"));
                continue;
            }
            InterviewQuestionDraft draft = readQuestion(
                    item, index, normalizedResume, normalizedJd, jdPresent, seenContents, issues);
            if (draft != null) {
                questions.add(draft);
            }
        }
        return questions;
    }

    private InterviewQuestionDraft readQuestion(JSONObject item,
                                                int index,
                                                String normalizedResume,
                                                String normalizedJd,
                                                boolean jdPresent,
                                                Set<String> seenContents,
                                                List<ValidationIssue> issues) {
        String content = requiredText(item, "content", index, false, ValidationIssue.IssueCode.CONTENT, issues);
        String category = requiredEnum(item, "category", CATEGORIES, index, issues);
        String difficulty = requiredEnum(item, "difficulty", DIFFICULTIES, index, issues);
        String topic = requiredText(item, "topic", index, false, ValidationIssue.IssueCode.STRUCTURE, issues);
        String source = requiredEnum(item, "source", SOURCES, index, issues);
        String evidence = requiredText(item, "evidence", index, true, ValidationIssue.IssueCode.STRUCTURE, issues);
        String requirement = requiredText(item, "requirement", index, true, ValidationIssue.IssueCode.STRUCTURE, issues);
        List<String> scoringPoints = readScoringPoints(item.get("scoringPoints"), index, issues);

        // 缺字段的题目无法构造完整草稿，其余字段的错误已经在上面逐项记录。
        if (content == null || category == null || difficulty == null || topic == null
                || source == null || evidence == null || requirement == null || scoringPoints == null) {
            return null;
        }

        if (content.length() > MAX_CONTENT_LENGTH) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.CONTENT,
                    "第 " + index + " 题题干超过 " + MAX_CONTENT_LENGTH + " 字"));
        }
        // 规范化后完全相同的题干直接拒绝；语义相近的交给人审，不做规则断言。
        String duplicateKey = normalizeForMatch(content);
        if (!duplicateKey.isEmpty() && !seenContents.add(duplicateKey)) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.DUPLICATE,
                    "第 " + index + " 题与前面的题目重复"));
        }

        ResolvedCitation citation = resolveCitation(
                index, category, source, evidence, normalizedResume, normalizedJd, jdPresent, issues);
        String resolvedRequirement = requirement;
        if (!jdPresent && StrUtil.isNotBlank(requirement)) {
            issues.add(ValidationIssue.soft(ValidationIssue.IssueCode.STRUCTURE,
                    "第 " + index + " 题在无 JD 时填写了 requirement，已清空", category));
            resolvedRequirement = "";
        }

        return new InterviewQuestionDraft(content, category, difficulty, topic,
                citation.source(), citation.evidence(), resolvedRequirement, scoringPoints);
    }

    /**
     * 证据核对：PROJECT 题必须能回查到简历原文，其余题无法核实就降级为 NONE。
     *
     * <p>匹配只做"统一 Unicode + 去掉空白与常见标点后的子串包含"，可复现、可解释，
     * 但只能证明文本出现过，不能证明模型理解正确；不要在这里引入语义相似度。</p>
     */
    private ResolvedCitation resolveCitation(int index,
                                             String category,
                                             String source,
                                             String evidence,
                                             String normalizedResume,
                                             String normalizedJd,
                                             boolean jdPresent,
                                             List<ValidationIssue> issues) {
        if ("PROJECT".equals(category)) {
            if (!"RESUME".equals(source)) {
                issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.EVIDENCE,
                        "第 " + index + " 题为 PROJECT，但 source 不是 RESUME", category));
                return new ResolvedCitation(source, evidence);
            }
            if (!matchesMaterial(evidence, normalizedResume)) {
                issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.EVIDENCE,
                        "第 " + index + " 题为 PROJECT，但 evidence 无法在简历中核实", category));
            }
            return new ResolvedCitation(source, evidence);
        }

        if ("NONE".equals(source)) {
            if (StrUtil.isNotBlank(evidence)) {
                issues.add(ValidationIssue.soft(ValidationIssue.IssueCode.EVIDENCE,
                        "第 " + index + " 题 source=NONE 但 evidence 非空，已清空", category));
            }
            return new ResolvedCitation("NONE", "");
        }

        if ("JD".equals(source) && !jdPresent) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.EVIDENCE,
                    "第 " + index + " 题引用了岗位要求，但本场未提供 JD", category));
            return new ResolvedCitation("NONE", "");
        }
        String material = "JD".equals(source) ? normalizedJd : normalizedResume;
        if (!matchesMaterial(evidence, material)) {
            issues.add(ValidationIssue.soft(ValidationIssue.IssueCode.EVIDENCE,
                    "第 " + index + " 题 evidence 无法在" + ("JD".equals(source) ? "岗位要求" : "简历")
                            + "中核实，已降级为 NONE", category));
            return new ResolvedCitation("NONE", "");
        }
        return new ResolvedCitation(source, evidence);
    }

    private boolean matchesMaterial(String evidence, String normalizedMaterial) {
        if (StrUtil.isBlank(evidence) || StrUtil.isBlank(normalizedMaterial)) {
            return false;
        }
        String normalizedEvidence = normalizeForMatch(evidence);
        return normalizedEvidence.length() >= MIN_EVIDENCE_LENGTH && normalizedMaterial.contains(normalizedEvidence);
    }

    private List<String> readScoringPoints(Object raw, int index, List<ValidationIssue> issues) {
        if (!(raw instanceof JSONArray array)) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.SCORING_POINTS,
                    "第 " + index + " 题的 scoringPoints 必须是数组"));
            return null;
        }
        if (array.size() < 2 || array.size() > 4) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.SCORING_POINTS,
                    "第 " + index + " 题的 scoringPoints 为 " + array.size() + " 条，要求 2 至 4 条"));
            return null;
        }
        List<String> points = new ArrayList<>(array.size());
        for (Object rawPoint : array) {
            // 这里不把对象、数字转成字符串：模型结构错了就该修，而不是塞进评分提示词里。
            if (!(rawPoint instanceof String point) || StrUtil.isBlank(point)) {
                issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.SCORING_POINTS,
                        "第 " + index + " 题的 scoringPoints 含非字符串或空项"));
                return null;
            }
            if (point.trim().length() > MAX_SCORING_POINT_LENGTH) {
                issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.SCORING_POINTS,
                        "第 " + index + " 题的某条 scoringPoints 超过 " + MAX_SCORING_POINT_LENGTH + " 字"));
                return null;
            }
            points.add(point.trim());
        }
        return points;
    }

    // ===== 其余字段 =====

    private Map<String, Object> readJdContext(Object raw, boolean jdPresent, List<ValidationIssue> issues) {
        if (!jdPresent) {
            if (raw instanceof JSONObject context && !context.isEmpty()) {
                issues.add(ValidationIssue.soft(ValidationIssue.IssueCode.STRUCTURE,
                        "本次未提供 JD，jdContext 已清空", null));
            }
            return Map.of();
        }
        if (!(raw instanceof JSONObject context)) {
            issues.add(ValidationIssue.soft(ValidationIssue.IssueCode.STRUCTURE,
                    "jdContext 不是对象，已清空", null));
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        context.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (value instanceof String text && StrUtil.isBlank(text)) {
                return;
            }
            if (value instanceof JSONArray array && array.isEmpty()) {
                return;
            }
            normalized.put(key, value);
        });
        return normalized;
    }

    private List<String> readSuggestions(Object raw, List<ValidationIssue> issues) {
        if (!(raw instanceof JSONArray array)) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.SUGGESTIONS, "suggestions 必须是数组"));
            return List.of();
        }
        if (array.size() < 3 || array.size() > 5) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.SUGGESTIONS,
                    "suggestions 为 " + array.size() + " 条，要求 3 至 5 条"));
        }
        List<String> suggestions = new ArrayList<>(array.size());
        for (Object item : array) {
            if (!(item instanceof String text) || StrUtil.isBlank(text)) {
                issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.SUGGESTIONS,
                        "suggestions 含非字符串或空项"));
                return suggestions;
            }
            suggestions.add(text.trim());
        }
        return suggestions;
    }

    private String readInterviewType(Object raw, List<ValidationIssue> issues) {
        if (raw instanceof String text && StrUtil.isNotBlank(text)) {
            return text.trim();
        }
        // 方向只是展示字段，缺失不影响可用性，给默认值而不送修复。
        issues.add(ValidationIssue.soft(ValidationIssue.IssueCode.STRUCTURE,
                "type 缺失或为空，已使用默认方向", null));
        return DEFAULT_INTERVIEW_TYPE;
    }

    private Integer readResumeScore(Object raw, List<ValidationIssue> issues) {
        Integer score = asStrictInteger(raw);
        if (score == null || score < 0 || score > 100) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.RESUME_SCORE,
                    "resumeScore 必须是 0 至 100 的整数"));
            return null;
        }
        return score;
    }

    private String readResumeSummary(Object raw, List<ValidationIssue> issues) {
        if (!(raw instanceof String text)) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.RESUME_SUMMARY,
                    "resumeSummary 必须是字符串"));
            return null;
        }
        String summary = text.trim();
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.RESUME_SUMMARY,
                    "resumeSummary 超过 " + MAX_SUMMARY_LENGTH + " 字"));
        }
        if (summary.isEmpty()) {
            issues.add(ValidationIssue.soft(ValidationIssue.IssueCode.RESUME_SUMMARY,
                    "resumeSummary 为空，评分上下文将缺少候选人画像", null));
        }
        return summary;
    }

    // ===== 基础类型读取 =====

    /**
     * 取必需字符串字段：字段必须存在，且空值是否允许由调用方决定。
     *
     * @return 通过校验的文本；缺失或类型不符返回 null，并记一条阻断问题
     */
    private String requiredText(JSONObject item,
                                String field,
                                int index,
                                boolean allowEmpty,
                                ValidationIssue.IssueCode code,
                                List<ValidationIssue> issues) {
        if (!item.containsKey(field)) {
            issues.add(ValidationIssue.critical(code, "第 " + index + " 题缺少字段 " + field));
            return null;
        }
        Object value = item.get(field);
        if (!(value instanceof String text)) {
            issues.add(ValidationIssue.critical(code, "第 " + index + " 题的 " + field + " 不是字符串"));
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() && !allowEmpty) {
            issues.add(ValidationIssue.critical(code, "第 " + index + " 题的 " + field + " 为空"));
            return null;
        }
        return trimmed;
    }

    private String requiredEnum(JSONObject item,
                                String field,
                                Set<String> allowed,
                                int index,
                                List<ValidationIssue> issues) {
        String value = requiredText(item, field, index, false, ValidationIssue.IssueCode.ENUM, issues);
        if (value == null) {
            return null;
        }
        if (!allowed.contains(value)) {
            issues.add(ValidationIssue.critical(ValidationIssue.IssueCode.ENUM,
                    "第 " + index + " 题的 " + field + " 取值 " + value + " 不在允许范围"));
            return null;
        }
        return value;
    }

    /**
     * 只接受整数值的 JSON 数字：浮点带小数、数字字符串都算类型错误。
     */
    private Integer asStrictInteger(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof BigDecimal decimal) {
            try {
                return decimal.stripTrailingZeros().scale() <= 0 ? decimal.intValueExact() : null;
            } catch (ArithmeticException ex) {
                return null;
            }
        }
        if (raw instanceof Double || raw instanceof Float) {
            double value = ((Number) raw).doubleValue();
            return value == Math.rint(value) ? (int) value : null;
        }
        return null;
    }

    // ===== JSON 定位与文本规范化 =====

    private JSONObject parseRoot(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String cleaned = stripCodeFence(raw.trim());
        JSONObject parsed = tryParseObject(cleaned);
        if (parsed != null) {
            return parsed;
        }
        // 模型偶尔在 JSON 前后带一句话，取首尾花括号之间再试一次；仍失败就判为结构错误。
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return tryParseObject(cleaned.substring(start, end + 1));
    }

    private JSONObject tryParseObject(String text) {
        try {
            return JSON.parseObject(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripCodeFence(String text) {
        String cleaned = text;
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    /**
     * 证据匹配用的规范化：NFKC 折叠全半角，去掉空白与常见标点，再转小写。
     *
     * <p>OCR 换行、全角逗号这类差异会被抹平；同义改写不会被抹平——这是有意为之，
     * 宁可降级为 NONE，也不要制造"已验证"的假象。</p>
     */
    static String normalizeForMatch(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isWhitespace(ch) || ch == '\u00A0' || ch == '\u3000') {
                continue;
            }
            if (IGNORED_PUNCTUATION.indexOf(ch) >= 0) {
                continue;
            }
            builder.append(Character.toLowerCase(ch));
        }
        return builder.toString();
    }

    private record ResolvedCitation(String source, String evidence) {
    }
}
