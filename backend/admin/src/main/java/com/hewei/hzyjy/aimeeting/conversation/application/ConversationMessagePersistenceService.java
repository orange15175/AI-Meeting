package com.hewei.hzyjy.aimeeting.conversation.application;

import cn.hutool.core.util.IdUtil;
import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiMessage;
import com.hewei.hzyjy.aimeeting.conversation.application.port.AiMessagePersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class ConversationMessagePersistenceService {

    private final MessageSequenceService messageSequenceService;
    private final AiMessagePersistencePort aiMessagePersistencePort;

    public int saveAiUserMessage(String sessionId, String content) {
        int messageSeq = messageSequenceService.nextAiMessageSeq(sessionId);
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRequestId(IdUtil.fastSimpleUUID());
        message.setMessageType(1);
        message.setMessageContent(content);
        message.setMessageSeq(messageSeq);
        message.setStatus("SUCCEEDED");
        message.setCreateTime(new Date());
        message.setDelFlag(0);
        aiMessagePersistencePort.save(message);
        return messageSeq;
    }

    public int saveAiAssistantMessage(
            String sessionId,
            String content,
            String reasoningContent,
            int responseTime,
            String errorMessage,
            String modelName) {
        int messageSeq = messageSequenceService.nextAiMessageSeq(sessionId);
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRequestId(IdUtil.fastSimpleUUID());
        message.setMessageType(2);
        message.setMessageContent(content);
        message.setReasoningContent(reasoningContent);
        message.setMessageSeq(messageSeq);
        message.setStatus("SUCCEEDED");
        message.setResponseTime(responseTime);
        message.setErrorMessage(errorMessage);
        message.setModelName(modelName);
        message.setCreateTime(new Date());
        message.setDelFlag(0);
        aiMessagePersistencePort.save(message);
        return messageSeq;
    }
}
