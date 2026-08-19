package com.myai.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 网关业务指标（Prometheus 可观测性）
 * <p>在关键链路（路由成功/失败/熔断跳过）收集业务指标，供 {@code /actuator/prometheus} 暴露：
 * <ul>
 *   <li>{@code mag_requests_total} — 路由请求总数（按模型/渠道/结果 tag）</li>
 *   <li>{@code mag_route_latency_seconds} — 路由请求耗时分布</li>
 *   <li>{@code mag_circuit_break_skip_total} — 熔断跳过次数</li>
 * </ul>
 * 组件为<b>可选能力</b>：由 Spring 注入 {@link MeterRegistry}（Spring Boot Actuator 自动提供）；
 * 若未注入（如单元测试直接 new）则不记录任何指标，行为不受影响。</p>
 */
@Component
public class RelayMetrics {

    /** 是否存在可用的 MeterRegistry（无则全部调用为空操作） */
    private final MeterRegistry registry;

    // 计数器缓存：tags → Counter，避免每次 new
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    // 耗时计时器：按 tags 缓存
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public RelayMetrics(MeterRegistry registry) {
        // Spring 启动时 registry 恒存在；直接 new（测试）时可能传入 null，判空降级
        this.registry = registry;
    }

    /**
     * 记录一次路由请求结果。
     *
     * @param model      请求模型名（会被归一化以防止高基数标签）
     * @param channel    命中渠道名（失败时为 null）
     * @param result     success / fail / skip
     * @param latencyMs  请求耗时（毫秒）
     */
    public void recordRoute(String model, String channel, String result, long latencyMs) {
        if (registry == null) {
            return;
        }
        String safeModel = normalizeModelName(model);
        String safeChannel = channel == null ? "none" : channel;
        // 请求计数
        counter("mag_requests_total",
                "model", safeModel, "channel", safeChannel, "result", result)
                .increment();
        // 耗时计时
        Timer timer = timerCache.computeIfAbsent(buildCacheKey("mag_route_latency_seconds", safeModel, safeChannel, result),
                k -> Timer.builder("mag_route_latency_seconds")
                        .tags("model", safeModel, "channel", safeChannel, "result", result)
                        .register(registry));
        timer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次熔断跳过事件。
     */
    public void recordCircuitBreakSkip(String scope) {
        if (registry == null) {
            return;
        }
        counter("mag_circuit_break_skip_total", "scope", scope == null ? "unknown" : scope).increment();
    }

    /**
     * 归一化模型名称，防止高基数标签导致 Prometheus 指标爆炸。
     * <p>剥离日期后缀（如 gpt-4o-2024-08-06 → gpt-4o），null 返回 "unknown"。</p>
     */
    static String normalizeModelName(String model) {
        if (model == null || model.isEmpty()) return "unknown";
        // 剥离末尾的日期后缀：-YYYY-MM-DD 或 -YYYYMMDD
        return model.replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "")
                    .replaceAll("-\\d{8}$", "");
    }

    /**
     * 统一的缓存键生成：使用 {@code |} 分隔，避免标签值中的 {@code :} 导致冲突。
     */
    private static String buildCacheKey(String name, String... tags) {
        StringBuilder sb = new StringBuilder(name);
        for (String tag : tags) {
            sb.append('|').append(tag);
        }
        return sb.toString();
    }

    private Counter counter(String name, String... tags) {
        String key = buildCacheKey(name, tags);
        return counterCache.computeIfAbsent(key, k ->
                Counter.builder(name).tags(tags).register(registry));
    }
}
