package com.hewei.hzyjy.aimeeting.interview.api.io.resp;

import lombok.Data;

import java.util.Date;
import java.util.Map;

@Data
public class InterviewQuestionRespDTO {

    private String id;

    private String sessionId;

    private String userName;

    private Map<String, String> questions;

    private Map<String, String> suggestions;

    private String interviewType;

    private String resumeFileUrl;

    private Integer responseTime;

    private Integer tokenCount;

    private Integer resumeScore;

    /**
     * 本场是否启用了岗位 JD
     */
    private Boolean jdEnabled;

    /**
     * JD 来源：TEXT / FILE
     */
    private String jdSource;

    /**
     * JD 原文件名，仅 TEXT 来源时为空
     */
    private String jdFileName;

    /**
     * 岗位摘要，供准备页与报告页展示
     */
    private String jdSummary;

    private Integer questionCount;

    private Integer suggestionCount;

    private Integer isSuccess = 1;

    private String errorMessage;

    private Date createTime;

    private Date updateTime;
}
