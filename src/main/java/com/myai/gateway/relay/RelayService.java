package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.*;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.stream.SseEvent;
import com.myai.gateway.relay.stream.SseHandler;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
import com.myai.gateway.relay.transformer.registry.TranslatorRegistry;
import com.myai.gateway.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 核心中继服务（门面）
 * <p>对外暴露统一的请求入口，内部委托给专门的子组件处理。</p>
 *
 * <p>职责分解：</p>
 * <ul>
 *   <li>{@link RequestPreprocessor} — 请求解析、Prompt 注入、多模态失效</li>
 *   <li>{@link RouteResolver} — 模型路由解析、候选构建</li>
 *   <li>{@link CandidateRouter} — 候选路由、重试、熔断、上游调用、响应转换</li>
 *   <li>{@link SseHandler} — SSE 事件构建、解析、发送、错误处理</li>
 *   <li>{@link RelayLogger} — 日志记录、API Key 掩码</li>
 * </ul>
 */
@Service
public class RelayService {

    private static final Logger log = LoggerFactory.getLogger(RelayService.class);

    private final RequestPreprocessor requestPreprocessor;
    private final RouteResolver routeResolver;
    private final CandidateRouter candidateRouter;
    private final SseHandler sseHandler;
    private final RelayLogger relayLogger;
    private final MessageTransformer messageTransformer;
    private final RequestLogService requestLogService;
    private final ApiKeyService apiKeyService;
    private final LatencyTracker latencyTracker;
    private final ModelService modelService;
    private final StreamContentManager streamContentManager;
    private final ObjectMapper objectMapper;

    /** 流式请求 token 用量累积器 */
    final ConcurrentHashMap<String, int[]> streamUsageMap = new ConcurrentHashMap<>();
    /** 流式请求跨 chunk 翻译状态 */
    final ConcurrentHashMap<String, com.myai.gateway.relay.transformer.registry.StreamTranslateState> streamTranslateStates = new ConcurrentHashMap<>();

    public RelayService(ChannelService channelService,
                        ChannelApiKeyService channelApiKeyService,
                        ApiKeyService apiKeyService,
                        ModelService modelService,
                        CircuitBreakerService circuitBreakerService,
                        RequestLogService requestLogService,
                        com.myai.gateway.relay.balancer.LoadBalancerFactory loadBalancerFactory,
                        ObjectMapper objectMapper,
                        MessageTransformer messageTransformer,
                        TranslatorRegistry translatorRegistry,
                        StreamContentManager streamContentManager,
                        LatencyTracker latencyTracker,
                        PromptInjectionService promptInjectionService) {
        this.messageTransformer = messageTransformer;
        this.requestLogService = requestLogService;
        this.apiKeyService = apiKeyService;
        this.latencyTracker = latencyTracker;
        this.modelService = modelService;
        this.streamContentManager = streamContentManager;
        this.objectMapper = objectMapper;

        // 创建子组件
        this.relayLogger = new RelayLogger(requestLogService, apiKeyService);
        this.sseHandler = new SseHandler(objectMapper, messageTransformer, translatorRegistry,
                streamContentManager, requestLogService, streamTranslateStates, streamUsageMap);
        this.routeResolver = new RouteResolver(modelService, circuitBreakerService, channelApiKeyService, relayLogger);
        this.requestPreprocessor = new RequestPreprocessor(messageTransformer, routeResolver,
                promptInjectionService, modelService);

        // 构建 WebClient（连接池化）
        reactor.netty.resources.ConnectionProvider provider = reactor.netty.resources.ConnectionProvider
                .builder("relay")
                .maxConnections(100)
                .pendingAcquireTimeout(java.time.Duration.ofSeconds(30))
                .maxIdleTime(java.time.Duration.ofSeconds(30))
                .build();
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create(provider)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
        WebClient webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        this.candidateRouter = new CandidateRouter(
                modelService, circuitBreakerService, requestLogService, loadBalancerFactory,
                objectMapper, messageTransformer, streamContentManager,
                latencyTracker, sseHandler,
                routeResolver, requestPreprocessor, relayLogger,
                webClient, streamUsageMap);
    }

    // ========== 非流式入口 ==========

    /**
     * OpenAI 兼容：非流式聊天补全
     */
    public Mono<String> chatCompletions(String authHeader, String requestBody) {
        return relayNonStream("openai", authHeader,
                relayLogger.buildOpenaiHeadersJson(authHeader), requestBody);
    }

    /**
     * Anthropic 兼容：非流式消息
     */
    public Mono<String> messages(String apiKeyHeader, String requestBody, String anthropicVersion) {
        return relayNonStream("anthropic", apiKeyHeader,
                relayLogger.buildAnthropicHeadersJson(apiKeyHeader, anthropicVersion), requestBody);
    }

    /**
     * 非流式中继内部实现（统一 OpenAI / Anthropic 协议入口）
     * <p>主流程：记录原始请求 -> 鉴权 -> 解析请求 -> 委托 CandidateRouter 执行路由</p>
     *
     * @param protocol    协议类型："openai" 或 "anthropic"
     * @param authHeader  鉴权头原值（透传给下游 CandidateRouter）
     * @param headersJson 记录日志用的脱敏请求头 JSON
     * @param requestBody 原始请求体
     */
    private Mono<String> relayNonStream(String protocol, String authHeader, String headersJson, String requestBody) {
        String traceId = requestLogService.startTrace();
        Long gatewayApiKeyId = relayLogger.logOriginalRequest(traceId, authHeader, headersJson, requestBody);
        if (gatewayApiKeyId == null) {
            requestLogService.logComplete(traceId, null, null, null, null, null,
                    "fail", "auth", "无效或缺失的 API Key", 0, 0);
            return Mono.just(messageTransformer.buildErrorResponse(
                    protocol, "无效或缺失的 API Key", "authentication_error", 401));
        }
        return Mono.fromCallable(() -> {
                    InternalRequest req = requestPreprocessor.parseRequest(requestBody, protocol);
                    requestPreprocessor.applyPromptInjections(req);
                    requestPreprocessor.preprocessMediaInvalidation(req);
                    requestPreprocessor.applyReasoningEffortOverride(req);
                    return req;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(req -> candidateRouter.executeRelay(traceId, authHeader, gatewayApiKeyId, req, protocol,
                        this::callProviderNonStream));
    }

    // ========== 流式入口 ==========

    /**
     * OpenAI 兼容：流式聊天补全
     */
    public SseEmitter chatCompletionsStream(String authHeader, String requestBody, boolean internalClient) {
        return relayStream("openai", authHeader,
                relayLogger.buildOpenaiHeadersJson(authHeader), requestBody, internalClient);
    }

    /**
     * Anthropic 兼容：流式消息
     */
    public SseEmitter messagesStream(String apiKeyHeader, String requestBody, String anthropicVersion, boolean internalClient) {
        return relayStream("anthropic", apiKeyHeader,
                relayLogger.buildAnthropicHeadersJson(apiKeyHeader, anthropicVersion), requestBody, internalClient);
    }

    /**
     * 流式中继内部实现（统一 OpenAI / Anthropic 协议入口）
     * <p>主流程：记录原始请求 -> 鉴权 -> 创建 SseEmitter -> 解析请求 -> 委托 CandidateRouter 执行流式路由</p>
     * <p>资源管理：emitter 完成/超时时自动清理 Disposable 和流状态</p>
     *
     * @param protocol      协议类型："openai" 或 "anthropic"
     * @param authHeader    鉴权头原值（透传给下游 CandidateRouter）
     * @param headersJson   记录日志用的脱敏请求头 JSON
     * @param requestBody   原始请求体
     * @param internalClient 是否为内部客户端调用
     */
    private SseEmitter relayStream(String protocol, String authHeader, String headersJson,
                                   String requestBody, boolean internalClient) {
        SseEmitter emitter = new SseEmitter(0L);
        String traceId = requestLogService.startTrace();
        Long gatewayApiKeyId = relayLogger.logOriginalRequest(traceId, authHeader, headersJson, requestBody);
        if (gatewayApiKeyId == null) {
            requestLogService.logComplete(traceId, null, null, null, null, null,
                    "fail", "auth", "无效或缺失的 API Key", 0, 0);
            sseHandler.sendSseError(emitter, "无效或缺失的 API Key");
            return emitter;
        }

        Disposable[] disposableRef = new Disposable[1];
        AtomicBoolean finalStateLogged = new AtomicBoolean(false);

        emitter.onCompletion(() -> {
            if (finalStateLogged.compareAndSet(false, true)) {
                requestLogService.logComplete(traceId, null, gatewayApiKeyId, null, null, null,
                        "fail", "interrupted", "客户端断开连接", 0, 0);
            }
            sseHandler.cleanupStreamResources(traceId);
            if (disposableRef[0] != null && !disposableRef[0].isDisposed()) {
                disposableRef[0].dispose();
            }
        });
        emitter.onTimeout(() -> {
            log.warn("SSE stream timeout, cleaning up resources - traceId={}", traceId);
            emitter.complete();
        });

        disposableRef[0] = Mono.fromCallable(() -> {
                    InternalRequest req = requestPreprocessor.parseRequest(requestBody, protocol);
                    requestPreprocessor.applyPromptInjections(req);
                    requestPreprocessor.preprocessMediaInvalidation(req);
                    requestPreprocessor.applyReasoningEffortOverride(req);
                    return req;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(req -> candidateRouter.executeStreamRelay(traceId, authHeader, gatewayApiKeyId,
                        req, protocol, internalClient, finalStateLogged, new ProviderInvoker() {
                            @Override
                            public Mono<String> invokeNonStream(String h, InternalRequest r, RoutingCandidate c, String p) {
                                throw new UnsupportedOperationException("Non-stream not supported in stream relay");
                            }
                            @Override
                            public Flux<SseEvent> invokeStream(String h, InternalRequest r, RoutingCandidate c, String p, boolean ic, String t) {
                                return callProviderStream(h, r, c, p, ic, t);
                            }
                        }))
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic(), 1)
                .subscribe(
                        event -> sseHandler.sendSseEvent(emitter, event),
                        err -> sseHandler.handleStreamSubscribeError(traceId, gatewayApiKeyId, emitter, err, finalStateLogged),
                        () -> {
                            finalStateLogged.set(true);
                            sseHandler.cleanupStreamResources(traceId);
                            emitter.complete();
                        }
                );
        return emitter;
    }

    // ========== 工具方法 ==========

    /**
     * 渠道模型快速测试
     */
    public String testChannelModel(Channel channel, ChannelModel channelModel, ChannelApiKey apiKey, String message) {
        String provider = channel.getChannelType();
        String baseUrl = channel.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "anthropic".equals(provider) ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1";
        }
        baseUrl = baseUrl.replaceAll("/$", "");
        String endpoint = "azure".equals(provider)
                ? baseUrl
                : baseUrl + ("anthropic".equals(provider) ? "/messages" : "/chat/completions");

        com.myai.gateway.relay.balancer.RoutingCandidate dummyCandidate =
                new com.myai.gateway.relay.balancer.RoutingCandidate(null, channel, channelModel, apiKey);
        Map<String, String> headers = candidateRouter.buildProviderHeaders(provider, apiKey.getApiKey(), null);

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

        // 复用 CandidateRouter 的 webClient
        org.springframework.web.reactive.function.client.WebClient.Builder wbBuilder = org.springframework.web.reactive.function.client.WebClient.builder();
        reactor.netty.resources.ConnectionProvider providerConn = reactor.netty.resources.ConnectionProvider
                .builder("relay-test")
                .maxConnections(10)
                .pendingAcquireTimeout(java.time.Duration.ofSeconds(10))
                .maxIdleTime(java.time.Duration.ofSeconds(10))
                .build();
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create(providerConn)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
        WebClient testWebClient = wbBuilder
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        log.info("渠道模型测试: channel={}, model={}, key={}, endpoint={}",
                channel.getName(), channelModel.getModelName(), apiKey.getKeyName(), endpoint);

        try {
            return testWebClient.post()
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
                    .block(); // 不限制超时时间，等待上游响应完成
        } catch (Exception e) {
            log.error("渠道模型测试异常: channel={}, model={}: {}",
                    channel.getName(), channelModel.getModelName(), e.getMessage());
            throw e;
        }
    }

    // ========== 包级可见方法（测试兼容 + ProviderInvoker 回调） ==========

    /**
     * 获取可用路由候选（包级可见，便于单元测试）
     */
    List<RoutingCandidate> getAvailableCandidates(InternalRequest req) {
        return routeResolver.getAvailableCandidates(req);
    }

    /**
     * 非流式候选尝试（包级可见，便于单元测试）
     * <p>内部通过 {@link ProviderInvoker} 委托给 {@link CandidateRouter}，
     * 同时传入 {@code this::callProviderNonStream} 以便 Mockito spy 拦截。</p>
     */
    Mono<String> tryCandidates(String traceId, List<RoutingCandidate> remaining,
                                         String authHeader, Long gatewayApiKeyId, InternalRequest req,
                                         String provider, int retryIndex, long startTime) {
        return candidateRouter.tryCandidates(traceId, remaining, authHeader, gatewayApiKeyId,
                req, provider, retryIndex, startTime, this::callProviderNonStream);
    }

    /**
     * 调用上游非流式接口（包级可见，便于单元测试）
     * <p>使用 {@link CandidateRouter#callProviderNonStreamWithWebClient} 的默认实现。</p>
     */
    Mono<String> callProviderNonStream(String authHeader, InternalRequest req,
                                        RoutingCandidate candidate, String provider) {
        return candidateRouter.callProviderNonStreamWithWebClient(authHeader, req, candidate, provider);
    }

    /**
     * 调用上游流式接口（包级可见，便于单元测试）
     * <p>使用 {@link CandidateRouter#callProviderStreamWithWebClient} 的默认实现。</p>
     *
     * @param traceId 链路追踪ID，透传给 WebClient 实现以启用 SSE 内容累积与跨 chunk 翻译状态
     */
    Flux<SseEvent> callProviderStream(String authHeader, InternalRequest req,
                                       RoutingCandidate candidate, String provider,
                                       boolean internalClient, String traceId) {
        return candidateRouter.callProviderStreamWithWebClient(authHeader, req, candidate, provider, internalClient, traceId);
    }

    /**
     * 构建带上下文的请求（包级可见，便于单元测试）
     */
    InternalRequest buildRequestWithContext(InternalRequest originalReq, String accumulatedContent) {
        return requestPreprocessor.buildRequestWithContext(originalReq, accumulatedContent);
    }

    /**
     * 从原始 SSE 数据中提取文本内容（包级可见，便于单元测试）
     */
    String extractTextContentFromRawData(String rawData, String provider) {
        return sseHandler.extractTextContentFromRawData(rawData, provider);
    }

    /**
     * 构建上游请求体（包级可见，便于单元测试）
     */
    String buildProviderRequestBody(InternalRequest req, RoutingCandidate candidate, String provider) {
        return candidateRouter.buildProviderRequestBody(req, candidate, provider);
    }
}
