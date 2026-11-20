package com.hewei.hzyjy.aimeeting.interview.dao.store;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewSessionMapper;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowStatus;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 面试会话存取。
 *
 * <p>所有会改变流程的写入都走条件更新（version 或 status 匹配才生效），
 * 返回值 false 表示状态已被其他执行者改写，调用方必须据此放弃本次结果。</p>
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionStore {

    private final InterviewSessionMapper sessionMapper;

    public InterviewSession create(Long userId) {
        Date now = new Date();
        InterviewSession session = new InterviewSession();
        session.setSessionId(IdUtil.getSnowflakeNextIdStr());
        session.setUserId(userId);
        session.setStatus(InterviewSessionStatus.DRAFT.name());
        session.setFlowStatus(InterviewFlowStatus.INIT.name());
        session.setConversationTitle("Interview Session");
        session.setTotalQuestions(0);
        session.setFollowUpCount(0);
        session.setMaxFollowUp(2);
        session.setVersion(0);
        session.setOperationEpoch(0L);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        session.setDelFlag(0);
        sessionMapper.insert(session);
        return session;
    }

    public InterviewSession findBySessionId(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return sessionMapper.selectOne(Wrappers.lambdaQuery(InterviewSession.class)
                .eq(InterviewSession::getSessionId, sessionId)
                .eq(InterviewSession::getDelFlag, 0));
    }

    public InterviewSession requireOwned(String sessionId, Long userId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        if (userId == null || userId <= 0) {
            throw new ClientException(InterviewErrorCodeEnum.INVALID_USER_ID);
        }
        InterviewSession session = findBySessionId(sessionId);
        if (session == null) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_NOT_FOUND);
        }
        if (!userId.equals(session.getUserId())) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_ACCESS_DENIED);
        }
        return session;
    }

    /**
     * 状态推进：只有当前状态符合预期时才生效，避免并发覆盖。
     */
    public boolean updateStatus(String sessionId, InterviewSessionStatus expected, InterviewSessionStatus target) {
        LambdaUpdateWrapper<InterviewSession> wrapper = Wrappers.lambdaUpdate(InterviewSession.class)
                .eq(InterviewSession::getSessionId, sessionId)
                .eq(InterviewSession::getDelFlag, 0)
                .set(InterviewSession::getStatus, target.name())
                .set(InterviewSession::getUpdateTime, new Date());
        if (expected != null) {
            wrapper.eq(InterviewSession::getStatus, expected.name());
        }
        return sessionMapper.update(null, wrapper) == 1;
    }

    public boolean updateMetadata(String sessionId, String resumeFileUrl, String interviewType) {
        return sessionMapper.update(null, Wrappers.lambdaUpdate(InterviewSession.class)
                .eq(InterviewSession::getSessionId, sessionId)
                .eq(InterviewSession::getDelFlag, 0)
                .set(StrUtil.isNotBlank(resumeFileUrl), InterviewSession::getResumeFileUrl, resumeFileUrl)
                .set(StrUtil.isNotBlank(interviewType), InterviewSession::getInterviewType, interviewType)
                .set(InterviewSession::getUpdateTime, new Date())) == 1;
    }

    /**
     * 条件推进流程：版本匹配才写入，并同步递增 version 与 operation_epoch。
     */
    public boolean commitProgress(InterviewProgressCommit commit) {
        if (commit == null || StrUtil.isBlank(commit.getSessionId()) || commit.getExpectedVersion() == null) {
            return false;
        }
        LambdaUpdateWrapper<InterviewSession> wrapper = Wrappers.lambdaUpdate(InterviewSession.class)
                .eq(InterviewSession::getSessionId, commit.getSessionId())
                .eq(InterviewSession::getDelFlag, 0)
                .eq(InterviewSession::getVersion, commit.getExpectedVersion())
                .set(InterviewSession::getVersion, commit.getExpectedVersion() + 1)
                .setSql("operation_epoch = operation_epoch + 1")
                .set(InterviewSession::getUpdateTime, new Date());
        if (StrUtil.isNotBlank(commit.getStatus())) {
            wrapper.set(InterviewSession::getStatus, commit.getStatus());
        }
        if (StrUtil.isNotBlank(commit.getFlowStatus())) {
            wrapper.set(InterviewSession::getFlowStatus, commit.getFlowStatus());
        }
        if (StrUtil.isNotBlank(commit.getCurrentQuestionNo())) {
            wrapper.set(InterviewSession::getCurrentQuestionNo, commit.getCurrentQuestionNo());
        }
        if (commit.getTotalQuestions() != null) {
            wrapper.set(InterviewSession::getTotalQuestions, commit.getTotalQuestions());
        }
        if (commit.getFollowUpCount() != null) {
            wrapper.set(InterviewSession::getFollowUpCount, commit.getFollowUpCount());
        }
        if (commit.getActiveSubmissionId() != null) {
            wrapper.set(InterviewSession::getActiveSubmissionId, commit.getActiveSubmissionId());
        }
        if (commit.getStartTime() != null) {
            wrapper.setSql("start_time = IFNULL(start_time, {0})", commit.getStartTime());
        }
        if (commit.getEndTime() != null) {
            wrapper.set(InterviewSession::getEndTime, commit.getEndTime());
        }
        return sessionMapper.update(null, wrapper) == 1;
    }

    public List<InterviewSession> listActiveByUser(Long userId) {
        return sessionMapper.selectList(Wrappers.lambdaQuery(InterviewSession.class)
                .eq(InterviewSession::getUserId, userId)
                .eq(InterviewSession::getDelFlag, 0)
                .in(InterviewSession::getStatus, List.of(
                        InterviewSessionStatus.DRAFT.name(),
                        InterviewSessionStatus.RESUME_UPLOADING.name(),
                        InterviewSessionStatus.READY.name(),
                        InterviewSessionStatus.IN_PROGRESS.name()))
                .orderByDesc(InterviewSession::getUpdateTime));
    }

    public int abandonSessions(List<InterviewSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        Date now = new Date();
        int affected = 0;
        for (InterviewSession session : sessions) {
            int rows = sessionMapper.update(null, Wrappers.lambdaUpdate(InterviewSession.class)
                    .eq(InterviewSession::getId, session.getId())
                    .eq(InterviewSession::getDelFlag, 0)
                    .set(InterviewSession::getStatus, InterviewSessionStatus.ABANDONED.name())
                    .set(InterviewSession::getEndTime, now)
                    .set(InterviewSession::getUpdateTime, now));
            affected += rows;
        }
        return affected;
    }

    public IPage<InterviewSession> page(Long userId, int current, int size, String status, String keyword) {
        LambdaQueryWrapper<InterviewSession> wrapper = Wrappers.lambdaQuery(InterviewSession.class)
                .eq(InterviewSession::getUserId, userId)
                .eq(InterviewSession::getDelFlag, 0)
                .eq(StrUtil.isNotBlank(status), InterviewSession::getStatus, status)
                .like(StrUtil.isNotBlank(keyword), InterviewSession::getConversationTitle, keyword)
                .orderByDesc(InterviewSession::getUpdateTime);
        return sessionMapper.selectPage(new Page<>(current, size), wrapper);
    }
}
