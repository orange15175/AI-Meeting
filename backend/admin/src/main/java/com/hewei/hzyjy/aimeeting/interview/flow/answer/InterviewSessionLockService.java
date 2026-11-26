package com.hewei.hzyjy.aimeeting.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.config.InterviewAnswerGuardConfiguration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 会话级互斥锁：同一会话同一时刻只接受一项改变流程的操作。
 *
 * <p>锁只负责"串行化入口"，不承担幂等与状态判定——那两件事由 MySQL 的
 * 唯一键、租约（heartbeat_at / deadline_at）与 attemptToken 完成。</p>
 *
 * <p><b>刻意不使用看门狗续期。</b>锁的保护窗口是有界的（一次答题最多两次模型调用），
 * 而无限续期会让卡死的线程永久占住临界区，接管请求连 claim 都到不了。
 * 锁过期不安全吗？不——锁过期后新请求仍要过 claim 的租约条件，能不能接管由数据库决定。</p>
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionLockService {

    private static final String LOCK_KEY_PREFIX = "interview:session:lock:";

    private final RedissonClient redissonClient;
    private final InterviewAnswerGuardConfiguration configuration;

    // 答题锁按 sessionId 分键；固定租期内不续期，到点自动释放。抢不到锁返回 null，由流水线返回忙。
    public RLock acquire(String sessionId) throws InterruptedException {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + sessionId);
        boolean acquired;
        if (useWatchdogLock()) {
            acquired = lock.tryLock(resolveWaitMillis(), -1L, TimeUnit.MILLISECONDS);
        } else {
            acquired = lock.tryLock(resolveWaitMillis(), resolveExpireMillis(), TimeUnit.MILLISECONDS);
        }
        return acquired ? lock : null;
    }

    public void release(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private long resolveExpireMillis() {
        Long configured = configuration.getLockExpireSeconds();
        long seconds = configured == null || configured <= 0 ? 120L : configured;
        return TimeUnit.SECONDS.toMillis(seconds);
    }

    private long resolveWaitMillis() {
        Long configured = configuration.getLockWaitMillis();
        return configured == null || configured < 0 ? 0L : configured;
    }

    private boolean useWatchdogLock() {
        return Boolean.TRUE.equals(configuration.getLockWatchdogEnabled())
                && (configuration.getLockExpireSeconds() == null || configuration.getLockExpireSeconds() <= 0);
    }
}
