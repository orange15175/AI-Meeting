package com.hewei.hzyjy.aimeeting.interview.flow.report;

import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.interview.application.InterviewSessionOwnershipService;
import com.hewei.hzyjy.aimeeting.interview.application.finalize.InterviewFinalizeLockService;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewRecordDO;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewRecordMapper;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRadarService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewSessionService;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewJobContextReader;
import com.hewei.hzyjy.aimeeting.interview.testing.PressureTestReportUtil;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewRecordFinalizeConcurrencyTest {

    @Test
    void shouldAllowOnlySingleFinalizeWriterUnderConcurrentRequests() throws Exception {
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

        InterviewSession session = new InterviewSession();
        session.setSessionId("session-finalize-1");
        session.setUserId(3003L);
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        session.setInterviewType("backend");
        session.setResumeFileUrl("https://mock/resume.pdf");
        session.setStartTime(new Date(System.currentTimeMillis() - 60_000));
        session.setCreateTime(new Date(System.currentTimeMillis() - 120_000));
        when(ownershipService.requireOwnedSession("session-finalize-1", 3003L)).thenReturn(session);

        when(materialStore.findBySession("session-finalize-1")).thenReturn(null);
        when(submissionStore.averageMainScore("session-finalize-1")).thenReturn(null);
        when(questionStore.countAll("session-finalize-1")).thenReturn(0);
        when(questionStore.listBySession("session-finalize-1")).thenReturn(List.of());
        when(submissionStore.listByQuestionOrder("session-finalize-1")).thenReturn(List.of());

        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(InterviewRecordDO.class))).thenReturn(1);

        AtomicBoolean winnerChosen = new AtomicBoolean(false);
        RLock lock = mock(RLock.class);
        when(finalizeLockService.acquire("session-finalize-1"))
                .thenAnswer(invocation -> winnerChosen.compareAndSet(false, true) ? lock : null);

        int concurrency = 24;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TaskResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                long begin = System.nanoTime();
                try {
                    service.saveInterviewRecordFromRedis("session-finalize-1", 3003L);
                    return new TaskResult(true, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
                } catch (ClientException ex) {
                    return new TaskResult(false, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
                }
            }));
        }

        start.countDown();

        int successCount = 0;
        int rejectedCount = 0;
        List<Long> latencies = new ArrayList<>();
        for (Future<TaskResult> future : futures) {
            TaskResult result = future.get(8, TimeUnit.SECONDS);
            latencies.add(result.latencyMs());
            if (result.success()) {
                successCount++;
            } else {
                rejectedCount++;
            }
        }
        pool.shutdownNow();
        PressureTestReportUtil.printSummary(
                "record.finalize.concurrent-single-writer",
                concurrency,
                successCount,
                rejectedCount,
                latencies,
                Map.of("sessionId", "session-finalize-1")
        );

        assertEquals(1, successCount);
        assertEquals(concurrency - 1, rejectedCount);
        assertTrue(winnerChosen.get());
        verify(mapper, times(1)).insert(any(InterviewRecordDO.class));
        verify(finalizeLockService, times(1)).release(lock);
        verify(sessionService, times(0)).finishSession("session-finalize-1", 3003L);
    }

    private record TaskResult(boolean success, long latencyMs) {
    }
}
