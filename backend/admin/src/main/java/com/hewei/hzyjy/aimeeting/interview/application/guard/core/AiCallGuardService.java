package com.hewei.hzyjy.aimeeting.interview.application.guard.core;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.config.InterviewAiGuardConfiguration;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified AI guard service with timeout/circuit-breaker/bulkhead/retry.
 */
@Service
@Slf4j
public class AiCallGuardService {

    private final InterviewAiGuardConfiguration configuration;
    private final MeterRegistry meterRegistry;
    private final ExecutorService aiIoExecutor;

    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Retry> retries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TimeLimiter> timeLimiters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> circuitStateGauges = new ConcurrentHashMap<>();

    public AiCallGuardService(
            InterviewAiGuardConfiguration configuration,
            MeterRegistry meterRegistry,
            @Qualifier("interviewAiIoExecutor") ExecutorService aiIoExecutor) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.aiIoExecutor = aiIoExecutor;
    }

    /**
     * 【关键节点：模型治理】按出题/评分/追问阶段选策略，统一记录耗时并将模型异常转换为业务可识别错误。
     * 保护关闭时直接执行；开启时依次经过熔断、并发隔离、重试和单次调用超时。
     */
    public <T> T execute(String stage, String requestKey, Callable<T> action) {
        // 按 stage 区分出题/评分/追问策略；统计整体执行耗时，成功与失败都记录指标。requestKey 仅用于日志定位。
        String safeStage = StrUtil.isNotBlank(stage) ? stage : "interview-default";
        long startNano = System.nanoTime();
        try {
            T result;
            if (Boolean.TRUE.equals(configuration.getEnable())) {
                result = guardedExecute(safeStage, action);
            } else {
                result = action.call();
            }
            recordMetrics(safeStage, "success", System.nanoTime() - startNano);
            return result;
        } catch (Throwable ex) {
            // 【关键节点：异常语义】把底层异常归类为超时/过载/不可用，向业务层抛统一异常；日志补充安全的类型和状态码。
            InterviewAiGuardException guardException = classifyException(ex, safeStage);
            String resultTag = guardException.getErrorCode().name().toLowerCase();
            recordMetrics(safeStage, resultTag, System.nanoTime() - startNano);
            if (guardException.getErrorCode() == InterviewAiGuardErrorCode.AI_OVERLOADED
                    && isBulkheadReject(unwrap(ex))) {
                meterRegistry.counter("ai_bulkhead_rejected_total", "stage", safeStage).increment();
            }
            log.warn(
                    "AI guarded call failed, stage={}, requestKey={}, code={}, elapsedMs={}, message={}, causeType={}, rootCauseType={}, httpStatus={}",
                    safeStage,
                    requestKey,
                    guardException.getErrorCode(),
                    (System.nanoTime() - startNano) / 1_000_000,
                    guardException.getMessage(),
                    unwrap(ex).getClass().getName(),
                    rootCauseType(ex),
                    upstreamHttpStatus(ex)
            );
            throw guardException;
        }
    }

    // 只记录异常类型和状态码，避免将上游响应正文、材料或凭据写入日志。
    private String rootCauseType(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 32 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private Integer upstreamHttpStatus(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            if (current instanceof org.springframework.web.client.RestClientResponseException responseException) {
                return responseException.getStatusCode().value();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    private <T> T guardedExecute(String stage, Callable<T> action) throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(stage, this::newCircuitBreaker);
        Bulkhead bulkhead = bulkheads.computeIfAbsent(stage, this::newBulkhead);
        Retry retry = retries.computeIfAbsent(stage, this::newRetry);

        // 装饰顺序固定为：CircuitBreaker -> Bulkhead -> Retry -> TimeLimiter。
        // 这样既能在入口快速拒绝故障流量，也能把重试限制在限流与超时边界内。
        Callable<T> decorated = CircuitBreaker.decorateCallable(
                circuitBreaker,
                Bulkhead.decorateCallable(
                        bulkhead,
                        Retry.decorateCallable(
                                retry,
                                () -> callWithTimeLimiter(stage, action)
                        )
                )
        );
        return decorated.call();
    }

    private <T> T callWithTimeLimiter(String stage, Callable<T> action) throws Exception {
        TimeLimiter timeLimiter = timeLimiters.computeIfAbsent(stage, this::newTimeLimiter);
        // 【关键节点：超时边界】AI I/O 在专用线程池执行，业务线程同步等待；TimeLimiter 限制等待时间。
        // 等待超时不代表远端模型已停止，迟到结果仍需由提交层的 attemptToken/version 拒绝。
        Callable<T> timeLimitedCall = TimeLimiter.decorateFutureSupplier(
                timeLimiter,
                () -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return action.call();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, aiIoExecutor)
        );
        return timeLimitedCall.call();
    }

    // 熔断配置区：按阶段缓存熔断器，依据失败率和窗口决定是否短路调用，并把状态变化暴露给监控。
    private CircuitBreaker newCircuitBreaker(String stage) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(resolveFailureRateThreshold())
                .slidingWindowSize(resolveSlidingWindowSize())
                .permittedNumberOfCallsInHalfOpenState(resolveHalfOpenProbeCount())
                .waitDurationInOpenState(Duration.ofMillis(resolveOpenWaitMillis()))
                .minimumNumberOfCalls(Math.min(10, resolveSlidingWindowSize()))
                .build();
        CircuitBreaker breaker = CircuitBreaker.of(stage, config);
        AtomicInteger stateGauge = new AtomicInteger(mapCircuitState(breaker.getState()));
        AtomicInteger existing = circuitStateGauges.putIfAbsent(stage, stateGauge);
        AtomicInteger gaugeTarget = existing != null ? existing : stateGauge;
        if (existing == null) {
            meterRegistry.gauge("ai_circuit_state", Tags.of("stage", stage), gaugeTarget, AtomicInteger::get);
        }
        breaker.getEventPublisher().onStateTransition(event ->
                gaugeTarget.set(mapCircuitState(event.getStateTransition().getToState())));
        return breaker;
    }

    // 并发隔离限制同阶段同时进入的调用数，等待为零；满额直接拒绝，避免请求无限堆积。
    private Bulkhead newBulkhead(String stage) {
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        int maxConcurrent = policy.getMaxConcurrentCalls() != null && policy.getMaxConcurrentCalls() > 0
                ? policy.getMaxConcurrentCalls()
                : 20;
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build();
        return Bulkhead.of(stage, config);
    }

    // 重试次数是额外尝试数，因此 maxAttempts = retryCount + 1；只有超时或 I/O 类异常符合这里的重试条件。
    private Retry newRetry(String stage) {
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        int retryCount = policy.getRetryCount() != null && policy.getRetryCount() >= 0 ? policy.getRetryCount() : 0;
        long waitMillis = policy.getRetryWaitMillis() != null && policy.getRetryWaitMillis() >= 0
                ? policy.getRetryWaitMillis()
                : 0L;
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Math.max(1, retryCount + 1))
                .waitDuration(Duration.ofMillis(waitMillis))
                .retryOnException(this::isRetriable)
                .ignoreExceptions(CallNotPermittedException.class, BulkheadFullException.class, InterviewAiGuardException.class)
                .build();
        return Retry.of(stage, config);
    }

    // 单次调用超时预算按阶段配置；超时后取消 Future 不保证已发出的远端请求立即终止。
    private TimeLimiter newTimeLimiter(String stage) {
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        long timeoutMillis = policy.getTimeoutMillis() != null && policy.getTimeoutMillis() > 0
                ? policy.getTimeoutMillis()
                : 5000L;
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeoutMillis))
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of(stage, config);
    }

    private boolean isRetriable(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return cause instanceof TimeoutException || cause instanceof IOException;
    }

    private InterviewAiGuardException classifyException(Throwable throwable, String stage) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof InterviewAiGuardException guardException) {
            return guardException;
        }

        // 统一映射为前端可稳定识别的三类语义，减少各链路各自兜底导致的分支复杂度。
        InterviewAiGuardErrorCode code;
        String message;
        if (cause instanceof TimeoutException) {
            code = InterviewAiGuardErrorCode.AI_TIMEOUT;
            message = "AI_TIMEOUT: ai call timed out, please retry";
        } else if (isOverloaded(cause)) {
            code = InterviewAiGuardErrorCode.AI_OVERLOADED;
            message = "AI_OVERLOADED: ai service is busy, please retry";
        } else {
            code = InterviewAiGuardErrorCode.AI_UNAVAILABLE;
            message = "AI_UNAVAILABLE: ai service is unavailable, please retry";
        }
        return new InterviewAiGuardException(code, stage, message, cause);
    }

    private boolean isOverloaded(Throwable cause) {
        return cause instanceof BulkheadFullException
                || cause instanceof RequestNotPermitted
                || cause instanceof RejectedExecutionException;
    }

    private boolean isBulkheadReject(Throwable cause) {
        return cause instanceof BulkheadFullException || cause instanceof RejectedExecutionException;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }

    private void recordMetrics(String stage, String result, long elapsedNano) {
        meterRegistry.counter("ai_call_total", "stage", stage, "result", result).increment();
        meterRegistry.timer("ai_call_latency_ms", "stage", stage, "result", result)
                .record(Duration.ofNanos(elapsedNano));
    }

    private int mapCircuitState(CircuitBreaker.State state) {
        if (state == CircuitBreaker.State.CLOSED) {
            return 0;
        }
        if (state == CircuitBreaker.State.HALF_OPEN) {
            return 1;
        }
        return 2;
    }

    private float resolveFailureRateThreshold() {
        Float configured = configuration.getCircuitFailureRateThreshold();
        return configured != null && configured > 0 ? configured : 50F;
    }

    private int resolveSlidingWindowSize() {
        Integer configured = configuration.getCircuitSlidingWindowSize();
        return configured != null && configured > 0 ? configured : 50;
    }

    private int resolveHalfOpenProbeCount() {
        Integer configured = configuration.getCircuitPermittedCallsInHalfOpenState();
        return configured != null && configured > 0 ? configured : 10;
    }

    private long resolveOpenWaitMillis() {
        Long configured = configuration.getCircuitOpenStateWaitMillis();
        return configured != null && configured > 0 ? configured : 30000L;
    }
}
