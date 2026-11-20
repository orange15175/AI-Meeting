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
 * 唯一键、版本号与 attemptToken 完成。锁默认走 Redisson 看门狗续期。</p>
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionLockService {

    private static final String LOCK_KEY_PREFIX = "interview:session:lock:";

    private final RedissonClient redissonClient;
    private final InterviewAnswerGuardConfiguration configuration;

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
