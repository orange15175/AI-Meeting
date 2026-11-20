package com.hewei.hzyjy.aimeeting.interview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewConversationPageReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewConversationRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewSessionService;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewSessionServiceImpl implements InterviewSessionService {

    private final InterviewSessionStore sessionStore;

    @Override
    public InterviewSessionCreateRespDTO createSession(Long userId) {
        abandonActiveSessions(userId);
        InterviewSession session = sessionStore.create(userId);
        return new InterviewSessionCreateRespDTO(session.getSessionId(), session.getStatus());
    }

    @Override
    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        IPage<InterviewSession> page = sessionStore.page(
                userId,
                requestParam.getCurrent(),
                requestParam.getSize(),
                StrUtil.trimToNull(requestParam.getStatus()),
                StrUtil.trimToNull(requestParam.getKeyword()));
        Page<InterviewConversationRespDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toRespDTO).collect(Collectors.toList()));
        return result;
    }

    @Override
    public InterviewSession getBySessionId(String sessionId) {
        return sessionStore.findBySessionId(sessionId);
    }

    @Override
    public InterviewSession requireOwnedSession(String sessionId, Long userId) {
        return sessionStore.requireOwned(sessionId, userId);
    }

    @Override
    public void markResumeUploading(String sessionId, Long userId) {
        InterviewSession session = requireResumable(sessionId, userId);
        // 已进入答题的会话不允许重新准备：重出题会作废题库并清空既有回答，
        // 只能让用户新建一场面试，避免静默吃掉历史作答。
        if (InterviewSessionStatus.IN_PROGRESS.name().equals(session.getStatus())) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_ALREADY_STARTED);
        }
        sessionStore.updateStatus(sessionId, InterviewSessionStatus.valueOf(session.getStatus()),
                InterviewSessionStatus.RESUME_UPLOADING);
    }

    @Override
    public void markReady(String sessionId, Long userId, String resumeFileUrl, String interviewType) {
        InterviewSession session = requireResumable(sessionId, userId);
        sessionStore.updateMetadata(sessionId, resumeFileUrl, interviewType);
        sessionStore.updateStatus(sessionId, InterviewSessionStatus.valueOf(session.getStatus()),
                InterviewSessionStatus.READY);
    }

    @Override
    public void markDraft(String sessionId, Long userId) {
        InterviewSession session = requireResumable(sessionId, userId);
        // 只有提取失败才回退到 DRAFT；已进入答题的会话不回退，避免把历史进度抹掉。
        if (InterviewSessionStatus.IN_PROGRESS.name().equals(session.getStatus())) {
            return;
        }
        sessionStore.updateStatus(sessionId, InterviewSessionStatus.valueOf(session.getStatus()),
                InterviewSessionStatus.DRAFT);
    }

    @Override
    public void markInProgressIfReady(String sessionId, Long userId) {
        InterviewSession session = requireOwnedSession(sessionId, userId);
        if (!InterviewSessionStatus.READY.name().equals(session.getStatus())) {
            return;
        }
        boolean updated = sessionStore.updateStatus(sessionId, InterviewSessionStatus.READY,
                InterviewSessionStatus.IN_PROGRESS);
        if (updated && session.getStartTime() == null) {
            sessionStore.commitProgress(com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewProgressCommit.builder()
                    .sessionId(sessionId)
                    .expectedVersion(session.getVersion())
                    .startTime(new Date())
                    .build());
        }
    }

    @Override
    public void finishSession(String sessionId, Long userId) {
        InterviewSession session = requireOwnedSession(sessionId, userId);
        if (InterviewSessionStatus.FINISHED.name().equals(session.getStatus())) {
            return;
        }
        Date now = new Date();
        sessionStore.commitProgress(com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewProgressCommit.builder()
                .sessionId(sessionId)
                .expectedVersion(session.getVersion())
                .status(InterviewSessionStatus.FINISHED.name())
                .startTime(session.getStartTime() == null ? now : null)
                .endTime(now)
                .build());
    }

    @Override
    public void abandonActiveSessions(Long userId) {
        List<InterviewSession> sessions = sessionStore.listActiveByUser(userId);
        sessionStore.abandonSessions(sessions);
    }

    private InterviewSession requireResumable(String sessionId, Long userId) {
        InterviewSession session = requireOwnedSession(sessionId, userId);
        InterviewSessionStatus status;
        try {
            status = InterviewSessionStatus.valueOf(session.getStatus());
        } catch (Exception ex) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
        }
        if (!status.isActive()) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
        }
        return session;
    }

    private InterviewConversationRespDTO toRespDTO(InterviewSession session) {
        InterviewConversationRespDTO respDTO = new InterviewConversationRespDTO();
        BeanUtils.copyProperties(session, respDTO);
        return respDTO;
    }
}
