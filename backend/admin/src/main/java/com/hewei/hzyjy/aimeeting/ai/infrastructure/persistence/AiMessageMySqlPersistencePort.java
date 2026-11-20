package com.hewei.hzyjy.aimeeting.ai.infrastructure.persistence;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiMessage;
import com.hewei.hzyjy.aimeeting.ai.dao.mapper.AiMessageMapper;
import com.hewei.hzyjy.aimeeting.conversation.application.port.AiMessagePersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AiMessageMySqlPersistencePort implements AiMessagePersistencePort {

    private final AiMessageMapper aiMessageMapper;

    @Override
    public List<AiMessage> findHistoryBySessionId(String sessionId) {
        return aiMessageMapper.selectList(Wrappers.lambdaQuery(AiMessage.class)
                .eq(AiMessage::getSessionId, sessionId)
                .eq(AiMessage::getDelFlag, 0)
                .orderByAsc(AiMessage::getMessageSeq));
    }

    @Override
    public IPage<AiMessage> pageHistoryBySessionId(String sessionId, long current, long size) {
        Page<AiMessage> page = new Page<>(current, size);
        return aiMessageMapper.selectPage(page, Wrappers.lambdaQuery(AiMessage.class)
                .eq(AiMessage::getSessionId, sessionId)
                .eq(AiMessage::getDelFlag, 0)
                .orderByAsc(AiMessage::getCreateTime));
    }

    @Override
    public IPage<AiMessage> pageHistoryBySessionIds(List<String> sessionIds, long current, long size) {
        Page<AiMessage> page = new Page<>(current, size);
        return aiMessageMapper.selectPage(page, Wrappers.lambdaQuery(AiMessage.class)
                .in(AiMessage::getSessionId, sessionIds)
                .eq(AiMessage::getDelFlag, 0)
                .orderByDesc(AiMessage::getCreateTime));
    }

    @Override
    public IPage<AiMessage> pageAllHistory(long current, long size) {
        Page<AiMessage> page = new Page<>(current, size);
        return aiMessageMapper.selectPage(page, Wrappers.lambdaQuery(AiMessage.class)
                .eq(AiMessage::getDelFlag, 0)
                .orderByDesc(AiMessage::getCreateTime));
    }

    @Override
    public void save(AiMessage message) {
        if (message.getStatus() == null) {
            message.setStatus("SUCCEEDED");
        }
        aiMessageMapper.insert(message);
    }
}
