package com.hewei.hzyjy.aimeeting.interview.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 面试题目行：主问题与追问各占一行，用 question_no 区分（如 1、1-F1）。
 */
@Data
@TableName("interview_question_item")
public class InterviewQuestionItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("question_no")
    private String questionNo;

    /**
     * MAIN / FOLLOW_UP
     */
    @TableField("kind")
    private String kind;

    @TableField("main_index")
    private Integer mainIndex;

    @TableField("follow_up_index")
    private Integer followUpIndex;

    @TableField("parent_id")
    private Long parentId;

    @TableField("content")
    private String content;

    @TableField("category")
    private String category;

    /**
     * 评分要点，仅服务端使用，不下发前端。
     */
    @TableField("points_json")
    private String pointsJson;

    /**
     * 该题对应的岗位要求与材料证据（JSON），仅服务端使用，用于评分与复盘追溯。
     */
    @TableField("target_context")
    private String targetContext;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableField("del_flag")
    private Integer delFlag;
}
