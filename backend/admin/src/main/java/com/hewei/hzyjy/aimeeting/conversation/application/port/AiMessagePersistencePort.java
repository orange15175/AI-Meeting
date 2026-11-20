package com.hewei.hzyjy.aimeeting.conversation.application.port;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiMessage;

import java.util.List;

public interface AiMessagePersistencePort {

    List<AiMessage> findHistoryBySessionId(String sessionId);

    IPage<AiMessage> pageHistoryBySessionId(String sessionId, long current, long size);

    IPage<AiMessage> pageHistoryBySessionIds(List<String> sessionIds, long current, long size);

    IPage<AiMessage> pageAllHistory(long current, long size);

    void save(AiMessage message);
}
