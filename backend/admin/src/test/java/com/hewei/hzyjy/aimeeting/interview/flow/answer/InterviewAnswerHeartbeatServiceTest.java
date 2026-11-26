package com.hewei.hzyjy.aimeeting.interview.flow.answer;

import com.hewei.hzyjy.aimeeting.interview.config.InterviewAnswerGuardConfiguration;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewAnswerHeartbeatServiceTest {

    @Mock
    private ScheduledExecutorService scheduler;

    @Mock
    private InterviewSubmissionStore submissionStore;

    private InterviewAnswerHeartbeatService heartbeatService;

    @BeforeEach
    void setUp() {
        InterviewAnswerGuardConfiguration configuration = new InterviewAnswerGuardConfiguration();
        configuration.setHeartbeatIntervalSeconds(20L);
        heartbeatService = new InterviewAnswerHeartbeatService(scheduler, submissionStore, configuration);
    }

    private ScheduledFuture<?> stubScheduler() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        // 用 doReturn 避免通配符返回值的泛型匹配问题。
        doReturn(future).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        return future;
    }

    private Runnable captureTask() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(captor.capture(), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
        return captor.getValue();
    }

    @Test
    @DisplayName("心跳成功：保持存活标志，任务不被取消")
    void renewKeepsAlive() {
        ScheduledFuture<?> future = stubScheduler();
        InterviewAnswerHeartbeatService.Heartbeat heartbeat = heartbeatService.start("sub-1", "token-1");
        Runnable task = captureTask();

        when(submissionStore.heartbeat("sub-1", "token-1")).thenReturn(true);
        task.run();

        assertTrue(heartbeat.alive());
        verify(future, times(0)).cancel(false);
    }

    @Test
    @DisplayName("心跳更新 0 行：判定失权并主动停止任务")
    void zeroRowMarksLostAndCancels() {
        ScheduledFuture<?> future = stubScheduler();
        InterviewAnswerHeartbeatService.Heartbeat heartbeat = heartbeatService.start("sub-1", "token-1");
        Runnable task = captureTask();

        when(submissionStore.heartbeat("sub-1", "token-1")).thenReturn(false);
        task.run();

        assertFalse(heartbeat.alive());
        verify(future).cancel(false);
    }

    @Test
    @DisplayName("心跳 SQL 异常：不算失权，留给下一次心跳与提交条件兜底")
    void exceptionKeepsAlive() {
        stubScheduler();
        InterviewAnswerHeartbeatService.Heartbeat heartbeat = heartbeatService.start("sub-1", "token-1");
        Runnable task = captureTask();

        when(submissionStore.heartbeat("sub-1", "token-1")).thenThrow(new RuntimeException("db down"));
        task.run();

        assertTrue(heartbeat.alive());
    }

    @Test
    @DisplayName("关闭幂等：重复 close 只取消一次")
    void closeIsIdempotent() {
        ScheduledFuture<?> future = stubScheduler();
        InterviewAnswerHeartbeatService.Heartbeat heartbeat = heartbeatService.start("sub-1", "token-1");

        heartbeat.close();
        heartbeat.close();

        verify(future, times(1)).cancel(false);
    }

    @Test
    @DisplayName("任务被调度器拒绝：降级为无心跳，不抛异常、不影响执行权判断")
    void rejectedBySchedulerDegrades() {
        doThrow(new RejectedExecutionException("shutdown")).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        InterviewAnswerHeartbeatService.Heartbeat heartbeat = heartbeatService.start("sub-1", "token-1");

        assertTrue(heartbeat.alive());
        heartbeat.close();
    }
}
