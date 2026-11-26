package com.hewei.hzyjy.aimeeting.interview.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewAnswerSubmission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface InterviewAnswerSubmissionMapper extends BaseMapper<InterviewAnswerSubmission> {

    /**
     * 主问题平均分：只统计已成功且有分数的提交，追问不计入。
     */
    @Select("SELECT ROUND(AVG(s.score)) FROM interview_answer_submission s "
            + "JOIN interview_question_item q ON q.session_id = s.session_id AND q.question_no = s.question_no "
            + "WHERE s.session_id = #{sessionId} AND s.status = 'SUCCEEDED' AND s.score IS NOT NULL "
            + "AND s.del_flag = 0 AND q.del_flag = 0 AND q.kind = 'MAIN'")
    Integer averageMainScore(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) FROM interview_answer_submission s "
            + "JOIN interview_question_item q ON q.session_id = s.session_id AND q.question_no = s.question_no "
            + "WHERE s.session_id = #{sessionId} AND s.status = 'SUCCEEDED' "
            + "AND s.del_flag = 0 AND q.del_flag = 0 AND q.kind = 'MAIN'")
    int countSucceededMain(@Param("sessionId") String sessionId);

    @Select("SELECT s.id AS id, s.submission_id AS submission_id, s.session_id AS session_id, "
            + "s.question_no AS question_no, s.request_id AS request_id, s.input_hash AS input_hash, "
            + "s.answer_content AS answer_content, s.skipped AS skipped, s.status AS status, s.stage AS stage, "
            + "s.attempt_token AS attempt_token, s.attempt_count AS attempt_count, s.score AS score, "
            + "s.heartbeat_at AS heartbeat_at, s.deadline_at AS deadline_at, "
            + "s.feedback AS feedback, s.missing_points AS missing_points, s.response_json AS response_json, "
            + "s.error_message AS error_message, s.finished AS finished, s.duration_ms AS duration_ms, "
            + "s.create_time AS create_time, s.update_time AS update_time, s.del_flag AS del_flag "
            + "FROM interview_answer_submission s "
            + "JOIN interview_question_item q ON q.session_id = s.session_id AND q.question_no = s.question_no "
            + "WHERE s.session_id = #{sessionId} AND s.del_flag = 0 AND q.del_flag = 0 "
            + "ORDER BY q.main_index ASC, q.follow_up_index ASC")
    List<InterviewAnswerSubmission> listByQuestionOrder(@Param("sessionId") String sessionId);
}
