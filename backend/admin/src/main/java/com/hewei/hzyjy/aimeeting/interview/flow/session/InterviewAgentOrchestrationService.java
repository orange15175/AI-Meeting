package com.hewei.hzyjy.aimeeting.interview.flow.session;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.aimeeting.interview.application.InterviewWorkflowService;
import com.hewei.hzyjy.aimeeting.interview.application.flow.InterviewFlowStateMachine;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewQuestionItem;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.flow.answer.InterviewAnswerPipeline;
import com.hewei.hzyjy.aimeeting.interview.flow.extraction.InterviewQuestionExtractionService;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 面试编排入口：出题、答题、当前题查询。
 *
 * <p>P3 起当前题直接读会话行的游标，不再需要"缓存丢失 → 按 turn 反推 → 重建 flow"
 * 的三级恢复逻辑；GET 接口严格只读，不推进题序。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewAgentOrchestrationService implements InterviewWorkflowService {

    private final InterviewQuestionExtractionService interviewQuestionExtractionService;
    private final InterviewAnswerPipeline interviewAnswerPipeline;
    private final InterviewFlowStateMachine flowStateMachine;
    private final InterviewSessionStore sessionStore;
    private final InterviewQuestionStore questionStore;
    private final InterviewSubmissionStore submissionStore;

    @Override
    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        return interviewQuestionExtractionService.extractInterviewQuestions(reqDTO);
    }

    @Override
    public InterviewAnswerRespDTO answerInterviewQuestion(String sessionId, InterviewAnswerReqDTO requestParam) {
        return interviewAnswerPipeline.execute(sessionId, requestParam);
    }

    @Override
    public InterviewAnswerRespDTO getNextQuestion(String sessionId) {
        return getCurrentQuestion(sessionId);
    }

    @Override
    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId) {
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();
        if (StrUtil.isBlank(sessionId)) {
            return response.fail("sessionId cannot be empty");
        }
        InterviewSession session = sessionStore.findBySessionId(sessionId);
        if (session == null) {
            return response.fail("interview session does not exist");
        }
        InterviewFlowState flow = flowStateMachine.toFlow(session);
        if (flowStateMachine.isCompleted(flow) || flowStateMachine.isOutOfRange(flow)) {
            response.setTotalScore(submissionStore.averageMainScore(sessionId));
            return response.finish().success();
        }
        String questionNo = flowStateMachine.currentQuestionNumber(flow);
        if (StrUtil.isBlank(questionNo)) {
            return response.fail("interview questions are not prepared yet");
        }
        InterviewQuestionItem question = questionStore.findByNo(sessionId, questionNo);
        if (question == null) {
            return response.fail("question does not exist or expired");
        }

        int followUpIndex = flowStateMachine.followUpIndexOf(questionNo);
        response.withCurrentQuestion(questionNo, question.getContent());
        response.withNextQuestion(questionNo, question.getContent(), followUpIndex > 0, followUpIndex);
        response.setTotalScore(submissionStore.averageMainScore(sessionId));
        return response.success();
    }
}
