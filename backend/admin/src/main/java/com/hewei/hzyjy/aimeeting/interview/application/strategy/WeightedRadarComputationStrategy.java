package com.hewei.hzyjy.aimeeting.interview.application.strategy;

import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;
import org.springframework.stereotype.Component;

/**
 * 加权雷达图计算。
 * <p>去掉神态轴后，保留项按原比例归一化：原 简历 25% / 面试 55%（合计 80%）
 * → 简历 25/80 = 31.25%、面试 55/80 = 68.75%。
 * <p>缺失项一律以 {@code null} 表示，不用 0 代替——"没评"和"得 0 分"是两件事。
 */
@Component
public class WeightedRadarComputationStrategy implements RadarComputationStrategy {

    /**
     * 综合分权重（已归一化，合计 1.0）
     */
    private static final double RESUME_WEIGHT = 0.3125D;
    private static final double INTERVIEW_WEIGHT = 0.6875D;

    /**
     * 专业技能轴沿用原有独立比例：简历 30% + 面试 70%
     */
    private static final double PROFESSIONAL_SKILLS_RESUME_WEIGHT = 0.30D;
    private static final double PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT = 0.70D;

    @Override
    public RadarChartDTO compute(Integer resumeScore, Integer interviewScore) {
        Integer resume = clampNullableScore(resumeScore);
        Integer interview = clampNullableScore(interviewScore);

        RadarChartDTO radarChart = new RadarChartDTO();
        radarChart.setResumeScore(resume);
        radarChart.setInterviewPerformance(interview);
        radarChart.setProfessionalSkills(weightedAverage(
                resume, PROFESSIONAL_SKILLS_RESUME_WEIGHT,
                interview, PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT));
        radarChart.setPotentialIndex(weightedAverage(
                resume, RESUME_WEIGHT,
                interview, INTERVIEW_WEIGHT));
        return radarChart;
    }

    /**
     * 只在有效项之间按权重平均；权重在缺失项上自动归一化，因此只有一项时结果就是该项分数。
     */
    private Integer weightedAverage(Integer first, double firstWeight, Integer second, double secondWeight) {
        double weightedScore = 0D;
        double totalWeight = 0D;
        if (first != null) {
            weightedScore += clampScore(first) * firstWeight;
            totalWeight += firstWeight;
        }
        if (second != null) {
            weightedScore += clampScore(second) * secondWeight;
            totalWeight += secondWeight;
        }
        if (totalWeight <= 0D) {
            return null;
        }
        return clampScore((int) Math.round(weightedScore / totalWeight));
    }

    private Integer clampNullableScore(Integer score) {
        if (score == null) {
            return null;
        }
        return clampScore(score);
    }

    private int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, score));
    }
}
