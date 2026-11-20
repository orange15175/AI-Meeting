package com.hewei.hzyjy.aimeeting.interview.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 面试会话（MySQL 权威记录）。
 *
 * <p>P3 起会话状态、流程游标与并发代次都以本表为准，Redis 只保留会话级互斥锁。
 * {@code version} 由条件更新维护，用于拒绝迟到的旧执行者。</p>
 */
@Data
@TableName("interview_session")
public class InterviewSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("status")
    private String status;

    @TableField("flow_status")
    private String flowStatus;

    @TableField("conversation_title")
    private String conversationTitle;

    @TableField("interview_type")
    private String interviewType;

    @TableField("resume_file_url")
    private String resumeFileUrl;

    @TableField("current_question_no")
    private String currentQuestionNo;

    @TableField("total_questions")
    private Integer totalQuestions;

    @TableField("follow_up_count")
    private Integer followUpCount;

    @TableField("max_follow_up")
    private Integer maxFollowUp;

    @TableField("version")
    private Integer version;

    @TableField("operation_epoch")
    private Long operationEpoch;

    @TableField("active_submission_id")
    private String activeSubmissionId;

    @TableField("start_time")
    private Date startTime;

    @TableField("end_time")
    private Date endTime;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableField("del_flag")
    private Integer delFlag;
}
