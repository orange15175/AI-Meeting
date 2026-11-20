package com.hewei.hzyjy.aimeeting.interview.dao.store;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 会话进度提交命令：一次条件更新完成状态、游标与代次推进。
 */
@Data
@Builder
public class InterviewProgressCommit {

    private String sessionId;

    /**
     * 读取时的版本号，条件更新用它拒绝迟到的旧执行者。
     */
    private Integer expectedVersion;

    private String status;

    private String flowStatus;

    private String currentQuestionNo;

    private Integer totalQuestions;

    private Integer followUpCount;

    private String activeSubmissionId;

    /**
     * 仅当会话 startTime 为空时写入。
     */
    private Date startTime;

    private Date endTime;
}
