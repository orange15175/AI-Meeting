package com.hewei.hzyjy.aimeeting.conversation.application;

import com.hewei.hzyjy.aimeeting.common.biz.message.MessageSequenceAllocator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageSequenceService {

    private final MessageSequenceAllocator messageSequenceAllocator;

    public int nextAiMessageSeq(String sessionId) {
        return messageSequenceAllocator.nextAiMessageSeq(sessionId);
    }
}
