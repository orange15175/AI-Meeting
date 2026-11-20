package com.hewei.hzyjy.aimeeting.interview.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 面试回答提交（正式记录）。
 *
 * <p>每题只保留一行有效提交（唯一键 session_id + question_no），
 * 失败重试复用同一行并递增 attempt_count/attempt_token；
 * {@code attempt_token} 用于拒绝旧执行者迟到的结果。</p>
 */
@Data
@TableName("interview_answer_submission")
public class InterviewAnswerSubmission {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("submission_id")
    private String submissionId;

    @TableField("session_id")
    private String sessionId;

    @TableField("question_no")
    private String questionNo;

    @TableField("request_id")
    private String requestId;

    @TableField("input_hash")
    private String inputHash;

    @TableField("answer_content")
    private String answerContent;

    @TableField("skipped")
    private Integer skipped;

    @TableField("status")
    private String status;

    @TableField("stage")
    private String stage;

    @TableField("attempt_token")
    private String attemptToken;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("score")
    private Integer score;

    @TableField("feedback")
    private String feedback;

    @TableField("missing_points")
    private String missingPoints;

    @TableField("response_json")
    private String responseJson;

    @TableField("error_message")
    private String errorMessage;

    @TableField("finished")
    private Integer finished;

    @TableField("duration_ms")
    private Integer durationMs;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableField("del_flag")
    private Integer delFlag;
}
