package com.hewei.hzyjy.aimeeting.interview.service.impl;

import com.hewei.hzyjy.aimeeting.interview.application.InterviewSessionOwnershipService;
import com.hewei.hzyjy.aimeeting.interview.application.finalize.InterviewFinalizeLockService;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewAnswerSubmission;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewQuestionItem;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewRecordDO;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewRecordMapper;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.flow.report.InterviewRecordServiceImpl;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRadarService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewSessionService;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewJobContextReader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewRecordServiceImplTest {

    @Test
    void shouldFinishSessionThenPersistRecordFromMysqlSources() throws Exception {
        InterviewSessionOwnershipService ownershipService = mock(InterviewSessionOwnershipService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewFinalizeLockService finalizeLockService = mock(InterviewFinalizeLockService.class);
        InterviewSubmissionStore submissionStore = mock(InterviewSubmissionStore.class);
        InterviewQuestionStore questionStore = mock(InterviewQuestionStore.class);
        InterviewMaterialStore materialStore = mock(InterviewMaterialStore.class);
        InterviewRadarService radarService = mock(InterviewRadarService.class);
        InterviewRecordMapper mapper = mock(InterviewRecordMapper.class);

        InterviewRecordServiceImpl service = new InterviewRecordServiceImpl(
                ownershipService,
                sessionService,
                finalizeLockService,
                submissionStore,
                questionStore,
                materialStore,
                radarService,
                mock(InterviewJobContextReader.class)
        );
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        RLock finalizeLock = mock(RLock.class);
        when(finalizeLockService.acquire("interview-session-1")).thenReturn(finalizeLock);

        InterviewSession readySession = session("interview-session-1", 1001L, InterviewSessionStatus.READY.name());
        InterviewSession finishedSession = session("interview-session-1", 1001L, InterviewSessionStatus.FINISHED.name());
        when(ownershipService.requireOwnedSession("interview-session-1", 1001L))
                .thenReturn(readySession, readySession, finishedSession);

        InterviewMaterial material = new InterviewMaterial();
        material.setResumeScore(86);
        material.setInterviewType("backend");
        material.setSuggestionsJson("{\"1\":\"Structured answer\"}");
        when(materialStore.findBySession("interview-session-1")).thenReturn(material);

        when(submissionStore.averageMainScore("interview-session-1")).thenReturn(92);
        when(questionStore.countAll("interview-session-1")).thenReturn(1);

        InterviewQuestionItem question = new InterviewQuestionItem();
        question.setQuestionNo("1");
        question.setKind(InterviewQuestionStore.KIND_MAIN);
        question.setContent("Describe JVM tuning");
        question.setMainIndex(1);
        question.setFollowUpIndex(0);
        when(questionStore.listBySession("interview-session-1")).thenReturn(List.of(question));

        InterviewAnswerSubmission submission = new InterviewAnswerSubmission();
        submission.setQuestionNo("1");
        submission.setScore(88);
        submission.setFeedback("Answer structure is clear and supported by a concrete project example.");
        submission.setAnswerContent("We used G1 with a 4G heap...");
        submission.setRequestId("rid-1");
        submission.setFinished(0);
        submission.setUpdateTime(new Date(System.currentTimeMillis() - 10_000));
        when(submissionStore.listByQuestionOrder("interview-session-1")).thenReturn(List.of(submission));

        InterviewRecordDO existingRecord = new InterviewRecordDO();
        existingRecord.setId(1L);
        existingRecord.setUserId(1001L);
        existingRecord.setSessionId("interview-session-1");
        existingRecord.setCreateTime(new Date(System.currentTimeMillis() - 30_000));
        when(mapper.selectOne(any())).thenReturn(null, existingRecord);
        when(mapper.insert(any(InterviewRecordDO.class))).thenReturn(1);
        when(mapper.updateById(any(InterviewRecordDO.class))).thenReturn(1);

        service.saveInterviewRecordFromRedis("interview-session-1", 1001L);

        InOrder inOrder = inOrder(ownershipService, mapper, sessionService);
        inOrder.verify(ownershipService, calls(1)).requireOwnedSession("interview-session-1", 1001L); // 外层判断是否已结束
        inOrder.verify(ownershipService, calls(1)).requireOwnedSession("interview-session-1", 1001L); // 第一次保存前校验归属
        inOrder.verify(mapper, calls(1)).selectOne(any());
        inOrder.verify(mapper).insert(any(InterviewRecordDO.class));
        inOrder.verify(sessionService).finishSession("interview-session-1", 1001L);
        inOrder.verify(ownershipService, calls(1)).requireOwnedSession("interview-session-1", 1001L); // 第二次保存前校验归属
        inOrder.verify(mapper, calls(1)).selectOne(any());
        inOrder.verify(mapper).updateById(any(InterviewRecordDO.class));

        ArgumentCaptor<InterviewRecordDO> recordCaptor = ArgumentCaptor.forClass(InterviewRecordDO.class);
        verify(mapper).updateById(recordCaptor.capture());
        InterviewRecordDO record = recordCaptor.getValue();
        assertEquals("interview-session-1", record.getSessionId());
        assertEquals(1001L, record.getUserId());
        assertEquals(InterviewSessionStatus.FINISHED.name(), record.getInterviewStatus());
        assertEquals(92, record.getInterviewScore());
        assertEquals(86, record.getResumeScore());
        assertEquals(1, record.getQuestionCount());
        assertTrue(record.getSessionSnapshotJson().contains("\"sessionStatus\":\"FINISHED\""));
        assertTrue(record.getSessionSnapshotJson().contains("\"reviewFeedback\""));
    }

    private InterviewSession session(String sessionId, Long userId, String status) {
        InterviewSession session = new InterviewSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setStatus(status);
        session.setInterviewType("backend");
        session.setResumeFileUrl("https://example.com/resume.pdf");
        session.setCreateTime(new Date(System.currentTimeMillis() - 120_000));
        session.setStartTime(new Date(System.currentTimeMillis() - 60_000));
        return session;
    }
}
