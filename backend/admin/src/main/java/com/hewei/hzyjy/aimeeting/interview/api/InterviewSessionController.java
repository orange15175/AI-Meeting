package com.hewei.hzyjy.aimeeting.interview.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewMessageHistoryRespDTO;
import com.hewei.hzyjy.aimeeting.common.convention.annotation.CurrentUser;
import com.hewei.hzyjy.aimeeting.common.convention.context.UserContext;
import com.hewei.hzyjy.aimeeting.common.convention.result.Result;
import com.hewei.hzyjy.aimeeting.common.convention.result.Results;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewConversationPageReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewConversationRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.aimeeting.interview.flow.session.InterviewSessionFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/ai-meeting/v1/interview")
@RequiredArgsConstructor
public class InterviewSessionController {

    private final InterviewSessionFacade interviewSessionFacade;

    /**
     * 【面试主线 1/6】创建面试会话。用户身份取自登录上下文，业务层生成会话 ID 并绑定归属。
     */
    @PostMapping("/sessions")
    public Result<InterviewSessionCreateRespDTO> createSession(@CurrentUser UserContext currentUser) {
        // 请求体无需传 userId；@CurrentUser 从登录身份提供用户。返回 DTO 的 sessionId/status 对应前端 CreateResult 类型。
        return Results.success(interviewSessionFacade.createSession(currentUser.getUserId()));
    }

    @GetMapping("/conversations")
    public Result<IPage<InterviewConversationRespDTO>> pageConversations(
            InterviewConversationPageReqDTO requestParam,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.pageConversations(currentUser.getUserId(), requestParam));
    }

    @GetMapping("/conversations/{sessionId}/messages")
    public Result<List<InterviewMessageHistoryRespDTO>> getConversationHistory(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getConversationHistory(sessionId, currentUser.getUserId()));
    }

    @GetMapping("/messages/history")
    public Result<IPage<InterviewMessageHistoryRespDTO>> pageHistoryMessages(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @CurrentUser UserContext currentUser) {
        return Results.success(
                interviewSessionFacade.pageHistoryMessages(sessionId, current, size, currentUser.getUserId()));
    }

    /**
     * 【面试主线 5/6】统一收口入口，供正常答完和提前结束复用；生成报告并结束会话。
     */
    @PutMapping("/sessions/{sessionId}/finish")
    public Result<Void> finishSession(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        interviewSessionFacade.finishSession(sessionId, currentUser.getUserId());
        return Results.success();
    }

    @PutMapping("/conversations/{sessionId}/end")
    public Result<Void> endConversation(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        interviewSessionFacade.endConversation(sessionId, currentUser.getUserId());
        return Results.success();
    }

    /**
     * 【面试主线 2/6】上传简历与可选 JD，解析材料并生成本场题目。
     * 简历是经历依据，JD 是岗位要求；后端独立校验两种 JD 输入互斥。
     * 题量与难度是可选出题选项，缺省时走配置默认值与「智能匹配」。
     */
    @PostMapping("/sessions/{sessionId}/interview-questions")
    public Result<InterviewQuestionRespDTO> extractInterviewQuestions(
            @PathVariable String sessionId,
            @RequestParam("resumePdf") MultipartFile resumePdf,
            @RequestParam(value = "jdText", required = false) String jdText,
            @RequestParam(value = "jdFile", required = false) MultipartFile jdFile,
            @RequestParam(value = "questionCount", required = false) String questionCount,
            @RequestParam(value = "difficulty", required = false) String difficulty,
            @CurrentUser UserContext currentUser) {
        // HTTP 入参来源分开：sessionId 来自 URL，简历/JD/选项来自 multipart，用户信息来自登录上下文。
        // Results.success 只是接口外层包装，出题业务是否成功还要看内部响应的 isSuccess。
        return Results.success(interviewSessionFacade.extractInterviewQuestions(
                sessionId, resumePdf, jdText, jdFile, questionCount, difficulty,
                currentUser.getUserId(), currentUser.getUsername()));
    }

    // 表单兼容入口：组装与 JSON 入口相同的答题 DTO；当前 Vue 主流程使用下面的 answer-json。
    @PostMapping("/sessions/{sessionId}/interview/answer")
    public Result<InterviewAnswerRespDTO> answerInterviewQuestion(
            @PathVariable String sessionId,
            @NotBlank(message = "questionNumber cannot be blank")
            @Size(max = 32, message = "questionNumber length must be less than or equal to 32")
            @RequestParam("questionNumber") String questionNumber,
            @NotBlank(message = "answerContent cannot be blank")
            @Size(max = 5000, message = "answerContent length must be less than or equal to 5000")
            @RequestParam("answerContent") String answerContent,
            @RequestParam(value = "requestId", required = false) String requestId,
            @CurrentUser UserContext currentUser) {
        InterviewAnswerReqDTO requestParam = new InterviewAnswerReqDTO();
        requestParam.setQuestionNumber(questionNumber);
        requestParam.setAnswerContent(answerContent);
        requestParam.setRequestId(requestId);
        return Results.success(
                interviewSessionFacade.answerInterviewQuestion(sessionId, requestParam, currentUser.getUserId()));
    }

    /**
     * 【面试主线 4/6】Vue 当前使用的答题入口：评分、追问决策、生成下一题并提交进度。
     * requestId 标识本次提交，题号及回答摘要参与后端重复提交判定。
     */
    @PostMapping(value = "/sessions/{sessionId}/interview/answer-json", consumes = "application/json")
    public Result<InterviewAnswerRespDTO> answerInterviewQuestionJson(
            @PathVariable String sessionId,
            @Valid @RequestBody InterviewAnswerReqDTO requestParam,
            @CurrentUser UserContext currentUser) {
        return Results.success(
                interviewSessionFacade.answerInterviewQuestion(sessionId, requestParam, currentUser.getUserId()));
    }

    @GetMapping("/sessions/{sessionId}/next-question")
    public Result<InterviewAnswerRespDTO> getNextQuestion(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getNextQuestion(sessionId, currentUser.getUserId()));
    }

    /**
     * 【面试主线 3/6】读取数据库游标对应的当前题；刷新页面不等于提交回答或切换下一题。
     */
    @GetMapping("/sessions/{sessionId}/current-question")
    public Result<InterviewAnswerRespDTO> getCurrentQuestion(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getCurrentQuestion(sessionId, currentUser.getUserId()));
    }

    /**
     * 【恢复分支】读取会话状态与材料摘要，判断能否继续；恢复接口本身不重新出题。
     */
    @GetMapping("/sessions/{sessionId}/restore")
    public Result<InterviewSessionRestoreRespDTO> restoreInterviewSession(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.restoreInterviewSession(sessionId, currentUser.getUserId()));
    }

    @GetMapping("/sessions/{sessionId}/interview/questions")
    public Result<Map<String, String>> getSessionInterviewQuestions(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getSessionInterviewQuestions(sessionId, currentUser.getUserId()));
    }

    @GetMapping("/sessions/{sessionId}/interview/score")
    public Result<Integer> getSessionTotalScore(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getSessionTotalScore(sessionId, currentUser.getUserId()));
    }

    @GetMapping("/sessions/{sessionId}/interview/suggestions")
    public Result<Map<String, String>> getSessionInterviewSuggestions(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(
                interviewSessionFacade.getSessionInterviewSuggestions(sessionId, currentUser.getUserId()));
    }

    @GetMapping("/sessions/{sessionId}/resume/score")
    public Result<Integer> getSessionResumeScore(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getSessionResumeScore(sessionId, currentUser.getUserId()));
    }

    @GetMapping("/sessions/{sessionId}/radar-chart")
    public Result<RadarChartDTO> getRadarChartData(
            @PathVariable String sessionId,
            @CurrentUser UserContext currentUser) {
        return Results.success(interviewSessionFacade.getRadarChartData(sessionId, currentUser.getUserId()));
    }

}
