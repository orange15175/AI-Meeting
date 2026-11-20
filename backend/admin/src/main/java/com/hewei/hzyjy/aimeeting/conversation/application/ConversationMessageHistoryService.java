package com.hewei.hzyjy.aimeeting.conversation.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hewei.hzyjy.aimeeting.ai.api.io.BaseMessageHistoryRespDTO;
import com.hewei.hzyjy.aimeeting.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.hewei.hzyjy.aimeeting.conversation.application.port.AiMessagePersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ConversationMessageHistoryService {

    private final AiMessagePersistencePort aiMessagePersistencePort;

    public List<AiMessageHistoryRespDTO> listAiHistory(String sessionId) {
        return mapList(aiMessagePersistencePort.findHistoryBySessionId(sessionId), AiMessageHistoryRespDTO::new);
    }

    public IPage<AiMessageHistoryRespDTO> pageAiHistory(String sessionId, Integer current, Integer size) {
        return mapPage(aiMessagePersistencePort.pageHistoryBySessionId(sessionId, current, size), current, size,
                AiMessageHistoryRespDTO::new);
    }

    public IPage<AiMessageHistoryRespDTO> pageAiHistory(List<String> sessionIds, Integer current, Integer size) {
        return mapPage(aiMessagePersistencePort.pageHistoryBySessionIds(sessionIds, current, size), current, size,
                AiMessageHistoryRespDTO::new);
    }

    public IPage<AiMessageHistoryRespDTO> pageAllAiHistory(Integer current, Integer size) {
        return mapPage(aiMessagePersistencePort.pageAllHistory(current, size), current, size,
                AiMessageHistoryRespDTO::new);
    }

    private <S, T extends BaseMessageHistoryRespDTO> List<T> mapList(List<S> source, Supplier<T> targetSupplier) {
        List<T> result = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (S item : source) {
            T target = targetSupplier.get();
            BeanUtils.copyProperties(item, target);
            result.add(target);
        }
        return result;
    }

    private <S, T extends BaseMessageHistoryRespDTO> IPage<T> mapPage(
            IPage<S> source,
            Integer current,
            Integer size,
            Supplier<T> targetSupplier) {
        Page<T> target = new Page<>(current, size);
        target.setTotal(source.getTotal());
        target.setRecords(mapList(source.getRecords(), targetSupplier));
        return target;
    }
}
