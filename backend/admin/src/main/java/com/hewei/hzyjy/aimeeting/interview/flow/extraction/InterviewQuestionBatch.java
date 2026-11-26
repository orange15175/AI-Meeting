package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次出题调用的完整产物：题目数组 + 岗位画像 + 面向候选人的建议与简历判断。
 *
 * <p>与 v3 的差别只在这里：题目是对象而不是字符串，题干与分类、难度、依据、要点绑定在同一项内，
 * 因此不再需要"按题号去另一个映射里取要点"这种跨字段对齐。</p>
 */
public record InterviewQuestionBatch(
        List<InterviewQuestionDraft> questions,
        Map<String, Object> jdContext,
        List<String> suggestions,
        String interviewType,
        Integer resumeScore,
        String resumeSummary) {

    public InterviewQuestionBatch {
        questions = questions == null ? List.of() : List.copyOf(questions);
        // 岗位上下文的取值来自模型，这里允许空值存在（只是不展示），不做 Map.copyOf 的 null 拒绝。
        jdContext = jdContext == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(jdContext));
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static InterviewQuestionBatch empty() {
        return new InterviewQuestionBatch(List.of(), Map.of(), List.of(), null, null, null);
    }
}
