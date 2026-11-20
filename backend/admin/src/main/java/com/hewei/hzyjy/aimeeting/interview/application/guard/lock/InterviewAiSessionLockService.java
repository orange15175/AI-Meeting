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

    public RLock acquire(String sessionId, String stage) throws InterruptedException {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(stage)) {
            return null;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + stage + ":" + sessionId);
        boolean acquired = lock.tryLock(
                resolveWaitMillis(),
                resolveExpireSeconds(),
                TimeUnit.SECONDS
        );
        return acquired ? lock : null;
    }

    public void release(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private long resolveExpireSeconds() {
        Long configured = configuration.getLockExpireSeconds();
        if (configured == null || configured <= 0) {
            return 180L;
        }
        return configured;
    }

    private long resolveWaitMillis() {
        Long configured = configuration.getLockWaitMillis();
        return configured == null || configured < 0 ? 0L : configured;
    }
}
