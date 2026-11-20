package com.hewei.hzyjy.aimeeting.interview.service.impl;

import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 准备阶段状态流转：已开始答题的会话不能被重新准备。
 */
class InterviewSessionServiceImplTest {

    private InterviewSession sessionOf(String status) {
        InterviewSession session = new InterviewSession();
        session.setSessionId("interview-1");
        session.setStatus(status);
        return session;
    }

    @Test
    void shouldRejectPreparationWhenInterviewAlreadyStarted() {
        InterviewSessionStore store = mock(InterviewSessionStore.class);
        InterviewSessionServiceImpl service = new InterviewSessionServiceImpl(store);
        when(store.requireOwned("interview-1", 1L))
                .thenReturn(sessionOf(InterviewSessionStatus.IN_PROGRESS.name()));

        ClientException ex = assertThrows(ClientException.class,
                () -> service.markResumeUploading("interview-1", 1L));

        assertEquals(InterviewErrorCodeEnum.INTERVIEW_ALREADY_STARTED.code(), ex.getErrorCode());
        verify(store, never()).updateStatus(anyString(), any(), any());
    }

    @Test
    void shouldAllowPreparationBeforeInterviewStarts() {
        InterviewSessionStore store = mock(InterviewSessionStore.class);
        InterviewSessionServiceImpl service = new InterviewSessionServiceImpl(store);
        when(store.requireOwned("interview-1", 1L))
                .thenReturn(sessionOf(InterviewSessionStatus.READY.name()));
        when(store.updateStatus("interview-1", InterviewSessionStatus.READY,
                InterviewSessionStatus.RESUME_UPLOADING)).thenReturn(true);

        service.markResumeUploading("interview-1", 1L);

        verify(store).updateStatus("interview-1", InterviewSessionStatus.READY,
                InterviewSessionStatus.RESUME_UPLOADING);
    }
}
