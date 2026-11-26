package com.hewei.hzyjy.aimeeting.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固化指标从 Micrometer 到 Prometheus 的命名转换结果。
 *
 * <p>两处命名容易踩坑：{@code ai_call_latency_ms} 名字里带 ms，但 Timer 的基本单位是秒，
 * 导出后会变成 {@code ai_call_latency_ms_seconds_*}；以 {@code _total} 结尾的 Counter
 * 是否被重复追加后缀，也依赖 Micrometer 的规范化逻辑。看板和告警都按真实名字写，
 * 这里用断言把结论钉住，避免以后再改名时静默失效。</p>
 */
class PrometheusMetricNamingTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    void timerWithMsInNameIsStillExportedInSeconds() {
        Timer.builder("ai_call_latency_ms")
                .tag("stage", "interview-evaluation")
                .tag("result", "success")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(1200));

        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("ai_call_latency_ms_seconds_count{")
                .contains("ai_call_latency_ms_seconds_bucket{");
        assertThat(scrape).doesNotContain("ai_call_latency_ms_count");
    }

    @Test
    void counterEndingWithTotalKeepsSingleSuffix() {
        Counter.builder("answer_heartbeat_total").register(registry).increment();

        String scrape = registry.scrape();

        assertThat(scrape).contains("answer_heartbeat_total ");
        assertThat(scrape).doesNotContain("answer_heartbeat_total_total");
    }

    @Test
    void gaugeAndLabeledCounterKeepDeclaredNames() {
        io.micrometer.core.instrument.Gauge.builder("ai_circuit_state", () -> 2)
                .tag("stage", "interview-followup")
                .register(registry);
        Counter.builder("ai_bulkhead_rejected_total").tag("stage", "interview-followup").register(registry).increment();

        String scrape = registry.scrape();

        assertThat(scrape).contains("ai_circuit_state{stage=\"interview-followup\"}");
        assertThat(scrape).contains("ai_bulkhead_rejected_total{stage=\"interview-followup\"");
    }
}
