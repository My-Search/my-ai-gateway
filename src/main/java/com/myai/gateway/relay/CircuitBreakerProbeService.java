package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelHeaders;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.Map;

/**
 * 熔断探测服务
 * <p>对熔断到期的模型/渠道发送最小探测请求（max_tokens=1 的极简 chat 请求），
 * 用于判断门能否打开。2xx 视为探测成功，其余（4xx/5xx/超时/连接失败）视为失败。</p>
 *
 * <p>独立于 {@link RelayService#testChannelModel}：探测只关心成功与否（布尔），
 * 且必须带超时（{@value #PROBE_TIMEOUT_SECONDS}s），避免探测任务长时间阻塞。</p>
 */
@Service
public class CircuitBreakerProbeService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerProbeService.class);

    /** 探测超时（秒）：连接 + 响应总超时 */
    public static final int PROBE_TIMEOUT_SECONDS = 5;

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public CircuitBreakerProbeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ConnectionProvider provider = ConnectionProvider
                .builder("relay-probe")
                .maxConnections(10)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * 探测渠道模型是否可用。
     *
     * @param channel      渠道（含 baseUrl / channelType）
     * @param channelModel 渠道模型（探测目标）
     * @param apiKey       渠道 API Key
     * @return true=2xx 响应（可用）；false=任何失败（不可用）
     */
    public boolean probe(Channel channel, ChannelModel channelModel, ChannelApiKey apiKey) {
        if (channel == null || channelModel == null || apiKey == null) {
            return false;
        }
        String endpoint = buildEndpoint(channel, channelModel.getChannelType());
        Map<String, String> headers = buildProviderHeaders(channel, apiKey.getApiKey());
        String requestBody;
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", channelModel.getModelName());
            root.put("max_tokens", 1);
            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", "ping");
            requestBody = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("构建探测请求体失败: channelModelId={}, err={}", channelModel.getId(), e.getMessage());
            return false;
        }

        log.debug("熔断探测 - channel={}, model={}, key={}, endpoint={}",
                channel.getName(), channelModel.getModelName(), apiKey.getKeyName(), endpoint);
        try {
            return Boolean.TRUE.equals(webClient.post()
                    .uri(endpoint)
                    .headers(h -> headers.forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(requestBody))
                    .exchangeToMono(resp -> {
                        if (resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(body -> true);
                        }
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> false);
                    })
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .block());
        } catch (Exception e) {
            log.warn("熔断探测失败 - channel={}, model={}, key={}: {}",
                    channel.getName(), channelModel.getModelName(), apiKey.getKeyName(), e.getMessage());
            return false;
        }
    }

    /**
     * 构建探测端点（与 CandidateRouter.buildEndpoint 逻辑一致）
     */
    private String buildEndpoint(Channel channel, String fallbackType) {
        String provider = channel.getChannelType();
        if (provider == null || provider.isBlank()) {
            provider = fallbackType;
        }
        String baseUrl = channel.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "anthropic".equals(provider) ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1";
        }
        baseUrl = baseUrl.replaceAll("/$", "");
        if ("azure".equals(provider)) return baseUrl;
        return baseUrl + ("anthropic".equals(provider) ? "/messages" : "/chat/completions");
    }

    /**
     * 构建探测请求头（与 CandidateRouter.buildProviderHeaders 逻辑一致）
     */
    private Map<String, String> buildProviderHeaders(Channel channel, String apiKeyValue) {
        Map<String, String> headers = new java.util.HashMap<>();
        String key = apiKeyValue != null ? apiKeyValue.trim() : "";
        String provider = channel.getChannelType();
        if ("azure".equals(provider)) {
            headers.put("api-key", key);
        } else if ("anthropic".equals(provider)) {
            headers.put("x-api-key", key);
            headers.put("anthropic-version", "2023-06-01");
        } else {
            headers.put("Authorization", "Bearer " + key);
        }
        headers.put("Content-Type", "application/json");
        ChannelHeaders.mergeInto(channel.getCustomHeaders(), headers);
        return headers;
    }
}
