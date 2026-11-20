package com.hewei.hzyjy.aimeeting.interview.application.strategy;

import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;

public interface RadarComputationStrategy {

    /**
     * 计算雷达图。去掉神态轴后只剩简历与面试两项。
     * <p>缺失项以 {@code null} 表示，不再用 0 冒充"得 0 分"；两项都缺失时综合分为 {@code null}。
     */
    RadarChartDTO compute(Integer resumeScore, Integer interviewScore);
}
