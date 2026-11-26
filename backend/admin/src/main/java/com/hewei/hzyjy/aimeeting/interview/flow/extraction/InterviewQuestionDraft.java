package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import java.util.List;

/**
 * 一道通过结构校验的主问题。
 *
 * <p>刻意与持久化实体分开：模型产出的对象先落在这里，整批校验通过后才转换成
 * 存量结构（题号 → 题干 / 要点 / 岗位映射）。这样"模型说了什么"和"库里存什么"
 * 不会互相污染。</p>
 *
 * @param content       题干，只有一个核心问题
 * @param category      PROJECT / JOB / FUNDAMENTAL
 * @param difficulty    EASY / MEDIUM / HARD
 * @param topic         简短技术主题
 * @param source        evidence 的来源：RESUME / JD / NONE
 * @param evidence      对应材料中连续出现的短原文，可为空
 * @param requirement   关联的岗位要求摘要，无 JD 时为空
 * @param scoringPoints 2 至 4 条评分参考
 */
public record InterviewQuestionDraft(
        String content,
        String category,
        String difficulty,
        String topic,
        String source,
        String evidence,
        String requirement,
        List<String> scoringPoints) {

    public InterviewQuestionDraft {
        scoringPoints = scoringPoints == null ? List.of() : List.copyOf(scoringPoints);
    }
}
