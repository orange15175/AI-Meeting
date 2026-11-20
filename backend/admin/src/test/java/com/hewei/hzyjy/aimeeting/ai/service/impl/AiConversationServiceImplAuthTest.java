package com.hewei.hzyjy.aimeeting.ai.service.impl;

import com.hewei.hzyjy.aimeeting.ai.dao.entity.AiConversation;
import com.hewei.hzyjy.aimeeting.ai.dao.mapper.AiConversationMapper;
import com.hewei.hzyjy.aimeeting.ai.service.AiPropertiesService;
import com.hewei.hzyjy.aimeeting.auth.application.CurrentUserService;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConversationServiceImplAuthTest {

    @Mock
    private AiConversationMapper aiConversationMapper;

    @Mock
    private AiPropertiesService aiPropertiesService;

    @Mock
    private CurrentUserService currentUserService;

    private AiConversationServiceImpl aiConversationService;

    @BeforeEach
    void setUp() {
        aiConversationService = new AiConversationServiceImpl(aiConversationMapper, aiPropertiesService, currentUserService);
    }

    @Test
    void requireOwnedConversation_ShouldThrow_WhenUsernameMismatch() {
        AiConversation conversation = new AiConversation();
        conversation.setId(1L);
        conversation.setSessionId("ai-session-1");
        conversation.setUsername("owner");

        when(aiConversationMapper.selectOne(any())).thenReturn(conversation);

        assertThrows(
                ClientException.class,
                () -> aiConversationService.requireOwnedConversation("ai-session-1", "intruder")
        );
    }

    @Test
    void requireOwnedConversation_ShouldPass_WhenUsernameMatches() {
        AiConversation conversation = new AiConversation();
        conversation.setId(2L);
        conversation.setSessionId("ai-session-2");
        conversation.setUsername("owner");

        when(aiConversationMapper.selectOne(any())).thenReturn(conversation);

        assertDoesNotThrow(() -> aiConversationService.requireOwnedConversation("ai-session-2", "owner"));
    }
}
