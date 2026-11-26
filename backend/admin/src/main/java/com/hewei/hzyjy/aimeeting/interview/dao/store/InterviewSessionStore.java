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
 * <p>准备/答题游标通过 version 条件更新，生命周期状态可按预期 status 更新；
 * 这些条件更新返回 false 表示未成功写入，调用方需处理；批量放弃旧会话另按主键逐行更新。</p>
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionStore {

    private final InterviewSessionMapper sessionMapper;

    // 【关键节点：会话落库】生成雪花 ID，绑定用户并初始化生命周期 DRAFT、题内阶段 INIT；此处只创建会话，不生成题目。
    public InterviewSession create(Long userId) {
        Date now = new Date();
        InterviewSession session = new InterviewSession();
        session.setSessionId(IdUtil.getSnowflakeNextIdStr());
        session.setUserId(userId);
        session.setStatus(InterviewSessionStatus.DRAFT.name());
        session.setFlowStatus(InterviewFlowStatus.INIT.name());
        session.setConversationTitle("Interview Session");
        // 初始化题量、追问次数及乐观锁版本；这些是数据库中的流程起点，之后由准备/答题提交服务更新。
        session.setTotalQuestions(0);
        session.setFollowUpCount(0);
        session.setMaxFollowUp(2);
        session.setVersion(0);
        session.setOperationEpoch(0L);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        session.setDelFlag(0);
        // 执行 INSERT 后才返回创建对象；若数据库写入抛异常，调用方拿不到成功响应。
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

    // 所有权检查：参数合法 → 会话存在 → userId 匹配；只知道 sessionId 并不足以读取或修改他人的面试。
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
     * 【关键节点：乐观锁落点】WHERE session_id + version 匹配才更新，并递增 version 与 operation_epoch。
     * 返回 false 表示本次没有成功推进；准备提交服务据此抛异常回滚，防止迟到结果覆盖新进度。
     */
    public boolean commitProgress(InterviewProgressCommit commit) {
        if (commit == null || StrUtil.isBlank(commit.getSessionId()) || commit.getExpectedVersion() == null) {
            return false;
        }
        return sessionMapper.update(null, buildProgressWrapper(commit)) == 1;
    }

    /**
     * 答题游标推进：在 version 乐观锁之外，额外要求会话仍处于 {@code IN_PROGRESS}、
     * 且当前题号仍停在本次作答的题目上。
     *
     * <p>为什么单独开一个方法而不是给 {@link #commitProgress} 加条件：后者还被出题提交与
     * 结束会话使用（前者写 READY→IN_PROGRESS 与题号，后者写 FINISHED），加条件会直接打断它们。</p>
     *
     * <p>条件检查的是更新前的值，因此最后一题把状态改成 FINISHED 时依然成立。</p>
     */
    public boolean advanceAnswerProgress(InterviewProgressCommit commit, String answeredQuestionNo) {
        if (commit == null || StrUtil.isBlank(commit.getSessionId()) || commit.getExpectedVersion() == null
                || StrUtil.isBlank(answeredQuestionNo)) {
            return false;
        }
        LambdaUpdateWrapper<InterviewSession> wrapper = buildProgressWrapper(commit)
                .eq(InterviewSession::getStatus, InterviewSessionStatus.IN_PROGRESS.name())
                .eq(InterviewSession::getCurrentQuestionNo, answeredQuestionNo);
        return sessionMapper.update(null, wrapper) == 1;
    }

    private LambdaUpdateWrapper<InterviewSession> buildProgressWrapper(InterviewProgressCommit commit) {
        // 【关键节点：版本条件写】把预期 version 放入 WHERE，同时在数据库递增版本；这才是乐观锁的实际落点。
        LambdaUpdateWrapper<InterviewSession> wrapper = Wrappers.lambdaUpdate(InterviewSession.class)
                .eq(InterviewSession::getSessionId, commit.getSessionId())
                .eq(InterviewSession::getDelFlag, 0)
                .eq(InterviewSession::getVersion, commit.getExpectedVersion())
                .set(InterviewSession::getVersion, commit.getExpectedVersion() + 1)
                .setSql("operation_epoch = operation_epoch + 1")
                .set(InterviewSession::getUpdateTime, new Date());
        // 按命令中有值的字段更新状态、题号、追问次数和时间；空字段不会自动清空数据库旧值。
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
        return wrapper;
    }

    // 活动会话筛选：只查本用户、未删除、DRAFT/RESUME_UPLOADING/READY/IN_PROGRESS 的记录；已完成和已放弃不参与。
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

    // 旧会话退场仅更新状态及结束时间，保留原记录；逐行更新，不等同于删除数据或保存复盘报告。
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
