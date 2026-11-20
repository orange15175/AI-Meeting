package com.hewei.hzyjy.aimeeting.interview.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 面试材料：简历解析结果与出题产物。
 *
 * <p>与题目表的分工：本表保存"这一份简历被解析成了什么"，
 * 题目表保存"由它生成的具体题目"。解析文本留在本表，重试出题时无需重复解析 PDF。</p>
 */
@Data
@TableName("interview_material")
public class InterviewMaterial {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("parse_status")
    private String parseStatus;

    @TableField("parse_version")
    private Integer parseVersion;

    @TableField("interview_type")
    private String interviewType;

    @TableField("resume_file_url")
    private String resumeFileUrl;

    @TableField("resume_text")
    private String resumeText;

    @TableField("resume_context")
    private String resumeContext;

    @TableField("suggestions_json")
    private String suggestionsJson;

    @TableField("resume_score")
    private Integer resumeScore;

    /**
     * 岗位 JD 来源：TEXT / FILE，空表示本场未提供岗位描述。
     */
    @TableField("jd_source")
    private String jdSource;

    @TableField("jd_file_url")
    private String jdFileUrl;

    @TableField("jd_file_name")
    private String jdFileName;

    @TableField("jd_text")
    private String jdText;

    /**
     * 岗位结构化上下文（职责、技能要求及其原文证据），仅服务端使用。
     */
    @TableField("jd_context")
    private String jdContext;

    @TableField("model_name")
    private String modelName;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("error_message")
    private String errorMessage;

    @TableField("response_time")
    private Integer responseTime;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("raw_response")
    private String rawResponse;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableField("del_flag")
    private Integer delFlag;
}
