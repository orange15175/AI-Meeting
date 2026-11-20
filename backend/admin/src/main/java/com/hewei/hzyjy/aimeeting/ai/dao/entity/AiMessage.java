package com.hewei.hzyjy.aimeeting.ai.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 普通 AI 聊天消息（MySQL 权威记录，P4 由 Mongo 迁移而来）。
 */
@Data
@TableName("ai_message")
public class AiMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("request_id")
    private String requestId;

    @TableField("message_seq")
    private Integer messageSeq;

    @TableField("message_type")
    private Integer messageType;

    @TableField("status")
    private String status;

    @TableField("message_content")
    private String messageContent;

    @TableField("reasoning_content")
    private String reasoningContent;

    @TableField("parent_msg_id")
    private Long parentMsgId;

    @TableField("model_name")
    private String modelName;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("response_time")
    private Integer responseTime;

    @TableField("error_message")
    private String errorMessage;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableField("del_flag")
    private Integer delFlag;
}
