package com.hewei.hzyjy.aimeeting.ai.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hewei.hzyjy.aimeeting.ai.api.io.req.AiConversationPageReqDTO;
import com.hewei.hzyjy.aimeeting.ai.api.io.resp.AiConversationRespDTO;
import com.hewei.hzyjy.aimeeting.ai.api.io.resp.AiSessionCreateRespDTO;
import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiConversation;
import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiPropertiesDO;
import com.hewei.hzyjy.aimeeting.ai.dao.mapper.AiConversationMapper;
import com.hewei.hzyjy.aimeeting.ai.service.AiConversationService;
import com.hewei.hzyjy.aimeeting.ai.service.AiPropertiesService;
import com.hewei.hzyjy.aimeeting.auth.application.CurrentUserService;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiConversationServiceImpl implements AiConversationService {

    private final AiConversationMapper aiConversationMapper;
    private final AiPropertiesService aiPropertiesService;
    private final CurrentUserService currentUserService;

    @Override
    public String createConversation(String username, Long aiId, String firstMessage) {
        AiPropertiesDO aiProperties = aiPropertiesService.getById(aiId);
        if (aiProperties == null || aiProperties.getDelFlag() == 1 || aiProperties.getIsEnabled() == 0) {
            throw new ClientException("AI config does not exist or is disabled");
        }

        String sessionId = IdUtil.getSnowflakeNextIdStr();
        String title = generateTitle(firstMessage);

        AiConversation conversation = new AiConversation();
        conversation.setSessionId(sessionId);
        conversation.setUsername(username);
        conversation.setUserId(currentUserService.getUserIdByUsername(username));
        conversation.setAiId(aiId);
        conversation.setTitle(title);
        conversation.setStatus(1);
        conversation.setMessageCount(0);
        conversation.setVersion(0);
        conversation.setLastMessageTime(new Date());
        conversation.setCreateTime(new Date());
        conversation.setUpdateTime(new Date());
        conversation.setDelFlag(0);

        aiConversationMapper.insert(conversation);
        return sessionId;
    }

    @Override
    public AiSessionCreateRespDTO createConversationWithTitle(String username, Long aiId, String firstMessage) {
        String sessionId = createConversation(username, aiId, firstMessage);
        AiSessionCreateRespDTO respDTO = new AiSessionCreateRespDTO();
        respDTO.setSessionId(sessionId);
        respDTO.setConversationTitle(generateTitle(firstMessage));
        return respDTO;
    }

    @Override
    public IPage<AiConversationRespDTO> pageConversations(String username, AiConversationPageReqDTO requestParam) {
        Page<AiConversation> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<AiConversation> conversationPage;
        if (requestParam.getAiId() != null) {
            conversationPage = aiConversationMapper.selectPage(page, Wrappers.lambdaQuery(AiConversation.class)
                    .eq(AiConversation::getUsername, username)
                    .eq(AiConversation::getAiId, requestParam.getAiId())
                    .eq(AiConversation::getDelFlag, 0)
                    .orderByDesc(AiConversation::getCreateTime));
        } else {
            conversationPage = aiConversationMapper.selectPage(page, Wrappers.lambdaQuery(AiConversation.class)
                    .eq(AiConversation::getUsername, username)
                    .eq(AiConversation::getDelFlag, 0)
                    .orderByDesc(AiConversation::getCreateTime));
        }

        Page<AiConversationRespDTO> resultPage = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        resultPage.setTotal(conversationPage.getTotal());

        List<AiConversationRespDTO> records = conversationPage.getRecords().stream()
                .map(conversation -> {
                    AiConversationRespDTO respDTO = new AiConversationRespDTO();
                    BeanUtil.copyProperties(conversation, respDTO);
                    AiPropertiesDO aiProperties = aiPropertiesService.getById(conversation.getAiId());
                    if (aiProperties != null) {
                        respDTO.setAiName(aiProperties.getAiName());
                    }
                    return respDTO;
                })
                .collect(Collectors.toList());

        resultPage.setRecords(records);
        return resultPage;
    }

    @Override
    public void updateConversation(String sessionId, Integer messageSeq, String title) {
        AiConversation conversation = findActive(sessionId);
        if (conversation == null) {
            return;
        }
        if (messageSeq != null) {
            conversation.setMessageCount(messageSeq);
        }
        if (StrUtil.isNotBlank(title)) {
            conversation.setTitle(title);
        }
        conversation.setLastMessageTime(new Date());
        conversation.setUpdateTime(new Date());
        aiConversationMapper.updateById(conversation);
    }

    @Override
    public void updateConversation(String sessionId, Integer messageSeq, String title, String username) {
        requireOwnedConversation(sessionId, username);
        updateConversation(sessionId, messageSeq, title);
    }

    @Override
    public void endConversation(String sessionId) {
        AiConversation conversation = findActive(sessionId);
        if (conversation == null) {
            throw new ClientException("Conversation does not exist");
        }
        conversation.setStatus(2);
        conversation.setUpdateTime(new Date());
        aiConversationMapper.updateById(conversation);
    }

    @Override
    public void endConversation(String sessionId, String username) {
        requireOwnedConversation(sessionId, username);
        endConversation(sessionId);
    }

    @Override
    public void deleteConversation(String sessionId) {
        AiConversation conversation = findActive(sessionId);
        if (conversation == null) {
            throw new ClientException("Conversation does not exist");
        }
        conversation.setDelFlag(1);
        conversation.setUpdateTime(new Date());
        aiConversationMapper.updateById(conversation);
    }

    @Override
    public void deleteConversation(String sessionId, String username) {
        requireOwnedConversation(sessionId, username);
        deleteConversation(sessionId);
    }

    @Override
    public AiConversationRespDTO getConversationBySessionId(String sessionId) {
        AiConversation conversation = findActive(sessionId);
        if (conversation == null) {
            throw new ClientException("Conversation does not exist");
        }
        AiConversationRespDTO respDTO = new AiConversationRespDTO();
        BeanUtil.copyProperties(conversation, respDTO);
        AiPropertiesDO aiProperties = aiPropertiesService.getById(conversation.getAiId());
        if (aiProperties != null) {
            respDTO.setAiName(aiProperties.getAiName());
        }
        return respDTO;
    }

    @Override
    public AiConversationRespDTO getConversationBySessionId(String sessionId, String username) {
        requireOwnedConversation(sessionId, username);
        return getConversationBySessionId(sessionId);
    }

    @Override
    public void requireOwnedConversation(String sessionId, String username) {
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException("sessionId cannot be empty");
        }
        if (StrUtil.isBlank(username)) {
            throw new ClientException("username cannot be empty");
        }
        AiConversation conversation = findActive(sessionId);
        if (conversation == null) {
            throw new ClientException("Conversation does not exist");
        }
        if (!username.equals(conversation.getUsername())) {
            throw new ClientException("No permission to access this conversation");
        }
    }

    @Override
    public List<String> listOwnedSessionIds(String username) {
        if (StrUtil.isBlank(username)) {
            throw new ClientException("username cannot be empty");
        }
        return aiConversationMapper.selectList(Wrappers.lambdaQuery(AiConversation.class)
                        .eq(AiConversation::getUsername, username)
                        .eq(AiConversation::getDelFlag, 0)
                        .orderByDesc(AiConversation::getCreateTime))
                .stream()
                .map(AiConversation::getSessionId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    private AiConversation findActive(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return aiConversationMapper.selectOne(Wrappers.lambdaQuery(AiConversation.class)
                .eq(AiConversation::getSessionId, sessionId)
                .eq(AiConversation::getDelFlag, 0));
    }

    private String generateTitle(String firstMessage) {
        if (StrUtil.isBlank(firstMessage)) {
            return "New Conversation";
        }
        if (firstMessage.length() <= 20) {
            return firstMessage;
        }
        return firstMessage.substring(0, 20) + "...";
    }
}
