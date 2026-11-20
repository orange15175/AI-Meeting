package com.hewei.hzyjy.aimeeting.ai.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 普通 AI 聊天会话（MySQL 权威记录，P4 由 Mongo 迁移而来）。
 */
@Data
@TableName("ai_conversation")
public class AiConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("username")
    private String username;

    @TableField("ai_id")
    private Long aiId;

    @TableField("title")
    private String title;

    @TableField("status")
    private Integer status;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("version")
    private Integer version;

    @TableField("active_request_id")
    private String activeRequestId;

    @TableField("last_message_time")
    private Date lastMessageTime;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableField("del_flag")
    private Integer delFlag;
}
