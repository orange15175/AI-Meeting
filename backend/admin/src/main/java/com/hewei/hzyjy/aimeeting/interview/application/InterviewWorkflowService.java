package com.hewei.hzyjy.aimeeting.interview.application;

import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;

public interface InterviewWorkflowService {

    InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO requestParam);

    InterviewAnswerRespDTO answerInterviewQuestion(String sessionId, InterviewAnswerReqDTO requestParam);

    InterviewAnswerRespDTO getNextQuestion(String sessionId);

    InterviewAnswerRespDTO getCurrentQuestion(String sessionId);
}
