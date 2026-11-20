package com.hewei.hzyjy.aimeeting.interview.api.io.resp;

import lombok.Data;

import java.util.Map;

@Data
public class InterviewSessionRestoreRespDTO {

    private String sessionId;

    private String status;

    private Boolean canResume;

    private String resumeFileUrl;

    private Integer resumeScore;

    private String interviewType;

    /**
     * 本场是否启用了岗位 JD
     */
    private Boolean jdEnabled;

    /**
     * JD 来源：TEXT / FILE
     */
    private String jdSource;

    private String jdFileName;

    /**
     * 岗位摘要，用于恢复页展示
     */
    private String jdSummary;

    private Map<String, String> suggestions;
}
