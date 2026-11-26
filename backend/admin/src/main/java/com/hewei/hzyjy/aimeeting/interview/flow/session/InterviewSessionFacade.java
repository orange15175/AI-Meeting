package com.hewei.hzyjy.aimeeting.interview.flow.session;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewAnswerReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewConversationPageReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewAnswerRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewConversationRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewMessageHistoryRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewRecordRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.aimeeting.interview.application.InterviewWorkflowService;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.flow.report.InterviewResumePreviewService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRadarService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRecordService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewSessionService;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewJobContextReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试门面：HTTP 入口的用例编排。
 *
 * <p>所有读取都直接落到 MySQL（会话 / 材料 / 题目 / 提交），
 * 不再有"缓存优先、缓存丢了再从快照反推"的分支。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionFacade {

    private final InterviewWorkflowService interviewWorkflowService;
    private final InterviewSessionService interviewSessionService;
    private final InterviewRecordService interviewRecordService;
    private final InterviewResumePreviewService interviewResumePreviewService;
    private final InterviewRadarService interviewRadarService;
    private final InterviewMaterialStore materialStore;
    private final InterviewQuestionStore questionStore;
    private final InterviewSubmissionStore submissionStore;
    private final InterviewJobContextReader jobContextReader;

    // 【面试主线 1/6】门面只转交创建用例；真正生成 sessionId 和插入会话行在 SessionStore。
    public InterviewSessionCreateRespDTO createSession(Long userId) {
        return interviewSessionService.createSession(userId);
    }

    // 会话列表用例：按当前登录用户分页查询；筛选和 DTO 转换交给会话服务。
    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        return interviewSessionService.pageConversations(userId, requestParam);
    }

    // 历史兼容接口暂未开放：先校验归属，再明确报错，不能误认为已经实现消息查询。
    public List<InterviewMessageHistoryRespDTO> getConversationHistory(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        throw new ClientException("interview history is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    public IPage<InterviewMessageHistoryRespDTO> pageHistoryMessages(
            String sessionId,
            Integer current,
            Integer size,
            Long userId) {
        if (StrUtil.isNotBlank(sessionId)) {
            interviewSessionService.requireOwnedSession(sessionId, userId);
        }
        throw new ClientException("interview history paging is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    public void finishSession(String sessionId, Long userId) {
        // 【面试主线 5/6】收口：写报告 + 会话置为结束，内部有 finalize 锁与重试。
        // 方法名保留 FromRedis 仅为兼容历史接口，当前报告输入实际来自 MySQL。
        interviewRecordService.saveInterviewRecordFromRedis(sessionId, userId);
    }

    public void endConversation(String sessionId, Long userId) {
        finishSession(sessionId, userId);
    }

    /**
     * 【面试主线 2/6】连接 HTTP 入参与出题编排：进入上传状态 → 保存起始版本 → 出题 → READY 或 DRAFT。
     * 会话归属和状态边界由 SessionService 检查，已进入答题的会话不能在这里换材料重出题。
     */
    public InterviewQuestionRespDTO extractInterviewQuestions(
            String sessionId,
            MultipartFile resumePdf,
            String jdText,
            MultipartFile jdFile,
            String questionCount,
            String difficulty,
            Long userId,
            String username) {
        interviewSessionService.markResumeUploading(sessionId, userId);

        // 记录准备开始时的会话版本：模型调用期间若会话被推进，提交阶段必须失败回滚，
        // 不能在提交时重新读一次版本号再当作请求起始版本。
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);

        // 把 HTTP 文件、文本、登录用户名及起始版本打包为内部命令；sessionId 由 URL 传入，用户归属已在上面校验。
        InterviewQuestionReqDTO reqDTO = new InterviewQuestionReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setResumePdf(resumePdf);
        reqDTO.setJdText(jdText);
        reqDTO.setJdFile(jdFile);
        // 题量与难度原样透传，解析与区间校验统一放在出题服务，避免两处各写一套规则。
        reqDTO.setQuestionCount(questionCount);
        reqDTO.setDifficulty(difficulty);
        reqDTO.setExpectedSessionVersion(session == null ? null : session.getVersion());

        // 【关键节点：准备结果收口】等待同步出题返回；业务成功才更新元信息并标为 READY，失败结果则尝试退回 DRAFT。
        InterviewQuestionRespDTO response = interviewWorkflowService.extractInterviewQuestions(reqDTO);
        if (response != null && Integer.valueOf(1).equals(response.getIsSuccess())) {
            interviewSessionService.markReady(
                    sessionId,
                    userId,
                    response.getResumeFileUrl(),
                    response.getInterviewType());
            return response;
        }
        interviewSessionService.markDraft(sessionId, userId);
        return response;
    }

    /**
     * 【关键节点：访问与状态边界】先检查会话归属及可继续状态，再进入答题流水线。
     */
    public InterviewAnswerRespDTO answerInterviewQuestion(
            String sessionId,
            InterviewAnswerReqDTO requestParam,
            Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        // 以已经通过归属检查的 URL 会话 ID 为准，覆盖请求对象中的 sessionId，避免请求体指向另一场会话。
        requestParam.setSessionId(sessionId);
        return interviewWorkflowService.answerInterviewQuestion(sessionId, requestParam);
    }

    // 兼容命名：当前实现最终读取当前题，不能通过重复调用这个方法跳过作答、推进题号。
    public InterviewAnswerRespDTO getNextQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        return interviewWorkflowService.getNextQuestion(sessionId);
    }

    /**
     * 【关键节点：状态与题号分开】成功读到题目后可以将 READY 标为 IN_PROGRESS，
     * 但真正的题号推进只发生在答题提交事务中，不能把读取当前题当作“下一题”操作。
     */
    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        InterviewAnswerRespDTO response = interviewWorkflowService.getCurrentQuestion(sessionId);
        if (response != null && Boolean.TRUE.equals(response.getIsSuccess()) && !Boolean.TRUE.equals(response.getFinished())) {
            interviewSessionService.markInProgressIfReady(sessionId, userId);
        }
        return response;
    }

    // 预览简历也必须先验证会话属于当前用户，再由预览服务解析本地资源。
    public InterviewResumePreviewService.ResumePreviewResource loadResumePreview(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return interviewResumePreviewService.loadResumePreview(sessionId);
    }

    // 恢复页组装会话状态和材料摘要；这里返回可恢复信息，不重新上传、不再次调用模型，也不重建题库。
    public InterviewSessionRestoreRespDTO restoreInterviewSession(String sessionId, Long userId) {
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        InterviewMaterial material = materialStore.findBySession(sessionId);

        InterviewSessionRestoreRespDTO response = new InterviewSessionRestoreRespDTO();
        response.setSessionId(sessionId);
        response.setStatus(session.getStatus());
        response.setCanResume(isSessionResumable(session));
        // 元信息优先用会话表；会话未回填时回退材料表，兼容准备阶段留下的材料记录。
        response.setResumeFileUrl(StrUtil.isNotBlank(session.getResumeFileUrl())
                ? session.getResumeFileUrl()
                : (material == null ? null : material.getResumeFileUrl()));
        response.setInterviewType(StrUtil.isNotBlank(session.getInterviewType())
                ? session.getInterviewType()
                : (material == null ? null : material.getInterviewType()));
        response.setResumeScore(material == null ? null : material.getResumeScore());
        response.setSuggestions(material == null ? Collections.emptyMap() : parseSuggestions(material.getSuggestionsJson()));
        boolean jdEnabled = material != null && StrUtil.isNotBlank(material.getJdSource());
        response.setJdEnabled(jdEnabled);
        if (jdEnabled) {
            response.setJdSource(material.getJdSource());
            response.setJdFileName(material.getJdFileName());
            response.setJdSummary(jobContextReader.readSummary(sessionId, 160));
        }
        return response;
    }

    // 以下题库、分数、建议及雷达查询都先检查归属；读取现有数据，不触发新的评分模型调用。
    public Map<String, String> getSessionInterviewQuestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return questionStore.questionsAsMap(sessionId);
    }

    public Integer getSessionTotalScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        // 总分优先来自成功主问题提交的平均分；尚无有效分数时才尝试取报告，缺失分数仍可能为 null。
        Integer score = submissionStore.averageMainScore(sessionId);
        if (score != null) {
            return score;
        }
        InterviewRecordRespDTO record = interviewRecordService.getBySessionId(sessionId, userId);
        return record == null ? null : record.getInterviewScore();
    }

    public Map<String, String> getSessionInterviewSuggestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        InterviewMaterial material = materialStore.findBySession(sessionId);
        return material == null ? Collections.emptyMap() : parseSuggestions(material.getSuggestionsJson());
    }

    public Integer getSessionResumeScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        InterviewMaterial material = materialStore.findBySession(sessionId);
        return material == null ? null : material.getResumeScore();
    }

    public RadarChartDTO getRadarChartData(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        InterviewMaterial material = materialStore.findBySession(sessionId);
        Integer resumeScore = material == null ? null : material.getResumeScore();
        Integer interviewScore = submissionStore.averageMainScore(sessionId);
        RadarChartDTO radar = interviewRadarService.buildRadarChart(resumeScore, interviewScore);
        if (hasRadarSignal(radar)) {
            return radar;
        }
        InterviewRecordRespDTO record = interviewRecordService.getBySessionId(sessionId, userId);
        return record != null && record.getRadarChart() != null ? record.getRadarChart() : radar;
    }

    // 展示字段容错：旧建议 JSON 为空或损坏时返回空映射，不让辅助展示阻断主流程。
    private Map<String, String> parseSuggestions(String suggestionsJson) {
        if (StrUtil.isBlank(suggestionsJson)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> parsed = JSON.parseObject(
                    suggestionsJson,
                    new TypeReference<LinkedHashMap<String, String>>() {
                    });
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse suggestions json, error={}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    // 公共业务门槛：除了会话属于当前用户，还必须满足生命周期的可继续条件。
    private void ensureInterviewCanProceed(String sessionId, Long userId) {
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        if (session == null || !isSessionResumable(session)) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
        }
    }

    private boolean isSessionResumable(InterviewSession session) {
        if (session == null || StrUtil.isBlank(session.getStatus())) {
            return false;
        }
        try {
            return InterviewSessionStatus.valueOf(session.getStatus()).canResume();
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean hasRadarSignal(RadarChartDTO radar) {
        if (radar == null) {
            return false;
        }
        return positive(radar.getResumeScore())
                || positive(radar.getInterviewPerformance())
                || positive(radar.getProfessionalSkills())
                || positive(radar.getPotentialIndex());
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
