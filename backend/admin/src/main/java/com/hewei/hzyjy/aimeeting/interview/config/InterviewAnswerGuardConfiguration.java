package com.hewei.hzyjy.aimeeting.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Guard configuration for interview answer concurrency and idempotency.
 *
 * <p>锁租期与答题执行权租约是两件事：锁只串行化入口，最长占用
 * {@code lockExpireSeconds}；执行权租约由提交行的 {@code heartbeat_at} /
 * {@code deadline_at} 表达，决定"什么时候允许别人接管这一行"。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-meeting.interview.answer-guard")
public class InterviewAnswerGuardConfiguration {

    /**
     * Lock lease time in seconds for session + question lock.
     * <=0 means using Redisson watchdog mode.
     *
     * <p>答题锁不应使用看门狗：它的保护窗口是有界的（两次模型调用），
     * 无限续期会让线程卡死的任务永久占住临界区。</p>
     */
    private Long lockExpireSeconds = 120L;

    /**
     * Use Redisson watchdog mode when lockExpireSeconds <= 0.
     */
    private Boolean lockWatchdogEnabled = false;

    /**
     * 重型 AI 锁（上传解析、出题）的固定租期（秒）。
     *
     * <p>出题阶段 LLM 超时配置为 180 秒，租期必须覆盖它，因此与答题锁分开配置。</p>
     */
    private Long heavyLockExpireSeconds = 180L;

    /**
     * Lock wait time in milliseconds.
     */
    private Long lockWaitMillis = 0L;

    /**
     * 心跳间隔（秒）：持有执行权期间，每隔该时长刷新一次提交行的 heartbeat_at。
     */
    private Long heartbeatIntervalSeconds = 20L;

    /**
     * 心跳租约（秒）：最近一次成功心跳距数据库当前时间达到该值，视为执行者失联，允许他人接管。
     *
     * <p>取心跳间隔的 3 倍，容忍连续两次心跳丢失（数据库抖动、GC 停顿）。</p>
     */
    private Long heartbeatLeaseSeconds = 60L;

    /**
     * 单次尝试截止（秒）：一次 attemptToken 从登记起的固定执行上限，不因心跳延长。
     * 必须覆盖"模型超时 + 重试 + 线程池排队"的最坏耗时。
     */
    private Long attemptDeadlineSeconds = 180L;

    /**
     * 历史记录兼容阈值（秒）：两列都为 NULL 的旧提交行（迁移前产生）仍按 update_time 判断过期。
     */
    private Long legacyProcessingTimeoutSeconds = 300L;
}
