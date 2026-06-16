package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.*;
import com.myai.gateway.relay.balancer.LoadBalancer;
import com.myai.gateway.relay.balancer.LoadBalancerFactory;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

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
 */
@Service
public class RelayService {

    private static final Logger log = LoggerFactory.getLogger(RelayService.class);

    private final ChannelService channelService;
    private final ChannelApiKeyService channelApiKeyService;
    private final ModelService modelService;
    private final CircuitBreakerService circuitBreakerService;
    private final RequestLogService requestLogService;
    private final LoadBalancerFactory loadBalancerFactory;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final MessageTransformer messageTransformer;

    public RelayService(ChannelService channelService,
                        ChannelApiKeyService channelApiKeyService,
                        ModelService modelService,
                        CircuitBreakerService circuitBreakerService,
                        RequestLogService requestLogService,
                        LoadBalancerFactory loadBalancerFactory,
                        ObjectMapper objectMapper,
                        MessageTransformer messageTransformer) {
        this.channelService = channelService;
        this.channelApiKeyService = channelApiKeyService;
        this.modelService = modelService;
        this.circuitBreakerService = circuitBreakerService;
        this.requestLogService = requestLogService;
        this.loadBalancerFactory = loadBalancerFactory;
        this.objectMapper = objectMapper;
        this.messageTransformer = messageTransformer;
        this.webClient = WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * OpenAI 兼容：非流式聊天补全
     */
    public Mono<String> chatCompletions(String authHeader, String requestBody) {
        String traceId = requestLogService.startTrace();
        return Mono.fromCallable(() -> parseRequest(requestBody, "openai"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(req -> executeRelay(traceId, authHeader, req, "openai", false));
    }

    /**
     * Anthropic 兼容：非流式消息
     */
    public Mono<String> messages(String apiKeyHeader, String requestBody, String anthropicVersion) {
        String traceId = requestLogService.startTrace();
        return Mono.fromCallable(() -> parseRequest(requestBody, "anthropic"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(req -> executeRelay(traceId, apiKeyHeader, req, "anthropic", false));
    }

    /**
     * OpenAI 兼容：流式聊天补全
     *
     * @param internalClient 是否为内部客户端（Playground），true 时发送 _routing_progress/_gateway_meta 等自定义事件
     */
    public SseEmitter chatCompletionsStream(String authHeader, String requestBody, boolean internalClient) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String traceId = requestLogService.startTrace();
        Mono.fromCallable(() -> parseRequest(requestBody, "openai"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(req -> executeStreamRelay(traceId, authHeader, req, "openai", internalClient))
                .subscribe(
                        event -> sendSseEvent(emitter, event),
                        err -> {
                            log.error("Stream relay failed", err);
                            sendSseError(emitter, err.getMessage());
                        },
                        emitter::complete
                );
        return emitter;
    }

    /**
     * Anthropic 兼容：流式消息
     *
     * @param internalClient 是否为内部客户端（Playground），true 时发送 _routing_progress/_gateway_meta 等自定义事件
     */
    public SseEmitter messagesStream(String apiKeyHeader, String requestBody, String anthropicVersion, boolean internalClient) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String traceId = requestLogService.startTrace();
        Mono.fromCallable(() -> parseRequest(requestBody, "anthropic"))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(req -> executeStreamRelay(traceId, apiKeyHeader, req, "anthropic", internalClient))
                .subscribe(
                        event -> sendSseEvent(emitter, event),
                        err -> {
                            log.error("Stream relay failed", err);
                            sendSseError(emitter, err.getMessage());
                        },
                        emitter::complete
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
    private Mono<String> executeRelay(String traceId, String authHeader, InternalRequest req, String provider, boolean retry) {
        long startTime = System.currentTimeMillis();
        String originalModel = req.getModel();
        logSkippedCircuitBrokenCandidates(traceId, req);
        List<RoutingCandidate> candidates = getAvailableCandidates(req);
        if (candidates.isEmpty()) {
            requestLogService.logComplete(traceId, null, originalModel, null, null,
                    "fail", "error", "没有可用的路由候选", System.currentTimeMillis() - startTime, 0);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    "没有可用的路由候选（渠道/API Key/模型都被熔断或不可用）", "api_error", 503));
        }

        return tryCandidates(traceId, new ArrayList<>(candidates), authHeader, req, provider, 0, startTime);
    }

    /**
     * 顺序尝试候选，失败后按配置进行候选内重试；重试耗尽后触发熔断并移除候选，继续下一个
     * （包级可见，便于单元测试）
     */
    Mono<String> tryCandidates(String traceId, List<RoutingCandidate> remaining,
                                        String authHeader, InternalRequest req,
                                        String provider, int retryIndex, long startTime) {
        if (remaining.isEmpty()) {
            requestLogService.logComplete(traceId, null, req.getModel(), null, null,
                    "fail", "error", "所有候选均失败", System.currentTimeMillis() - startTime, retryIndex);
            log.warn("所有候选均失败，无法完成请求 - traceId={}", traceId);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    "所有候选均失败，无法完成请求", "api_error", 503));
        }

        LoadBalancer balancer = loadBalancerFactory.getBalancer(provider);
        // 使用自定义模型 ID 作为负载均衡维度
        Long modelId = resolveModelId(req.getModel());
        RoutingCandidate candidate = balancer.select(remaining, modelId);
        if (candidate == null) {
            log.warn("负载均衡器返回null候选 - traceId={}", traceId);
            return Mono.just(messageTransformer.buildErrorResponse(req.getClientApiFormat(),
                    "没有可用的路由候选", "api_error", 503));
        }

        log.info("路由决策 - traceId={} retryIndex={} 选中候选: channel={} model={} key={} (剩余{}个候选)",
                traceId, retryIndex,
                candidate.getChannel().getName(),
                candidate.getChannelModel().getModelName(),
                candidate.getChannelApiKey().getKeyName(),
                remaining.size());

        logPhase(traceId, candidate, req, "start",
                "路由到 " + candidate.getChannel().getName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);

        int maxAttempts = getMaxAttempts(req.getModel());
        log.info("开始调用候选 - traceId={} maxAttempts={} (retryCount={})",
                traceId, maxAttempts, getRetryCount(req.getModel()));

        return invokeCandidateWithRetries(traceId, authHeader, req, candidate, provider, retryIndex, 1, maxAttempts)
                .flatMap(body -> {
                    balancer.markSuccess(candidate);
                    String transformed = transformResponse(body, candidate, req, provider, false);
                    requestLogService.logComplete(traceId, candidate.getChannelApiKey().getKeyName(),
                            req.getModel(), candidate.getChannelModel().getModelName(),
                            candidate.getChannel().getName(),
                            "success", "success", "请求成功",
                            System.currentTimeMillis() - startTime, retryIndex);
                    log.info("候选请求成功 - traceId={} channel={} model={} key={}",
                            traceId, candidate.getChannel().getName(),
                            candidate.getChannelModel().getModelName(),
                            candidate.getChannelApiKey().getKeyName());
                    return Mono.just(transformed);
                })
                .onErrorResume(err -> {
                    // 仅在重试耗尽时触发熔断（doOnError会在每次重试都触发）
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
                    return tryCandidates(traceId, remaining, authHeader, req, provider, retryIndex + 1, startTime);
                });
    }

    /**
     * 对单个候选按重试次数进行调用，每次失败立即重试同一候选
     */
    private Mono<String> invokeCandidateWithRetries(String traceId, String authHeader, InternalRequest req,
                                                     RoutingCandidate candidate, String provider,
                                                     int retryIndex, int attempt, int maxAttempts) {
        return callProviderNonStream(authHeader, req, candidate, provider)
                .timeout(Duration.ofSeconds(60))
                .doOnError(err -> log.warn("候选尝试 {}/{} 失败 channel={} key={} model={}: {}",
                        attempt, maxAttempts,
                        candidate.getChannel().getName(),
                        candidate.getChannelApiKey().getKeyName(),
                        candidate.getChannelModel().getModelName(),
                        err.getMessage()))
                .onErrorResume(err -> {
                    if (attempt < maxAttempts) {
                        logPhase(traceId, candidate, req, "retry",
                                "同一候选第 " + attempt + " 次失败，准备第 " + (attempt + 1) + " 次重试", retryIndex);
                        return invokeCandidateWithRetries(traceId, authHeader, req, candidate, provider,
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
    private Flux<SseEvent> executeStreamRelay(String traceId, String authHeader, InternalRequest req, String provider, boolean internalClient) {
        long startTime = System.currentTimeMillis();
        logSkippedCircuitBrokenCandidates(traceId, req);
        List<RoutingCandidate> candidates = getAvailableCandidates(req);
        if (candidates.isEmpty()) {
            requestLogService.logComplete(traceId, null, req.getModel(), null, null,
                    "fail", "error", "没有可用的路由候选", System.currentTimeMillis() - startTime, 0);
            return Flux.error(new RuntimeException("没有可用的路由候选"));
        }

        return tryStreamCandidates(traceId, new ArrayList<>(candidates), authHeader, req, provider, 0, startTime, internalClient);
    }

    /**
     * 顺序尝试流式候选，失败后按配置进行候选内重试；重试耗尽后触发熔断并移除候选，继续下一个
     * （包级可见，便于单元测试）
     *
     * @param internalClient 是否为内部客户端，true 时发送 _routing_progress 等自定义 SSE 事件
     */
    Flux<SseEvent> tryStreamCandidates(String traceId, List<RoutingCandidate> remaining,
                                                String authHeader, InternalRequest req,
                                                String provider, int retryIndex, long startTime,
                                                boolean internalClient) {
        if (remaining.isEmpty()) {
            requestLogService.logComplete(traceId, null, req.getModel(), null, null,
                    "fail", "error", "所有流式候选均失败", System.currentTimeMillis() - startTime, retryIndex);
            log.warn("所有流式候选均失败 - traceId={}", traceId);
            return Flux.error(new RuntimeException("所有流式候选均失败"));
        }

        LoadBalancer balancer = loadBalancerFactory.getBalancer(provider);
        Long modelId = resolveModelId(req.getModel());
        RoutingCandidate candidate = balancer.select(remaining, modelId);
        if (candidate == null) {
            log.warn("流式负载均衡器返回null候选 - traceId={}", traceId);
            return Flux.error(new RuntimeException("没有可用的路由候选"));
        }

        log.info("流式路由决策 - traceId={} retryIndex={} 选中候选: channel={} model={} key={} (剩余{}个候选)",
                traceId, retryIndex,
                candidate.getChannel().getName(),
                candidate.getChannelModel().getModelName(),
                candidate.getChannelApiKey().getKeyName(),
                remaining.size());

        logPhase(traceId, candidate, req, "start",
                "流式路由到 " + candidate.getChannel().getName() + "/" + candidate.getChannelModel().getModelName(), retryIndex);

        int maxAttempts = getMaxAttempts(req.getModel());
        // 仅内部客户端发送路由进度事件
        Flux<SseEvent> routingStart = internalClient
                ? Flux.just(new SseEvent(null, buildRoutingProgressJson("trying", candidate, retryIndex, null)))
                : Flux.empty();
        return Flux.concat(routingStart,
                invokeStreamCandidateWithRetries(traceId, authHeader, req, candidate, provider, retryIndex, 1, maxAttempts, internalClient))
                .doOnComplete(() -> {
                    requestLogService.logComplete(traceId, candidate.getChannelApiKey().getKeyName(),
                            req.getModel(), candidate.getChannelModel().getModelName(),
                            candidate.getChannel().getName(),
                            "success", "success", "流式请求成功",
                            System.currentTimeMillis() - startTime, retryIndex);
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
                    // 仅在重试耗尽时触发熔断（doOnError会在每次重试都触发）
                    log.warn("流式候选失败（重试耗尽）channel={} key={} model={} (已重试{}次): {}",
                            candidate.getChannel().getName(),
                            candidate.getChannelApiKey().getKeyName(),
                            candidate.getChannelModel().getModelName(),
                            maxAttempts - 1,
                            err.getMessage());
                    handleFailure(candidate, req);
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
                            tryStreamCandidates(traceId, remaining, authHeader, req, provider, retryIndex + 1, startTime, internalClient));
                });
    }

    /**
     * 对单个流式候选按重试次数进行调用，每次失败立即重试同一候选
     *
     * @param internalClient 是否为内部客户端，true 时发送 _routing_progress 重试事件
     */
    private Flux<SseEvent> invokeStreamCandidateWithRetries(String traceId, String authHeader, InternalRequest req,
                                                             RoutingCandidate candidate, String provider,
                                                             int retryIndex, int attempt, int maxAttempts,
                                                             boolean internalClient) {
        log.info("开始调用流式候选 - traceId={} attempt={}/{} channel={} model={} key={}",
                traceId, attempt, maxAttempts,
                candidate.getChannel().getName(),
                candidate.getChannelModel().getModelName(),
                candidate.getChannelApiKey().getKeyName());
        return callProviderStream(authHeader, req, candidate, provider, internalClient)
                .timeout(Duration.ofSeconds(60))
                .doOnError(err -> log.warn("流式候选尝试 {}/{} 失败 channel={} key={} model={}: {}",
                        attempt, maxAttempts,
                        candidate.getChannel().getName(),
                        candidate.getChannelApiKey().getKeyName(),
                        candidate.getChannelModel().getModelName(),
                        err.getMessage()))
                .onErrorResume(err -> {
                    if (attempt < maxAttempts) {
                        logPhase(traceId, candidate, req, "retry",
                                "同一流式候选第 " + attempt + " 次失败，准备第 " + (attempt + 1) + " 次重试", retryIndex);
                        // 仅内部客户端发送路由进度事件：候选内重试
                        Flux<SseEvent> routingRetry = internalClient
                                ? Flux.just(new SseEvent(null, buildRoutingProgressJson(
                                        "retrying", candidate, retryIndex,
                                        "第" + attempt + "次失败，第" + (attempt + 1) + "次重试")))
                                : Flux.empty();
                        return Flux.concat(routingRetry,
                                invokeStreamCandidateWithRetries(traceId, authHeader, req, candidate, provider,
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

        return webClient.post()
                .uri(endpoint)
                .headers(h -> headers.forEach(h::add))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(providerReqBody))
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .flatMap(body -> {
                            if (resp.statusCode().is2xxSuccessful()) {
                                return Mono.just(body);
                            }
                            log.warn("Provider returned error status {} for channel={} keyId={}",
                                    resp.statusCode(), candidate.getChannel().getId(), candidate.getChannelApiKey().getId());
                            return Mono.error(new RuntimeException("Provider error: " + resp.statusCode() + " body: " + body));
                        }));
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
        String endpoint = buildEndpoint(candidate, provider);
        String apiKey = candidate.getChannelApiKey().getApiKey();
        Map<String, String> headers = buildProviderHeaders(provider, apiKey, authHeader);
        String providerReqBody = buildProviderRequestBody(req, candidate, provider);

        return webClient.post()
                .uri(endpoint)
                .headers(h -> headers.forEach(h::add))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(providerReqBody))
                .exchangeToFlux(resp -> {
                    if (!resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class)
                                .flatMapMany(body -> {
                                    log.warn("Provider stream error status {} for channel={} keyId={}",
                                            resp.statusCode(), candidate.getChannel().getId(), candidate.getChannelApiKey().getId());
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
                            .concatMap(block -> Flux.fromIterable(parseSseEventBlock(block, candidate, provider, req))));
                });
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
        req.setModel(candidate.getChannelModel().getModelName());
        try {
            if ("anthropic".equals(provider)) {
                return messageTransformer.buildAnthropicRequest(req);
            }
            return messageTransformer.buildOpenAiRequest(req);
        } finally {
            req.setModel(originalModel);
        }
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
     */
    private static class ByteAccumulator {
        private final StringBuilder builder = new StringBuilder();

        public void append(byte[] bytes) {
            builder.append(new String(bytes, StandardCharsets.UTF_8));
        }

        public List<String> extractCompleteEvents() {
            String delimiter = "\n\n";
            List<String> events = new ArrayList<>();
            int idx;
            while ((idx = builder.indexOf(delimiter)) >= 0) {
                events.add(builder.substring(0, idx));
                builder.delete(0, idx + delimiter.length());
            }
            return events;
        }
    }

    /**
     * 解析 SSE 事件块为 SseEvent 列表
     */
    private List<SseEvent> parseSseEventBlock(String block, RoutingCandidate candidate, String provider, InternalRequest req) {
        log.debug("解析SSE事件块 - block长度={}, block内容前100字符={}", block.length(), block.length() > 100 ? block.substring(0, 100) : block);
        List<SseEvent> events = new ArrayList<>();
        String[] lines = block.split("\n");
        String currentEvent = null;
        StringBuilder dataBuilder = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("event:")) {
                if (dataBuilder.length() > 0) {
                    flushEvent(events, currentEvent, dataBuilder.toString(), candidate, provider, req);
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
            flushEvent(events, currentEvent, dataBuilder.toString(), candidate, provider, req);
        }
        log.debug("解析SSE事件块完成 - 生成{}个事件", events.size());
        return events;
    }

    private void flushEvent(List<SseEvent> events, String event, String data,
                            RoutingCandidate candidate, String provider, InternalRequest req) {
        if ("[DONE]".equals(data)) {
            events.add(new SseEvent(null, "[DONE]"));
            return;
        }
        String clientFormat = req.getClientApiFormat();
        String originalModel = req.getModel();
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
        events.add(new SseEvent(event, transformed));
    }

    /**
     * 构建上游请求头
     */
    private Map<String, String> buildProviderHeaders(String provider, String apiKey, String authHeader) {
        Map<String, String> headers = new HashMap<>();
        if ("openai".equals(provider) || "azure".equals(provider)) {
            headers.put("Authorization", "Bearer " + apiKey);
        } else if ("anthropic".equals(provider)) {
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");
        }
        headers.put("Content-Type", "application/json");
        return headers;
    }

    /**
     * 构建上游端点
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
            List<ChannelApiKey> apiKeys = getApiKeysForCandidate(channel.getId(), specifiedKeyId);
            log.info("渠道模型: {} (id={}), 指定API Key: {}, 可用Keys: {}",
                    channelModel.getModelName(), channelModel.getId(), specifiedKeyId, apiKeys.size());

            for (ChannelApiKey apiKey : apiKeys) {
                if (apiKey.getEnabled() == null || apiKey.getEnabled() != 1) {
                    log.debug("API Key被禁用: keyId={}, keyName={}", apiKey.getId(), apiKey.getKeyName());
                    continue;
                }
                if (circuitBreakerService.isChannelCircuitBroken(channel.getId(), apiKey.getId())) {
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
     * 获取自定义模型的候选内重试次数
     */
    private int getRetryCount(String modelName) {
        Long customModelId = resolveModelId(modelName);
        if (customModelId == null) {
            return 0;
        }
        CircuitBreakerConfig config = modelService.getCircuitBreakerConfig(customModelId);
        if (config == null || config.getRetryCount() == null) {
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
     * 记录请求阶段日志
     */
    private void logPhase(String traceId, RoutingCandidate candidate, InternalRequest req,
                          String phase, String message, int retryIndex) {
        String apiKeyName = candidate != null ? candidate.getChannelApiKey().getKeyName() : null;
        String modelName = req != null ? req.getModel() : null;
        String channelModelName = candidate != null ? candidate.getChannelModel().getModelName() : null;
        String channelName = candidate != null ? candidate.getChannel().getName() : null;
        requestLogService.log(traceId, apiKeyName, modelName, channelModelName, channelName, phase, message, retryIndex);
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
        } catch (IOException e) {
            log.warn("Failed to send SSE event", e);
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
        } catch (IOException e) {
            log.warn("Failed to send SSE error", e);
        }
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
     * 记录因熔断被跳过的候选到请求日志，让用户在 UI 中能看到完整的路由链路
     */
    private void logSkippedCircuitBrokenCandidates(String traceId, InternalRequest req) {
        String modelName = req.getModel();
        Long customModelId = resolveModelId(modelName);
        if (customModelId == null) return;

        List<ModelChannelRel> rels = modelService.getChannelRels(customModelId);
        for (ModelChannelRel rel : rels) {
            if (rel.getEnabled() == null || rel.getEnabled() != 1) continue;
            ChannelModel channelModel = modelService.getChannelModelById(rel.getChannelModelId());
            if (channelModel == null || channelModel.getEnabled() == null || channelModel.getEnabled() != 1) continue;
            Channel channel = modelService.getChannelById(channelModel.getChannelId());
            if (channel == null || channel.getEnabled() == null || channel.getEnabled() != 1) continue;

            List<ChannelApiKey> apiKeys = getApiKeysForCandidate(channel.getId(), channelModel.getChannelApiKeyId());
            for (ChannelApiKey apiKey : apiKeys) {
                if (apiKey.getEnabled() == null || apiKey.getEnabled() != 1) continue;
                boolean channelBroken = circuitBreakerService.isChannelCircuitBroken(channel.getId(), apiKey.getId());
                boolean modelBroken = circuitBreakerService.isModelCircuitBroken(channelModel.getId(), apiKey.getId());
                if (channelBroken || modelBroken) {
                    String scope = channelBroken ? "渠道级熔断" : "模型级熔断";
                    logPhase(traceId, new RoutingCandidate(rel, channel, channelModel, apiKey),
                            req, "skip", scope + "跳过 " + channel.getName() + "/" + channelModel.getModelName(), 0);
                }
            }
        }
    }

    public record SseEvent(String event, String data) {}
}
