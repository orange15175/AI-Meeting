package com.hewei.hzyjy.aimeeting.interview.application.strategy;

import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeightedRadarComputationStrategyTest {

    private final WeightedRadarComputationStrategy strategy = new WeightedRadarComputationStrategy();

    @Test
    void shouldComputeNormalizedWeightsWithoutDemeanor() {
        RadarChartDTO chart = strategy.compute(80, 70);

        assertEquals(80, chart.getResumeScore());
        assertEquals(70, chart.getInterviewPerformance());
        // 专业技能轴沿用 简历 30% / 面试 70%
        assertEquals(73, chart.getProfessionalSkills());
        // 综合分：80 * 0.3125 + 70 * 0.6875 = 73.125 -> 73
        assertEquals(73, chart.getPotentialIndex());
    }

    @Test
    void shouldKeepNullWhenBothDimensionsMissing() {
        RadarChartDTO chart = strategy.compute(null, null);

        assertNull(chart.getResumeScore());
        assertNull(chart.getInterviewPerformance());
        assertNull(chart.getProfessionalSkills());
        assertNull(chart.getPotentialIndex());
    }

    @Test
    void shouldUseSingleDimensionWhenOnlyOnePresent() {
        RadarChartDTO chart = strategy.compute(null, 66);

        assertNull(chart.getResumeScore());
        assertEquals(66, chart.getInterviewPerformance());
        // 只有一项时权重自动归一化，结果就是该项分数，不因缺失项被拉低
        assertEquals(66, chart.getPotentialIndex());
        assertEquals(66, chart.getProfessionalSkills());
    }

    @Test
    void shouldClampOutOfRangeScores() {
        RadarChartDTO chart = strategy.compute(120, -5);

        assertEquals(100, chart.getResumeScore());
        assertEquals(0, chart.getInterviewPerformance());
    }
}
