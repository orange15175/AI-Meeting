package com.hewei.hzyjy.aimeeting.interview.service.model;

/**
 * 【关键节点：会话生命周期】常规路线为 DRAFT → RESUME_UPLOADING → READY → IN_PROGRESS → FINISHED。
 * 出题失败可回退 DRAFT；新建面试会放弃旧活动会话并标为 ABANDONED。
 * 与题内流程 flowStatus 分开：会话描述整场生命周期，flowStatus 描述当前题的问答阶段。
 */
public enum InterviewSessionStatus {

    DRAFT,
    RESUME_UPLOADING,
    READY,
    IN_PROGRESS,
    FINISHED,
    // 新场面试创建时，旧活动会话可能进入此状态；它不是正常完成。
    ABANDONED;

    public boolean isActive() {
        return this == DRAFT || this == RESUME_UPLOADING || this == READY || this == IN_PROGRESS;
    }

    public boolean canResume() {
        return this == READY || this == IN_PROGRESS;
    }
}
