package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.*;
import com.myai.gateway.relay.balancer.LoadBalancer;
import com.myai.gateway.relay.balancer.LoadBalancerFactory;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalMessage;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
import com.myai.gateway.relay.transformer.registry.ProtocolTranslator;
import com.myai.gateway.relay.transformer.registry.StreamTranslateState;
import com.myai.gateway.relay.transformer.registry.TranslatorRegistry;
import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 核心中继服务
 * 负责请求的路由、重试、熔断、日志记录、格式转换
 *
 * <p>路由单元：每个 (ModelChannelRel, Channel, ChannelModel, ChannelApiKey) 四元组被视为一个独立的路由候选。
 * 同一渠道下的多个 API Key 会被展开为多个候选，相当于多个子渠道。</p>
 *
 * <p>重试策略：对每个候选会先按熔断配置中的 {@code retryCount} 进行候选内重试；
 * 全部重试失败后才会触发熔断，并将该候选从列表中移除，继续尝试下一个候选。</p>
 *
 * <p>熔断粒度：
 * <ul>
 *   <li>模型级：按 {@code (channelModelId, channelApiKeyId)} 组合熔断，只影响该 API Key 下的该模型。</li>
 *   <li>渠道级（按 API Key）：按 {@code (channelId, channelApiKeyId)} 组合熔断，
 *       该 API Key 下的所有模型均不可用。</li>
 * </ul>
 * </p>
 *
 * <p>流式上下文传递：当流式请求中途失败时，已返回的内容会被累积并作为下一个候选的上下文，
 * 使下一个候选能够基于已输出的内容继续生成，实现客户端无感切换。</p>
 */
@Service
public class RelayService {

    private static final Logger log = LoggerFactory.getLogger(RelayService.class);

    private final ChannelService channelService;
    private final ChannelApiKeyService channelApiKeyService;
    private final ApiKeyService apiKeyService;
    private final ModelService modelService;
    private final CircuitBreakerService circuitBreakerService;
    private final RequestLogService requestLogService;
    private final LoadBalancerFactory loadBalancerFactory;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final MessageTransformer messageTransformer;
    private final TranslatorRegistry translatorRegistry;
    private final StreamContentManager streamContentManager;
    private final LatencyTracker latencyTracker;
    private final PromptInjectionService promptInjectionService;

    /** 流式请求 token 用量累积器：traceId -> [promptTokens, completionTokens, totalTokens] */
    private final ConcurrentHashMap<String, int[]> streamUsageMap = new ConcurrentHashMap<>();

    /** 流式空闲超时下限（毫秒）：自适应超时低于此值时仍按此值计，确保长流有足够空闲容忍 */
    private static final long STREAM_IDLE_TIMEOUT_MS = 120_000L;

    /** 流式请求跨 chunk 翻译状态：traceId -> StreamTranslateState */
    private final ConcurrentHashMap<String, StreamTranslateState> streamTranslateStates = new ConcurrentHashMap<>();

    public RelayService(ChannelService channelService,
                        ChannelApiKeyService channelApiKeyService,
                        ApiKeyService apiKeyService,
                        ModelService modelService,
                        CircuitBreakerService circuitBreakerService,
                        RequestLogService requestLogService,
                        LoadBalancerFactory loadBalancerFactory,
                        ObjectMapper objectMapper,
                        MessageTransformer messageTransformer,
                        TranslatorRegistry translatorRegistry,
                        StreamContentManager streamContentManager,
                        LatencyTracker latencyTracker,
                        PromptInjectionService promptInjectionService) {
        this.channelService = channelService;
        this.channelApiKeyService = channelApiKeyService;
        this.apiKeyService = apiKeyService;
        this.modelService = modelService;
        this.circuitBreakerService = circuitBreakerService;
        this.requestLogService = requestLogService;
        this.loadBalancerFactory = loadBalancerFactory;
        this.objectMapper = objectMapper;
        this.messageTransformer = messageTransformer;
        this.translatorRegistry = translatorRegistry;
        this.streamContentManager = streamContentManager;
        this.latencyTracker = latencyTracker;
        this.promptInjectionService = promptInjectionService;
        // 配置 HttpClient 超时：连接超时 10s、响应超时 60s
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(java.time.Duration.ofSeconds(60));
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * OpenAI 兼容：非流式聊天补全
     */
    public Mono<String> chatCompletions(String authHeader, String requestBody) {
        String traceId = requestLogService.startTrace();
        Long gatewayApiKeyId = logOriginalRequest(traceId, authHeader, buildOpenaiHeadersJson(authHeader), requestBody);
        // 鉴权校验：无效/缺失网关 API Key 时直接拒绝，不转发到上游（避免未授权白嫖付费 API）
        if (gatewayApiKeyId == null) {
            requestLogService.logComplete(traceId, null, null, null, null, null,
                    "fail", "auth", "无效或缺失的 API Key", 0, 0);
            return Mono.just(messageTransformer.buildErrorResponse(
                    "openai", "无效或缺失的 API Key", "authentication_error", 401));
        }
        return Mono.fromCallable(() -> {
                    InternalRequest req = parseRequest(requestBody, "openai");
                    applyPromptInjections(req);
                    preprocessMediaInvalidation(req);
                    return req;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(req -> executeRelay(traceId, authHeader, gatewayApiKeyId, req, "openai", false));
    }

    public Mono<String> messages(String apiKeyHeader, String requestBody, String anthropicVersion) {
        String traceId = requestLogService.startTrace();
        Long gatewayApiKeyId = logOriginalRequest(traceId, apiKeyHeader, buildAnthropicHeadersJson(apiKeyHeader, anthropicVersion), requestBody);
        // 鉴权校验：无效/缺失网关 API Key 时直接拒绝，不转发到上游（避免未授权白嫖付费 API）
        if (gatewayApiKeyId == null) {
            requestLogService.logComplete(traceId, null, null, null, null, null,
                    "fail", "auth", "无效或缺失的 API Key", 0, 0);
            return Mono.just(messageTransformer.buildErrorResponse(
                    "anthropic", "无效或缺失的 API Key", "authentication_error", 401));
        }
        return Mono.fromCallable(() -> {
                    InternalRequest req = parseRequest(requestBody, "anthropic");
                    applyPromptInjections(req);
                    preprocessMediaInvalidation(req);
                    return req;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(req -> executeRelay(traceId, apiKeyHeader, gatewayApiKeyId, req, "anthropic", false));
    }

    /**
     * OpenAI 兼容：流式聊天补全
     *
     * @param internalClient 是否为内部客户端（Playground），true 时发送 _routing_progress/_gateway_meta 等自定义事件
     */
    public SseEmitter chatCompletionsStream(String authHeader, String requestBody, boolean internalClient) {
        // 0L = 禁用 SseEmitter 墙钟硬超时，改由 STREAM_IDLE_TIMEOUT_MS（120s 空闲超时）管控死连接。
        // 原先 300_000L (5min) 会强制完成 emitter，但推理模型/长文本生成可能超过 5 分钟，
        // 导致上游仍在吐数据时 emitter 已完成，send() 抛 IllegalStateException。
        SseEmitter emitter = new SseEmitter(0L);
        String traceId = requestLogService.startTrace();
        Long gatewayApiKeyId = logOriginalRequest(traceId, authHeader, buildOpenaiHeadersJson(authHeader), requestBody);
        // 鉴权校验：无效/缺失网关 API Key 时直接拒绝，不转发到上游（避免未授权白嫖付费 API）
        if (gatewayApiKeyId == null) {
            requestLogService.logComplete(traceId, null, null, null, null, null,
                    "fail", "auth", "无效或缺失的 API Key", 0, 0);
            sendSseError(emitter, "无效或缺失的 API Key");
            return emitter;
        }
        // 保存订阅句柄，用于在超时/完成时取消上游 Flux，防止资源泄漏与对已完成 emitter 的写入
        Disposable[] disposableRef = new Disposable[1];
        // 注册资源清理回调：无论正常完成、超时还是客户端断开，都清理 StreamContentManager 和 streamUsageMap
        emitter.onCompletion(() -> {
            cleanupStreamResources(traceId);
            if (disposableRef[0] != null && !disposableRef[0].isDisposed()) {
                disposableRef[0].dispose();
            }
        });
        emitter.onTimeout(() -> {
            log.warn("SSE stream timeout, cleaning up resources - traceId={}", traceId);
            // 由 onCompletion 统一处理 cleanupStreamResources + Disposable.dispose()
            emitter.complete();
        });
        disposableRef[0] = Mono.fromCallable(() -> {
                    InternalRequest req = parseRequest(requestBody, "openai");
                    applyPromptInjections(req);
                    preprocessMediaInvalidation(req);
                    return req;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(req -> executeStreamRelay(traceId, authHeader, gatewayApiKeyId, req, "openai", internalClient))
                // publishOn(prefetch=1)：每处理完一个事件再请求下一个，让 SSE 发送之间有空隙，
                // 确保 Tomcat flush 能真正将数据推送到 TCP 栈，避免多个事件被合并发送
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic(), 1)
                .subscribe(
                        event -> sendSseEvent(emitter, event),
                        err -> handleStreamSubscribeError(traceId, gatewayApiKeyId, emitter, err),
                        () -> {
                            cleanupStreamResources(traceId);
                            emitter.complete();
                        }
                );
        return emitter;
    }

    /**
     * Anthropic 兼容：流式消息
     *
     * @param internalClient 是否为内部客户端（Playground），true 时发送 _routing_progress/_gateway_meta 等自定义事件
     */
    public SseEmitter messagesStream(String apiKeyHeader, String requestBody, String anthropicVersion, boolean internalClient) {
        // 0L = 禁用 SseEmitter 墙钟硬超时，改由 STREAM_IDLE_TIMEOUT_MS（120s 空闲超时）管控死连接
        SseEmitter emitter = new SseEmitter(0L);
        String traceId = requestLogService.startTrace();
        Long gatewayApiKeyId = logOriginalRequest(traceId, apiKeyHeader, buildAnthropicHeadersJson(apiKeyHeader, anthropicVersion), requestBody);
        // 鉴权校验：无效/缺失网关 API Key 时直接拒绝，不转发到上游（避免未授权白嫖付费 API）
        if (gatewayApiKeyId == null) {
            requestLogService.logComplete(traceId, null, null, null, null, null,
                    "fail", "auth", "无效或缺失的 API Key", 0, 0);
            sendSseError(emitter, "无效或缺失的 API Key");
            return emitter;
        }
        // 保存订阅句柄，用于在超时/完成时取消上游 Flux，防止资源泄漏与对已完成 emitter 的写入
        Disposable[] disposableRef = new Disposable[1];
        // 注册资源清理回调：无论正常完成、超时还是客户端断开，都清理 StreamContentManager 和 streamUsageMap
        emitter.onCompletion(() -> {
            cleanupStreamResources(traceId);
            if (disposableRef[0] != null && !disposableRef[0].isDisposed()) {
                disposableRef[0].dispose();
            }
        });
        emitter.onTimeout(() -> {
            log.warn("SSE stream timeout, cleaning up resources - traceId={}", traceId);
            // 由 onCompletion 统一处理 cleanupStreamResources + Disposable.dispose()
            emitter.complete();
        });
        disposableRef[0] = Mono.fromCallable(() -> {
                    InternalRequest req = parseRequest(requestBody, "anthropic");
                    applyPromptInjections(req);
                    preprocessMediaInvalidation(req);
                    return req;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(req -> executeStreamRelay(traceId, apiKeyHeader, gatewayApiKeyId, req, "anthropic", internalClient))
                // publishOn(prefetch=1)：同 OpenAI 流式，确保事件逐个发送
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic(), 1)
                .subscribe(
                        event -> sendSseEvent(emitter, event),
                        err -> handleStreamSubscribeError(traceId, gatewayApiKeyId, emitter, err),
                        () -> {
                            cleanupStreamResources(traceId);
                            emitter.complete();
                        }
                );
        return emitter;
    }

    /**
     * 解析请求体为内部统一请求
     */
    private InternalRequest parseRequest(String requestBody, String clientFormat) throws IOException {
        JsonNode json = objectMapper.readTree(requestBody);
        InternalRequest req;
        if ("anthropic".equals(clientFormat)) {
            req = messageTransformer.parseAnthropicRequest(json);
        } else {
            req = messageTransformer.parseOpenAiRequest(json);
        }
        req.setClientApiFormat(clientFormat);
        return req;
    }

    /**
     * 执行非流式中继
     */
    private Mono<String> executeRelay(String traceId, String authHeader, Long gatewayApiKeyId,
                                     InternalRequest req, String provider, boolean retry) {
        long startTime = System.currentTimeMillis();
        String originalModel = req.getModel();
        // 一次性解析模型路由配置，避免 tryCandidates 每次递归重复查 DB
        RoutingContext ctx = resolveModelRouting(originalModel);
        List<RoutingCandidate> candidates = getAvailableCandidates(req);
        logSkippedCandidatesFromResult(traceId, gatewayApiKeyId, req, candidates, ctx);
        if (candidates.isEmpty()) {
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, originalModel, null, null,
                    "fail", "error", "没有可用的路由候选", System.currentTimeMillis() - startTime, 0);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    "没有可用的路由候选（渠道/API Key/模型都被熔断或不可用）", "api_error", 503));
        }

        return tryCandidates(traceId, new ArrayList<>(candidates), authHeader, gatewayApiKeyId, req, provider, 0, startTime, ctx, null);
    }

    /**
     * 顺序尝试候选，失败后按配置进行候选内重试；重试耗尽后触发熔断并移除候选，继续下一个
     * （包级可见，便于单元测试）
     * <p>测试兼容入口：内部解析路由上下文后委托给带 ctx 的重载。</p>
     */
    Mono<String> tryCandidates(String traceId, List<RoutingCandidate> remaining,
                                        String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                        String provider, int retryIndex, long startTime) {
        RoutingContext ctx = resolveModelRouting(req.getModel());
        return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex, startTime, ctx, null);
    }

    /**
     * 顺序尝试候选，失败后按配置进行候选内重试；重试耗尽后触发熔断并移除候选，继续下一个。
     * 携带 lastErrorMsg 用于在全部候选失败时展示具体的失败原因。
     * 复用传入的 RoutingContext，避免每次递归重复查 DB。
     */
    private Mono<String> tryCandidates(String traceId, List<RoutingCandidate> remaining,
                                        String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                        String provider, int retryIndex, long startTime,
                                        RoutingContext ctx, String lastErrorMsg) {
        if (remaining.isEmpty()) {
            String failMsg = buildFailMessage(lastErrorMsg);
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, req.getModel(), null, null,
                    "fail", "error", failMsg, System.currentTimeMillis() - startTime, retryIndex);
            log.warn("所有候选均失败，无法完成请求 - traceId={}, lastError={}", traceId, lastErrorMsg);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    failMsg, "api_error", 503));
        }

        // 使用缓存的模型路由配置，避免每次递归查 DB
        LoadBalancer balancer = loadBalancerFactory.getBalancer(ctx.strategy());
        RoutingCandidate candidate = balancer.select(remaining, ctx.modelId());
        if (candidate == null) {
            log.warn("负载均衡器返回null候选 - traceId={}", traceId);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    "没有可用的路由候选", "api_error", 503));
        }

        // 发起请求前实时检查熔断状态：若已被其他并发请求熔断，快速跳过
        if (isCandidateCircuitBroken(candidate)) {
            log.info("已熔断跳过 - traceId={} retryIndex={} channel={} model={} key={} (已被熔断，跳过该候选)",
                    traceId, retryIndex,
                    candidate.getChannel().getName(),
                    candidate.getChannelModel().getModelName(),
                    candidate.getChannelApiKey().getKeyName());
            logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                    "已熔断跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);
            remaining.remove(candidate);
            return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex + 1, startTime, ctx, lastErrorMsg);
        }

        // 请求含媒体类型但候选不支持 -> 按关联顺序跳过，记录跳过原因后继续尝试下一个候选
        if (skipIfMediaTypeUnsupported(traceId, gatewayApiKeyId, candidate, req, retryIndex)) {
            remaining.remove(candidate);
            return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex + 1, startTime, ctx, lastErrorMsg);
        }

        log.info("路由决策 - traceId={} retryIndex={} 选中候选: channel={} model={} key={} (剩余{}个候选)",
                traceId, retryIndex,
                candidate.getChannel().getName(),
                candidate.getChannelModel().getModelName(),
                candidate.getChannelApiKey().getKeyName(),
                remaining.size());

        logPhase(traceId, gatewayApiKeyId, candidate, req, "start",
                "路由到 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);

        int maxAttempts = ctx.maxAttempts();
        log.info("开始调用候选 - traceId={} maxAttempts={} (retryCount={})",
                traceId, maxAttempts, ctx.retryCount());

        // provider 由渠道类型决定，而非入口 API 格式：Anthropic 客户端可路由到 OpenAI 兼容渠道
        String actualProvider = resolveProvider(candidate, provider);
        return invokeCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, req, candidate, actualProvider, retryIndex, 1, maxAttempts)
                .flatMap(body -> {
                    balancer.markSuccess(candidate);
                    // 更新渠道模型最后使用时间（用于轮询 LRU 排序）
                    modelService.updateChannelModelLastUsed(candidate.getChannelModel().getId());
                    // 更新网关 API Key 最后使用时间
                    updateGatewayApiKeyLastUsed(authHeader);
                    // 非流式：响应时间 ≈ TTFT，记录到自适应超时统计
                    latencyTracker.record(candidate.getChannel().getId(), candidate.getChannelModel().getId(),
                            System.currentTimeMillis() - startTime);
                    String transformed = transformResponse(body, candidate, req, actualProvider, false);
                    int[] usage = extractUsageFromProviderResponse(body);
                    requestLogService.logComplete(traceId, candidate.getChannelApiKey().getKeyName(), gatewayApiKeyId,
                            req.getModel(), candidate.getChannelModel().getModelName(),
                            candidate.getChannel().getName(),
                            "success", "success", "请求成功",
                            System.currentTimeMillis() - startTime, retryIndex,
                            usage[0], usage[1], usage[2]);
                    log.info("候选请求成功 - traceId={} channel={} model={} key={}",
                            traceId, candidate.getChannel().getName(),
                            candidate.getChannelModel().getModelName(),
                            candidate.getChannelApiKey().getKeyName());
                    return Mono.just(transformed);
                })
                .onErrorResume(err -> {
                    // 400 不触发熔断，不移除消息也不修复请求体，直接重路由到下一个候选
                    if (err instanceof NonRetryableProviderException) {
                        log.warn("候选返回400，不触发熔断，直接重路由 channel={} model={} key={}",
                                candidate.getChannel().getName(),
                                candidate.getChannelModel().getModelName(),
                                candidate.getChannelApiKey().getKeyName());
                        remaining.remove(candidate);
                        log.info("候选失败已移除，准备重路由 - traceId={} 剩余候选数={}", traceId, remaining.size());
                        return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex + 1, startTime, ctx, err.getMessage());
                    }
                    // 非 400 错误（超时、5xx、429 等）：触发熔断，重路由到下一候选
                    log.warn("候选失败（重试耗尽）channel={} key={} model={} (已重试{}次): {}",
                            candidate.getChannel().getName(),
                            candidate.getChannelApiKey().getKeyName(),
                            candidate.getChannelModel().getModelName(),
                            maxAttempts - 1,
                            err.getMessage());
                    handleFailure(candidate, req);
                    balancer.markFailed(candidate);
                    remaining.remove(candidate);
                    log.info("候选失败已移除，准备重路由 - traceId={} 剩余候选数={}", traceId, remaining.size());
                    for (int i = 0; i < remaining.size(); i++) {
                        RoutingCandidate c = remaining.get(i);
                        log.info("  剩余候选[{}]: channel={} model={} key={}", i,
                                c.getChannel().getName(), c.getChannelModel().getModelName(), c.getChannelApiKey().getKeyName());
                    }
                    return tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex + 1, startTime, ctx, err.getMessage());
                });
    }

    /**
     * 对单个候选按重试次数进行调用，每次失败立即重试同一候选
     */
    private Mono<String> invokeCandidateWithRetries(String traceId, String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                                      RoutingCandidate candidate, String provider,
                                                      int retryIndex, int attempt, int maxAttempts) {
        long attemptStartTime = System.currentTimeMillis();
        long timeoutMs = latencyTracker.getTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId());
        return callProviderNonStream(authHeader, req, candidate, provider)
                .flatMap(body -> {
                    // 空响应立即失败（不等 timeout），视为与超时同等级错误，触发重试/重路由
                    if (body == null || body.isBlank()) {
                        log.warn("候选返回空响应 - traceId={} attempt={}/{} channel={} model={} key={} (视为超时错误，触发重路由)",
                                traceId, attempt, maxAttempts,
                                candidate.getChannel().getName(),
                                candidate.getChannelApiKey().getKeyName(),
                                candidate.getChannelModel().getModelName());
                        return Mono.error(new RuntimeException("Provider returned empty response, treated as timeout"));
                    }
                    return Mono.just(body);
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .doOnError(err -> {
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    latencyTracker.recordTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId(), timeoutMs);
                    log.warn("候选尝试 {}/{} 失败 channel={} key={} model={} (耗时 {}ms): {}",
                            attempt, maxAttempts,
                            candidate.getChannel().getName(),
                            candidate.getChannelApiKey().getKeyName(),
                            candidate.getChannelModel().getModelName(),
                            attemptDurationMs,
                            err.getMessage());
                })
                .onErrorResume(err -> {
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    // 400 不修复请求体，不重试同一候选，直接抛给上层重路由
                    if (err instanceof NonRetryableProviderException nre && nre.getHttpStatus() == 400) {
                        return Mono.error(err);
                    }
                    if (attempt < maxAttempts) {
                        // 保留完整错误信息：列表视图依赖 CSS 截断，详情弹框依赖 .dialog-pre 的 max-height + 滚动
                        String retryErrDetail2 = err.getMessage() != null ? err.getMessage() : "";
                        logPhase(traceId, gatewayApiKeyId, candidate, req, "retry",
                                "第 " + attempt + " 次失败: " + retryErrDetail2 + "，准备第 " + (attempt + 1) + " 次重试", retryIndex, attemptDurationMs);
                        return invokeCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, req, candidate, provider,
                                retryIndex, attempt + 1, maxAttempts);
                    }
                    return Mono.error(err);
                });
    }

    /**
     * 执行流式中继
     *
     * @param internalClient 是否为内部客户端，true 时发送路由进度等自定义 SSE 事件
     */
    private Flux<SseEvent> executeStreamRelay(String traceId, String authHeader, Long gatewayApiKeyId,
                                            InternalRequest req, String provider, boolean internalClient) {
        long startTime = System.currentTimeMillis();
        // 一次性解析模型路由配置，避免 tryStreamCandidates 每次递归重复查 DB
        RoutingContext ctx = resolveModelRouting(req.getModel());
        List<RoutingCandidate> candidates = getAvailableCandidates(req);
        logSkippedCandidatesFromResult(traceId, gatewayApiKeyId, req, candidates, ctx);
        if (candidates.isEmpty()) {
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, req.getModel(), null, null,
                    "fail", "error", "没有可用的路由候选", System.currentTimeMillis() - startTime, 0);
            return Flux.error(new RuntimeException("没有可用的路由候选"));
        }

        return tryStreamCandidates(traceId, new ArrayList<>(candidates), authHeader, gatewayApiKeyId, req, provider, 0, startTime, internalClient, ctx, null);
    }

    /**
     * 顺序尝试流式候选，失败后按配置进行候选内重试；重试耗尽后触发熔断并移除候选，继续下一个
     * （包级可见，便于单元测试）
     *
     * @param internalClient 是否为内部客户端，true 时发送 _routing_progress 等自定义 SSE 事件
     */
    Flux<SseEvent> tryStreamCandidates(String traceId, List<RoutingCandidate> remaining,
                                                String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                                String provider, int retryIndex, long startTime,
                                                boolean internalClient) {
        RoutingContext ctx = resolveModelRouting(req.getModel());
        return tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex, startTime, internalClient, ctx, null);
    }

    /**
     * 顺序尝试流式候选，失败后按配置进行候选内重试；重试耗尽后触发熔断并移除候选，继续下一个。
     * 携带 lastErrorMsg 用于在全部候选失败时展示具体的失败原因。
     * 复用传入的 RoutingContext，避免每次递归重复查 DB。
     */
    private Flux<SseEvent> tryStreamCandidates(String traceId, List<RoutingCandidate> remaining,
                                                String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                                String provider, int retryIndex, long startTime,
                                                boolean internalClient, RoutingContext ctx, String lastErrorMsg) {
        if (remaining.isEmpty()) {
            // 清理流式内容累积
            streamContentManager.clearContent(traceId);
            String extraInfo = req.isContextRetry() ? "（已拼接上下文但无剩余候选）" : "";
            String failMsg = buildFailMessage(lastErrorMsg);
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, req.getModel(), null, null,
                    "fail", "error", failMsg, System.currentTimeMillis() - startTime, retryIndex);
            log.warn("所有流式候选均失败 - traceId={}{}, lastError={}", traceId, extraInfo, lastErrorMsg);
            return Flux.error(new RuntimeException(failMsg));
        }

        // 使用缓存的模型路由配置，避免每次递归查 DB
        LoadBalancer balancer = loadBalancerFactory.getBalancer(ctx.strategy());
        RoutingCandidate candidate = balancer.select(remaining, ctx.modelId());
        if (candidate == null) {
            log.warn("流式负载均衡器返回null候选 - traceId={}", traceId);
            return Flux.error(new RuntimeException("没有可用的路由候选"));
        }

        // 发起请求前实时检查熔断状态：若已被其他并发请求熔断，快速跳过
        if (isCandidateCircuitBroken(candidate)) {
            log.info("已熔断跳过 - traceId={} retryIndex={} channel={} model={} key={} (已被熔断，跳过该流式候选)",
                    traceId, retryIndex,
                    candidate.getChannel().getName(),
                    candidate.getChannelModel().getModelName(),
                    candidate.getChannelApiKey().getKeyName());
            logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                    "已熔断跳过 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);
            remaining.remove(candidate);
            return tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex + 1, startTime, internalClient, ctx, lastErrorMsg);
        }

        // 请求含媒体类型但候选不支持 -> 按关联顺序跳过，记录跳过原因后继续尝试下一个候选
        if (skipIfMediaTypeUnsupported(traceId, gatewayApiKeyId, candidate, req, retryIndex)) {
            remaining.remove(candidate);
            return tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex + 1, startTime, internalClient, ctx, lastErrorMsg);
        }

        log.info("流式路由决策 - traceId={} retryIndex={} 选中候选: channel={} model={} key={} (剩余{}个候选){}",
                traceId, retryIndex,
                candidate.getChannel().getName(),
                candidate.getChannelModel().getModelName(),
                candidate.getChannelApiKey().getKeyName(),
                remaining.size(),
                req.isContextRetry() ? " [已拼接]" : "");

        logPhase(traceId, gatewayApiKeyId, candidate, req, "start",
                "流式路由到 " + candidate.getChannel().getName() + "/" + candidate.getChannelApiKey().getKeyName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);

        int maxAttempts = ctx.maxAttempts();
        // provider 由渠道类型决定，而非入口 API 格式：Anthropic 客户端可路由到 OpenAI 兼容渠道
        String actualProvider = resolveProvider(candidate, provider);
        // 仅内部客户端发送路由进度事件
        Flux<SseEvent> routingStart = internalClient
                ? Flux.just(new SseEvent(null, buildRoutingProgressJson("trying", candidate, retryIndex, null)))
                : Flux.empty();
        return Flux.concat(routingStart,
                invokeStreamCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, req, candidate, actualProvider, retryIndex, 1, maxAttempts, internalClient))
                .doOnNext(event -> {
                    int[] usage = extractUsageFromSseData(event.data());
                    if (usage != null) {
                        streamUsageMap.put(traceId, usage);
                    }
                })
                .doOnComplete(() -> {
                    int[] usage = streamUsageMap.remove(traceId);
                    int pt = usage != null ? usage[0] : 0;
                    int ct = usage != null ? usage[1] : 0;
                    int tt = usage != null ? usage[2] : 0;
                    String resultMsg = "流式请求成功";
                    // 降级：provider 未返回 usage 时，从累积内容做粗略估算，避免仪表盘展示 0 tokens
                    if (pt == 0 && ct == 0 && tt == 0) {
                        String content = streamContentManager.getContent(traceId);
                        if (content != null && !content.isEmpty()) {
                            // 混合中英文场景下粗略估算：约 2 字符 / token
                            ct = Math.max(1, content.length() / 2);
                            tt = ct;
                            resultMsg = "流式请求成功（用量为估算值）";
                            log.info("流式 token 用量降级估算 - traceId={} 内容长度={} 估算completionTokens={}",
                                    traceId, content.length(), ct);
                        }
                    }
                    // 标记成功并更新渠道模型最后使用时间（用于轮询 LRU 排序）
                    balancer.markSuccess(candidate);
                    modelService.updateChannelModelLastUsed(candidate.getChannelModel().getId());
                    // 更新网关 API Key 最后使用时间
                    updateGatewayApiKeyLastUsed(authHeader);
                    // 记录流式完成的总响应时间（从开始路由到流结束），而非 TTFT
                    latencyTracker.record(candidate.getChannel().getId(), candidate.getChannelModel().getId(),
                            System.currentTimeMillis() - startTime);
                    // 清理流式内容累积
                    streamContentManager.clearContent(traceId);
                    requestLogService.logComplete(traceId, candidate.getChannelApiKey().getKeyName(), gatewayApiKeyId,
                            req.getModel(), candidate.getChannelModel().getModelName(),
                            candidate.getChannel().getName(),
                            "success", "success", resultMsg,
                            System.currentTimeMillis() - startTime, retryIndex,
                            pt, ct, tt);
                    log.info("流式候选调用完成 - traceId={} channel={} model={} key={}",
                            traceId, candidate.getChannel().getName(),
                            candidate.getChannelModel().getModelName(),
                            candidate.getChannelApiKey().getKeyName());
                })
                .switchIfEmpty(Flux.defer(() -> {
                    log.warn("流式候选返回空响应（无SSE事件）- traceId={} channel={} model={} key={}",
                            traceId, candidate.getChannel().getName(),
                            candidate.getChannelModel().getModelName(),
                            candidate.getChannelApiKey().getKeyName());
                    return Flux.error(new RuntimeException("流式候选返回空响应"));
                }))
                .onErrorResume(err -> {
                    // 400 不触发熔断，不移除消息也不修复请求体，直接重路由到下一个候选
                    if (err instanceof NonRetryableProviderException) {
                        log.warn("流式候选返回400，不触发熔断，直接重路由 channel={} model={} key={}",
                                candidate.getChannel().getName(),
                                candidate.getChannelModel().getModelName(),
                                candidate.getChannelApiKey().getKeyName());
                        streamContentManager.clearContent(traceId);
                        remaining.remove(candidate);
                        return Flux.concat(
                                tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, req, provider, retryIndex + 1, startTime, internalClient, ctx, err.getMessage()));
                    }
                    // 非 400 错误（超时、5xx、429 等）：触发熔断，重路由到下一候选
                    log.warn("流式候选失败（重试耗尽）channel={} key={} model={} (已重试{}次): {}",
                            candidate.getChannel().getName(),
                            candidate.getChannelApiKey().getKeyName(),
                            candidate.getChannelModel().getModelName(),
                            maxAttempts - 1,
                            err.getMessage());

                    // 获取已累积的流式内容，作为下一个候选的上下文
                    String accumulatedContent = streamContentManager.getAndClearContent(traceId);
                    InternalRequest contextReq = req;
                    if (accumulatedContent != null && !accumulatedContent.isEmpty()) {
                        contextReq = buildRequestWithContext(req, accumulatedContent);
                        log.warn("请求路径：中途失败 - traceId={} 失败候选=[{}]{} 已累积内容长度={} 拼接后将重试下一个候选",
                                traceId,
                                candidate.getChannel().getName(),
                                candidate.getChannelModel().getModelName(),
                                accumulatedContent.length());
                    } else {
                        log.info("无可累积的流式内容，直接切换候选 - traceId={}", traceId);
                    }

                    handleFailure(candidate, contextReq);
                    balancer.markFailed(candidate);
                    remaining.remove(candidate);
                    log.info("流式候选失败已移除，准备重路由 - traceId={} 剩余候选数={}", traceId, remaining.size());
                    for (int i = 0; i < remaining.size(); i++) {
                        RoutingCandidate c = remaining.get(i);
                        log.info("  剩余候选[{}]: channel={} model={} key={}", i,
                                c.getChannel().getName(), c.getChannelModel().getModelName(), c.getChannelApiKey().getKeyName());
                    }
                    // 仅内部客户端发送路由切换事件
                    Flux<SseEvent> routingSwitch = Flux.empty();
                    if (internalClient) {
                        String failReason = err.getMessage() != null ? err.getMessage() : "unknown";
                        routingSwitch = Flux.just(new SseEvent(null, buildRoutingProgressJson(
                                "switching", candidate, retryIndex + 1, failReason)));
                    }
                    return Flux.concat(routingSwitch,
                            tryStreamCandidates(traceId, remaining, authHeader, gatewayApiKeyId, contextReq, provider, retryIndex + 1, startTime, internalClient, ctx, err.getMessage()));
                });
    }

    /**
     * 对单个流式候选按重试次数进行调用，每次失败立即重试同一候选
     *
     * @param internalClient 是否为内部客户端，true 时发送 _routing_progress 重试事件
     */
    private Flux<SseEvent> invokeStreamCandidateWithRetries(String traceId, String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                                              RoutingCandidate candidate, String provider,
                                                              int retryIndex, int attempt, int maxAttempts,
                                                              boolean internalClient) {
        long attemptStartTime = System.currentTimeMillis();
        long timeoutMs = latencyTracker.getTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId());
        log.info("开始调用流式候选 - traceId={} attempt={}/{} channel={} model={} key={} timeout={}ms",
                traceId, attempt, maxAttempts,
                candidate.getChannel().getName(),
                candidate.getChannelModel().getModelName(),
                candidate.getChannelApiKey().getKeyName(),
                timeoutMs);
        return callProviderStream(authHeader, req, candidate, provider, internalClient, traceId)
                // 流式使用「空闲超时」而非全局超时：每收到一个 chunk 计时器重置，
                // 只有持续无输出的卡死连接才会触发，避免误杀持续出 chunk 的长响应（如长文生成、推理模型）。
                // idleTimeout 取 Math.max(自适应超时, 120s)，保证长流至少有 2 分钟空闲容忍。
                .timeout(Duration.ofMillis(Math.max(timeoutMs, STREAM_IDLE_TIMEOUT_MS)),
                        Flux.error(new RuntimeException("Stream idle timeout")))
                .doOnError(err -> {
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    latencyTracker.recordTimeout(candidate.getChannel().getId(), candidate.getChannelModel().getId(), timeoutMs);
                    log.warn("流式候选尝试 {}/{} 失败 channel={} key={} model={} (耗时 {}ms): {}",
                            attempt, maxAttempts,
                            candidate.getChannel().getName(),
                            candidate.getChannelApiKey().getKeyName(),
                            candidate.getChannelModel().getModelName(),
                            attemptDurationMs,
                            err.getMessage());
                })
                .onErrorResume(err -> {
                    long attemptDurationMs = System.currentTimeMillis() - attemptStartTime;
                    // 400 不修复请求体，不重试同一候选，直接抛给上层重路由
                    if (err instanceof NonRetryableProviderException nre && nre.getHttpStatus() == 400) {
                        return Flux.error(err);
                    }
                    if (attempt < maxAttempts) {
                        // 获取已累积的流式内容，携带到重试请求中
                        InternalRequest retryReq = req;
                        String accumulatedContent = streamContentManager.getContent(traceId);
                        if (accumulatedContent != null && !accumulatedContent.isEmpty()) {
                            retryReq = buildRequestWithContext(retryReq, accumulatedContent);
                            log.warn("同一候选重试携带已累积内容 - traceId={} attempt={}/{} 累积长度={}",
                                    traceId, attempt, maxAttempts, accumulatedContent.length());
                        }
                        // 保留完整错误信息：列表视图依赖 CSS 截断，详情弹框依赖 .dialog-pre 的 max-height + 滚动
                            String streamRetryErrDetail2 = err.getMessage() != null ? err.getMessage() : "";
                            logPhase(traceId, gatewayApiKeyId, candidate, retryReq, "retry",
                                    "第 " + attempt + " 次失败: " + streamRetryErrDetail2 + "，准备第 " + (attempt + 1) + " 次重试", retryIndex, attemptDurationMs);
                        // 仅内部客户端发送路由进度事件：候选内重试
                        Flux<SseEvent> routingRetry = internalClient
                                ? Flux.just(new SseEvent(null, buildRoutingProgressJson(
                                        "retrying", candidate, retryIndex,
                                        "第" + attempt + "次失败，第" + (attempt + 1) + "次重试")))
                                : Flux.empty();
                        return Flux.concat(routingRetry,
                                invokeStreamCandidateWithRetries(traceId, authHeader, gatewayApiKeyId, retryReq, candidate, provider,
                                        retryIndex, attempt + 1, maxAttempts, internalClient));
                    }
                    return Flux.error(err);
                });
    }

    /**
     * 调用上游非流式接口
     * （包级可见，便于单元测试）
     */
    Mono<String> callProviderNonStream(String authHeader, InternalRequest req,
                                        RoutingCandidate candidate, String provider) {
        String endpoint = buildEndpoint(candidate, provider);
        String apiKey = candidate.getChannelApiKey().getApiKey();
        Map<String, String> headers = buildProviderHeaders(provider, apiKey, authHeader);
        String providerReqBody = buildProviderRequestBody(req, candidate, provider);
        log.debug("非流式调用上游: endpoint={}, provider={}, channel={}, keyId={}, apiKeyMasked={}",
                endpoint, provider, candidate.getChannel().getName(),
                candidate.getChannelApiKey().getId(), maskBearerToken(apiKey));

        return webClient.post()
                .uri(endpoint)
                .headers(h -> headers.forEach(h::add))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(providerReqBody))
                .exchangeToMono(resp -> {
                    if (resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class).flatMap(Mono::just);
                    }
                    return resp.bodyToMono(String.class).flatMap(body -> {
                        log.warn("Provider returned error status {} for channel={} keyId={} keyMasked={} keyLen={}",
                                resp.statusCode(), candidate.getChannel().getId(),
                                candidate.getChannelApiKey().getId(),
                                maskBearerToken(apiKey),
                                apiKey == null ? 0 : apiKey.trim().length());
                        // 400 请求体格式不正确：修复请求后再重试
                        int status = resp.statusCode().value();
                        if (status == 400) {
                            return Mono.error(new NonRetryableProviderException(status, body));
                        }
                        return Mono.error(new RuntimeException("Provider error: " + resp.statusCode() + " body: " + body));
                    });
                });
    }

    /**
     * 调用上游流式接口
     * （包级可见，便于单元测试）
     *
     * @param internalClient 是否为内部客户端，true 时在流开头注入 _gateway_meta 事件
     */
    Flux<SseEvent> callProviderStream(String authHeader, InternalRequest req,
                                       RoutingCandidate candidate, String provider,
                                       boolean internalClient) {
        return callProviderStream(authHeader, req, candidate, provider, internalClient, null);
    }

    /**
     * 调用上游流式接口
     *
     * @param internalClient 是否为内部客户端，true 时在流开头注入 _gateway_meta 事件
     * @param traceId        链路追踪ID，不为null时启用内容累积
     */
    Flux<SseEvent> callProviderStream(String authHeader, InternalRequest req,
                                       RoutingCandidate candidate, String provider,
                                       boolean internalClient, String traceId) {
        String endpoint = buildEndpoint(candidate, provider);
        String apiKey = candidate.getChannelApiKey().getApiKey();
        Map<String, String> headers = buildProviderHeaders(provider, apiKey, authHeader);
        String providerReqBody = buildProviderRequestBody(req, candidate, provider);
        log.debug("流式调用上游: endpoint={}, provider={}, channel={}, keyId={}, apiKeyMasked={}",
                endpoint, provider, candidate.getChannel().getName(),
                candidate.getChannelApiKey().getId(), maskBearerToken(apiKey));

        return webClient.post()
                .uri(endpoint)
                .headers(h -> headers.forEach(h::add))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(providerReqBody))
                .exchangeToFlux(resp -> {
                    if (!resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class)
                                .flatMapMany(body -> {
                                    log.warn("Provider stream error status {} for channel={} keyId={} keyMasked={} keyLen={}",
                                            resp.statusCode(), candidate.getChannel().getId(),
                                            candidate.getChannelApiKey().getId(),
                                            maskBearerToken(apiKey),
                                            apiKey == null ? 0 : apiKey.trim().length());
                                    // 400 请求体格式不正确：修复请求后再重试
                                    if (resp.statusCode().value() == 400) {
                                        return Flux.error(new NonRetryableProviderException(400, body));
                                    }
                                    return Flux.error(new RuntimeException("Provider stream error: " + resp.statusCode() + " body: " + body));
                                });
                    }
                    log.info("Provider stream success status {} for channel={} keyId={} model={}",
                            resp.statusCode(), candidate.getChannel().getId(), candidate.getChannelApiKey().getId(),
                            candidate.getChannelModel().getModelName());
                    // 仅内部客户端在流开头注入 _gateway_meta 事件，前端据此显示渠道/模型信息
                    Flux<SseEvent> metaFlux = internalClient
                            ? Flux.just(buildGatewayMetaEvent(candidate))
                            : Flux.empty();
                    return metaFlux.concatWith(extractCompleteEvents(resp.bodyToFlux(DataBuffer.class))
                            .concatMap(block -> Flux.fromIterable(parseSseEventBlock(block, candidate, provider, req, traceId))));
                });
    }

    /**
     * 渠道模型快速测试 — 走网关中继而非直接构造 HTTP 请求
     * <p>
     * 复用 RelayService 的 WebClient、请求头构建逻辑和错误处理，
     * 与 Playground 的 "/admin/api/chat" 走相同的技术栈，
     * 确保测试结果与真实请求一致。
     *
     * @param channel      渠道
     * @param channelModel 渠道模型
     * @param apiKey       渠道 API Key
     * @param message      测试消息
     * @return 上游原始响应体
     */
    public String testChannelModel(Channel channel, ChannelModel channelModel, ChannelApiKey apiKey, String message) {
        String provider = channel.getChannelType();
        // 构建端点（与 buildEndpoint 规则一致：azure 用完整 deployment 路径，不追加 path）
        String baseUrl = channel.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "anthropic".equals(provider) ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1";
        }
        baseUrl = baseUrl.replaceAll("/$", "");
        String endpoint = "azure".equals(provider)
                ? baseUrl
                : baseUrl + ("anthropic".equals(provider) ? "/messages" : "/chat/completions");

        // 构建请求头（复用 buildProviderHeaders，已含 Content-Type）
        Map<String, String> headers = buildProviderHeaders(provider, apiKey.getApiKey(), null);

        // 构建请求体：使用 ObjectMapper 构造 JSON，避免字符串拼接在 message 含特殊字符（引号、反斜杠、换行）时破坏 JSON 格式
        String requestBody;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode reqNode = objectMapper.createObjectNode();
            reqNode.put("model", channelModel.getModelName());
            reqNode.put("max_tokens", 100);
            com.fasterxml.jackson.databind.node.ArrayNode messages = reqNode.putArray("messages");
            com.fasterxml.jackson.databind.node.ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", message);
            requestBody = objectMapper.writeValueAsString(reqNode);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("构建测试请求体失败", e);
        }

        log.info("渠道模型测试: channel={}, model={}, key={}, endpoint={}",
                channel.getName(), channelModel.getModelName(), apiKey.getKeyName(), endpoint);

        try {
            return webClient.post()
                    .uri(endpoint)
                    .headers(h -> headers.forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(requestBody))
                    .exchangeToMono(resp -> resp.bodyToMono(String.class)
                            .flatMap(body -> {
                                if (resp.statusCode().is2xxSuccessful()) {
                                    return Mono.just(body);
                                }
                                log.warn("渠道模型测试失败: status={}, channel={}, model={}, key={}",
                                        resp.statusCode(), channel.getName(), channelModel.getModelName(), apiKey.getKeyName());
                                return Mono.error(new RuntimeException("渠道测试失败: " + resp.statusCode() + " body: " + body));
                            }))
                    .block(java.time.Duration.ofSeconds(30));
        } catch (Exception e) {
            log.error("渠道模型测试异常: channel={}, model={}: {}",
                    channel.getName(), channelModel.getModelName(), e.getMessage());
            throw e;
        }
    }

    /**
     * 构建 _gateway_meta SSE 事件，包含渠道类型、渠道名、模型名等信息
     */
    private SseEvent buildGatewayMetaEvent(RoutingCandidate candidate) {
        ObjectNode gatewayMeta = objectMapper.createObjectNode();
        gatewayMeta.put("_gateway_meta", true);
        gatewayMeta.put("channel_type", candidate.getChannel().getChannelType());
        gatewayMeta.put("channel", candidate.getChannel().getName());
        gatewayMeta.put("channel_model", candidate.getChannelModel().getModelName());
        return new SseEvent(null, gatewayMeta.toString());
    }

    /**
     * 构建上游请求体：将内部模型名替换为渠道模型名
     */
    private String buildProviderRequestBody(InternalRequest req, RoutingCandidate candidate, String provider) {
        String originalModel = req.getModel();
        String originalEffort = req.getReasoningEffort();
        req.setModel(candidate.getChannelModel().getModelName());
        try {
            // 如果请求没有指定 reasoning_effort，使用关联上配置的默认值
            if (req.getReasoningEffort() == null || req.getReasoningEffort().isEmpty()) {
                String relEffort = candidate.getRel().getReasoningEffort();
                if (relEffort != null && !relEffort.isEmpty()) {
                    req.setReasoningEffort(relEffort);
                }
            }
            if ("anthropic".equals(provider)) {
                return messageTransformer.buildAnthropicRequest(req);
            }
            return messageTransformer.buildOpenAiRequest(req);
        } finally {
            req.setModel(originalModel);
            req.setReasoningEffort(originalEffort);
        }
    }

    /**
     * 构建带有历史上下文的新内部请求
     * 将前一个候选已输出的流式内容作为 assistant 消息添加到消息列表末尾，
     * 使下一个候选能够基于已输出的内容继续生成，实现客户端无感切换
     *
     * @param originalReq        原始请求
     * @param accumulatedContent 前一个候选已输出的文本内容
     * @return 新的内部请求（包含历史上下文）
     */
    InternalRequest buildRequestWithContext(InternalRequest originalReq, String accumulatedContent) {
        InternalRequest contextReq = new InternalRequest();
        contextReq.setModel(originalReq.getModel());
        contextReq.setStream(originalReq.isStream());
        contextReq.setMaxTokens(originalReq.getMaxTokens());
        contextReq.setTemperature(originalReq.getTemperature());
        contextReq.setTopP(originalReq.getTopP());
        contextReq.setStop(originalReq.getStop());
        contextReq.setTools(originalReq.getTools());
        contextReq.setToolChoice(originalReq.getToolChoice());
        contextReq.setSystemPrompt(originalReq.getSystemPrompt());
        contextReq.setOriginalRequestJson(originalReq.getOriginalRequestJson());
        contextReq.setClientApiFormat(originalReq.getClientApiFormat());
        contextReq.setExtraParams(originalReq.getExtraParams());
        contextReq.setReasoningEffort(originalReq.getReasoningEffort());

        // 标记为上下文重试请求（中途失败后拼接）
        contextReq.setContextRetry(true);

        // 复制原始消息列表
        List<InternalMessage> newMessages = new ArrayList<>();
        if (originalReq.getMessages() != null) {
            for (InternalMessage msg : originalReq.getMessages()) {
                InternalMessage copy = new InternalMessage(msg.getRole(), msg.getContent());
                copy.setContentParts(msg.getContentParts());
                copy.setToolCalls(msg.getToolCalls());
                copy.setToolCallId(msg.getToolCallId());
                copy.setName(msg.getName());
                copy.setReasoningContent(msg.getReasoningContent());
                newMessages.add(copy);
            }
        }

        // 追加前一个候选已输出的内容作为 assistant 消息
        newMessages.add(new InternalMessage("assistant", accumulatedContent));

        contextReq.setMessages(newMessages);
        log.debug("构建带上下文请求完成 - 原始消息数={}, 添加assistant消息长度={}",
                originalReq.getMessages() != null ? originalReq.getMessages().size() : 0,
                accumulatedContent.length());
        return contextReq;
    }

    /**
     * 转换上游响应为客户端格式
     */
    private String transformResponse(String providerBody, RoutingCandidate candidate,
                                      InternalRequest req, String provider, boolean stream) {
        String clientFormat = req.getClientApiFormat();
        String originalModel = req.getModel();
        try {
            JsonNode root = objectMapper.readTree(providerBody);
            if ("anthropic".equals(provider)) {
                return messageTransformer.transformAnthropicResponseToClient(root, clientFormat, originalModel);
            }
            return messageTransformer.transformOpenAiResponseToClient(root, clientFormat, originalModel);
        } catch (IOException e) {
            log.warn("响应转换失败，原样返回", e);
            return providerBody;
        }
    }

    /**
     * 从 DataBuffer 流中提取完整的 SSE 事件块（按双换行分割）
     */
    private Flux<String> extractCompleteEvents(Flux<DataBuffer> buffers) {
        ByteAccumulator acc = new ByteAccumulator();
        return buffers.concatMap(buf -> {
            byte[] chunk = new byte[buf.readableByteCount()];
            buf.read(chunk);
            DataBufferUtils.release(buf);
            acc.append(chunk);
            List<String> events = acc.extractCompleteEvents();
            return Flux.fromIterable(events);
        });
    }

    /**
     * SSE 字节累积器
     * <p>
     * 累积原始字节，仅在提取完整 SSE 事件（按 {@code \n\n} 分隔）时解码为 String。
     * 避免因 DataBuffer 跨 chunk 分割 UTF-8 多字节字符导致乱码（如"需"的 3 字节被拆分后各自解码成 {@code \uFFFD}）。
     * </p>
     */
    private static class ByteAccumulator {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public void append(byte[] bytes) {
            try {
                buffer.write(bytes);
            } catch (IOException e) {
                throw new RuntimeException("Failed to accumulate bytes", e);
            }
        }

        public List<String> extractCompleteEvents() {
            byte[] allBytes = buffer.toByteArray();
            byte[] delimiter = "\n\n".getBytes(StandardCharsets.UTF_8);

            List<String> events = new ArrayList<>();
            int searchStart = 0;

            while (true) {
                int delimiterPos = indexOf(allBytes, delimiter, searchStart);
                if (delimiterPos < 0) break;

                // 解码完整事件字节（从 searchStart 到 delimiterPos）为 String
                int eventLength = delimiterPos - searchStart;
                if (eventLength > 0) {
                    String event = new String(allBytes, searchStart, eventLength, StandardCharsets.UTF_8);
                    events.add(event);
                }

                searchStart = delimiterPos + delimiter.length;
            }

            // 保留剩余字节（不完整事件数据）到 buffer 中，等待后续数据到达
            buffer.reset();
            if (searchStart < allBytes.length) {
                buffer.write(allBytes, searchStart, allBytes.length - searchStart);
            }

            return events;
        }

        /**
         * 在字节数组中查找子串的起始位置
         */
        private static int indexOf(byte[] data, byte[] pattern, int start) {
            for (int i = start; i <= data.length - pattern.length; i++) {
                boolean found = true;
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) {
                        found = false;
                        break;
                    }
                }
                if (found) return i;
            }
            return -1;
        }
    }

    /**
     * 解析 SSE 事件块为 SseEvent 列表
     */
    private List<SseEvent> parseSseEventBlock(String block, RoutingCandidate candidate, String provider, InternalRequest req) {
        return parseSseEventBlock(block, candidate, provider, req, null);
    }

    /**
     * 解析 SSE 事件块为 SseEvent 列表，并累积内容
     *
     * @param traceId 链路追踪ID，不为null时启用内容累积
     */
    private List<SseEvent> parseSseEventBlock(String block, RoutingCandidate candidate, String provider, InternalRequest req, String traceId) {
        log.debug("解析SSE事件块 - block长度={}, block内容前100字符={}", block.length(), block.length() > 100 ? block.substring(0, 100) : block);
        List<SseEvent> events = new ArrayList<>();
        String[] lines = block.split("\n");
        String currentEvent = null;
        StringBuilder dataBuilder = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("event:")) {
                if (dataBuilder.length() > 0) {
                    flushEvent(events, currentEvent, dataBuilder.toString(), candidate, provider, req, traceId);
                    dataBuilder.setLength(0);
                }
                currentEvent = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (dataBuilder.length() > 0) {
                    dataBuilder.append("\n");
                }
                dataBuilder.append(line.substring("data:".length()).trim());
            }
        }
        if (dataBuilder.length() > 0) {
            flushEvent(events, currentEvent, dataBuilder.toString(), candidate, provider, req, traceId);
        }
        log.debug("解析SSE事件块完成 - 生成{}个事件", events.size());
        return events;
    }

    private void flushEvent(List<SseEvent> events, String event, String data,
                            RoutingCandidate candidate, String provider, InternalRequest req) {
        flushEvent(events, event, data, candidate, provider, req, null);
    }

    /**
     * 处理单个 SSE 事件：通过 {@link TranslatorRegistry} 进行协议转换，
     * 使用 per-traceId 的 {@link StreamTranslateState} 维护跨 chunk 上下文。
     *
     * <p>CPA 参考：对应 Go executor 中 {@code var param any; ... TranslateStream(&param)} 模式。
     */
    private void flushEvent(List<SseEvent> events, String event, String data,
                            RoutingCandidate candidate, String provider, InternalRequest req, String traceId) {
        String clientFormat = req.getClientApiFormat();
        String originalModel = req.getModel();

        // 需要协议转换
        if (!provider.equals(clientFormat)) {
            ProtocolTranslator translator = translatorRegistry.find(provider, clientFormat);
            if (translator != null) {
                String transformed;
                if ("[DONE]".equals(data)) {
                    // [DONE] 信号：刷出终结事件并清理状态
                    if (traceId != null) {
                        StreamTranslateState state = streamTranslateStates.remove(traceId);
                        if (state != null) {
                            transformed = translator.translateStreamEnd(originalModel, state);
                            if (transformed != null) {
                                addSseEventSplit(events, null, transformed);
                            }
                        }
                    }
                    events.add(new SseEvent(null, "[DONE]"));
                    return;
                }

                // 获取或创建跨 chunk 状态
                StreamTranslateState state = traceId != null
                        ? streamTranslateStates.computeIfAbsent(traceId, k -> translator.createStreamState())
                        : translator.createStreamState();

                transformed = translator.translateStreamEvent(event, data, originalModel, state);
                if (transformed == null) {
                    log.debug("SSE事件被丢弃 - event={}, provider={}, clientFormat={}", event, provider, clientFormat);
                    return;
                }
                // 累积流式内容，用于候选切换时的上下文传递
                if (traceId != null) {
                    String content = extractTextContentFromRawData(data, provider);
                    if (content != null && !content.isEmpty()) {
                        streamContentManager.appendContent(traceId, content);
                    }
                }
                addSseEventSplit(events, event, transformed);
                return;
            }
            // 无翻译器时降级到 MessageTransformer 旧逻辑
            log.debug("未找到翻译器 {}→{}，降级到 MessageTransformer", provider, clientFormat);
        }

        // 同格式或降级：走 MessageTransformer 原有逻辑
        if ("[DONE]".equals(data)) {
            events.add(new SseEvent(null, "[DONE]"));
            return;
        }
        String transformed;
        if ("anthropic".equals(provider)) {
            transformed = messageTransformer.transformAnthropicStreamEvent(event, data, clientFormat, originalModel);
        } else {
            transformed = messageTransformer.transformOpenAiStreamEvent(data, clientFormat, originalModel);
        }
        if (transformed == null) {
            log.debug("SSE事件被丢弃 - event={}, data={}, provider={}, clientFormat={}", event, data, provider, clientFormat);
            return;
        }
        // 累积流式内容，用于候选切换时的上下文传递
        if (traceId != null) {
            String content = extractTextContentFromRawData(data, provider);
            if (content != null && !content.isEmpty()) {
                streamContentManager.appendContent(traceId, content);
            }
        }
        addSseEventSplit(events, event, transformed);
    }

    /**
     * 添加 SSE 事件，如果 data 包含换行符则拆分为多个独立的 {@link SseEvent}。
     * <p>
     * 某些翻译器（如 OpenAI→Anthropic 处理多个 tool_calls 时）可能将多个 JSON 事件
     * 拼接到同一个字符串中返回，此处按行拆分确保每个事件单独发送。
     * </p>
     */
    private void addSseEventSplit(List<SseEvent> events, String event, String data) {
        if (data.contains("\n")) {
            String[] parts = data.split("\n", -1);
            for (String part : parts) {
                if (!part.isEmpty()) {
                    events.add(new SseEvent(event, part));
                }
            }
        } else {
            events.add(new SseEvent(event, data));
        }
    }

    /**
     * 从原始 SSE 数据中提取文本内容
     * OpenAI 格式：choices[0].delta.content
     * Anthropic 格式：delta.text（content_block_delta 事件）
     */
    String extractTextContentFromRawData(String rawData, String provider) {
        try {
            JsonNode json = objectMapper.readTree(rawData);
            if ("anthropic".equals(provider)) {
                JsonNode delta = json.get("delta");
                if (delta != null && delta.has("text")) {
                    return delta.get("text").asText();
                }
                // Anthropic content_block_start 也可能包含文本
                JsonNode contentBlock = json.get("content_block");
                if (contentBlock != null && contentBlock.has("text")) {
                    return contentBlock.get("text").asText();
                }
            } else {
                JsonNode choices = json.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null) {
                        // 仅提取 content（常规文本），用于流式上下文累积。
                        // 注意：reasoning_content（思考/推理过程）不在此提取——它是模型的内部推理，
                        // 不应作为 assistant 已输出的正文参与候选切换时的上下文续写，
                        // 否则下一个候选会基于推理过程续写，导致语义错乱。
                        if (delta.has("content")) {
                            return delta.get("content").asText();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从原始SSE数据提取文本内容失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 更新网关 API Key 的最后使用时间
     * <p>从 Authorization 头中提取 Bearer token，验证并更新 lastUsedAt</p>
     */
    private void updateGatewayApiKeyLastUsed(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return;
        }
        try {
            ApiKey apiKey = apiKeyService.validateKey(authHeader);
            if (apiKey != null && apiKey.getId() != null) {
                apiKeyService.updateLastUsed(apiKey.getId());
                log.debug("已更新网关 API Key lastUsedAt: id={}, name={}", apiKey.getId(), apiKey.getKeyName());
            }
        } catch (Exception e) {
            log.warn("更新网关 API Key lastUsedAt 失败: {}", e.getMessage());
        }
    }

    /**
     * 根据渠道类型决定上游调用方式（认证头、端点、请求体格式）。
     * <p>
     * provider 不应由入口 API 格式（/v1/messages vs /v1/chat/completions）硬编码，
     * 而应由渠道的 channelType 决定。这样 Anthropic 客户端可以路由到 OpenAI 兼容渠道，
     * 网关会自动完成协议转换（Anthropic ↔ OpenAI）。
     * </p>
     *
     * @param candidate       路由候选
     * @param defaultProvider 回退值（渠道类型为空时使用）
     * @return 实际用于调用上游的 provider
     */
    private String resolveProvider(RoutingCandidate candidate, String defaultProvider) {
        String channelType = candidate.getChannel().getChannelType();
        if (channelType != null && !channelType.isBlank()) {
            return channelType;
        }
        return defaultProvider;
    }

    /**
     * 构建上游请求头
     * <p>各渠道鉴权方式：</p>
     * <ul>
     *   <li>openai / 默认：Authorization: Bearer {apiKey}</li>
     *   <li>azure：api-key: {apiKey}（Azure OpenAI 使用独立的 api-key 请求头）</li>
     *   <li>anthropic：x-api-key + anthropic-version</li>
     * </ul>
     */
    private Map<String, String> buildProviderHeaders(String provider, String apiKey, String authHeader) {
        Map<String, String> headers = new HashMap<>();
        // trim 防止 DB 中存储的 Key 带有尾部空白/换行符，导致上游 401
        String key = apiKey != null ? apiKey.trim() : "";
        if ("azure".equals(provider)) {
            // Azure OpenAI 使用 api-key 请求头而非 Authorization Bearer
            headers.put("api-key", key);
        } else if ("anthropic".equals(provider)) {
            headers.put("x-api-key", key);
            headers.put("anthropic-version", "2023-06-01");
        } else {
            // openai 及其他默认走 Bearer
            headers.put("Authorization", "Bearer " + key);
        }
        headers.put("Content-Type", "application/json");
        return headers;
    }

    /**
     * 构建上游端点
     * <p>各渠道端点规则：</p>
     * <ul>
     *   <li>azure：用户需在 baseUrl 配置完整的 deployment 路径
     *       （如 {@code https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version=2024-02-15-preview}），
     *       本方法不追加 path，仅去除尾部斜杠</li>
     *   <li>anthropic：baseUrl + /messages</li>
     *   <li>openai/其他：baseUrl + /chat/completions</li>
     * </ul>
     */
    private String buildEndpoint(RoutingCandidate candidate, String provider) {
        Channel channel = candidate.getChannel();
        String baseUrl = channel.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            if ("anthropic".equals(provider)) {
                baseUrl = "https://api.anthropic.com/v1";
            } else {
                baseUrl = "https://api.openai.com/v1";
            }
        }
        baseUrl = baseUrl.replaceAll("/$", "");
        // Azure 端点由用户在 baseUrl 中配置完整 deployment 路径（含 query string），不追加固定 path
        if ("azure".equals(provider)) {
            return baseUrl;
        }
        String path = "anthropic".equals(provider) ? "/messages" : "/chat/completions";
        return baseUrl + path;
    }

    /**
     * 处理失败：触发熔断
     */
    private void handleFailure(RoutingCandidate candidate, InternalRequest req) {
        Long customModelId = resolveModelId(req.getModel());
        if (customModelId == null) {
            return;
        }
        circuitBreakerService.triggerCircuitBreak(customModelId,
                candidate.getChannel().getId(),
                candidate.getChannelApiKey().getId(),
                candidate.getChannelModel().getId());
    }

    /**
     * 构建失败消息文本，根据最后一条错误信息区分超时和 AI 返回错误。
     * <ul>
     *   <li>超时 / 空响应 → "请求超时"</li>
     *   <li>Provider 返回错误（含 status code）→ 提取 AI 返回的错误 message</li>
     *   <li>其他 → 原样截取显示</li>
     * </ul>
     */
    private String buildFailMessage(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) {
            return "所有候选均失败";
        }
        String lower = errorMsg.toLowerCase();
        // 超时检测
        if (lower.contains("timeout") || lower.contains("did not observe")
                || lower.contains("read timed out") || lower.contains("connect timed out")) {
            return "请求超时";
        }
        // Provider 返回错误（如 401, 429 等）：提取有意义的错误信息
        if (errorMsg.startsWith("Provider error:")) {
            int bodyIdx = errorMsg.indexOf("body: ");
            if (bodyIdx > 0) {
                String body = errorMsg.substring(bodyIdx + 6);
                // 尝试从 JSON body 中提取 error.message
                try {
                    JsonNode json = objectMapper.readTree(body);
                    if (json.has("error")) {
                        JsonNode errorNode = json.get("error");
                        if (errorNode.has("message")) {
                            String msg = errorNode.get("message").asText();
                            if (errorNode.has("type") && !errorNode.get("type").isNull()
                                    && !"null".equals(errorNode.get("type").asText())) {
                                return "[" + errorNode.get("type").asText() + "] " + msg;
                            }
                            return msg;
                        }
                        if (errorNode.isTextual()) {
                            return errorNode.asText();
                        }
                    }
                } catch (Exception ignored) {
                    // 解析失败回退到截取错误描述
                }
                // JSON 解析失败时回退到完整 body，避免详情弹框看不到真实错误
                String statusPart = errorMsg.substring("Provider error:".length(), bodyIdx).trim();
                return statusPart + " " + body;
            }
            return errorMsg;
        }
        // 空响应视为超时
        if (lower.contains("empty response") || lower.contains("treated as timeout")) {
            return "请求超时";
        }
        // 其他错误：保留完整信息，列表视图靠 CSS 截断，详情弹框靠滚动
        return errorMsg;
    }

    /**
     * 预处理请求：根据入口模型的 Prompt 注入规则，在消息列表前/后注入自定义消息。
     * <p>
     * 注入顺序：
     * <ol>
     *   <li>按 {@code injectPosition} 确定注入位置：prepend（消息前）、append（消息后）、replace_system（替换系统消息）</li>
     *   <li>按 {@code injectRole} 确定角色：system / user / assistant</li>
     *   <li>按 {@code priority} 排序，priority 越小越先执行</li>
     * </ol>
     * 多个注入规则按优先级依次执行，同一个位置的注入按执行顺序排列。
     * </p>
     *
     * @param req 内部请求（会直接修改 messages 列表）
     */
    private void applyPromptInjections(InternalRequest req) {
        Long customModelId = resolveModelId(req.getModel());
        if (customModelId == null) return;

        List<PromptInjection> rules = promptInjectionService.listEnabledByModelId(customModelId);
        if (rules == null || rules.isEmpty()) return;

        // 确保 messages 列表存在
        if (req.getMessages() == null) {
            req.setMessages(new ArrayList<>());
        }
        List<InternalMessage> messages = req.getMessages();

        // 先收集所有 prepend 和 append 规则（需要知道最终顺序）
        List<InternalMessage> prependMessages = new ArrayList<>();
        List<InternalMessage> appendMessages = new ArrayList<>();
        // 处理 replace_system：Service 层已保证每个模型只有一条，直接用单一变量
        InternalMessage replaceSystemMsg = null;

        for (PromptInjection rule : rules) {
            InternalMessage injectMsg = new InternalMessage(rule.getInjectRole(), rule.getContent());
            log.info("应用 Prompt 注入规则: modelId={}, ruleName={}, role={}, position={}",
                    customModelId, rule.getName(), rule.getInjectRole(), rule.getInjectPosition());

            switch (rule.getInjectPosition()) {
                case "prepend":
                    prependMessages.add(injectMsg);
                    break;
                case "append":
                    appendMessages.add(injectMsg);
                    break;
                case "replace_system":
                    // Service 层已保证每个模型只有一条 replace_system 规则
                    replaceSystemMsg = injectMsg;
                    break;
                default:
                    log.warn("未知的注入位置: {}", rule.getInjectPosition());
            }
        }

        // 执行替换系统消息
        if (replaceSystemMsg != null) {
            boolean foundSystem = false;
            for (int i = 0; i < messages.size(); i++) {
                if ("system".equals(messages.get(i).getRole())) {
                    messages.set(i, replaceSystemMsg);
                    foundSystem = true;
                    log.info("已替换系统消息 - 新角色={}, 内容长度={}",
                            replaceSystemMsg.getRole(), replaceSystemMsg.getContent().length());
                    break;
                }
            }
            if (!foundSystem) {
                // 没有 system 消息时，将 replace_system 规则作为 prepend 注入
                prependMessages.add(replaceSystemMsg);
                log.info("未找到系统消息，将 replace_system 规则作为 prepend 注入");
            }
        }

        // 按顺序构建新的消息列表：prepend + 原消息 + append
        List<InternalMessage> newMessages = new ArrayList<>();
        newMessages.addAll(prependMessages);
        newMessages.addAll(messages);
        newMessages.addAll(appendMessages);

        req.setMessages(newMessages);

        if (!prependMessages.isEmpty() || !appendMessages.isEmpty()) {
            log.info("Prompt 注入完成 - prependCount={}, appendCount={}, 原始消息数={}, 最终消息数={}",
                    prependMessages.size(), appendMessages.size(),
                    messages.size(), newMessages.size());
        }
    }

    /**
     * 预处理请求：根据入口模型的多模态失效配置，移除已失效的媒体内容。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>根据模型名查询入口模型的 imageInvalidateCount / videoInvalidateCount / audioInvalidateCount</li>
     *   <li>对每个 >0 的媒体类型，找到最后一个含有该媒体类型的 user 消息</li>
     *   <li>统计该消息之后还有多少个 user 消息</li>
     *   <li>如果后续 user 消息数 ≥ 配置值，则将该类型的所有媒体内容替换为纯文本提示</li>
     * </ol>
     * 替换后清除 detectedMediaTypes 缓存，使后续检测重新计算。
     * </p>
     *
     * @param req 内部请求（会直接修改 messages 中的 contentParts）
     */
    private void preprocessMediaInvalidation(InternalRequest req) {
        if (req.getMessages() == null || req.getMessages().isEmpty()) return;

        Long customModelId = resolveModelId(req.getModel());
        if (customModelId == null) return;

        Model model = modelService.getById(customModelId);
        if (model == null) return;

        // 检查三种媒体类型的配置
        int imageN = model.getImageInvalidateCount() != null ? model.getImageInvalidateCount() : 0;
        int videoN = model.getVideoInvalidateCount() != null ? model.getVideoInvalidateCount() : 0;
        int audioN = model.getAudioInvalidateCount() != null ? model.getAudioInvalidateCount() : 0;

        if (imageN > 0) {
            preprocessMediaInvalidationForType(req, "image", imageN, MEDIA_REPLACE_TEXT_IMAGE);
        }
        if (videoN > 0) {
            preprocessMediaInvalidationForType(req, "video", videoN, MEDIA_REPLACE_TEXT_VIDEO);
        }
        if (audioN > 0) {
            preprocessMediaInvalidationForType(req, "audio", audioN, MEDIA_REPLACE_TEXT_AUDIO);
        }
    }

    /**
     * 对指定媒体类型执行失效检查与替换。
     * <p>
     * 主干流程：
     * <ol>
     *   <li>从后向前遍历 messages，找到最后一个包含该媒体类型的 user 消息</li>
     *   <li>统计该消息之后的 user 消息数量</li>
     *   <li>如果后续 user 消息数 ≥ 配置值，遍历所有消息将该媒体类型的 contentPart 替换为文本</li>
     * </ol>
     * </p>
     */
    private void preprocessMediaInvalidationForType(InternalRequest req, String mediaTypePrefix,
                                                     int invalidateCount, String replaceText) {
        List<InternalMessage> messages = req.getMessages();
        List<InternalMessage> userMessages = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).getRole())) {
                userMessages.add(messages.get(i));
            }
        }

        // 从后向前找到最后一个含该媒体类型的 user 消息
        int lastMediaUserIdx = -1;
        for (int i = userMessages.size() - 1; i >= 0; i--) {
            InternalMessage msg = userMessages.get(i);
            if (hasMediaType(msg, mediaTypePrefix)) {
                lastMediaUserIdx = i;
                break;
            }
        }

        // 没找到则无需处理
        if (lastMediaUserIdx < 0) return;

        // 统计该消息之后还有多少个 user 消息
        int userMsgsAfter = userMessages.size() - 1 - lastMediaUserIdx;

        // 如果后续 user 消息数 >= 配置值，则替换所有该类型的媒体内容
        if (userMsgsAfter >= invalidateCount) {
            log.info("多模态失效触发: mediaType={}, invalidateCount={}, userMsgsAfter={}, model={}",
                    mediaTypePrefix, invalidateCount, userMsgsAfter, req.getModel());
            for (InternalMessage msg : messages) {
                replaceMediaParts(msg, mediaTypePrefix, replaceText);
            }
            // 清除 detectedMediaTypes 缓存，使后续检测重新计算
            req.setDetectedMediaTypes(null);
        }
    }

    /**
     * 检查消息中是否包含指定前缀的媒体类型
     */
    private boolean hasMediaType(InternalMessage msg, String typePrefix) {
        if (msg.getContentParts() == null) return false;
        for (Map<String, Object> part : msg.getContentParts()) {
            Object type = part.get("type");
            if (type != null && type.toString().startsWith(typePrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将消息中指定前缀的媒体 contentPart 替换为纯文本
     */
    private void replaceMediaParts(InternalMessage msg, String typePrefix, String replaceText) {
        if (msg.getContentParts() == null) return;
        List<Map<String, Object>> newParts = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> part : msg.getContentParts()) {
            Object type = part.get("type");
            if (type != null && type.toString().startsWith(typePrefix)) {
                if (!replaced) {
                    // 只添加一次替换文本（多个连续媒体只生成一个文本提示）
                    Map<String, Object> textPart = new LinkedHashMap<>();
                    textPart.put("type", "text");
                    textPart.put("text", replaceText);
                    newParts.add(textPart);
                    replaced = true;
                }
                // 跳过原媒体 part
            } else {
                newParts.add(part);
            }
        }
        // 如果原消息只有媒体 part（无文本），替换后只保留文本提示
        // 如果原消息有文本 + 媒体，媒体 part 被替换为一条提示文本
        msg.setContentParts(newParts);
    }

    /**
     * 检测请求中包含的所有媒体类型（image/video/audio）
     * 遍历所有消息的 contentParts，收集 type 字段前缀作为媒体类型。
     * 使用 startsWith 以兼容未来可能的变体（如 image_url、image_file、video_file）。
     * 检测结果会缓存到 req.detectedMediaTypes 避免重复遍历。
     *
     * @return 包含的媒体类型集合（不会包含 text），如 ["image", "video"]
     */
    private Set<String> detectRequestMediaTypes(InternalRequest req) {
        // 优先使用缓存结果
        if (req.getDetectedMediaTypes() != null) {
            return req.getDetectedMediaTypes();
        }
        if (req.getMessages() == null) {
            req.setDetectedMediaTypes(Collections.emptySet());
            return Collections.emptySet();
        }
        Set<String> types = new HashSet<>();
        for (InternalMessage msg : req.getMessages()) {
            if (msg.getContentParts() == null) continue;
            for (Map<String, Object> part : msg.getContentParts()) {
                Object type = part.get("type");
                if (type != null) {
                    String t = type.toString();
                    if (t.startsWith("image")) {
                        types.add("image");
                    } else if (t.startsWith("video")) {
                        types.add("video");
                    } else if (t.startsWith("audio")) {
                        types.add("audio");
                    }
                }
            }
        }
        req.setDetectedMediaTypes(types);
        return types;
    }

    /**
     * 检查渠道模型是否支持指定的媒体类型
     *
     * @param channelModel 渠道模型
     * @param mediaType    媒体类型（如 "image"、"video"、"audio"）
     * @return true 表示支持
     */
    private boolean supportsMediaType(ChannelModel channelModel, String mediaType) {
        String input = channelModel.getInput();
        return input != null && input.contains(mediaType);
    }

    /**
     * 候选不支持请求中的媒体类型时返回 true，并记录跳过原因
     * <p>
     * 一次性检测请求中的所有媒体类型，与模型的 input 字段逐一比对，
     * 将所有不支持的媒体类型合并为一条日志输出，格式如：
     * "当前模型不支持请求中含有的这些类型：image、video"
     * </p>
     *
     * @return true 表示候选不满足请求的媒体类型要求，应由调用方移除候选并继续
     */
    private boolean skipIfMediaTypeUnsupported(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate,
                                               InternalRequest req, int retryIndex) {
        Set<String> types = detectRequestMediaTypes(req);
        if (types.isEmpty()) return false;

        // 找出所有不支持的媒体类型
        List<String> unsupportedTypes = new ArrayList<>();
        for (String type : types) {
            if (!supportsMediaType(candidate.getChannelModel(), type)) {
                unsupportedTypes.add(type);
            }
        }

        if (!unsupportedTypes.isEmpty()) {
            logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                    "当前模型不支持请求中含有的这些类型：" + String.join("、", unsupportedTypes)
                            + " 因此跳过 " + candidate.getChannel().getName()
                            + "/" + candidate.getChannelApiKey().getKeyName()
                            + "/" + candidate.getChannelModel().getModelName(), retryIndex);
            return true;
        }
        return false;
    }

    /**
     * 获取可用的路由候选列表
     * 将每个 (ChannelModel, API Key) 组合展开为一个候选
     * （包级可见，便于单元测试）
     */
    List<RoutingCandidate> getAvailableCandidates(InternalRequest req) {
        String modelName = req.getModel();
        Long customModelId = resolveModelId(modelName);
        if (customModelId == null) {
            log.warn("找不到自定义模型: {}", modelName);
            return Collections.emptyList();
        }

        List<ModelChannelRel> rels = modelService.getChannelRels(customModelId);
        List<RoutingCandidate> candidates = new ArrayList<>();

        log.info("构建路由候选 - 自定义模型: {} (id={}), 关联数: {}", modelName, customModelId, rels.size());

        for (ModelChannelRel rel : rels) {
            if (rel.getEnabled() == null || rel.getEnabled() != 1) {
                log.debug("关联被禁用: relId={}", rel.getId());
                continue;
            }
            ChannelModel channelModel = modelService.getChannelModelById(rel.getChannelModelId());
            if (channelModel == null || channelModel.getEnabled() == null || channelModel.getEnabled() != 1) {
                log.debug("渠道模型不可用: channelModelId={}", rel.getChannelModelId());
                continue;
            }
            Channel channel = modelService.getChannelById(channelModel.getChannelId());
            if (channel == null || channel.getEnabled() == null || channel.getEnabled() != 1) {
                log.debug("渠道不可用: channelId={}", channelModel.getChannelId());
                continue;
            }

            Long specifiedKeyId = channelModel.getChannelApiKeyId();
            // 渠道级熔断在循环外检查一次，避免对每个 API Key 重复查
            boolean channelLevelBroken = circuitBreakerService.isChannelCircuitBroken(channel.getId());
            List<ChannelApiKey> apiKeys = getApiKeysForCandidate(channel.getId(), specifiedKeyId);
            log.info("渠道模型: {} (id={}), 指定API Key: {}, 可用Keys: {}",
                    channelModel.getModelName(), channelModel.getId(), specifiedKeyId, apiKeys.size());

            for (ChannelApiKey apiKey : apiKeys) {
                if (apiKey.getEnabled() == null || apiKey.getEnabled() != 1) {
                    log.debug("API Key被禁用: keyId={}, keyName={}", apiKey.getId(), apiKey.getKeyName());
                    continue;
                }
                if (channelLevelBroken
                        || circuitBreakerService.isChannelCircuitBroken(channel.getId(), apiKey.getId())) {
                    log.info("API Key渠道级熔断跳过: channel={} key={}", channel.getName(), apiKey.getKeyName());
                    continue;
                }
                if (circuitBreakerService.isModelCircuitBroken(channelModel.getId(), apiKey.getId())) {
                    log.info("模型级熔断跳过: model={} key={}", channelModel.getModelName(), apiKey.getKeyName());
                    continue;
                }
                candidates.add(new RoutingCandidate(rel, channel, channelModel, apiKey));
                log.info("添加候选: {} / {} / {}", channel.getName(), channelModel.getModelName(), apiKey.getKeyName());
            }
        }

        log.info("路由候选构建完成 - 共 {} 个候选", candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            RoutingCandidate c = candidates.get(i);
            log.info("  候选[{}]: channel={} model={} key={}", i,
                    c.getChannel().getName(), c.getChannelModel().getModelName(), c.getChannelApiKey().getKeyName());
        }

        return candidates;
    }

    /**
     * 获取候选可用的 API Keys
     * 若渠道模型指定了 API Key ID，则仅使用该 Key；否则使用渠道下所有可用 Key
     */
    private List<ChannelApiKey> getApiKeysForCandidate(Long channelId, Long specifiedKeyId) {
        if (specifiedKeyId != null) {
            ChannelApiKey key = channelApiKeyService.getById(specifiedKeyId);
            return key != null ? List.of(key) : Collections.emptyList();
        }
        List<ChannelApiKey> keys = channelApiKeyService.getAvailableApiKeys(channelId);
        return keys != null ? keys : Collections.emptyList();
    }

    /**
     * 根据模型名解析自定义模型 ID
     */
    private Long resolveModelId(String modelName) {
        Model model = modelService.getByModelName(modelName);
        return model != null ? model.getId() : null;
    }

    /**
     * 根据模型名解析该模型配置的选择策略
     * <p>返回策略名称（random / round_robin / failover），
     * 未配置策略时默认返回 failover 以保持兼容。</p>
     */
    private String resolveModelStrategy(String modelName) {
        Model model = modelService.getByModelName(modelName);
        if (model == null || model.getStrategy() == null || model.getStrategy().isBlank()) {
            return "failover";
        }
        return model.getStrategy();
    }

    /**
     * 获取自定义模型的候选内重试次数
     */
    private int getRetryCount(String modelName) {
        Long customModelId = resolveModelId(modelName);
        if (customModelId == null) {
            return 0;
        }
        CircuitBreakerConfig config = modelService.getCircuitBreakerConfig(customModelId);
        if (config == null || config.getRetryCount() == null
                || config.getEnabled() == null || config.getEnabled() != 1) {
            return 0;
        }
        return Math.max(0, config.getRetryCount());
    }

    /**
     * 获取单个候选的最大尝试次数（1 次首次调用 + retryCount 次重试）
     */
    private int getMaxAttempts(String modelName) {
        return Math.max(1, getRetryCount(modelName) + 1);
    }

    /**
     * 模型路由上下文：缓存单次请求中不变的路由配置，避免在 tryCandidates/tryStreamCandidates
     * 每次递归时重复查询数据库解析 modelId / strategy / retryCount。
     *
     * <p>在 executeRelay / executeStreamRelay 入口处解析一次，通过参数传递给各重试方法。</p>
     */
    record RoutingContext(Long modelId, String strategy, int maxAttempts, int retryCount) {
        /** 模型不存在时的默认上下文：failover 策略、不重试 */
        static RoutingContext defaultEmpty() {
            return new RoutingContext(null, "failover", 1, 0);
        }
    }

    /**
     * 一次性解析模型的路由配置（modelId / strategy / maxAttempts / retryCount）。
     * <p>替代 tryCandidates 内每次递归分别调用 resolveModelId/resolveModelStrategy/getMaxAttempts，
     * 将 3~4 次 DB 查询合并为 1~2 次（getByModelName + getCircuitBreakerConfig）。</p>
     */
    private RoutingContext resolveModelRouting(String modelName) {
        Model model = modelService.getByModelName(modelName);
        if (model == null) {
            return RoutingContext.defaultEmpty();
        }
        String strategy = (model.getStrategy() == null || model.getStrategy().isBlank())
                ? "failover" : model.getStrategy();
        int retryCount = 0;
        CircuitBreakerConfig config = modelService.getCircuitBreakerConfig(model.getId());
        if (config != null && config.getRetryCount() != null
                && config.getEnabled() != null && config.getEnabled() == 1) {
            retryCount = Math.max(0, config.getRetryCount());
        }
        int maxAttempts = Math.max(1, retryCount + 1);
        return new RoutingContext(model.getId(), strategy, maxAttempts, retryCount);
    }

    /**
     * 检查候选是否已被熔断（含渠道级和模型级）
     * <p>用于在发起请求前或重试前实时检测，若被其他并发请求熔断则快速跳过。</p>
     */
    private boolean isCandidateCircuitBroken(RoutingCandidate candidate) {
        return circuitBreakerService.isChannelCircuitBroken(candidate.getChannel().getId())
                || circuitBreakerService.isChannelCircuitBroken(
                        candidate.getChannel().getId(), candidate.getChannelApiKey().getId())
                || circuitBreakerService.isModelCircuitBroken(
                        candidate.getChannelModel().getId(), candidate.getChannelApiKey().getId());
    }

    /**
     * 记录请求阶段日志
     */
    private void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate, InternalRequest req,
                          String phase, String message, int retryIndex) {
        logPhase(traceId, gatewayApiKeyId, candidate, req, phase, message, retryIndex, null);
    }

    /**
     * 记录请求阶段日志（带响应时间）——用于"该次尝试耗时"已知但尚未走到 success/fail 的场景，
     * 例如：候选内重试失败时，把本次尝试的耗时写入 retry 日志，让前端能展示每次真实请求的用时。
     */
    private void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate, InternalRequest req,
                          String phase, String message, int retryIndex, Long responseTimeMs) {
        String apiKeyName = candidate != null ? candidate.getChannelApiKey().getKeyName() : null;
        String modelName = req != null ? req.getModel() : null;
        String channelModelName = candidate != null ? candidate.getChannelModel().getModelName() : null;
        String channelName = candidate != null ? candidate.getChannel().getName() : null;
        if (responseTimeMs != null) {
            requestLogService.logWithResponseTime(traceId, apiKeyName, gatewayApiKeyId, modelName, channelModelName, channelName,
                    phase, message, retryIndex, responseTimeMs);
        } else {
            requestLogService.log(traceId, apiKeyName, gatewayApiKeyId, modelName, channelModelName, channelName, phase, message, retryIndex);
        }
    }

    /** 多模态失效替换文本：图片输入已被后续对话覆盖，由系统自动移除以路由到文本模型 */
    private static final String MEDIA_REPLACE_TEXT_IMAGE = "图片输入已失效已被系统移除";
    /** 多模态失效替换文本：视频输入已被后续对话覆盖 */
    private static final String MEDIA_REPLACE_TEXT_VIDEO = "视频输入已失效已被系统移除";
    /** 多模态失效替换文本：音频输入已被后续对话覆盖 */
    private static final String MEDIA_REPLACE_TEXT_AUDIO = "音频输入已失效已被系统移除";

    /**
     * 记录原始请求数据（请求头 + 请求体），在每个入口方法生成 traceId 后立即调用。
     * 前端"请求日志"中的"查看原始请求"功能依赖此记录。
     *
     * @param traceId        追踪 ID
     * @param authHeader     原始 Authorization 头（用于解析网关 API Key id）
     * @param headersJson    原始请求头（已转 JSON 字符串）
     * @param requestBody    原始请求体
     * @return 解析出的网关 API Key id（与本请求内所有日志记录共用，避免每条日志都查一次 DB）
     */
    private Long logOriginalRequest(String traceId, String authHeader, String headersJson, String requestBody) {
        String modelName = extractModelFromBody(requestBody);
        Long gatewayApiKeyId = apiKeyService.resolveIdFromAuthHeader(authHeader);
        requestLogService.logStart(traceId, null, gatewayApiKeyId, modelName, null, null,
                "请求开始", 0, headersJson, requestBody);
        return gatewayApiKeyId;
    }

    /**
     * 从请求体中提取 model 字段。
     * <p>
     * 使用简单正则匹配 {@code "model": "xxx"}，避免全量 JSON 解析。
     * 后续 {@code parseRequest(requestBody)} 会再做一次完整解析，这里只取 model 做日志记录，
     * 不需要完整 AST。
     * </p>
     */
    private String extractModelFromBody(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) return null;
        java.util.regex.Matcher m = MODEL_NAME_PATTERN.matcher(requestBody);
        return m.find() ? m.group(1) : null;
    }

    private static final java.util.regex.Pattern MODEL_NAME_PATTERN =
            java.util.regex.Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 构建 OpenAI 兼容格式的请求头 JSON（对 Authorization 做掩码处理）
     */
    private String buildOpenaiHeadersJson(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return "{\"Content-Type\": \"application/json\"}";
        }
        String masked;
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            masked = "Bearer " + maskBearerToken(token);
        } else {
            masked = maskBearerToken(authHeader);
        }
        return "{\"Authorization\": \"" + masked + "\", \"Content-Type\": \"application/json\"}";
    }

    /**
     * 构建 Anthropic 兼容格式的请求头 JSON（对 x-api-key 做掩码处理）
     */
    private String buildAnthropicHeadersJson(String apiKeyHeader, String anthropicVersion) {
        String masked = maskBearerToken(apiKeyHeader);
        return "{\"x-api-key\": \"" + masked + "\", \"anthropic-version\": \"" + anthropicVersion + "\", \"Content-Type\": \"application/json\"}";
    }

    /**
     * 对 Bearer Token / API Key 做掩码处理，避免敏感凭证明文存入日志数据库
     */
    private String maskBearerToken(String token) {
        if (token == null || token.isBlank()) return "";
        if (token.length() > 12) {
            return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
        }
        return token.substring(0, Math.min(6, token.length())) + "...";
    }

    /**
     * 发送 SSE 事件
     */
    private void sendSseEvent(SseEmitter emitter, SseEvent event) {
        try {
            log.debug("发送SSE事件 - event={}, data长度={}", event.event(), event.data().length());
            if ("[DONE]".equals(event.data())) {
                // 发送 [DONE] 信号关闭流
                emitter.send(SseEmitter.event().data("[DONE]"));
            } else if (event.event() != null && !event.event().isEmpty()) {
                emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
            } else {
                emitter.send(SseEmitter.event().data(event.data()));
            }
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开 / SseEmitter 已超时/已完成，抛出 RuntimeException 触发 subscriber onError
            // -> 上游 Flux 被取消（停止 Provider 计费）-> doOnComplete 不执行 -> 日志不记 "success"
            // IllegalStateException: emitter 已 complete() 后 send() 抛出，非 IOException，需一并捕获
            throw new RuntimeException("Client disconnected from SSE stream", e);
        }
    }

    /**
     * 发送 SSE 错误事件
     */
    private void sendSseError(SseEmitter emitter, String message) {
        try {
            ObjectNode err = objectMapper.createObjectNode();
            err.put("error", message != null ? message : "Unknown stream error");
            emitter.send(SseEmitter.event().name("error").data(err.toString()));
            emitter.complete();
        } catch (IOException e) {
            log.warn("Failed to send SSE error (client already disconnected?): {}", e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // SseEmitter 可能已关闭/已完成，忽略二次异常
            }
        }
    }

    /**
     * 处理流式 subscribe 的 onError 回调。
     * <p>区分两种错误来源：</p>
     * <ul>
     *   <li><b>客户端断开</b>（{@code sendSseEvent} 抛 "Client disconnected"）：
     *       上游 doOnComplete 不会执行（onError 路径），请求日志会停留在"请求开始"无终态。
     *       此处补记一条 {@code fail/interrupted} 终态日志，保证统计准确性。</li>
     *   <li><b>上游错误</b>（所有候选失败等）：已被 {@code tryStreamCandidates} 的 onErrorResume
     *       记录了 {@code fail}，此处不重复记录，仅发送 SSE 错误事件并清理。</li>
     * </ul>
     */
    private void handleStreamSubscribeError(String traceId, Long gatewayApiKeyId, SseEmitter emitter, Throwable err) {
        log.error("Stream relay failed - traceId={}", traceId, err);
        cleanupStreamResources(traceId);
        // 客户端断开时补记终态日志（上游错误已由 tryStreamCandidates 记录，不重复）
        String msg = err.getMessage() != null ? err.getMessage() : "";
        if (msg.contains("Client disconnected")) {
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, null, null, null,
                    "fail", "interrupted", "客户端断开连接", 0, 0);
            // 客户端已断开，sendSseError 大概率失败，但仍尝试（内部已处理异常）
            try {
                emitter.completeWithError(err);
            } catch (Exception ignored) {
            }
        } else {
            // 非客户端断开的错误（如 parseRequest 阶段异常、executeStreamRelay 前的错误）：
            // tryStreamCandidates 的 onErrorResume 已记录终态，但 parseRequest 阶段抛出的错误
            // 不经过 onErrorResume，请求日志会停留在"请求开始"状态，此处补记终态
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, null, null, null,
                    "fail", "error", msg.isEmpty() ? "流式请求失败" : msg, 0, 0);
            sendSseError(emitter, msg);
        }
    }

    /**
     * 清理 SSE 流资源：清除 StreamContentManager 中的累积内容和 streamUsageMap 记录
     */
    private void cleanupStreamResources(String traceId) {
        streamContentManager.clearContent(traceId);
        streamUsageMap.remove(traceId);
        streamTranslateStates.remove(traceId);
    }

    /**
     * 构建路由进度 SSE 事件 JSON
     * 前端据此实时展示当前正在尝试的模型/渠道信息
     *
     * @param phase    阶段类型：trying / retrying / switching
     * @param candidate 当前路由候选
     * @param retryIndex 候选重试索引（第几个候选）
     * @param extraMsg 附加说明（如失败原因）
     */
    private String buildRoutingProgressJson(String phase, RoutingCandidate candidate,
                                             int retryIndex, String extraMsg) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("_routing_progress", true);
        node.put("phase", phase);
        node.put("channel_type", candidate.getChannel().getChannelType());
        node.put("channel", candidate.getChannel().getName());
        node.put("channel_model", candidate.getChannelModel().getModelName());
        node.put("retry_index", retryIndex);
        if (extraMsg != null) {
            node.put("message", extraMsg);
        }
        return node.toString();
    }

    /**
     * 记录被跳过的路由候选到请求日志（熔断、图片能力不匹配等），
     * 让用户在 UI 中能看到完整的路由链路。
     *
     * <p>使用已解析的 {@link RoutingContext#modelId()} 避免重复调用 resolveModelId 查 DB。
     * 注意：本方法仍需遍历 rels/channelModels/apiKeys 检查熔断状态以记录跳过原因，
     * 与 getAvailableCandidates 存在部分查询重叠（均为日志展示用途，不影响路由决策）。</p>
     *
     * @param ctx 已解析的模型路由上下文（modelId 为 null 时直接返回）
     */
    private void logSkippedCandidatesFromResult(String traceId, Long gatewayApiKeyId, InternalRequest req,
                                                List<RoutingCandidate> candidates, RoutingContext ctx) {
        Long customModelId = ctx.modelId();
        if (customModelId == null) return;

        List<ModelChannelRel> rels = modelService.getChannelRels(customModelId);
        for (ModelChannelRel rel : rels) {
            if (rel.getEnabled() == null || rel.getEnabled() != 1) continue;
            ChannelModel channelModel = modelService.getChannelModelById(rel.getChannelModelId());
            if (channelModel == null || channelModel.getEnabled() == null || channelModel.getEnabled() != 1) continue;
            Channel channel = modelService.getChannelById(channelModel.getChannelId());
            if (channel == null || channel.getEnabled() == null || channel.getEnabled() != 1) continue;

            // 渠道级熔断在循环外检查一次，避免对每个 API Key 重复查
            boolean channelLevelBroken = circuitBreakerService.isChannelCircuitBroken(channel.getId());
            List<ChannelApiKey> apiKeys = getApiKeysForCandidate(channel.getId(), channelModel.getChannelApiKeyId());
            for (ChannelApiKey apiKey : apiKeys) {
                if (apiKey.getEnabled() == null || apiKey.getEnabled() != 1) continue;

                boolean channelBroken = channelLevelBroken
                        || circuitBreakerService.isChannelCircuitBroken(channel.getId(), apiKey.getId());
                boolean modelBroken = circuitBreakerService.isModelCircuitBroken(channelModel.getId(), apiKey.getId());
                if (channelBroken || modelBroken) {
                    String scope = channelBroken ? "渠道级熔断" : "模型级熔断";
                    logPhase(traceId, gatewayApiKeyId, new RoutingCandidate(rel, channel, channelModel, apiKey),
                            req, "skip", scope + "跳过 " + channel.getName() + "/" + apiKey.getKeyName() + "/" + channelModel.getModelName(), 0);
                }
            }
        }
    }

    /**
     * 从上游非流式响应体中提取 token 用量
     * 支持 OpenAI 格式 (prompt_tokens/completion_tokens) 和 Anthropic 格式 (input_tokens/output_tokens)
     *
     * @return [promptTokens, completionTokens, totalTokens]，无 usage 时返回 [0, 0, 0]
     */
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

    /**
     * 从 SSE 事件数据中提取 token 用量（流式响应的最后一个含 usage 的 chunk）
     *
     * @return [promptTokens, completionTokens, totalTokens]，无 usage 时返回 null
     */
    private int[] extractUsageFromSseData(String data) {
        if (data == null || data.isEmpty() || "[DONE]".equals(data)) return null;
        try {
            JsonNode json = objectMapper.readTree(data);
            JsonNode usage = json.get("usage");
            if (usage != null && usage.isObject()) {
                int pt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt()
                        : usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                int ct = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt()
                        : usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                int tt = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : pt + ct;
                return new int[]{pt, ct, tt};
            }
        } catch (Exception e) {
            log.debug("提取 SSE token 用量失败: {}", e.getMessage());
        }
        return null;
    }

    public record SseEvent(String event, String data) {}
}
