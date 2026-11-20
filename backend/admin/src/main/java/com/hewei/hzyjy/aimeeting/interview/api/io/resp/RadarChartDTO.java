package com.hewei.hzyjy.aimeeting.interview.api.io.resp;

import lombok.Data;

/**
 * 雷达图数据传输对象
 */
@Data
public class RadarChartDTO {
    
    /**
     * 简历评估得分 (0-100)
     */
    private Integer resumeScore;
    
    /**
     * 面试表现得分 (0-100)，未评分为 null
     */
    private Integer interviewPerformance;

    /**
     * 用户潜力指数 (0-100)：简历与面试的加权综合，两项都缺失时为 null
     */
    private Integer potentialIndex;
    
    /**
     * 专业技能评分 (0-100)
     */
    private Integer professionalSkills;
}