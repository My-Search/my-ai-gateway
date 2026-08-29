package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.config.LocalCacheService;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelHeaders;
import com.myai.gateway.observability.RelayMetrics;
import com.myai.gateway.relay.balancer.LoadBalancer;
import com.myai.gateway.service.CircuitBreakerStateChangedEvent;
import com.myai.gateway.relay.balancer.LoadBalancerFactory;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.stream.SseEvent;
import com.myai.gateway.relay.stream.SseHandler;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
import com.myai.gateway.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 候选路由引擎
 * <p>负责非流式和流式请求的候选选择、候选内重试、熔断触发、响应转换等核心路由逻辑。</p>
 * <p>上游调用通过 {@link ProviderInvoker} 回调，由调用方（如 {@link RelayService}）传入，
 * 便于测试时通过 Mockito spy 拦截。</p>
 */
public class CandidateRouter {

    private static final Logger log = LoggerFactory.getLogger(CandidateRouter.class);

    private static final long NON_STREAM_MIN_TIMEOUT_MS = 30_000L;
    private static final long MAX_TOTAL_TIMEOUT_MS = 600_000L;

    private final ModelService modelService;
    private final CircuitBreakerService circuitBreakerService;
    private final RequestLogService requestLogService;
    private final LoadBalancerFactory loadBalancerFactory;
    private final ObjectMapper objectMapper;
    private final MessageTransformer messageTransformer;
    private final StreamContentManager streamContentManager;
    private final LatencyTracker latencyTracker;
    private final SseHandler sseHandler;
    private final RouteResolver routeResolver;
    private final RequestPreprocessor requestPreprocessor;
    private final RelayLogger relayLogger;
    private final WebClient webClient;
    private final CircuitBreakerRecoveryService recoveryService;
    final ConcurrentHashMap<String, int[]> streamUsageMap;

    /**
     * 首字节响应时间采集：traceId -> 收到首个响应字节的绝对时间戳（ms）
     * <p>由上游调用实现（WebClient 默认实现）在首个 DataBuffer 到达时写入，
     * 路由成功/失败落日志时读取并清空，用于"首字节平均响应"统计。
     * 与 {@link #streamUsageMap} 相同的模式：同一 traceId 的多次尝试后写覆盖先写。</p>
     */
    private final ConcurrentHashMap<String, Long> firstByteArrivalMsByTraceId = new ConcurrentHashMap<>();

    /**
     * 熔断状态短路缓存（可选能力，由 Spring 注入）。
     * <p>缓解 {@code circuitBreakScope} 对每个候选每个重试轮次的 selectCount 重复查询
     * （同一候选依次被选多次时，熔断状态在毫秒级内不变，无需反复查 DB）。
     * 使用极短 TTL（1s）自愈，熔断/探测开门延迟不超过 1 秒；未注入（测试）时走直查行为不变。</p>
     */
    private LocalCacheService localCacheService;

    /** 熔断短路缓存 TTL（毫秒级极短，权衡查询次数与熔断生效延迟） */
    private static final Duration CIRCUIT_CACHE_TTL = Duration.ofSeconds(1);
    private static final String CACHE_NS_CIRCUIT = LocalCacheService.NS_CIRCUIT_STATE_BY_SCOPE;

    /**
     * "未熔断"哨兵值：Caffeine 不缓存 null 值，健康候选（最常见情形）需以哨兵形式
     * 写入缓存才能命中，否则每次仍直查 DB，短路缓存形同虚设。
     */
    private static final String CIRCUIT_HEALTHY_SENTINEL = "__healthy__";

    /**
     * 业务指标（可选能力，由 {@link RelayService} 透传注入；测试直接 new 时为 null 不埋点）。
     */
    private RelayMetrics relayMetrics;

    public CandidateRouter(ModelService modelService,
                           CircuitBreakerService circuitBreakerService,
                           RequestLogService requestLogService,
                           LoadBalancerFactory loadBalancerFactory,
                           ObjectMapper objectMapper,
                           MessageTransformer messageTransformer,
                           StreamContentManager streamContentManager,
                           LatencyTracker latencyTracker,
                           SseHandler sseHandler,
                           RouteResolver routeResolver,
                           RequestPreprocessor requestPreprocessor,
                           RelayLogger relayLogger,
                           WebClient webClient,
                           CircuitBreakerRecoveryService recoveryService,
                           ConcurrentHashMap<String, int[]> streamUsageMap) {
        this.modelService = modelService;
        this.circuitBreakerService = circuitBreakerService;
        this.requestLogService = requestLogService;
        this.loadBalancerFactory = loadBalancerFactory;
        this.objectMapper = objectMapper;
        this.messageTransformer = messageTransformer;
        this.streamContentManager = streamContentManager;
        this.latencyTracker = latencyTracker;
        this.sseHandler = sseHandler;
        this.routeResolver = routeResolver;
        this.requestPreprocessor = requestPreprocessor;
        this.relayLogger = relayLogger;
        this.webClient = webClient;
        this.recoveryService = recoveryService;
        this.streamUsageMap = streamUsageMap;
    }

    /**
     * 注入本地缓存服务（可选能力，由 Spring 调用；测试直接 new 时可省略）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLocalCacheService(LocalCacheService localCacheService) {
        this.localCacheService = localCacheService;
    }

    /**
     * 注入业务指标（可选能力，由 {@link RelayService} 透传；测试直接 new 时可省略）。
     */
    public void setRelayMetrics(RelayMetrics relayMetrics) {
        this.relayMetrics = relayMetrics;
    }

    /**
     * 监听熔断状态变更事件，主动失效熔断短路缓存。
     * <p>由 {@code CircuitBreakerService} 在触发/解除/探测开门/手动恢复时发布，
     * 避免状态变更后缓存中仍持有旧值导致最多 1 秒窗口内的不一致。</p>
     * <p>失效粒度：{@code channelModelId} 与 {@code channelApiKeyId} 均有值时精确失效单个 key；
     * 任一为空（渠道级/全渠道熔断）时影响该渠道下全部候选，无法定位到具体 key，
     * 失效整个命名空间（TTL 仅 1s，整体失效成本可忽略）。</p>
     */
    @org.springframework.context.event.EventListener
    public void onCircuitBreakerStateChanged(CircuitBreakerStateChangedEvent event) {
        if (localCacheService == null) {
            return;
        }
        if (event.getChannelModelId() == null || event.getChannelApiKeyId() == null) {
            // 渠道级/全渠道熔断：影响面为该渠道全部候选，整体失效确保一致性
            localCacheService.invalidateAll(CACHE_NS_CIRCUIT);
            log.debug("熔断状态变更事件 - 失效整个命名空间: channelId={}, channelModelId={}, channelApiKeyId={}",
                    event.getChannelId(), event.getChannelModelId(), event.getChannelApiKeyId());
            return;
        }
        String key = event.buildCacheKey();
        localCacheService.invalidate(CACHE_NS_CIRCUIT, key);
        log.debug("熔断状态变更事件 - 失效缓存: channelId={}, channelModelId={}, channelApiKeyId={}",
                event.getChannelId(), event.getChannelModelId(), event.getChannelApiKeyId());
    }

    // ========== 非流式路由 ==========

    public Mono<String> executeRelay(String traceId, String authHeader, Long gatewayApiKeyId,
                                      InternalRequest req, String provider, ProviderInvoker invoker) {
        long startTime = System.currentTimeMillis();
        String originalModel = req.getModel();
        RouteResolver.RoutingContext ctx = routeResolver.resolveModelRouting(originalModel);
        List<RoutingCandidate> candidates = routeResolver.getAvailableCandidates(req);
        // 请求进入即触发各候选渠道的到期熔断探测：异步投递（微秒级），与请求处理并行，
        // 探测开门后对后续请求生效，不阻塞当前请求
        triggerProbeForCandidates(candidates);
        if (candidates.isEmpty()) {
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, originalModel, null, null,
                    "fail", "error", "没有可用的路由候选", System.currentTimeMillis() - startTime, 0);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    "没有可用的路由候选（关联的渠道/API Key/模型均不可用）", "api_error", 503));
        }
        return tryCandidates(traceId, new ArrayList<>(candidates), authHeader, gatewayApiKeyId,
                req, provider, 0, startTime, ctx, null, invoker)
                .timeout(Duration.ofMillis(MAX_TOTAL_TIMEOUT_MS))
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.warn("请求总超时兜底触发 - traceId={} 总耗时={}ms", traceId, System.currentTimeMillis() - startTime);
                    requestLogService.logComplete(traceId, null, gatewayApiKeyId, originalModel, null, null,
                            "fail", "timeout", "请求总超时", System.currentTimeMillis() - startTime, 0);
                    return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                            "请求总超时（所有候选重试总耗时超过上限）", "api_error", 503));
                })
                // 兜底清理：正常成功/失败路径已在落日志时读取并移除首字节时间戳（幂等），
                // 此处保证超时、客户端取消等未落日志的出口不残留 map 条目
                .doFinally(signal -> firstByteArrivalMsByTraceId.remove(traceId));
    }

    /**
     * 顺序尝试候选（包级可见，便于单元测试）
     */
    Mono<String> tryCandidates(String traceId, List<RoutingCandidate> remaining,
                                        String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                        String provider, int retryIndex, long startTime,
                                        ProviderInvoker invoker) {
        RouteResolver.RoutingContext ctx = routeResolver.resolveModelRouting(req.getModel());
        return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                retryIndex, startTime, ctx, null, invoker);
    }

    private Mono<String> tryCandidates(String traceId, List<RoutingCandidate> remaining,
                                        String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                        String provider, int retryIndex, long startTime,
                                        RouteResolver.RoutingContext ctx, String lastErrorMsg,
                                        ProviderInvoker invoker) {
        if (remaining.isEmpty()) {
            String failMsg = buildFailMessage(lastErrorMsg);
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, req.getModel(), null, null,
                    "fail", "error", failMsg, System.currentTimeMillis() - startTime,
                    firstByteMsSince(traceId, startTime), retryIndex);
            log.warn("所有候选均失败 - traceId={}, lastError={}", traceId, lastErrorMsg);
            recordRouteMetric(req.getModel(), null, "fail", startTime);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    failMsg, "api_error", 503));
        }

        LoadBalancer balancer = loadBalancerFactory.getBalancer(ctx.strategy());
        RoutingCandidate candidate = balancer.select(remaining, ctx.modelId());
        if (candidate == null) {
            log.warn("负载均衡器返回null候选 - traceId={}", traceId);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    "没有可用的路由候选", "api_error", 503));
        }

        String breakScope = circuitBreakScope(candidate);
        if (breakScope != null) {
            log.info("已熔断跳过 - traceId={} retryIndex={} channel={} model={} key={} scope={}",
                    traceId, retryIndex, candidate.getChannel().getName(),
                    candidate.getChannelModel().getModelName(), candidate.getChannelApiKey().getKeyName(), breakScope);
            if (relayMetrics != null) {
                relayMetrics.recordCircuitBreakSkip(breakScope);
            }
            relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                    breakScope + "跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);
            remaining.remove(candidate);
            return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                    retryIndex + 1, startTime, ctx, lastErrorMsg, invoker);
        }

        if (requestPreprocessor.skipIfMediaTypeUnsupported(traceId, gatewayApiKeyId, candidate, req, retryIndex, relayLogger)) {
            remaining.remove(candidate);
            return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                    retryIndex + 1, startTime, ctx, lastErrorMsg, invoker);
        }

        log.info("路由决策 - traceId={} retryIndex={} 选中候选: channel={} model={} key={} (剩余{}个候选)",
                traceId, retryIndex, candidate.getChannel().getName(),
                candidate.getChannelModel().getModelName(), candidate.getChannelApiKey().getKeyName(), remaining.size());

        relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "start",
                "路由到 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex,
                resolveEffectiveReasoningEffort(req, candidate));

        int maxAttempts = ctx.maxAttempts();
        String actualProvider = resolveProvider(candidate, provider);
        return invokeCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, req, candidate,
                actualProvider, retryIndex, 1, maxAttempts, invoker)
                .flatMap(body -> {
                    balancer.markSuccess(candidate);
                    modelService.updateChannelModelLastUsed(candidate.getChannelModel().getId());
                    relayLogger.updateGatewayApiKeyLastUsed(authHeader);
                    Long firstByteMs = firstByteMsSince(traceId, startTime);
                    latencyTracker.record(candidate.getChannel().getId(), candidate.getChannelModel().getId(),
                            firstByteMs != null ? firstByteMs : System.currentTimeMillis() - startTime);
                    recordRouteMetric(req.getModel(), candidate.getChannel().getName(), "success", startTime);
                    String transformed = transformResponse(body, candidate, req, actualProvider);
                    int[] usage = extractUsageFromProviderResponse(body);
                    requestLogService.logComplete(traceId, candidate.getChannelApiKey().getKeyName(), gatewayApiKeyId,
                            req.getModel(), candidate.getChannelModel().getModelName(),
                            candidate.getChannel().getName(), "success", "success", "请求成功",
                            System.currentTimeMillis() - startTime, firstByteMs,
                            retryIndex, usage[0], usage[1], usage[2]);
                    log.info("候选请求成功 - traceId={} channel={} model={} key={}",
                            traceId, candidate.getChannel().getName(), candidate.getChannelModel().getModelName(),
                            candidate.getChannelApiKey().getKeyName());
                    return Mono.just(transformed);
                })
                .onErrorResume(err -> {
                    if (err instanceof NonRetryableProviderException) {
                        log.warn("候选返回400，不触发熔断，直接重路由 channel={} model={} key={}",
                                candidate.getChannel().getName(), candidate.getChannelModel().getModelName(),
                                candidate.getChannelApiKey().getKeyName());
                        remaining.remove(candidate);
                        relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                                "400错误跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName() + " 原因: " + err.getMessage(), retryIndex);
                        return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                                retryIndex + 1, startTime, ctx, err.getMessage(), invoker);
                    }
                    log.warn("候选失败（重试耗尽）channel={} key={} model={} (已重试{}次): {}",
                            candidate.getChannel().getName(), candidate.getChannelApiKey().getKeyName(),
                            candidate.getChannelModel().getModelName(), maxAttempts - 1, err.getMessage());
                    handleFailure(candidate, req);
                    balancer.markFailed(candidate);
                    remaining.remove(candidate);
                    relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                            "重试耗尽跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName() + " 原因: " + err.getMessage(), retryIndex);
                    return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                            retryIndex + 1, startTime, ctx, err.getMessage(), invoker);
                });
    }

    // ========== 非流式候选内重试 ==========

    private Mono<String> invokeCandidateWithRetries(String traceId, String authHeader, Long gatewayApiKeyId,
                                                     InternalRequest req, RoutingCandidate candidate,
                                                     String provider, int retryIndex, int attempt, int maxAttempts,
                                                     ProviderInvoker invoker) {
        long attemptStartTime = System.currentTimeMillis();
        long timeoutMs = latencyTracker.getTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId());
        // 每次尝试独立测量首字节：清掉上一次尝试（可能属于失败候选）残留的时间戳
        firstByteArrivalMsByTraceId.remove(traceId);
        return invoker.invokeNonStream(authHeader, req, candidate, provider, traceId)
                .flatMap(body -> {
                    if (body == null || body.isBlank()) {
                        log.warn("候选返回空响应 - traceId={} attempt={}/{} channel={} model={} key={}",
                                traceId, attempt, maxAttempts, candidate.getChannel().getName(),
                                candidate.getChannelApiKey().getKeyName(), candidate.getChannelModel().getModelName());
                        return Mono.error(new RuntimeException("Provider returned empty response, treated as timeout"));
                    }
                    return Mono.just(body);
                })
                .timeout(Duration.ofMillis(Math.max(timeoutMs, NON_STREAM_MIN_TIMEOUT_MS)))
                .doOnError(err -> {
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    latencyTracker.recordTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId(), timeoutMs);
                    log.warn("候选尝试 {}/{} 失败 channel={} key={} model={} (耗时 {}ms): {}",
                            attempt, maxAttempts, candidate.getChannel().getName(),
                            candidate.getChannelApiKey().getKeyName(), candidate.getChannelModel().getModelName(),
                            attemptDurationMs, err.getMessage());
                })
                .onErrorResume(err -> {
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    if (err instanceof NonRetryableProviderException nre && nre.getHttpStatus() == 400) {
                        return Mono.error(err);
                    }
                    if (attempt < maxAttempts) {
                        relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "retry",
                                "第 " + attempt + " 次失败: " + (err.getMessage() != null ? err.getMessage() : "") + "，准备第 " + (attempt + 1) + " 次重试", retryIndex, attemptDurationMs);
                        return invokeCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, req, candidate, provider,
                                retryIndex, attempt + 1, maxAttempts, invoker);
                    }
                    return Mono.error(err);
                });
    }

    // ========== 流式路由 ==========

    public Flux<SseEvent> executeStreamRelay(String traceId, String authHeader, Long gatewayApiKeyId,
                                              InternalRequest req, String provider, boolean internalClient,
                                              AtomicBoolean finalStateLogged, ProviderInvoker invoker) {
        long startTime = System.currentTimeMillis();
        RouteResolver.RoutingContext ctx = routeResolver.resolveModelRouting(req.getModel());
        List<RoutingCandidate> candidates = routeResolver.getAvailableCandidates(req);
        // 请求进入即触发各候选渠道的到期熔断探测：异步投递（微秒级），与请求处理并行
        triggerProbeForCandidates(candidates);
        if (candidates.isEmpty()) {
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, req.getModel(), null, null,
                    "fail", "error", "没有可用的路由候选", System.currentTimeMillis() - startTime, 0);
            return Flux.error(new RuntimeException("没有可用的路由候选"));
        }
        return tryStreamCandidates(traceId, new ArrayList<>(candidates), authHeader, gatewayApiKeyId,
                req, provider, 0, startTime, internalClient, ctx, null, finalStateLogged, invoker)
                // 兜底清理：成功/全部失败路径已在落日志时读取并移除首字节时间戳（幂等），
                // 此处保证客户端断开、取消订阅等未落日志的出口不残留 map 条目
                .doFinally(signal -> firstByteArrivalMsByTraceId.remove(traceId));
    }

    /**
     * 顺序尝试流式候选（包级可见，便于单元测试）
     */
    Flux<SseEvent> tryStreamCandidates(String traceId, List<RoutingCandidate> remaining,
                                                String authHeader, Long gatewayApiKeyId,
                                                InternalRequest req, String provider, int retryIndex,
                                                long startTime, boolean internalClient,
                                                AtomicBoolean finalStateLogged, ProviderInvoker invoker) {
        RouteResolver.RoutingContext ctx = routeResolver.resolveModelRouting(req.getModel());
        return tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                retryIndex, startTime, internalClient, ctx, null, finalStateLogged, invoker);
    }

    private Flux<SseEvent> tryStreamCandidates(String traceId, List<RoutingCandidate> remaining,
                                                String authHeader, Long gatewayApiKeyId,
                                                InternalRequest req, String provider, int retryIndex,
                                                long startTime, boolean internalClient,
                                                RouteResolver.RoutingContext ctx, String lastErrorMsg,
                                                AtomicBoolean finalStateLogged, ProviderInvoker invoker) {
        if (remaining.isEmpty()) {
            streamContentManager.clearContent(traceId);
            String extraInfo = req.isContextRetry() ? "（已拼接上下文但无剩余候选）" : "";
            String failMsg = buildFailMessage(lastErrorMsg);
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, req.getModel(), null, null,
                    "fail", "error", failMsg, System.currentTimeMillis() - startTime,
                    firstByteMsSince(traceId, startTime), retryIndex);
            log.warn("所有流式候选均失败 - traceId={}{}, lastError={}", traceId, extraInfo, lastErrorMsg);
            recordRouteMetric(req.getModel(), null, "fail", startTime);
            return Flux.error(new RuntimeException(failMsg));
        }

        if (finalStateLogged.get()) {
            streamContentManager.clearContent(traceId);
            return Flux.error(new RuntimeException("Client disconnected from SSE stream"));
        }

        LoadBalancer balancer = loadBalancerFactory.getBalancer(ctx.strategy());
        RoutingCandidate candidate = balancer.select(remaining, ctx.modelId());
        if (candidate == null) {
            log.warn("流式负载均衡器返回null候选 - traceId={}", traceId);
            return Flux.error(new RuntimeException("没有可用的路由候选"));
        }

        String breakScope = circuitBreakScope(candidate);
        if (breakScope != null) {
            log.info("已熔断跳过 - traceId={} retryIndex={} channel={} model={} key={} scope={}",
                    traceId, retryIndex, candidate.getChannel().getName(),
                    candidate.getChannelModel().getModelName(), candidate.getChannelApiKey().getKeyName(), breakScope);
            if (relayMetrics != null) {
                relayMetrics.recordCircuitBreakSkip(breakScope);
            }
            relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                    breakScope + "跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);
            remaining.remove(candidate);
            return tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                    retryIndex + 1, startTime, internalClient, ctx, lastErrorMsg, finalStateLogged, invoker);
        }

        if (requestPreprocessor.skipIfMediaTypeUnsupported(traceId, gatewayApiKeyId, candidate, req, retryIndex, relayLogger)) {
            remaining.remove(candidate);
            return tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                    retryIndex + 1, startTime, internalClient, ctx, lastErrorMsg, finalStateLogged, invoker);
        }

        log.info("流式路由决策 - traceId={} retryIndex={} 选中候选: channel={} model={} key={} (剩余{}个候选){}",
                traceId, retryIndex, candidate.getChannel().getName(), candidate.getChannelModel().getModelName(),
                candidate.getChannelApiKey().getKeyName(), remaining.size(),
                req.isContextRetry() ? " [已拼接]" : "");

        relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "start",
                "流式路由到 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex,
                resolveEffectiveReasoningEffort(req, candidate));

        int maxAttempts = ctx.maxAttempts();
        String actualProvider = resolveProvider(candidate, provider);
        Flux<SseEvent> routingStart = internalClient
                ? Flux.just(new SseEvent(null, sseHandler.buildRoutingProgressJson("trying", candidate, retryIndex, null)))
                : Flux.empty();

        return Flux.concat(routingStart,
                invokeStreamCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, req, candidate,
                        actualProvider, retryIndex, 1, maxAttempts, internalClient, finalStateLogged, invoker))
                .doOnNext(event -> {
                    int[] usage = sseHandler.extractUsageFromSseData(event.data());
                    if (usage != null) streamUsageMap.put(traceId, usage);
                })
                .doOnComplete(() -> {
                    int[] usage = streamUsageMap.remove(traceId);
                    int pt = usage != null ? usage[0] : 0;
                    int ct = usage != null ? usage[1] : 0;
                    int tt = usage != null ? usage[2] : 0;
                    String resultMsg = "流式请求成功";
                    if (pt == 0 && ct == 0 && tt == 0) {
                        String content = streamContentManager.getContent(traceId);
                        if (content != null && !content.isEmpty()) {
                            ct = Math.max(1, content.length() / 2);
                            tt = ct;
                            resultMsg = "流式请求成功（用量为估算值）";
                        }
                    }
                    Long firstByteMs = firstByteMsSince(traceId, startTime);
                    balancer.markSuccess(candidate);
                    modelService.updateChannelModelLastUsed(candidate.getChannelModel().getId());
                    relayLogger.updateGatewayApiKeyLastUsed(authHeader);
                    latencyTracker.record(candidate.getChannel().getId(), candidate.getChannelModel().getId(),
                            firstByteMs != null ? firstByteMs : System.currentTimeMillis() - startTime);
                    recordRouteMetric(req.getModel(), candidate.getChannel().getName(), "success", startTime);
                    streamContentManager.clearContent(traceId);
                    requestLogService.logComplete(traceId, candidate.getChannelApiKey().getKeyName(), gatewayApiKeyId,
                            req.getModel(), candidate.getChannelModel().getModelName(),
                            candidate.getChannel().getName(), "success", "success", resultMsg,
                            System.currentTimeMillis() - startTime, firstByteMs,
                            retryIndex, pt, ct, tt);
                })
                .switchIfEmpty(Flux.defer(() -> {
                    log.warn("流式候选返回空响应（无SSE事件）- traceId={}", traceId);
                    return Flux.error(new RuntimeException("流式候选返回空响应"));
                }))
                .onErrorResume(err -> {
                    if (finalStateLogged.get()) {
                        log.info("客户端已断开，跳过后续流式候选 - traceId={}", traceId);
                        return Flux.error(new RuntimeException("Client disconnected from SSE stream"));
                    }
                    if (err instanceof NonRetryableProviderException) {
                        log.warn("流式候选返回400，不触发熔断，直接重路由 channel={} model={} key={}",
                                candidate.getChannel().getName(), candidate.getChannelModel().getModelName(),
                                candidate.getChannelApiKey().getKeyName());
                        streamContentManager.clearContent(traceId);
                        remaining.remove(candidate);
                        relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                                "400错误跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName() + " 原因: " + err.getMessage(), retryIndex);
                        return tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider,
                                retryIndex + 1, startTime, internalClient, ctx, err.getMessage(), finalStateLogged, invoker);
                    }
                    log.warn("流式候选失败（重试耗尽）channel={} key={} model={} (已重试{}次): {}",
                            candidate.getChannel().getName(), candidate.getChannelApiKey().getKeyName(),
                            candidate.getChannelModel().getModelName(), maxAttempts - 1, err.getMessage());
                    String accumulatedContent = streamContentManager.getAndClearContent(traceId);
                    InternalRequest contextReq = req;
                    if (accumulatedContent != null && !accumulatedContent.isEmpty()) {
                        contextReq = requestPreprocessor.buildRequestWithContext(req, accumulatedContent);
                    }
                    handleFailure(candidate, contextReq);
                    balancer.markFailed(candidate);
                    remaining.remove(candidate);
                    relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                            "重试耗尽跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName() + " 原因: " + err.getMessage(), retryIndex);
                    Flux<SseEvent> routingSwitch = internalClient
                            ? Flux.just(new SseEvent(null, sseHandler.buildRoutingProgressJson("switching", candidate, retryIndex + 1, err.getMessage())))
                            : Flux.empty();
                    return Flux.concat(routingSwitch,
                            tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, contextReq, provider,
                                    retryIndex + 1, startTime, internalClient, ctx, err.getMessage(), finalStateLogged, invoker));
                });
    }

    // ========== 流式候选内重试 ==========

    private Flux<SseEvent> invokeStreamCandidateWithRetries(String traceId, String authHeader, Long gatewayApiKeyId,
                                                             InternalRequest req, RoutingCandidate candidate,
                                                             String provider, int retryIndex, int attempt, int maxAttempts,
                                                             boolean internalClient, AtomicBoolean finalStateLogged,
                                                             ProviderInvoker invoker) {
        long attemptStartTime = System.currentTimeMillis();
        long timeoutMs = latencyTracker.getTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId());
        // 每次尝试独立测量首字节：清掉上一次尝试（可能属于失败候选）残留的时间戳
        firstByteArrivalMsByTraceId.remove(traceId);
        return invoker.invokeStream(authHeader, req, candidate, provider, internalClient, traceId)
                // 仅对首个数据包应用超时（初次响应超时），收到任意一个事件后即不再限制后续空闲时间
                // firstTimeout = Mono.delay(timeoutMs)：在 timeoutMs 后发出信号触发首元素超时
                // nextTimeoutFactory 返回 Mono.never()：表示收到元素后不再有任何超时
                .timeout(Mono.delay(Duration.ofMillis(timeoutMs)), event -> Mono.never())
                .doOnError(err -> {
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    latencyTracker.recordTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId(), timeoutMs);
                })
                .onErrorResume(err -> {
                    if (finalStateLogged.get()) {
                        return Flux.error(new RuntimeException("Client disconnected from SSE stream"));
                    }
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    if (err instanceof NonRetryableProviderException nre && nre.getHttpStatus() == 400) {
                        return Flux.error(err);
                    }
                    if (attempt < maxAttempts) {
                        InternalRequest retryReq = req;
                        String accumulatedContent = streamContentManager.getContent(traceId);
                        if (accumulatedContent != null && !accumulatedContent.isEmpty()) {
                            retryReq = requestPreprocessor.buildRequestWithContext(retryReq, accumulatedContent);
                        }
                        relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, retryReq, "retry",
                                "第 " + attempt + " 次失败: " + (err.getMessage() != null ? err.getMessage() : "") + "，准备第 " + (attempt + 1) + " 次重试", retryIndex, attemptDurationMs);
                        Flux<SseEvent> routingRetry = internalClient
                                ? Flux.just(new SseEvent(null, sseHandler.buildRoutingProgressJson("retrying", candidate, retryIndex, "第" + attempt + "次失败，第" + (attempt + 1) + "次重试")))
                                : Flux.empty();
                        return Flux.concat(routingRetry,
                                invokeStreamCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, retryReq, candidate, provider,
                                        retryIndex, attempt + 1, maxAttempts, internalClient, finalStateLogged, invoker));
                    }
                    return Flux.error(err);
                });
    }

    // ========== 上游调用（默认 WebClient 实现） ==========

    /**
     * 调用上游非流式接口（使用 WebClient 的默认实现）
     * <p>通过 DataBuffer 流感知首个响应字节（bodyToMono(String) 会等整个响应体接收完，
     * 无法测出首字节时间），首字节到达时间按 traceId 暂存，供路由落日志时回填。</p>
     */
    Mono<String> callProviderNonStreamWithWebClient(String authHeader, InternalRequest req,
                                                     RoutingCandidate candidate, String provider,
                                                     String traceId) {
        String endpoint = buildEndpoint(candidate, provider);
        String apiKey = candidate.getChannelApiKey().getApiKey();
        Map<String, String> headers = buildProviderHeaders(provider, apiKey, authHeader);
        ChannelHeaders.mergeInto(candidate.getChannel().getCustomHeaders(), headers);
        String providerReqBody = buildProviderRequestBody(req, candidate, provider);
        log.debug("非流式调用上游: endpoint={}, provider={}, channel={}, keyId={}, apiKeyMasked={}",
                endpoint, provider, candidate.getChannel().getName(),
                candidate.getChannelApiKey().getId(), relayLogger.maskBearerToken(apiKey));

        return webClient.post()
                .uri(endpoint)
                .headers(h -> headers.forEach(h::add))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(org.springframework.web.reactive.function.BodyInserters.fromValue(providerReqBody))
                .exchangeToMono(resp -> {
                    boolean ok = resp.statusCode().is2xxSuccessful();
                    return bodyToUtf8StringWithFirstByte(resp, traceId).flatMap(body -> {
                        if (ok) {
                            return Mono.just(body);
                        }
                        log.warn("Provider returned error status {} for channel={} keyId={} keyMasked={} keyLen={}",
                                resp.statusCode(), candidate.getChannel().getId(),
                                candidate.getChannelApiKey().getId(),
                                relayLogger.maskBearerToken(apiKey),
                                apiKey == null ? 0 : apiKey.trim().length());
                        int status = resp.statusCode().value();
                        if (status == 400) return Mono.error(new NonRetryableProviderException(status, body));
                        return Mono.error(new RuntimeException("Provider error: " + resp.statusCode() + " body: " + body));
                    });
                });
    }

    /**
     * 读取响应体为 UTF-8 字符串，并在首个 DataBuffer 到达时记录首字节时间戳（按 traceId）
     */
    private Mono<String> bodyToUtf8StringWithFirstByte(org.springframework.web.reactive.function.client.ClientResponse resp,
                                                       String traceId) {
        return resp.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                .doOnNext(buf -> firstByteArrivalMsByTraceId.putIfAbsent(traceId, System.currentTimeMillis()))
                .collectList()
                .map(buffers -> {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    for (org.springframework.core.io.buffer.DataBuffer buf : buffers) {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                        out.write(bytes, 0, bytes.length);
                    }
                    return out.toString(java.nio.charset.StandardCharsets.UTF_8);
                });
    }

    /**
     * 调用上游流式接口（使用 WebClient 的默认实现）
     */
    Flux<SseEvent> callProviderStreamWithWebClient(String authHeader, InternalRequest req,
                                                    RoutingCandidate candidate, String provider,
                                                    boolean internalClient, String traceId) {
        String endpoint = buildEndpoint(candidate, provider);
        String apiKey = candidate.getChannelApiKey().getApiKey();
        Map<String, String> headers = buildProviderHeaders(provider, apiKey, authHeader);
        ChannelHeaders.mergeInto(candidate.getChannel().getCustomHeaders(), headers);
        String providerReqBody = buildProviderRequestBody(req, candidate, provider);
        log.debug("流式调用上游: endpoint={}, provider={}, channel={}, keyId={}, apiKeyMasked={}",
                endpoint, provider, candidate.getChannel().getName(),
                candidate.getChannelApiKey().getId(), relayLogger.maskBearerToken(apiKey));

        return webClient.post()
                .uri(endpoint)
                .headers(h -> headers.forEach(h::add))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(org.springframework.web.reactive.function.BodyInserters.fromValue(providerReqBody))
                .exchangeToFlux(resp -> {
                    if (!resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class)
                                .flatMapMany(body -> {
                                    log.warn("Provider stream error status {} for channel={} keyId={} keyMasked={} keyLen={}",
                                            resp.statusCode(), candidate.getChannel().getId(),
                                            candidate.getChannelApiKey().getId(),
                                            relayLogger.maskBearerToken(apiKey),
                                            apiKey == null ? 0 : apiKey.trim().length());
                                    // 400 请求体格式不正确：不触发熔断，直接重路由到下一个候选
                                    if (resp.statusCode().value() == 400) {
                                        return Flux.error(new NonRetryableProviderException(400, body));
                                    }
                                    return Flux.error(new RuntimeException("Provider stream error: " + resp.statusCode() + " body: " + body));
                                });
                    }
                    log.info("Provider stream success status {} for channel={} keyId={} model={}",
                            resp.statusCode(), candidate.getChannel().getId(),
                            candidate.getChannelApiKey().getId(),
                            candidate.getChannelModel().getModelName());
                    // 仅内部客户端在流开头注入 _gateway_meta 事件，前端据此显示渠道/模型信息
                    Flux<SseEvent> metaFlux = internalClient
                            ? Flux.just(sseHandler.buildGatewayMetaEvent(candidate))
                            : Flux.empty();
                    // 关键：必须先用 ByteAccumulator 按 \n\n 切分完整 SSE 事件，
                    // 再交给 parseSseEventBlock 解析单个事件。
                    // 否则当一个 DataBuffer 包含多个事件时，parseSseEventBlock 会把
                    // 多个 data: 行合并为一个 data 字段（形如 "A\nB"），
                    // 随后 replaceModelInRawJson 调用 objectMapper.readTree 只解析第一个
                    // JSON 对象，导致后续 token 全部丢失。
                    return metaFlux.concatWith(sseHandler.extractCompleteEvents(
                                    resp.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                                            .doOnNext(buf -> firstByteArrivalMsByTraceId.putIfAbsent(traceId, System.currentTimeMillis())))
                            .map(chunk -> sseHandler.parseSseEventBlock(
                                    chunk, candidate, provider, req, traceId))
                            .flatMapSequential(Flux::fromIterable));
                });
    }

    /**
     * 读取并清空该 traceId 的首字节时间戳，换算为相对请求路由开始的时间（ms）。
     * <p>未采集到首字节（如连接失败/超时）时返回 null，落日志后该条记录不参与首字节平均统计。</p>
     */
    private Long firstByteMsSince(String traceId, long startTime) {
        Long arrival = firstByteArrivalMsByTraceId.remove(traceId);
        return arrival != null ? Math.max(0, arrival - startTime) : null;
    }

    // ========== 辅助方法 ==========

    /**
     * 解析路由到该候选时实际使用的思考强度：请求未携带时回退到关联关系配置的默认值。
     * <p>仅用于日志展示，与 {@link #buildProviderRequestBody} 的实际取值逻辑保持一致。</p>
     */
    private String resolveEffectiveReasoningEffort(InternalRequest req, RoutingCandidate candidate) {
        if (req.getReasoningEffort() != null && !req.getReasoningEffort().isEmpty()) {
            return req.getReasoningEffort();
        }
        if (candidate.getRel() == null) return null;
        return candidate.getRel().getReasoningEffort();
    }

    String buildProviderRequestBody(InternalRequest req, RoutingCandidate candidate, String provider) {
        String originalModel = req.getModel();
        String originalEffort = req.getReasoningEffort();
        req.setModel(candidate.getChannelModel().getModelName());
        try {
            if (req.getReasoningEffort() == null || req.getReasoningEffort().isEmpty()) {
                String relEffort = candidate.getRel().getReasoningEffort();
                if (relEffort != null && !relEffort.isEmpty()) {
                    req.setReasoningEffort(relEffort);
                }
            }
            if ("anthropic".equals(provider)) return messageTransformer.buildAnthropicRequest(req);
            return messageTransformer.buildOpenAiRequest(req);
        } finally {
            req.setModel(originalModel);
            req.setReasoningEffort(originalEffort);
        }
    }

    private String buildEndpoint(RoutingCandidate candidate, String provider) {
        Channel channel = candidate.getChannel();
        String baseUrl = channel.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "anthropic".equals(provider) ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1";
        }
        baseUrl = baseUrl.replaceAll("/$", "");
        if ("azure".equals(provider)) return baseUrl;
        return baseUrl + ("anthropic".equals(provider) ? "/messages" : "/chat/completions");
    }

    public Map<String, String> buildProviderHeaders(String provider, String apiKey, String authHeader) {
        Map<String, String> headers = new java.util.HashMap<>();
        String key = apiKey != null ? apiKey.trim() : "";
        if ("azure".equals(provider)) {
            headers.put("api-key", key);
        } else if ("anthropic".equals(provider)) {
            headers.put("x-api-key", key);
            headers.put("anthropic-version", "2023-06-01");
        } else {
            headers.put("Authorization", "Bearer " + key);
        }
        headers.put("Content-Type", "application/json");
        return headers;
    }

    public String resolveProvider(RoutingCandidate candidate, String defaultProvider) {
        String channelType = candidate.getChannel().getChannelType();
        return (channelType != null && !channelType.isBlank()) ? channelType : defaultProvider;
    }

    private String transformResponse(String providerBody, RoutingCandidate candidate,
                                      InternalRequest req, String provider) {
        String clientFormat = req.getClientApiFormat();
        String originalModel = req.getModel();
        try {
            JsonNode root = objectMapper.readTree(providerBody);
            if ("anthropic".equals(provider))
                return messageTransformer.transformAnthropicResponseToClient(root, clientFormat, originalModel);
            return messageTransformer.transformOpenAiResponseToClient(root, clientFormat, originalModel);
        } catch (Exception e) {
            log.warn("响应转换失败，原样返回", e);
            return providerBody;
        }
    }

    /**
     * 判断候选是否处于熔断状态，返回熔断级别（"渠道级熔断"/"模型级熔断"），未熔断返回 null。
     * <p>在路由到该候选时判断：熔断则跳过并记录到请求日志（每 API Key 一条），
     * 由路由循环按候选列表顺序逐个经过，保证请求日志完整展示跳过过程。</p>
     * <p>熔断状态查询结果带 1s 短路缓存：同一候选在短时间内（同请求重试轮次）只会查一次 DB，
     * 缓解候选数量大时的 selectCount 重复查询；熔断/探测开门延迟不超过 1 秒。</p>
     */
    private String circuitBreakScope(RoutingCandidate candidate) {
        if (localCacheService == null) {
            return queryCircuitBreakScope(candidate);
        }
        String key = candidate.getChannel().getId() + ":"
                + candidate.getChannelModel().getId() + ":"
                + candidate.getChannelApiKey().getId();
        String cached = localCacheService.get(CACHE_NS_CIRCUIT, key, CIRCUIT_CACHE_TTL,
                () -> {
                    String scope = queryCircuitBreakScope(candidate);
                    // 健康状态（null）以哨兵值缓存，保证命中
                    return scope == null ? CIRCUIT_HEALTHY_SENTINEL : scope;
                });
        return CIRCUIT_HEALTHY_SENTINEL.equals(cached) ? null : cached;
    }

    private String queryCircuitBreakScope(RoutingCandidate candidate) {
        if (circuitBreakerService.isChannelCircuitBroken(candidate.getChannel().getId())
                || circuitBreakerService.isChannelCircuitBroken(candidate.getChannel().getId(), candidate.getChannelApiKey().getId())) {
            return "渠道级熔断";
        }
        if (circuitBreakerService.isModelCircuitBroken(candidate.getChannelModel().getId(), candidate.getChannelApiKey().getId())) {
            return "模型级熔断";
        }
        return null;
    }

    /**
     * 记录一次路由请求的业务指标（可选能力，未注入 RelayMetrics 时为空操作）。
     */
    private void recordRouteMetric(String model, String channel, String result, long startTime) {
        if (relayMetrics != null) {
            relayMetrics.recordRoute(model, channel, result, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 请求进入时触发候选渠道的到期熔断探测（异步投递，不阻塞请求线程）。
     * <p>每个渠道仅触发一次（按渠道去重）；探测与请求处理并行，
     * 探测开门后对后续请求生效。</p>
     */
    private void triggerProbeForCandidates(List<RoutingCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        candidates.stream()
                .map(c -> c.getChannel().getId())
                .distinct()
                .forEach(recoveryService::triggerProbeByChannel);
    }

    private void handleFailure(RoutingCandidate candidate, InternalRequest req) {
        Long customModelId = routeResolver.resolveModelId(req.getModel());
        if (customModelId == null) return;
        try {
            circuitBreakerService.triggerCircuitBreak(customModelId,
                    candidate.getChannel().getId(),
                    candidate.getChannelApiKey().getId(),
                    candidate.getChannelModel().getId());
        } catch (Exception e) {
            // 熔断写入失败不阻断主流程：在 onErrorResume 内抛出会替换原始 Provider 错误，
            // 导致上游错误被 DB 异常掩盖（甚至把 SQL 堆栈原样返回给客户端）
            log.error("触发熔断写入失败 - channel={} key={} model={}",
                    candidate.getChannel().getName(), candidate.getChannelApiKey().getKeyName(),
                    candidate.getChannelModel().getModelName(), e);
        }
    }

    String buildFailMessage(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) return "所有候选均失败";
        String lower = errorMsg.toLowerCase();
        if (lower.contains("timeout") || lower.contains("did not observe")
                || lower.contains("read timed out") || lower.contains("connect timed out"))
            return "请求超时";
        if (errorMsg.startsWith("Provider error:")) {
            int bodyIdx = errorMsg.indexOf("body: ");
            if (bodyIdx > 0) {
                String body = errorMsg.substring(bodyIdx + 6);
                try {
                    JsonNode json = objectMapper.readTree(body);
                    if (json.has("error")) {
                        JsonNode errorNode = json.get("error");
                        if (errorNode.has("message")) {
                            String msg = errorNode.get("message").asText();
                            if (errorNode.has("type") && !errorNode.get("type").isNull()
                                    && !"null".equals(errorNode.get("type").asText()))
                                return "[" + errorNode.get("type").asText() + "] " + msg;
                            return msg;
                        }
                        if (errorNode.isTextual()) return errorNode.asText();
                    }
                } catch (Exception e) {
                    log.warn("解析 Provider 错误响应体失败，使用原始文本: body={}", body, e);
                }
                String statusPart = errorMsg.substring("Provider error:".length(), bodyIdx).trim();
                return statusPart + " " + body;
            }
            return errorMsg;
        }
        if (lower.contains("empty response") || lower.contains("treated as timeout"))
            return "请求超时";
        return errorMsg;
    }

    private int[] extractUsageFromProviderResponse(String providerBody) {
        try {
            JsonNode root = objectMapper.readTree(providerBody);
            JsonNode usage = root.get("usage");
            if (usage != null && usage.isObject()) {
                int pt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt()
                        : usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                int ct = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt()
                        : usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                int tt = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : pt + ct;
                return new int[]{pt, ct, tt};
            }
        } catch (Exception e) {
            log.debug("提取响应 token 用量失败: {}", e.getMessage());
        }
        return new int[]{0, 0, 0};
    }
}
