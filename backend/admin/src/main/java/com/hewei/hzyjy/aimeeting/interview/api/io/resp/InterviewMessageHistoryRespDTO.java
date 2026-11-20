package com.hewei.hzyjy.aimeeting.interview.api.io.resp;

import lombok.Data;

import java.util.Date;

/**
 * 面试消息历史响应 DTO。
 * <p>面试模块不再复用 {@code agent.api.io.resp.AgentMessageHistoryRespDTO}：
 * 通用 Agent 的历史结构会随该产品入口一起下线，面试需要自己的稳定契约。
 * 字段按面试历史的实际展示需要裁剪，不再暴露 token 消耗等模型内部指标。
 */
@Data
public class InterviewMessageHistoryRespDTO {

    /**
     * 消息ID
     */
    private String id;

    /**
     * 面试会话ID
     */
    private String sessionId;

    /**
     * 消息类型：1-用户消息，2-AI回复
     */
    private Integer messageType;

    /**
     * 消息内容
     */
    private String messageContent;

    /**
     * 消息序号
     */
    private Integer messageSeq;

    /**
     * 创建时间
     */
    private Date createTime;
}
