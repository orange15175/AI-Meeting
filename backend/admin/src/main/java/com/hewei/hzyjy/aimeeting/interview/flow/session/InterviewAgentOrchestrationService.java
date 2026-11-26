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
 * <p>阅读路线：出题进入 InterviewQuestionExtractionService；答题进入 InterviewAnswerPipeline；
 * 当前题查询在本类按 MySQL 游标读取。类名中的 Agent 不代表仍调用旧星辰工作流。</p>
 *
 * <p>P3 起当前题直接读会话行的游标，不再需要"缓存丢失 → 按 turn 反推 → 重建 flow"
 * 的三级恢复逻辑；本类查询只读题目游标。Facade 可将会话标为进行中，但 GET 不推进题序。</p>
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
    // 按用例分派：出题交给材料出题服务；本层不重复保存材料或调用第二次模型。
    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        return interviewQuestionExtractionService.extractInterviewQuestions(reqDTO);
    }

    @Override
    // 答题统一交给 Pipeline，由它协调锁、幂等、评分、追问和提交事务。
    public InterviewAnswerRespDTO answerInterviewQuestion(String sessionId, InterviewAnswerReqDTO requestParam) {
        return interviewAnswerPipeline.execute(sessionId, requestParam);
    }

    @Override
    // 保留旧方法名的只读适配：返回数据库当前题，真正切题发生在成功答题提交时。
    public InterviewAnswerRespDTO getNextQuestion(String sessionId) {
        return getCurrentQuestion(sessionId);
    }

    @Override
    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId) {
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();
        if (StrUtil.isBlank(sessionId)) {
            return response.fail("sessionId cannot be empty");
        }
        // 【面试主线 3/6】恢复/刷新只读已提交的题号和题干，不再次调用出题模型。
        InterviewSession session = sessionStore.findBySessionId(sessionId);
        if (session == null) {
            return response.fail("interview session does not exist");
        }
        // 第 2 段：把会话持久化游标映射为流程状态；已完成或越界时返回 finished，而不是再找一道题。
        InterviewFlowState flow = flowStateMachine.toFlow(session);
        if (flowStateMachine.isCompleted(flow) || flowStateMachine.isOutOfRange(flow)) {
            response.setTotalScore(submissionStore.averageMainScore(sessionId));
            return response.finish().success();
        }
        String questionNo = flowStateMachine.currentQuestionNumber(flow);
        if (StrUtil.isBlank(questionNo)) {
            return response.fail("interview questions are not prepared yet");
        }
        // 第 3 段：按游标查题干；找不到就提示未准备或已失效，不临时调用模型补题。
        InterviewQuestionItem question = questionStore.findByNo(sessionId, questionNo);
        if (question == null) {
            return response.fail("question does not exist or expired");
        }

        // 第 4 段：解析 3-F1 这类追问题号，填好当前/待展示题与主问题均分，供房间首次进入和刷新共用。
        int followUpIndex = flowStateMachine.followUpIndexOf(questionNo);
        response.withCurrentQuestion(questionNo, question.getContent());
        response.withNextQuestion(questionNo, question.getContent(), followUpIndex > 0, followUpIndex);
        response.setTotalScore(submissionStore.averageMainScore(sessionId));
        return response.success();
    }
}
