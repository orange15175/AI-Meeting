package com.hewei.hzyjy.aimeeting.interview.service;

import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;

public interface InterviewRadarService {

    RadarChartDTO buildRadarChart(Integer resumeScore, Integer interviewScore);
}
