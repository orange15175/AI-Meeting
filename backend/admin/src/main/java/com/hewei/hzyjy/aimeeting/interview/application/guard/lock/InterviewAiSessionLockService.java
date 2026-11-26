package com.hewei.hzyjy.aimeeting.interview.application.guard.lock;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.config.InterviewAnswerGuardConfiguration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 重型 AI 操作（上传解析、出题）的会话级锁，与答题锁分开按键，避免互相阻塞。
 */
@Service
@RequiredArgsConstructor
public class InterviewAiSessionLockService {

    private static final String LOCK_KEY_PREFIX = "interview:ai:heavy:lock:";

    private final RedissonClient redissonClient;
    private final InterviewAnswerGuardConfiguration configuration;

    // 出题重操作锁按 stage + sessionId 分键，使用固定租期；与答题锁不是同一把锁，不能代替数据库版本校验。
    public RLock acquire(String sessionId, String stage) throws InterruptedException {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(stage)) {
            return null;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + stage + ":" + sessionId);
        // 等待时间与租期同为毫秒，避免把毫秒值按秒解释（曾出现 lock-wait=500 变成等待 500 秒）。
        boolean acquired = lock.tryLock(
                resolveWaitMillis(),
                resolveExpireMillis(),
                TimeUnit.MILLISECONDS
        );
        return acquired ? lock : null;
    }

    public void release(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private long resolveExpireMillis() {
        Long configured = configuration.getHeavyLockExpireSeconds();
        long seconds = configured == null || configured <= 0 ? 180L : configured;
        return TimeUnit.SECONDS.toMillis(seconds);
    }

    private long resolveWaitMillis() {
        Long configured = configuration.getLockWaitMillis();
        return configured == null || configured < 0 ? 0L : configured;
    }
}
