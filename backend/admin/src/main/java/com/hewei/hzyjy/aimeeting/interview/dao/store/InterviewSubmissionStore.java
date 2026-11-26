package com.hewei.hzyjy.aimeeting.interview.dao.store;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hewei.hzyjy.aimeeting.interview.config.InterviewAnswerGuardConfiguration;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewAnswerSubmission;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewAnswerSubmissionMapper;
import io.micrometer.core.instrument.Metrics;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 回答提交存取：幂等登记、执行权租约、条件完成与分数聚合。
 *
 * <p>幂等不依赖 Redis 去重集合：唯一键 (session_id, question_no) 保证每题只有一行有效提交，
 * (session_id, request_id) 索引支撑同次重试回放原结果。</p>
 *
 * <p>执行权租约：登记/接管时写入 {@code heartbeat_at} 与 {@code deadline_at}（都用数据库时间），
 * 执行期间由心跳任务刷新 {@code heartbeat_at}。判定"能否接管"看租约是否过期，
 * 而不是看这一行多久没被业务写过——后者度量的是业务耗时，不是执行者是否存活。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSubmissionStore {

    private final InterviewAnswerSubmissionMapper submissionMapper;
    private final InterviewAnswerGuardConfiguration configuration;

    public enum ClaimStatus {
        /**
         * 首次登记，可以继续调模型。
         */
        NEW,
        /**
         * 上次失败或孤儿接管，可以重试。
         */
        RETRY,
        /**
         * 同一次请求已完成，直接回放原响应。
         */
        REPLAY,
        /**
         * 同 requestId 但输入不同，拒绝。
         */
        CONFLICT,
        /**
         * 已有成功提交，且不是同一次请求（旧题重复提交）。
         */
        ALREADY_ANSWERED,
        /**
         * 同一题正在处理中。
         */
        BUSY
    }

    @Data
    @Builder
    public static class ClaimResult {
        private ClaimStatus status;
        private InterviewAnswerSubmission submission;
    }

    // 按会话 + 题号定位本题唯一有效提交；回答重试复用这条记录，而不是不断新增评分行。
    public InterviewAnswerSubmission findByQuestion(String sessionId, String questionNo) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNo)) {
            return null;
        }
        return submissionMapper.selectOne(Wrappers.lambdaQuery(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSessionId, sessionId)
                .eq(InterviewAnswerSubmission::getQuestionNo, questionNo.trim())
                .eq(InterviewAnswerSubmission::getDelFlag, 0));
    }

    /**
     * 登记一次提交。返回 REPLAY 时调用方应直接回放 {@code submission.responseJson}。
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimResult claim(
            String sessionId,
            String questionNo,
            String requestId,
            String inputHash,
            String answerContent,
            String submissionId,
            String attemptToken) {
        // 【关键节点：数据库占位】先登记 PROCESSING；唯一约束处理并发插入，登记事务结束后才由上层调用模型。
        InterviewAnswerSubmission existing = findByQuestion(sessionId, questionNo);
        if (existing == null) {
            Date now = new Date();
            InterviewAnswerSubmission fresh = new InterviewAnswerSubmission();
            fresh.setSubmissionId(submissionId);
            fresh.setSessionId(sessionId);
            fresh.setQuestionNo(questionNo);
            fresh.setRequestId(requestId);
            fresh.setInputHash(inputHash);
            fresh.setAnswerContent(answerContent);
            fresh.setSkipped(0);
            fresh.setStatus(InterviewAnswerSubmission.STATUS_PROCESSING);
            // attemptToken 表示当前这一轮执行权；失败重试或孤儿接管会更换它，旧模型结果因此失去提交资格。
            fresh.setAttemptToken(attemptToken);
            fresh.setAttemptCount(1);
            fresh.setFinished(0);
            fresh.setCreateTime(now);
            fresh.setUpdateTime(now);
            // 先用应用时间占位，插入成功后立刻用数据库时间校准，避免应用与库时钟偏差影响租约判定。
            fresh.setHeartbeatAt(now);
            fresh.setDeadlineAt(new Date(now.getTime() + resolveDeadlineMillis()));
            fresh.setDelFlag(0);
            try {
                submissionMapper.insert(fresh);
                calibrateLease(submissionId, attemptToken);
                return ClaimResult.builder().status(ClaimStatus.NEW).submission(fresh).build();
            } catch (DuplicateKeyException duplicateKeyException) {
                // 并发下另一个执行者先插入了同一题，退化为按已有行处理。
                existing = findByQuestion(sessionId, questionNo);
                if (existing == null) {
                    throw duplicateKeyException;
                }
                log.info("Submission claim hit concurrent insert, sessionId={}, questionNo={}", sessionId, questionNo);
            }
        }
        return settleExisting(existing, requestId, inputHash, answerContent, attemptToken);
    }

    private ClaimResult settleExisting(
            InterviewAnswerSubmission existing,
            String requestId,
            String inputHash,
            String answerContent,
            String attemptToken) {
        String status = existing.getStatus();
        // 【关键节点：重试分流】成功行只允许同 requestId、同输入回放；不同输入为冲突，其他请求视为旧题。
        if (InterviewAnswerSubmission.STATUS_SUCCEEDED.equals(status)) {
            if (Objects.equals(existing.getRequestId(), requestId)) {
                if (Objects.equals(existing.getInputHash(), inputHash)) {
                    return ClaimResult.builder().status(ClaimStatus.REPLAY).submission(existing).build();
                }
                return ClaimResult.builder().status(ClaimStatus.CONFLICT).submission(existing).build();
            }
            return ClaimResult.builder().status(ClaimStatus.ALREADY_ANSWERED).submission(existing).build();
        }

        // 【关键节点：处理中与孤儿】是否过期只由数据库判定，见 takeOver 的 WHERE。
        // 刻意不在 Java 侧用 System.currentTimeMillis() 预判：租约字段由数据库 NOW(3) 写入，
        // 而 JDBC 连接串声明的 serverTimezone 与数据库实际时区可能不一致（本项目即如此），
        // 用应用时钟减读出的时间会整体偏移，导致"永远过期"或"永不接管"。
        // 结论：预判省下的只是一次主键级 UPDATE，不值得为此引入时区依赖。
        if (InterviewAnswerSubmission.STATUS_PROCESSING.equals(status)) {
            log.info("Answer submission is processing, attempt takeover with lease condition, sessionId={}, "
                            + "questionNo={}, attemptToken={}",
                    existing.getSessionId(), existing.getQuestionNo(), existing.getAttemptToken());
        }

        // 接管采用旧状态、旧令牌、租约过期作为更新条件；查询后若已被别人接管，更新行数不为 1，当前请求退回 BUSY。
        int updated = takeOver(existing, requestId, inputHash, answerContent, attemptToken);
        if (updated != 1) {
            Metrics.counter("answer_claim_takeover_rejected_total").increment();
            return ClaimResult.builder().status(ClaimStatus.BUSY).submission(findByQuestion(
                    existing.getSessionId(), existing.getQuestionNo())).build();
        }
        int nextAttempt = existing.getAttemptCount() == null ? 2 : existing.getAttemptCount() + 1;
        existing.setRequestId(requestId);
        existing.setInputHash(inputHash);
        existing.setStatus(InterviewAnswerSubmission.STATUS_PROCESSING);
        existing.setAttemptToken(attemptToken);
        existing.setAttemptCount(nextAttempt);
        return ClaimResult.builder().status(ClaimStatus.RETRY).submission(existing).build();
    }

    /**
     * 抢占提交行。PROCESSING 必须同时满足"租约已过期"，否则并发的两个接管者可能互相覆盖。
     */
    private int takeOver(
            InterviewAnswerSubmission existing,
            String requestId,
            String inputHash,
            String answerContent,
            String attemptToken) {
        LambdaUpdateWrapper<InterviewAnswerSubmission> wrapper = Wrappers
                .lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getId, existing.getId())
                .eq(InterviewAnswerSubmission::getStatus, existing.getStatus())
                .eq(existing.getAttemptToken() != null,
                        InterviewAnswerSubmission::getAttemptToken, existing.getAttemptToken())
                .set(InterviewAnswerSubmission::getRequestId, requestId)
                .set(InterviewAnswerSubmission::getInputHash, inputHash)
                .set(InterviewAnswerSubmission::getAnswerContent, answerContent)
                .set(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_PROCESSING)
                .set(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .set(InterviewAnswerSubmission::getAttemptCount,
                        existing.getAttemptCount() == null ? 2 : existing.getAttemptCount() + 1)
                .set(InterviewAnswerSubmission::getStage, null)
                .set(InterviewAnswerSubmission::getErrorMessage, null)
                .set(InterviewAnswerSubmission::getUpdateTime, new Date())
                .setSql(leaseResetSql());
        if (InterviewAnswerSubmission.STATUS_PROCESSING.equals(existing.getStatus())) {
            // 过期条件必须放进同一条 UPDATE：否则"读到过期"和"写入接管"之间可能有人刚续期成功。
            // 两列都为 NULL 的历史行退回 update_time 阈值；只有一列为 NULL 的异常行不可接管。
            wrapper.apply("((heartbeat_at IS NOT NULL AND deadline_at IS NOT NULL"
                            + " AND (heartbeat_at <= TIMESTAMPADD(MICROSECOND, {0}, NOW(3))"
                            + " OR deadline_at <= NOW(3)))"
                            + " OR (heartbeat_at IS NULL AND deadline_at IS NULL"
                            + " AND update_time <= TIMESTAMPADD(SECOND, {1}, NOW(3))))",
                    -TimeUnit.MILLISECONDS.toMicros(resolveLeaseMillis()),
                    -resolveLegacyTimeoutSeconds());
        }
        return submissionMapper.update(null, wrapper);
    }

    /**
     * 心跳续期：只刷新存活时间，不改状态、不改业务字段。
     *
     * <p>条件里带 attemptToken，使"更新 0 行"同时意味着"我已经失去执行权"；
     * 带 deadline_at 与租约条件，避免晚到的心跳让已过期的尝试复活。</p>
     *
     * @return true 表示本次续期成功；false 表示令牌/状态/租约/截止条件之一不满足
     */
    public boolean heartbeat(String submissionId, String attemptToken) {
        if (StrUtil.isBlank(submissionId) || StrUtil.isBlank(attemptToken)) {
            return false;
        }
        return submissionMapper.update(null, Wrappers.lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSubmissionId, submissionId)
                .eq(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .eq(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_PROCESSING)
                .apply("deadline_at > NOW(3)")
                .apply("heartbeat_at > TIMESTAMPADD(MICROSECOND, {0}, NOW(3))",
                        -TimeUnit.MILLISECONDS.toMicros(resolveLeaseMillis()))
                .setSql("heartbeat_at = NOW(3)")) == 1;
    }

    /**
     * 条件完成：只有仍持有同一 attemptToken、且租约与截止时间都仍有效的执行者能落库。
     *
     * <p>加租约条件是刻意的：如果这次尝试已经超过 deadline，说明执行者可能只是"慢"，
     * 别人随时可以接管；此时把结果写进去会造成同一题两次入账。</p>
     */
    public boolean markSucceeded(
            String submissionId,
            String attemptToken,
            int score,
            String feedback,
            String missingPointsJson,
            String responseJson,
            boolean finished,
            String stage,
            int durationMs) {
        return submissionMapper.update(null, Wrappers.lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSubmissionId, submissionId)
                .eq(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .eq(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_PROCESSING)
                .apply("deadline_at > NOW(3)")
                .apply("heartbeat_at > TIMESTAMPADD(MICROSECOND, {0}, NOW(3))",
                        -TimeUnit.MILLISECONDS.toMicros(resolveLeaseMillis()))
                .set(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_SUCCEEDED)
                .set(InterviewAnswerSubmission::getScore, score)
                .set(InterviewAnswerSubmission::getFeedback, feedback)
                .set(InterviewAnswerSubmission::getMissingPoints, missingPointsJson)
                .set(InterviewAnswerSubmission::getResponseJson, responseJson)
                .set(InterviewAnswerSubmission::getFinished, finished ? 1 : 0)
                .set(InterviewAnswerSubmission::getStage, stage)
                .set(InterviewAnswerSubmission::getDurationMs, durationMs)
                .set(InterviewAnswerSubmission::getErrorMessage, null)
                .set(InterviewAnswerSubmission::getUpdateTime, new Date())) == 1;
    }

    // 失败也必须匹配执行令牌与 PROCESSING 状态；防止旧尝试的异常把新尝试或已成功结果覆盖。
    // 刻意不带租约条件：执行者主动认输时即使已过截止，也应该把行收敛成 FAILED，
    // 否则该行会永远停在 PROCESSING，只能等下一个请求来接管。
    public boolean markFailed(String submissionId, String attemptToken, String stage, String errorMessage) {
        return submissionMapper.update(null, Wrappers.lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSubmissionId, submissionId)
                .eq(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .eq(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_PROCESSING)
                .set(InterviewAnswerSubmission::getStatus, InterviewAnswerSubmission.STATUS_FAILED)
                .set(InterviewAnswerSubmission::getStage, stage)
                .set(InterviewAnswerSubmission::getErrorMessage, StrUtil.maxLength(errorMessage, 480))
                .set(InterviewAnswerSubmission::getUpdateTime, new Date())) == 1;
    }

    /**
     * 提交被拒时判断原因，仅用于日志与指标；不参与任何业务判定。
     *
     * <p>等值令牌只能告诉你"影响 0 行"，这里补一次读，把"被接管 / 状态已变 / 行已删除"分开，
     * 让排障时不必再猜。刻意不在这里比较 deadline：租约字段由数据库时间写入，
     * 用应用时钟去减会受连接串时区声明影响，宁可不给结论。</p>
     */
    public String describeLostReason(String submissionId, String attemptToken) {
        InterviewAnswerSubmission row = submissionMapper.selectOne(Wrappers.lambdaQuery(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSubmissionId, submissionId)
                .eq(InterviewAnswerSubmission::getDelFlag, 0));
        if (row == null) {
            return "row_missing";
        }
        if (!Objects.equals(row.getAttemptToken(), attemptToken)) {
            return "superseded";
        }
        if (!InterviewAnswerSubmission.STATUS_PROCESSING.equals(row.getStatus())) {
            return "status_" + row.getStatus();
        }
        // 令牌仍是自己的、状态仍是处理中，说明是租约或截止条件未通过。
        return "lease_rejected";
    }

    /**
     * 主问题平均分；无有效评分时返回 null，不写 0。
     */
    public Integer averageMainScore(String sessionId) {
        return submissionMapper.averageMainScore(sessionId);
    }

    // 报告聚合与回放入口：统计有效主问题，按题序加载提交；具体 SQL 条件由对应 Mapper 实现。
    public int countSucceededMain(String sessionId) {
        return submissionMapper.countSucceededMain(sessionId);
    }

    public List<InterviewAnswerSubmission> listByQuestionOrder(String sessionId) {
        return submissionMapper.listByQuestionOrder(sessionId);
    }

    /**
     * 重试出题时作废本轮问答。
     */
    public int deleteBySession(String sessionId) {
        return submissionMapper.delete(Wrappers.lambdaQuery(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSessionId, sessionId));
    }

    // 用数据库时间校准新插入行的租约，避免应用与数据库时钟偏差被带进过期判定。
    private void calibrateLease(String submissionId, String attemptToken) {
        submissionMapper.update(null, Wrappers.lambdaUpdate(InterviewAnswerSubmission.class)
                .eq(InterviewAnswerSubmission::getSubmissionId, submissionId)
                .eq(InterviewAnswerSubmission::getAttemptToken, attemptToken)
                .setSql(leaseResetSql()));
    }

    // 租约重置片段：两列统一使用数据库当前时间，deadline 为 NOW + D。
    private String leaseResetSql() {
        return "heartbeat_at = NOW(3), deadline_at = TIMESTAMPADD(MICROSECOND, "
                + TimeUnit.MILLISECONDS.toMicros(resolveDeadlineMillis()) + ", NOW(3))";
    }

    private long resolveLeaseMillis() {
        Long seconds = configuration.getHeartbeatLeaseSeconds();
        return TimeUnit.SECONDS.toMillis(seconds == null || seconds <= 0 ? 60L : seconds);
    }

    private long resolveDeadlineMillis() {
        Long seconds = configuration.getAttemptDeadlineSeconds();
        return TimeUnit.SECONDS.toMillis(seconds == null || seconds <= 0 ? 180L : seconds);
    }

    private long resolveLegacyTimeoutMillis() {
        Long seconds = configuration.getLegacyProcessingTimeoutSeconds();
        return TimeUnit.SECONDS.toMillis(seconds == null || seconds <= 0 ? 300L : seconds);
    }

    private long resolveLegacyTimeoutSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(resolveLegacyTimeoutMillis());
    }
}
