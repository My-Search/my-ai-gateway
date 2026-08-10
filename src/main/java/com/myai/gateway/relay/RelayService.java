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

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
                        CircuitBreakerRecoveryService circuitBreakerRecoveryService,
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
                webClient, circuitBreakerRecoveryService, streamUsageMap);
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
     * 渠道模型快速测试结果
     *
     * @param content           累积的输出文本（SSE 流式累积，或上游忽略 stream 时的普通 JSON 回退解析）
     * @param firstByteTimeMs   首字节响应时间：从请求发出到收到第一个响应字节（ms）
     * @param usageOutputTokens 上游返回的输出 token 数，上游未返回 usage 时为 null
     */
    public record ChannelTestResult(String content, long firstByteTimeMs, Long usageOutputTokens) {
    }

    /**
     * 渠道模型快速测试（流式）
     * <p>以 {@code stream: true} 请求上游，记录首字节响应时间，并累积输出文本与 usage 统计。</p>
     */
    public ChannelTestResult testChannelModel(Channel channel, ChannelModel channelModel, ChannelApiKey apiKey, String message) {
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
            reqNode.put("stream", true);
            // OpenAI 兼容接口请求返回 usage 统计；不支持的实现会忽略该字段，走估算回退
            if (!"anthropic".equals(provider) && !"azure".equals(provider)) {
                reqNode.putObject("stream_options").put("include_usage", true);
            }
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

        long requestStart = System.currentTimeMillis();
        final AtomicLong firstByteTimeMs = new AtomicLong(-1);
        final ByteArrayOutputStream rawBody = new ByteArrayOutputStream();

        String body;
        try {
            body = testWebClient.post()
                    .uri(endpoint)
                    .headers(h -> headers.forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(requestBody))
                    .exchangeToMono(resp -> {
                        if (!resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(String.class).flatMap(errBody -> {
                                log.warn("渠道模型测试失败: status={}, channel={}, model={}, key={}",
                                        resp.statusCode(), channel.getName(), channelModel.getModelName(), apiKey.getKeyName());
                                return Mono.error(new RuntimeException("渠道测试失败: " + resp.statusCode() + " body: " + errBody));
                            });
                        }
                        return resp.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                                .doOnNext(buf -> {
                                    // 记录首字节响应时间：第一个响应字节到达时
                                    firstByteTimeMs.compareAndSet(-1, System.currentTimeMillis() - requestStart);
                                    byte[] bytes = new byte[buf.readableByteCount()];
                                    buf.read(bytes);
                                    org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                                    if (rawBody.size() + bytes.length > 10 * 1024 * 1024) {
                                        throw new RuntimeException("渠道测试响应超过 10MB 限制");
                                    }
                                    rawBody.write(bytes, 0, bytes.length);
                                })
                                .then(Mono.fromCallable(() -> new String(rawBody.toByteArray(), StandardCharsets.UTF_8)));
                    })
                    .block(); // 不限制超时时间，等待上游响应完成
        } catch (Exception e) {
            log.error("渠道模型测试异常: channel={}, model={}: {}",
                    channel.getName(), channelModel.getModelName(), e.getMessage());
            throw e;
        }

        ChannelTestResult parsed = parseStreamingTestResponse(body, provider);
        return new ChannelTestResult(parsed.content(), firstByteTimeMs.get(), parsed.usageOutputTokens());
    }

    /**
     * 解析流式测试响应（SSE）：累积各 data 行的输出文本与 usage。
     * <p>上游忽略 {@code stream} 参数直接返回普通 JSON（非合规实现）时，回退按非流式格式解析。</p>
     */
    private ChannelTestResult parseStreamingTestResponse(String body, String provider) {
        StringBuilder content = new StringBuilder();
        Long usageTokens = null;
        boolean sawData = false;
        for (String block : body.split("\n\n")) {
            StringBuilder dataBuilder = new StringBuilder();
            for (String line : block.split("\n")) {
                if (!line.startsWith("data:")) continue;
                if (dataBuilder.length() > 0) dataBuilder.append("\n");
                dataBuilder.append(line.substring("data:".length()).trim());
            }
            String data = dataBuilder.toString();
            if (data.isEmpty()) continue;
            sawData = true;
            if ("[DONE]".equals(data)) continue;
            String text = sseHandler.extractTextContentFromRawData(data, provider);
            if (text != null && !text.isEmpty()) {
                content.append(text);
            } else if (content.length() == 0) {
                // 尚未累积到文本时，尝试提取流中的 error 事件信息
                String errMsg = extractSseErrorText(data);
                if (errMsg != null) {
                    content.append("API Error: ").append(errMsg);
                }
            }
            int[] usage = sseHandler.extractUsageFromSseData(data);
            if (usage != null) usageTokens = (long) usage[1];
        }
        if (!sawData) {
            // 非 SSE 响应（普通 JSON/纯文本）：回退解析
            content.setLength(0);
            usageTokens = null;
            parseNonStreamingJson(body, provider, content);
            int[] usage = sseHandler.extractUsageFromSseData(body);
            if (usage != null) usageTokens = (long) usage[1];
        }
        return new ChannelTestResult(content.toString(), -1, usageTokens);
    }

    /**
     * 从 SSE data 中提取错误信息（{@code {"error": ...}}），非错误事件时返回 null
     */
    private String extractSseErrorText(String data) {
        try {
            JsonNode json = objectMapper.readTree(data);
            JsonNode err = json.get("error");
            if (err == null) return null;
            if (err.isObject() && err.has("message")) return err.get("message").asText();
            return err.isTextual() ? err.asText() : err.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 非流式 JSON 回退解析：兼容忽略 stream 参数直接返回普通 JSON 的上游实现
     */
    private void parseNonStreamingJson(String body, String provider, StringBuilder content) {
        try {
            JsonNode json = objectMapper.readTree(body);
            if ("anthropic".equals(provider) && json.has("content") && json.get("content").isArray() && json.get("content").size() > 0) {
                content.append(json.get("content").get(0).path("text").asText());
            } else if (json.has("choices") && json.get("choices").isArray() && json.get("choices").size() > 0) {
                JsonNode message = json.get("choices").get(0).path("message");
                if (message.has("content") && !message.get("content").isNull()) {
                    content.append(message.get("content").asText());
                }
            }
            if (content.length() == 0 && json.has("error")) {
                content.append("API Error: ").append(json.get("error").toString());
            }
        } catch (Exception ignored) {
            // 非 JSON 响应（如纯文本/错误页），整段保留
        }
        if (content.length() == 0) {
            content.append(body.length() > 500 ? body.substring(0, 500) + "..." : body);
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
