package com.myai.gateway.relay.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
import com.myai.gateway.relay.transformer.registry.ProtocolTranslator;
import com.myai.gateway.relay.transformer.registry.StreamTranslateState;
import com.myai.gateway.relay.transformer.registry.TranslatorRegistry;
import com.myai.gateway.service.RequestLogService;
import com.myai.gateway.relay.StreamContentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 事件处理器
 * <p>负责 SSE 事件的构建、解析、发送、错误处理和资源清理。</p>
 */
public class SseHandler {

    private static final Logger log = LoggerFactory.getLogger(SseHandler.class);

    private final ObjectMapper objectMapper;
    private final MessageTransformer messageTransformer;
    private final TranslatorRegistry translatorRegistry;
    private final StreamContentManager streamContentManager;
    private final RequestLogService requestLogService;
    /** 流式请求跨 chunk 翻译状态：traceId -> StreamTranslateState */
    private final ConcurrentHashMap<String, StreamTranslateState> streamTranslateStates;
    /** 流式请求 token 用量累积器：traceId -> [promptTokens, completionTokens, totalTokens] */
    private final ConcurrentHashMap<String, int[]> streamUsageMap;

    public SseHandler(ObjectMapper objectMapper,
                      MessageTransformer messageTransformer,
                      TranslatorRegistry translatorRegistry,
                      StreamContentManager streamContentManager,
                      RequestLogService requestLogService,
                      ConcurrentHashMap<String, StreamTranslateState> streamTranslateStates,
                      ConcurrentHashMap<String, int[]> streamUsageMap) {
        this.objectMapper = objectMapper;
        this.messageTransformer = messageTransformer;
        this.translatorRegistry = translatorRegistry;
        this.streamContentManager = streamContentManager;
        this.requestLogService = requestLogService;
        this.streamTranslateStates = streamTranslateStates;
        this.streamUsageMap = streamUsageMap;
    }

    // ========== 事件构建 ==========

    /**
     * 构建 _gateway_meta SSE 事件，包含渠道类型、渠道名、模型名等信息
     */
    public SseEvent buildGatewayMetaEvent(RoutingCandidate candidate) {
        ObjectNode gatewayMeta = objectMapper.createObjectNode();
        gatewayMeta.put("_gateway_meta", true);
        gatewayMeta.put("channel_type", candidate.getChannel().getChannelType());
        gatewayMeta.put("channel", candidate.getChannel().getName());
        gatewayMeta.put("channel_model", candidate.getChannelModel().getModelName());
        return new SseEvent(null, gatewayMeta.toString());
    }

    /**
     * 构建路由进度 SSE 事件 JSON
     *
     * @param phase    阶段类型：trying / retrying / switching
     * @param candidate 当前路由候选
     * @param retryIndex 候选重试索引
     * @param extraMsg 附加说明
     */
    public String buildRoutingProgressJson(String phase, RoutingCandidate candidate,
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

    // ========== SSE 事件解析 ==========

    /**
     * 从 DataBuffer 流中提取完整的 SSE 事件块（按双换行分割）
     */
    public Flux<String> extractCompleteEvents(Flux<DataBuffer> buffers) {
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
     * 解析 SSE 事件块为 SseEvent 列表
     */
    public List<SseEvent> parseSseEventBlock(String block, RoutingCandidate candidate,
                                              String provider, InternalRequest req) {
        return parseSseEventBlock(block, candidate, provider, req, null);
    }

    /**
     * 解析 SSE 事件块为 SseEvent 列表，并累积内容
     *
     * @param traceId 链路追踪ID，不为null时启用内容累积
     */
    public List<SseEvent> parseSseEventBlock(String block, RoutingCandidate candidate,
                                              String provider, InternalRequest req, String traceId) {
        log.debug("解析SSE事件块 - block长度={}, block内容前100字符={}",
                block.length(), block.length() > 100 ? block.substring(0, 100) : block);
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

    /**
     * 处理单个 SSE 事件：通过 TranslatorRegistry 进行协议转换，
     * 使用 per-traceId 的 StreamTranslateState 维护跨 chunk 上下文。
     */
    private void flushEvent(List<SseEvent> events, String event, String data,
                             RoutingCandidate candidate, String provider,
                             InternalRequest req, String traceId) {
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
                    log.debug("SSE事件被丢弃 - event={}, provider={}, clientFormat={}",
                            event, provider, clientFormat);
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
            // 无翻译器时降级到 MessageTransformer
            log.debug("未找到翻译器 {}→{}，降级到 MessageTransformer", provider, clientFormat);
        }

        // 同格式或降级：走 MessageTransformer
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
            log.debug("SSE事件被丢弃 - event={}, data={}, provider={}, clientFormat={}",
                    event, data, provider, clientFormat);
            return;
        }
        // 累积流式内容
        if (traceId != null) {
            String content = extractTextContentFromRawData(data, provider);
            if (content != null && !content.isEmpty()) {
                streamContentManager.appendContent(traceId, content);
            }
        }
        addSseEventSplit(events, event, transformed);
    }

    /**
     * 添加 SSE 事件，如果 data 包含换行符则拆分为多个独立事件。
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

    // ========== 内容提取 ==========

    /**
     * 从原始 SSE 数据中提取文本内容
     */
    public String extractTextContentFromRawData(String rawData, String provider) {
        try {
            JsonNode json = objectMapper.readTree(rawData);
            if ("anthropic".equals(provider)) {
                JsonNode delta = json.get("delta");
                if (delta != null && delta.has("text")) {
                    return delta.get("text").asText();
                }
                JsonNode contentBlock = json.get("content_block");
                if (contentBlock != null && contentBlock.has("text")) {
                    return contentBlock.get("text").asText();
                }
            } else {
                JsonNode choices = json.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null) {
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
     * 从 SSE 事件数据中提取 token 用量（流式响应的最后一个含 usage 的 chunk）
     *
     * @return [promptTokens, completionTokens, totalTokens]，无 usage 时返回 null
     */
    public int[] extractUsageFromSseData(String data) {
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

    // ========== SSE 发送 ==========

    /**
     * 发送 SSE 事件
     */
    public void sendSseEvent(SseEmitter emitter, SseEvent event) {
        try {
            log.debug("发送SSE事件 - event={}, data长度={}", event.event(), event.data().length());
            if ("[DONE]".equals(event.data())) {
                emitter.send(SseEmitter.event().data("[DONE]"));
            } else if (event.event() != null && !event.event().isEmpty()) {
                emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
            } else {
                emitter.send(SseEmitter.event().data(event.data()));
            }
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开 / SseEmitter 已超时/已完成，抛出 RuntimeException 触发 subscriber onError
            throw new RuntimeException("Client disconnected from SSE stream", e);
        }
    }

    /**
     * 发送 SSE 错误事件
     */
    public void sendSseError(SseEmitter emitter, String message) {
        try {
            ObjectNode err = objectMapper.createObjectNode();
            err.put("error", message != null ? message : "Unknown stream error");
            emitter.send(SseEmitter.event().name("error").data(err.toString()));
        } catch (Exception e) {
            log.warn("Failed to send SSE error (client already disconnected?): {}", e.getMessage());
        }
        safeCompleteEmitter(emitter);
    }

    /**
     * 安全地完成 SseEmitter，吞掉所有二次异常
     */
    public void safeCompleteEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // emitter 可能已关闭/已完成
        }
    }

    /**
     * 处理流式 subscribe 的 onError 回调
     */
    public void handleStreamSubscribeError(String traceId, Long gatewayApiKeyId,
                                            SseEmitter emitter, Throwable err,
                                            java.util.concurrent.atomic.AtomicBoolean finalStateLogged) {
        log.error("Stream relay failed - traceId={}", traceId, err);
        cleanupStreamResources(traceId);
        String msg = err.getMessage() != null ? err.getMessage() : "";
        if (msg.contains("Client disconnected") && finalStateLogged.compareAndSet(false, true)) {
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, null, null, null,
                    "fail", "interrupted", "客户端断开连接", 0, 0);
            safeCompleteEmitter(emitter);
        } else if (finalStateLogged.compareAndSet(false, true)) {
            requestLogService.logComplete(traceId, null, gatewayApiKeyId, null, null, null,
                    "fail", "error", msg.isEmpty() ? "流式请求失败" : msg, 0, 0);
            sendSseError(emitter, msg);
        }
    }

    /**
     * 清理 SSE 流资源
     */
    public void cleanupStreamResources(String traceId) {
        streamContentManager.clearContent(traceId);
        streamUsageMap.remove(traceId);
        streamTranslateStates.remove(traceId);
    }

    // ========== 内部类 ==========

    /**
     * SSE 字节累积器
     * <p>累积原始字节，仅在提取完整 SSE 事件（按 {@code \n\n} 分隔）时解码为 String。</p>
     */
    public static class ByteAccumulator {
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

                int eventLength = delimiterPos - searchStart;
                if (eventLength > 0) {
                    String event = new String(allBytes, searchStart, eventLength, StandardCharsets.UTF_8);
                    events.add(event);
                }

                searchStart = delimiterPos + delimiter.length;
            }

            // 保留剩余字节（不完整事件数据）到 buffer 中
            buffer.reset();
            if (searchStart < allBytes.length) {
                buffer.write(allBytes, searchStart, allBytes.length - searchStart);
            }

            return events;
        }

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
}
