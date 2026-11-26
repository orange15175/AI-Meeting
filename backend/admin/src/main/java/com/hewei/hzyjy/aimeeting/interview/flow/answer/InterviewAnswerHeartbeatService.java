package com.hewei.hzyjy.aimeeting.interview.flow.answer;

import com.hewei.hzyjy.aimeeting.interview.config.InterviewAnswerGuardConfiguration;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 答题执行权心跳。
 *
 * <p>执行期间每隔 {@code heartbeat-interval-seconds} 用一条单行 UPDATE 刷新提交行的
 * {@code heartbeat_at}，证明"这次尝试的执行者还活着"。它不刷新业务进度，也不代表业务线程一定在跑
 * （线程卡死时调度线程仍可能继续续期），因此另用 {@code deadline_at} 作为单次尝试的硬上限。</p>
 *
 * <p>心跳跑在独立的调度线程上：业务线程此时正阻塞在模型 HTTP 调用里，无法自己报活。
 * 心跳失败只降级、不传播——最坏情况是退回"没有心跳"的固定超时行为，正确性始终由
 * 提交时的令牌条件更新保证。</p>
 */
@Slf4j
@Component
public class InterviewAnswerHeartbeatService {

    private final ScheduledExecutorService scheduler;
    private final InterviewSubmissionStore submissionStore;
    private final InterviewAnswerGuardConfiguration configuration;

    public InterviewAnswerHeartbeatService(
            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler,
            InterviewSubmissionStore submissionStore,
            InterviewAnswerGuardConfiguration configuration) {
        this.scheduler = scheduler;
        this.submissionStore = submissionStore;
        this.configuration = configuration;
    }

    /**
     * 为一次已取得执行权的尝试启动心跳。
     *
     * <p>必须在 claim 事务提交之后调用：claim 事务内插入的行对其他连接不可见，
     * 心跳会更新 0 行并误判自己已失去执行权。</p>
     */
    public Heartbeat start(String submissionId, String attemptToken) {
        Heartbeat heartbeat = new Heartbeat();
        long intervalSeconds = resolveIntervalSeconds();
        try {
            // 固定延迟而非固定频率：上一次心跳结束后再等一个间隔，数据库变慢时不会堆积任务。
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                    () -> renew(submissionId, attemptToken, heartbeat),
                    intervalSeconds,
                    intervalSeconds,
                    TimeUnit.SECONDS);
            heartbeat.attach(future);
            Metrics.counter("answer_heartbeat_started_total").increment();
        } catch (RejectedExecutionException rejected) {
            // 调度器已关闭或队列已满：降级为"没有心跳"，行为退回固定超时，不影响数据正确性。
            Metrics.counter("answer_heartbeat_start_failed_total").increment();
            log.warn("Heartbeat task rejected by scheduler, submissionId={}", submissionId, rejected);
        }
        return heartbeat;
    }

    private void renew(String submissionId, String attemptToken, Heartbeat heartbeat) {
        if (heartbeat.isClosed()) {
            return;
        }
        try {
            if (submissionStore.heartbeat(submissionId, attemptToken)) {
                Metrics.counter("answer_heartbeat_total").increment();
                return;
            }
            // 更新 0 行：令牌已被接管、状态已变或租约/截止条件已不满足，本次尝试失去执行权。
            heartbeat.markLost();
            Metrics.counter("answer_heartbeat_lost_total").increment();
            log.warn("Heartbeat detected lost execution right, submissionId={}", submissionId);
            heartbeat.cancel();
        } catch (Throwable throwable) {
            // 必须吞掉 Throwable：定时任务抛出未捕获异常后会被永久取消，整个心跳会静默停摆。
            // 单次异常不判定失权——那会让一次数据库抖动废掉正在进行的一次答题。
            Metrics.counter("answer_heartbeat_error_total").increment();
            log.warn("Heartbeat renew failed, submissionId={}", submissionId, throwable);
        }
    }

    private long resolveIntervalSeconds() {
        Long configured = configuration.getHeartbeatIntervalSeconds();
        return configured == null || configured <= 0 ? 20L : configured;
    }

    /**
     * 一次心跳的句柄：关闭即停止；{@link #alive()} 表示本地是否仍认为自己持有执行权。
     *
     * <p>本地标志是"可能滞后、但不会误报死亡"的：只有真的更新到 0 行才会置为 false，
     * 因此它只用于在模型调用之间提前止损，不参与正确性判定。</p>
     */
    public static final class Heartbeat implements AutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> future;

        void attach(ScheduledFuture<?> scheduledFuture) {
            this.future = scheduledFuture;
        }

        boolean isClosed() {
            return closed.get();
        }

        void markLost() {
            alive.set(false);
        }

        /**
         * 截至上一次心跳，本次尝试是否仍持有执行权。
         */
        public boolean alive() {
            return alive.get();
        }

        void cancel() {
            ScheduledFuture<?> current = future;
            if (current != null) {
                current.cancel(false);
            }
        }

        /**
         * 幂等关闭：正常路径关闭一次，finally 兜底再关闭一次，第二次静默返回。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                cancel();
            }
        }
    }
}
