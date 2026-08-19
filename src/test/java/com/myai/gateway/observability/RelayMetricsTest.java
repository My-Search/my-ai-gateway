package com.myai.gateway.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelayMetrics 单元测试
 * 验证：
 * - 指标被正确记录（请求计数 / 熔断跳过计数）
 * - registry 为 null 时安全降级（不抛异常）
 */
class RelayMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private RelayMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new RelayMetrics(meterRegistry);
    }

    @Test
    void recordRoute_incrementsRequestCounterAndTimer() {
        metrics.recordRoute("gpt-4o", "openai", "success", 120L);

        double count = meterRegistry.counter("mag_requests_total",
                "model", "gpt-4o", "channel", "openai", "result", "success").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordRoute_multipleCalls_accumulate() {
        metrics.recordRoute("gpt-4o", null, "fail", 50L);

        double count = meterRegistry.counter("mag_requests_total",
                "model", "gpt-4o", "channel", "none", "result", "fail").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordCircuitBreakSkip_incrementsSkipCounter() {
        metrics.recordCircuitBreakSkip("渠道级熔断");

        double count = meterRegistry.counter("mag_circuit_break_skip_total", "scope", "渠道级熔断").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void nullRegistry_isSafeNoOp() {
        RelayMetrics noRegistry = new RelayMetrics(null);

        // 不应抛异常
        noRegistry.recordRoute("m", "c", "success", 10L);
        noRegistry.recordCircuitBreakSkip("scope");

        assertThat(true).isTrue();
    }
}
