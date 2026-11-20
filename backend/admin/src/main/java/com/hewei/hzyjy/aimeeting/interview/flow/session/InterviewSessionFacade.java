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

    public InterviewSessionCreateRespDTO createSession(Long userId) {
        return interviewSessionService.createSession(userId);
    }

    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        return interviewSessionService.pageConversations(userId, requestParam);
    }

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
        // 收口：写报告 + 会话置为结束，内部有 finalize 锁与重试。
        interviewRecordService.saveInterviewRecordFromRedis(sessionId, userId);
    }

    public void endConversation(String sessionId, Long userId) {
        finishSession(sessionId, userId);
    }

    public InterviewQuestionRespDTO extractInterviewQuestions(
            String sessionId,
            MultipartFile resumePdf,
            String jdText,
            MultipartFile jdFile,
            Long userId,
            String username) {
        interviewSessionService.markResumeUploading(sessionId, userId);

        // 记录准备开始时的会话版本：模型调用期间若会话被推进，提交阶段必须失败回滚，
        // 不能在提交时重新读一次版本号再当作请求起始版本。
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);

        InterviewQuestionReqDTO reqDTO = new InterviewQuestionReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setResumePdf(resumePdf);
        reqDTO.setJdText(jdText);
        reqDTO.setJdFile(jdFile);
        reqDTO.setExpectedSessionVersion(session == null ? null : session.getVersion());

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

    public InterviewAnswerRespDTO answerInterviewQuestion(
            String sessionId,
            InterviewAnswerReqDTO requestParam,
            Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        requestParam.setSessionId(sessionId);
        return interviewWorkflowService.answerInterviewQuestion(sessionId, requestParam);
    }

    public InterviewAnswerRespDTO getNextQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        return interviewWorkflowService.getNextQuestion(sessionId);
    }

    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        InterviewAnswerRespDTO response = interviewWorkflowService.getCurrentQuestion(sessionId);
        if (response != null && Boolean.TRUE.equals(response.getIsSuccess()) && !Boolean.TRUE.equals(response.getFinished())) {
            interviewSessionService.markInProgressIfReady(sessionId, userId);
        }
        return response;
    }

    public InterviewResumePreviewService.ResumePreviewResource loadResumePreview(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return interviewResumePreviewService.loadResumePreview(sessionId);
    }

    public InterviewSessionRestoreRespDTO restoreInterviewSession(String sessionId, Long userId) {
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        InterviewMaterial material = materialStore.findBySession(sessionId);

        InterviewSessionRestoreRespDTO response = new InterviewSessionRestoreRespDTO();
        response.setSessionId(sessionId);
        response.setStatus(session.getStatus());
        response.setCanResume(isSessionResumable(session));
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

    public Map<String, String> getSessionInterviewQuestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return questionStore.questionsAsMap(sessionId);
    }

    public Integer getSessionTotalScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
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
