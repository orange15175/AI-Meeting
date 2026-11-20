package com.hewei.hzyjy.aimeeting.interview.application;

import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会话归属校验：统一收敛在会话仓储，业务侧不再各自查库。
 */
@Component
@RequiredArgsConstructor
public class InterviewSessionOwnershipService {

    private final InterviewSessionStore sessionStore;

    public InterviewSession requireOwnedSession(String sessionId, Long userId) {
        return sessionStore.requireOwned(sessionId, userId);
    }
}
