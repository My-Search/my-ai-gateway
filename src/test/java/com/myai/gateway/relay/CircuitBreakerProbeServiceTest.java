package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CircuitBreakerProbeService 单元测试
 * 使用 reactor-netty 本地 HTTP 服务器验证探测成功/失败/端点与请求头
 */
class CircuitBreakerProbeServiceTest {

    private CircuitBreakerProbeService probeService;
    private DisposableServer server;
    private int port;

    private Channel channel;
    private ChannelModel channelModel;
    private ChannelApiKey apiKey;

    @BeforeEach
    void setUp() {
        probeService = new CircuitBreakerProbeService(new ObjectMapper());

        channel = new Channel();
        channel.setId(1L);
        channel.setName("test");
        channel.setChannelType("openai");
        channel.setBaseUrl("http://127.0.0.1:PORT/v1");
        channel.setEnabled(1);

        channelModel = new ChannelModel();
        channelModel.setId(10L);
        channelModel.setChannelId(1L);
        channelModel.setModelName("gpt-test");
        channelModel.setEnabled(1);

        apiKey = new ChannelApiKey();
        apiKey.setId(20L);
        apiKey.setKeyName("key-1");
        apiKey.setApiKey("sk-test-123");
        apiKey.setEnabled(1);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void probe_2xxResponse_returnsTrue() {
        startServer((req, res) -> res
                .header("Content-Type", "application/json")
                .sendString(Mono.just("{\"id\":\"1\",\"object\":\"chat.completion\"}")));
        channel.setBaseUrl("http://127.0.0.1:" + port + "/v1");

        assertThat(probeService.probe(channel, channelModel, apiKey)).isTrue();
    }

    @Test
    void probe_5xxResponse_returnsFalse() {
        startServer((req, res) -> res.status(500)
                .header("Content-Type", "application/json")
                .sendString(Mono.just("{\"error\":\"boom\"}")));
        channel.setBaseUrl("http://127.0.0.1:" + port + "/v1");

        assertThat(probeService.probe(channel, channelModel, apiKey)).isFalse();
    }

    @Test
    void probe_4xxResponse_returnsFalse() {
        startServer((req, res) -> res.status(401)
                .header("Content-Type", "application/json")
                .sendString(Mono.just("{\"error\":\"unauthorized\"}")));
        channel.setBaseUrl("http://127.0.0.1:" + port + "/v1");

        assertThat(probeService.probe(channel, channelModel, apiKey)).isFalse();
    }

    @Test
    void probe_connectionRefused_returnsFalse() {
        // 不启动服务器，端口必然拒绝连接
        channel.setBaseUrl("http://127.0.0.1:1/v1");

        assertThat(probeService.probe(channel, channelModel, apiKey)).isFalse();
    }

    @Test
    void probe_anthropicEndpoint_usesMessagesPath() {
        startServer((req, res) -> {
            // 注意：reactor-netty 的 req.path() 不含前导斜杠，用 uri() 比较
            String path = req.uri();
            if (!"/v1/messages".equals(path)) {
                return res.status(404).sendString(Mono.just("wrong path: " + path));
            }
            return res.header("Content-Type", "application/json")
                    .sendString(Mono.just("{\"id\":\"1\",\"type\":\"message\"}"));
        });
        channel.setChannelType("anthropic");
        channel.setBaseUrl("http://127.0.0.1:" + port + "/v1");

        assertThat(probeService.probe(channel, channelModel, apiKey)).isTrue();
    }

    @Test
    void probe_nullArguments_returnsFalse() {
        assertThat(probeService.probe(null, channelModel, apiKey)).isFalse();
        assertThat(probeService.probe(channel, null, apiKey)).isFalse();
        assertThat(probeService.probe(channel, channelModel, null)).isFalse();
    }

    private void startServer(java.util.function.BiFunction<reactor.netty.http.server.HttpServerRequest,
            reactor.netty.http.server.HttpServerResponse, org.reactivestreams.Publisher<Void>> handler) {
        server = HttpServer.create()
                .port(0)
                .handle(handler)
                .bindNow();
        port = server.port();
    }
}
