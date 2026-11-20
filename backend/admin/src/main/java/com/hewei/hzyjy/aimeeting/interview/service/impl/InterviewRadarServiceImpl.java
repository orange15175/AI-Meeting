package com.hewei.hzyjy.aimeeting.interview.service.impl;

import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.aimeeting.interview.application.strategy.RadarComputationStrategy;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRadarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InterviewRadarServiceImpl implements InterviewRadarService {

    private final RadarComputationStrategy radarComputationStrategy;

    @Override
    public RadarChartDTO buildRadarChart(Integer resumeScore, Integer interviewScore) {
        try {
            return radarComputationStrategy.compute(resumeScore, interviewScore);
        } catch (Exception ex) {
            // 计算异常时返回全 null，让前端显示"暂无数据"，而不是伪造 0 分
            return new RadarChartDTO();
        }
    }
}
