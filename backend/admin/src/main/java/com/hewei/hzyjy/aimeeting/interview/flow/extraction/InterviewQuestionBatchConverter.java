package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把校验通过的题目数组转换成存量持久化结构。
 *
 * <p>关键点：<b>题号在这里生成</b>，不再是模型自报的数字。数组第 n 项就是题号 "n"，
 * 题干、分类、难度、考察要点、岗位映射全部用同一个下标绑定，从根上消除"空题被过滤后题号错位"的旧问题。
 * 落库接口（{@code replaceMainQuestions} / {@code updatePoints} / {@code updateTargetContext}）
 * 只增加随题干写入的分类与难度，旧会话与报告的读取路径不受影响。</p>
 */
@Component
public class InterviewQuestionBatchConverter {

    /**
     * 转换结果：全部是存量结构，可直接交给 {@code InterviewPreparationCommitService}。
     */
    public record Conversion(
            List<InterviewQuestionStore.MainQuestion> questions,
            Map<String, List<String>> questionPoints,
            Map<String, Object> questionTargets,
            Map<String, Object> jdContext,
            Map<String, Object> resumeContext,
            String interviewType,
            Integer resumeScore) {
    }

    public Conversion convert(InterviewQuestionBatch batch, boolean jdPresent) {
        List<InterviewQuestionStore.MainQuestion> questions = new ArrayList<>(batch.questions().size());
        Map<String, List<String>> questionPoints = new LinkedHashMap<>();
        Map<String, Object> questionTargets = new LinkedHashMap<>();

        for (int i = 0; i < batch.questions().size(); i++) {
            String questionNo = String.valueOf(i + 1);
            InterviewQuestionDraft draft = batch.questions().get(i);
            questions.add(new InterviewQuestionStore.MainQuestion(
                    questionNo, draft.content(), draft.category(), draft.difficulty()));
            questionPoints.put(questionNo, draft.scoringPoints());
            questionTargets.put(questionNo, buildTarget(draft));
        }

        return new Conversion(
                questions,
                questionPoints,
                questionTargets,
                jdPresent ? batch.jdContext() : Map.of(),
                buildResumeContext(batch),
                batch.interviewType(),
                batch.resumeScore());
    }

    /**
     * 题目与材料依据的映射。报告只读 requirement，其余字段用于追溯"这题为什么问"。
     */
    private Map<String, Object> buildTarget(InterviewQuestionDraft draft) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(draft.requirement())) {
            target.put("requirement", draft.requirement());
        }
        target.put("source", draft.source());
        if (StrUtil.isNotBlank(draft.evidence())) {
            target.put("evidence", draft.evidence());
        }
        return target;
    }

    /**
     * 评分时注入的候选人背景：只放画像与方向，不再整包透传模型输出。
     */
    private Map<String, Object> buildResumeContext(InterviewQuestionBatch batch) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(batch.interviewType())) {
            context.put("type", batch.interviewType());
        }
        if (batch.resumeScore() != null) {
            context.put("resumeScore", batch.resumeScore());
        }
        if (StrUtil.isNotBlank(batch.resumeSummary())) {
            context.put("resumeSummary", batch.resumeSummary());
        }
        return context;
    }
}
