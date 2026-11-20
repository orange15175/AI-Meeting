package com.hewei.hzyjy.aimeeting.ai.service.chat;

import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiPropertiesDO;
import com.hewei.hzyjy.aimeeting.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.hewei.hzyjy.aimeeting.toolkit.llm.AIContentAccumulator;
import reactor.core.publisher.FluxSink;

import java.util.List;

public interface AiChatHandler {
    String getType();

    void streamToSink(AiPropertiesDO aiProperties, String userMessage, List<AiMessageHistoryRespDTO> historyMessages,
                      FluxSink<String> sink, AIContentAccumulator accumulator) throws Exception;
}

