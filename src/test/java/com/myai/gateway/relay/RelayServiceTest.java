package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.*;
import com.myai.gateway.relay.balancer.FailoverBalancer;
import com.myai.gateway.relay.balancer.LoadBalancerFactory;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalMessage;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
import com.myai.gateway.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RelayService 单元测试
 * 验证路由候选过滤、候选内重试、熔断后重路由等核心逻辑
 */
class RelayServiceTest {

    private ChannelService channelService;
    private ChannelApiKeyService channelApiKeyService;
    private ModelService modelService;
    private CircuitBreakerService circuitBreakerService;
    private RequestLogService requestLogService;
    private LoadBalancerFactory loadBalancerFactory;
    private ObjectMapper objectMapper;
    private MessageTransformer messageTransformer;
    private StreamContentManager streamContentManager;

    private RelayService relayService;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        channelApiKeyService = mock(ChannelApiKeyService.class);
        modelService = mock(ModelService.class);
        circuitBreakerService = mock(CircuitBreakerService.class);
        requestLogService = mock(RequestLogService.class);
        loadBalancerFactory = mock(LoadBalancerFactory.class);
        objectMapper = new ObjectMapper();
        messageTransformer = mock(MessageTransformer.class);
        streamContentManager = new StreamContentManager();

        relayService = new RelayService(channelService, channelApiKeyService, modelService,
                circuitBreakerService, requestLogService, loadBalancerFactory,
                objectMapper, messageTransformer, streamContentManager);

        when(loadBalancerFactory.getBalancer(anyString())).thenReturn(new FailoverBalancer());
        when(requestLogService.startTrace()).thenReturn("trace-1");
    }

    @Test
    void getAvailableCandidates_filtersModelLevelCircuitBrokenPerApiKey() {
        // 自定义模型 x
        Model model = new Model();
        model.setId(1L);
        model.setModelName("x");
        when(modelService.getByModelName("x")).thenReturn(model);

        // 渠道 A
        Channel channelA = new Channel();
        channelA.setId(10L);
        channelA.setName("A");
        channelA.setEnabled(1);

        // 渠道模型 a1、a2
        ChannelModel cmA1 = new ChannelModel();
        cmA1.setId(100L);
        cmA1.setChannelId(10L);
        cmA1.setModelName("a1");
        cmA1.setEnabled(1);

        ChannelModel cmA2 = new ChannelModel();
        cmA2.setId(101L);
        cmA2.setChannelId(10L);
        cmA2.setModelName("a2");
        cmA2.setEnabled(1);

        // API Keys
        ChannelApiKey ak1 = new ChannelApiKey();
        ak1.setId(1000L);
        ak1.setChannelId(10L);
        ak1.setKeyName("ak1");
        ak1.setEnabled(1);

        ChannelApiKey ak2 = new ChannelApiKey();
        ak2.setId(1001L);
        ak2.setChannelId(10L);
        ak2.setKeyName("ak2");
        ak2.setEnabled(1);

        // 关联关系：x -> a1, x -> a2
        ModelChannelRel relA1 = new ModelChannelRel(1L, 100L);
        relA1.setSortOrder(0);
        relA1.setEnabled(1);
        ModelChannelRel relA2 = new ModelChannelRel(1L, 101L);
        relA2.setSortOrder(1);
        relA2.setEnabled(1);

        when(modelService.getChannelRels(1L)).thenReturn(List.of(relA1, relA2));
        when(modelService.getChannelModelById(100L)).thenReturn(cmA1);
        when(modelService.getChannelModelById(101L)).thenReturn(cmA2);
        when(modelService.getChannelById(10L)).thenReturn(channelA);
        when(channelApiKeyService.getAvailableApiKeys(10L)).thenReturn(List.of(ak1, ak2));

        // 仅熔断 a1 + ak1 的组合
        when(circuitBreakerService.isChannelCircuitBroken(10L, 1000L)).thenReturn(false);
        when(circuitBreakerService.isChannelCircuitBroken(10L, 1001L)).thenReturn(false);
        when(circuitBreakerService.isModelCircuitBroken(100L, 1000L)).thenReturn(true);
        when(circuitBreakerService.isModelCircuitBroken(100L, 1001L)).thenReturn(false);
        when(circuitBreakerService.isModelCircuitBroken(101L, 1000L)).thenReturn(false);
        when(circuitBreakerService.isModelCircuitBroken(101L, 1001L)).thenReturn(false);

        InternalRequest req = new InternalRequest();
        req.setModel("x");

        List<RoutingCandidate> candidates = relayService.getAvailableCandidates(req);

        // 应过滤掉 a1+ak1，保留 a1+ak2、a2+ak1、a2+ak2
        assertThat(candidates).hasSize(3);
        assertThat(candidates).noneMatch(c ->
                c.getChannelModel().getModelName().equals("a1") && c.getChannelApiKey().getKeyName().equals("ak1"));
        assertThat(candidates).anyMatch(c ->
                c.getChannelModel().getModelName().equals("a1") && c.getChannelApiKey().getKeyName().equals("ak2"));
        assertThat(candidates).anyMatch(c ->
                c.getChannelModel().getModelName().equals("a2") && c.getChannelApiKey().getKeyName().equals("ak1"));
        assertThat(candidates).anyMatch(c ->
                c.getChannelModel().getModelName().equals("a2") && c.getChannelApiKey().getKeyName().equals("ak2"));
    }

    @Test
    void tryCandidates_retriesSameCandidateAndSucceedsWithoutTriggeringCircuitBreaker() {
        RelayService spyService = spy(relayService);

        // 设置重试次数为 2，即最多 3 次尝试
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setRetryCount(2);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        config.setEnabled(1);
        when(modelService.getCircuitBreakerConfig(1L)).thenReturn(config);

        Model model = new Model();
        model.setId(1L);
        model.setModelName("x");
        when(modelService.getByModelName("x")).thenReturn(model);

        Channel channel = new Channel();
        channel.setId(10L);
        channel.setName("A");
        channel.setEnabled(1);

        ChannelModel cm = new ChannelModel();
        cm.setId(100L);
        cm.setChannelId(10L);
        cm.setModelName("a1");
        cm.setEnabled(1);

        ChannelApiKey key = new ChannelApiKey();
        key.setId(1000L);
        key.setChannelId(10L);
        key.setKeyName("ak1");
        key.setEnabled(1);

        ModelChannelRel rel = new ModelChannelRel(1L, 100L);
        rel.setSortOrder(0);
        rel.setEnabled(1);

        RoutingCandidate candidate = new RoutingCandidate(rel, channel, cm, key);
        List<RoutingCandidate> candidates = new ArrayList<>(List.of(candidate));

        InternalRequest req = new InternalRequest();
        req.setModel("x");
        req.setClientApiFormat("openai");

        // 前两次失败，第三次成功
        doReturn(Mono.error(new RuntimeException("fail 1")),
                Mono.error(new RuntimeException("fail 2")),
                Mono.just("{\"success\":true}"))
                .when(spyService).callProviderNonStream(any(), any(), any(), any());
        when(messageTransformer.transformOpenAiResponseToClient(any(), eq("openai"), eq("x")))
                .thenReturn("{\"success\":true}");

        String result = spyService.tryCandidates("trace-1", candidates, "auth", req, "openai", 0, System.currentTimeMillis())
                .block(Duration.ofSeconds(5));
        assertThat(result).isEqualTo("{\"success\":true}");

        // 验证对同一个候选尝试了 3 次
        verify(spyService, times(3)).callProviderNonStream(any(), any(), eq(candidate), eq("openai"));
        // 成功后不应触发熔断
        verify(circuitBreakerService, never()).triggerCircuitBreak(any(), any(), any(), any());
    }

    @Test
    void tryCandidates_failsOverAndTriggersCircuitBreakerAfterRetriesExhausted() {
        RelayService spyService = spy(relayService);

        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setRetryCount(1);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        config.setEnabled(1);
        when(modelService.getCircuitBreakerConfig(1L)).thenReturn(config);

        Model model = new Model();
        model.setId(1L);
        model.setModelName("x");
        when(modelService.getByModelName("x")).thenReturn(model);

        Channel channel = new Channel();
        channel.setId(10L);
        channel.setName("A");
        channel.setEnabled(1);

        ChannelModel cm1 = new ChannelModel();
        cm1.setId(100L);
        cm1.setChannelId(10L);
        cm1.setModelName("a1");
        cm1.setEnabled(1);

        ChannelModel cm2 = new ChannelModel();
        cm2.setId(101L);
        cm2.setChannelId(10L);
        cm2.setModelName("a2");
        cm2.setEnabled(1);

        ChannelApiKey key1 = new ChannelApiKey();
        key1.setId(1000L);
        key1.setChannelId(10L);
        key1.setKeyName("ak1");
        key1.setEnabled(1);

        ChannelApiKey key2 = new ChannelApiKey();
        key2.setId(1001L);
        key2.setChannelId(10L);
        key2.setKeyName("ak2");
        key2.setEnabled(1);

        ModelChannelRel rel1 = new ModelChannelRel(1L, 100L);
        rel1.setSortOrder(0);
        rel1.setEnabled(1);
        ModelChannelRel rel2 = new ModelChannelRel(1L, 101L);
        rel2.setSortOrder(1);
        rel2.setEnabled(1);

        RoutingCandidate candidate1 = new RoutingCandidate(rel1, channel, cm1, key1);
        RoutingCandidate candidate2 = new RoutingCandidate(rel2, channel, cm2, key2);
        List<RoutingCandidate> candidates = new ArrayList<>(List.of(candidate1, candidate2));

        InternalRequest req = new InternalRequest();
        req.setModel("x");
        req.setClientApiFormat("openai");

        // 所有候选全部失败
        doReturn(Mono.error(new RuntimeException("fail")))
                .when(spyService).callProviderNonStream(any(), any(), any(), any());
        when(messageTransformer.buildErrorResponse(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("{\"error\":true}");

        String result = spyService.tryCandidates("trace-1", candidates, "auth", req, "openai", 0, System.currentTimeMillis())
                .block(Duration.ofSeconds(5));
        assertThat(result).isEqualTo("{\"error\":true}");

        // 每个候选尝试 retryCount+1 = 2 次，共 4 次
        verify(spyService, times(4)).callProviderNonStream(any(), any(), any(), eq("openai"));
        // 每个候选在重试耗尽后都触发了模型级熔断
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1000L, 100L);
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1001L, 101L);
    }

    @Test
    void tryCandidates_failsOverInStrictSequentialOrder() {
        RelayService spyService = spy(relayService);

        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setRetryCount(0);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        config.setEnabled(1);
        when(modelService.getCircuitBreakerConfig(1L)).thenReturn(config);

        Model model = new Model();
        model.setId(1L);
        model.setModelName("x");
        when(modelService.getByModelName("x")).thenReturn(model);

        Channel channel = new Channel();
        channel.setId(10L);
        channel.setName("A");
        channel.setEnabled(1);

        ChannelModel cm1 = createChannelModel(100L, "test");
        ChannelModel cm2 = createChannelModel(101L, "deepseek-v4-flash-free");
        ChannelModel cm3 = createChannelModel(102L, "deepseek-ai/DeepSeek-V4-Flash");

        ChannelApiKey key1 = createApiKey(1000L, "ak1");

        ModelChannelRel rel1 = new ModelChannelRel(1L, 100L);
        rel1.setSortOrder(0);
        ModelChannelRel rel2 = new ModelChannelRel(1L, 101L);
        rel2.setSortOrder(1);
        ModelChannelRel rel3 = new ModelChannelRel(1L, 102L);
        rel3.setSortOrder(2);

        RoutingCandidate candidate1 = new RoutingCandidate(rel1, channel, cm1, key1);
        RoutingCandidate candidate2 = new RoutingCandidate(rel2, channel, cm2, key1);
        RoutingCandidate candidate3 = new RoutingCandidate(rel3, channel, cm3, key1);
        List<RoutingCandidate> candidates = new ArrayList<>(List.of(candidate1, candidate2, candidate3));

        InternalRequest req = new InternalRequest();
        req.setModel("x");
        req.setClientApiFormat("openai");

        // 前两个候选失败，第三个成功
        doReturn(Mono.error(new RuntimeException("fail")))
                .doReturn(Mono.error(new RuntimeException("fail")))
                .doReturn(Mono.just("{\"success\":true}"))
                .when(spyService).callProviderNonStream(any(), any(), any(), any());
        when(messageTransformer.transformOpenAiResponseToClient(any(), eq("openai"), eq("x")))
                .thenReturn("{\"success\":true}");

        String result = spyService.tryCandidates("trace-1", candidates, "auth", req, "openai", 0, System.currentTimeMillis())
                .block(Duration.ofSeconds(5));
        assertThat(result).isEqualTo("{\"success\":true}");

        // 验证严格按照 test -> deepseek-v4-flash-free -> deepseek-ai/DeepSeek-V4-Flash 顺序尝试
        verify(spyService).callProviderNonStream(any(), any(), eq(candidate1), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(candidate2), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(candidate3), eq("openai"));
        verify(spyService, times(3)).callProviderNonStream(any(), any(), any(), eq("openai"));
    }

    @Test
    void getAvailableCandidates_multiApiKeys_expandsCorrectlyAndMaintainsOrder() {
        // 自定义模型 x
        Model model = new Model();
        model.setId(1L);
        model.setModelName("x");
        when(modelService.getByModelName("x")).thenReturn(model);

        // 渠道 A
        Channel channelA = new Channel();
        channelA.setId(10L);
        channelA.setName("A");
        channelA.setEnabled(1);

        // 渠道 B
        Channel channelB = new Channel();
        channelB.setId(11L);
        channelB.setName("B");
        channelB.setEnabled(1);

        // 渠道模型 a1、a2（A渠道），b1（B渠道）
        ChannelModel cmA1 = createChannelModel(100L, "a1", 10L);
        ChannelModel cmA2 = createChannelModel(101L, "a2", 10L);
        ChannelModel cmB1 = createChannelModel(102L, "b1", 11L);

        // API Keys：A渠道有ak1、ak2；B渠道有bk1
        ChannelApiKey ak1 = createApiKey(1000L, "ak1", 10L);
        ChannelApiKey ak2 = createApiKey(1001L, "ak2", 10L);
        ChannelApiKey bk1 = createApiKey(2000L, "bk1", 11L);

        // 关联关系：x -> a1(sort=0), a2(sort=1), b1(sort=2)
        ModelChannelRel relA1 = new ModelChannelRel(1L, 100L);
        relA1.setSortOrder(0);
        relA1.setEnabled(1);
        ModelChannelRel relA2 = new ModelChannelRel(1L, 101L);
        relA2.setSortOrder(1);
        relA2.setEnabled(1);
        ModelChannelRel relB1 = new ModelChannelRel(1L, 102L);
        relB1.setSortOrder(2);
        relB1.setEnabled(1);

        when(modelService.getChannelRels(1L)).thenReturn(List.of(relA1, relA2, relB1));
        when(modelService.getChannelModelById(100L)).thenReturn(cmA1);
        when(modelService.getChannelModelById(101L)).thenReturn(cmA2);
        when(modelService.getChannelModelById(102L)).thenReturn(cmB1);
        when(modelService.getChannelById(10L)).thenReturn(channelA);
        when(modelService.getChannelById(11L)).thenReturn(channelB);
        when(channelApiKeyService.getAvailableApiKeys(10L)).thenReturn(List.of(ak1, ak2));
        when(channelApiKeyService.getAvailableApiKeys(11L)).thenReturn(List.of(bk1));

        // 无熔断
        when(circuitBreakerService.isChannelCircuitBroken(any(), any())).thenReturn(false);
        when(circuitBreakerService.isModelCircuitBroken(any(), any())).thenReturn(false);

        InternalRequest req = new InternalRequest();
        req.setModel("x");

        List<RoutingCandidate> candidates = relayService.getAvailableCandidates(req);

        // 应返回 5 个候选：a1+ak1, a1+ak2, a2+ak1, a2+ak2, b1+bk1
        assertThat(candidates).hasSize(5);
        assertThat(candidates.get(0).getChannelModel().getModelName()).isEqualTo("a1");
        assertThat(candidates.get(0).getChannelApiKey().getKeyName()).isEqualTo("ak1");
        assertThat(candidates.get(1).getChannelModel().getModelName()).isEqualTo("a1");
        assertThat(candidates.get(1).getChannelApiKey().getKeyName()).isEqualTo("ak2");
        assertThat(candidates.get(2).getChannelModel().getModelName()).isEqualTo("a2");
        assertThat(candidates.get(2).getChannelApiKey().getKeyName()).isEqualTo("ak1");
        assertThat(candidates.get(3).getChannelModel().getModelName()).isEqualTo("a2");
        assertThat(candidates.get(3).getChannelApiKey().getKeyName()).isEqualTo("ak2");
        assertThat(candidates.get(4).getChannelModel().getModelName()).isEqualTo("b1");
        assertThat(candidates.get(4).getChannelApiKey().getKeyName()).isEqualTo("bk1");
    }

    @Test
    void getAvailableCandidates_channelLevelCircuitBreaker_skipsAllModelsForThatKey() {
        // 自定义模型 x
        Model model = new Model();
        model.setId(1L);
        model.setModelName("x");
        when(modelService.getByModelName("x")).thenReturn(model);

        // 渠道 A
        Channel channelA = new Channel();
        channelA.setId(10L);
        channelA.setName("A");
        channelA.setEnabled(1);

        // 渠道模型 a1、a2
        ChannelModel cmA1 = createChannelModel(100L, "a1", 10L);
        ChannelModel cmA2 = createChannelModel(101L, "a2", 10L);

        // API Keys：ak1、ak2
        ChannelApiKey ak1 = createApiKey(1000L, "ak1", 10L);
        ChannelApiKey ak2 = createApiKey(1001L, "ak2", 10L);

        // 关联关系
        ModelChannelRel relA1 = new ModelChannelRel(1L, 100L);
        relA1.setSortOrder(0);
        relA1.setEnabled(1);
        ModelChannelRel relA2 = new ModelChannelRel(1L, 101L);
        relA2.setSortOrder(1);
        relA2.setEnabled(1);

        when(modelService.getChannelRels(1L)).thenReturn(List.of(relA1, relA2));
        when(modelService.getChannelModelById(100L)).thenReturn(cmA1);
        when(modelService.getChannelModelById(101L)).thenReturn(cmA2);
        when(modelService.getChannelById(10L)).thenReturn(channelA);
        when(channelApiKeyService.getAvailableApiKeys(10L)).thenReturn(List.of(ak1, ak2));

        // 渠道级熔断 ak1：ak1 下所有模型都不可用
        when(circuitBreakerService.isChannelCircuitBroken(10L, 1000L)).thenReturn(true);
        when(circuitBreakerService.isChannelCircuitBroken(10L, 1001L)).thenReturn(false);
        when(circuitBreakerService.isModelCircuitBroken(any(), any())).thenReturn(false);

        InternalRequest req = new InternalRequest();
        req.setModel("x");

        List<RoutingCandidate> candidates = relayService.getAvailableCandidates(req);

        // 应跳过 ak1 下的所有模型，只保留 a1+ak2, a2+ak2
        assertThat(candidates).hasSize(2);
        assertThat(candidates).noneMatch(c -> c.getChannelApiKey().getKeyName().equals("ak1"));
        assertThat(candidates.get(0).getChannelModel().getModelName()).isEqualTo("a1");
        assertThat(candidates.get(0).getChannelApiKey().getKeyName()).isEqualTo("ak2");
        assertThat(candidates.get(1).getChannelModel().getModelName()).isEqualTo("a2");
        assertThat(candidates.get(1).getChannelApiKey().getKeyName()).isEqualTo("ak2");
    }

    @Test
    void tryCandidates_multiApiKeys_failsOverInCorrectOrder() {
        RelayService spyService = spy(relayService);

        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setRetryCount(0);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        config.setEnabled(1);
        when(modelService.getCircuitBreakerConfig(1L)).thenReturn(config);

        Model model = new Model();
        model.setId(1L);
        model.setModelName("x");
        when(modelService.getByModelName("x")).thenReturn(model);

        Channel channel = new Channel();
        channel.setId(10L);
        channel.setName("A");
        channel.setEnabled(1);

        ChannelModel cm1 = createChannelModel(100L, "test", 10L);
        ChannelModel cm2 = createChannelModel(101L, "deepseek-v4-flash-free", 10L);
        ChannelModel cm3 = createChannelModel(102L, "deepseek-ai/DeepSeek-V4-Flash", 10L);
        ChannelModel cm4 = createChannelModel(103L, "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b", 10L);

        ChannelApiKey key1 = createApiKey(1000L, "ak1", 10L);
        ChannelApiKey key2 = createApiKey(1001L, "ak2", 10L);

        ModelChannelRel rel1 = new ModelChannelRel(1L, 100L);
        rel1.setSortOrder(0);
        ModelChannelRel rel2 = new ModelChannelRel(1L, 101L);
        rel2.setSortOrder(1);
        ModelChannelRel rel3 = new ModelChannelRel(1L, 102L);
        rel3.setSortOrder(2);
        ModelChannelRel rel4 = new ModelChannelRel(1L, 103L);
        rel4.setSortOrder(3);

        // 构造多 API Key 候选：每个模型展开为 2 个 Key
        RoutingCandidate c1_k1 = new RoutingCandidate(rel1, channel, cm1, key1);
        RoutingCandidate c1_k2 = new RoutingCandidate(rel1, channel, cm1, key2);
        RoutingCandidate c2_k1 = new RoutingCandidate(rel2, channel, cm2, key1);
        RoutingCandidate c2_k2 = new RoutingCandidate(rel2, channel, cm2, key2);
        RoutingCandidate c3_k1 = new RoutingCandidate(rel3, channel, cm3, key1);
        RoutingCandidate c3_k2 = new RoutingCandidate(rel3, channel, cm3, key2);
        RoutingCandidate c4_k1 = new RoutingCandidate(rel4, channel, cm4, key1);
        RoutingCandidate c4_k2 = new RoutingCandidate(rel4, channel, cm4, key2);

        List<RoutingCandidate> candidates = new ArrayList<>(List.of(
                c1_k1, c1_k2, c2_k1, c2_k2, c3_k1, c3_k2, c4_k1, c4_k2));

        InternalRequest req = new InternalRequest();
        req.setModel("x");
        req.setClientApiFormat("openai");

        // 前 7 个候选失败，最后一个成功
        doReturn(
                Mono.error(new RuntimeException("fail")),
                Mono.error(new RuntimeException("fail")),
                Mono.error(new RuntimeException("fail")),
                Mono.error(new RuntimeException("fail")),
                Mono.error(new RuntimeException("fail")),
                Mono.error(new RuntimeException("fail")),
                Mono.error(new RuntimeException("fail")),
                Mono.just("{\"success\":true}")
        ).when(spyService).callProviderNonStream(any(), any(), any(), any());
        when(messageTransformer.transformOpenAiResponseToClient(any(), eq("openai"), eq("x")))
                .thenReturn("{\"success\":true}");

        String result = spyService.tryCandidates("trace-1", candidates, "auth", req, "openai", 0, System.currentTimeMillis())
                .block(Duration.ofSeconds(5));
        assertThat(result).isEqualTo("{\"success\":true}");

        // 验证严格按照 (test,ak1)->(test,ak2)->(deepseek-v4-flash-free,ak1)->... 顺序尝试
        verify(spyService).callProviderNonStream(any(), any(), eq(c1_k1), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(c1_k2), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(c2_k1), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(c2_k2), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(c3_k1), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(c3_k2), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(c4_k1), eq("openai"));
        verify(spyService).callProviderNonStream(any(), any(), eq(c4_k2), eq("openai"));
        verify(spyService, times(8)).callProviderNonStream(any(), any(), any(), eq("openai"));

        // 验证失败的候选都触发了模型级熔断
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1000L, 100L);
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1001L, 100L);
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1000L, 101L);
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1001L, 101L);
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1000L, 102L);
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1001L, 102L);
        verify(circuitBreakerService).triggerCircuitBreak(1L, 10L, 1000L, 103L);
    }

    private ChannelModel createChannelModel(Long id, String modelName) {
        ChannelModel cm = new ChannelModel();
        cm.setId(id);
        cm.setChannelId(10L);
        cm.setModelName(modelName);
        cm.setEnabled(1);
        return cm;
    }

    private ChannelModel createChannelModel(Long id, String modelName, Long channelId) {
        ChannelModel cm = new ChannelModel();
        cm.setId(id);
        cm.setChannelId(channelId);
        cm.setModelName(modelName);
        cm.setEnabled(1);
        return cm;
    }

    private ChannelApiKey createApiKey(Long id, String keyName) {
        ChannelApiKey key = new ChannelApiKey();
        key.setId(id);
        key.setChannelId(10L);
        key.setKeyName(keyName);
        key.setEnabled(1);
        return key;
    }

    private ChannelApiKey createApiKey(Long id, String keyName, Long channelId) {
        ChannelApiKey key = new ChannelApiKey();
        key.setId(id);
        key.setChannelId(channelId);
        key.setKeyName(keyName);
        key.setEnabled(1);
        return key;
    }

    // ========== 流式内容上下文传递测试 ==========

    @Test
    void buildRequestWithContext_appendsAssistantMessageWithAccumulatedContent() {
        InternalRequest originalReq = new InternalRequest();
        originalReq.setModel("x");
        originalReq.setClientApiFormat("openai");
        originalReq.setStream(true);
        originalReq.setMaxTokens(2048);
        originalReq.setTemperature(0.7);
        originalReq.setSystemPrompt("You are a helpful assistant.");

        // 添加原始用户消息
        List<InternalMessage> messages = new ArrayList<>();
        messages.add(new InternalMessage("user", "Hello"));
        originalReq.setMessages(messages);

        String accumulatedContent = "Hello! I'm AI model";
        InternalRequest contextReq = relayService.buildRequestWithContext(originalReq, accumulatedContent);

        // 验证模型名和参数不变
        assertThat(contextReq.getModel()).isEqualTo("x");
        assertThat(contextReq.isStream()).isTrue();
        assertThat(contextReq.getMaxTokens()).isEqualTo(2048);
        assertThat(contextReq.getTemperature()).isEqualTo(0.7);
        assertThat(contextReq.getSystemPrompt()).isEqualTo("You are a helpful assistant.");
        assertThat(contextReq.getClientApiFormat()).isEqualTo("openai");

        // 验证消息列表：原始消息 + 新追加的 assistant 消息
        assertThat(contextReq.getMessages()).hasSize(2);
        assertThat(contextReq.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(contextReq.getMessages().get(0).getContent()).isEqualTo("Hello");
        assertThat(contextReq.getMessages().get(1).getRole()).isEqualTo("assistant");
        assertThat(contextReq.getMessages().get(1).getContent()).isEqualTo("Hello! I'm AI model");
    }

    @Test
    void buildRequestWithContext_preservesToolAndContentPartsInMessages() {
        InternalRequest originalReq = new InternalRequest();
        originalReq.setModel("x");
        originalReq.setClientApiFormat("openai");

        // 添加含 tool_calls 的消息
        InternalMessage msgWithTools = new InternalMessage("assistant", "");
        msgWithTools.setToolCalls(List.of(Map.of("type", "function", "function", Map.of("name", "getWeather"))));
        originalReq.setMessages(new ArrayList<>(List.of(new InternalMessage("user", "What's the weather?"), msgWithTools)));

        String accumulatedContent = "Let me check the weather for you.";
        InternalRequest contextReq = relayService.buildRequestWithContext(originalReq, accumulatedContent);

        assertThat(contextReq.getMessages()).hasSize(3);
        assertThat(contextReq.getMessages().get(1).getToolCalls()).isNotNull();
        assertThat(contextReq.getMessages().get(2).getRole()).isEqualTo("assistant");
        assertThat(contextReq.getMessages().get(2).getContent()).isEqualTo("Let me check the weather for you.");
    }

    @Test
    void buildRequestWithContext_originalRequestUnchanged() {
        InternalRequest originalReq = new InternalRequest();
        originalReq.setModel("x");
        originalReq.setClientApiFormat("openai");
        originalReq.setMessages(new ArrayList<>(List.of(new InternalMessage("user", "Hello"))));

        relayService.buildRequestWithContext(originalReq, "Some content");

        // 原始请求不应被修改
        assertThat(originalReq.getMessages()).hasSize(1);
        assertThat(originalReq.getMessages().get(0).getRole()).isEqualTo("user");
    }

    @Test
    void buildRequestWithContext_emptyMessages_stillAddsAssistantMessage() {
        InternalRequest originalReq = new InternalRequest();
        originalReq.setModel("x");
        originalReq.setClientApiFormat("openai");
        originalReq.setMessages(new ArrayList<>());

        String accumulatedContent = "Response content";
        InternalRequest contextReq = relayService.buildRequestWithContext(originalReq, accumulatedContent);

        assertThat(contextReq.getMessages()).hasSize(1);
        assertThat(contextReq.getMessages().get(0).getRole()).isEqualTo("assistant");
        assertThat(contextReq.getMessages().get(0).getContent()).isEqualTo("Response content");
    }

    @Test
    void extractTextContentFromRawData_openaiDeltaContent() {
        String rawData = "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}";
        String content = relayService.extractTextContentFromRawData(rawData, "openai");
        assertThat(content).isEqualTo("Hello");
    }

    @Test
    void extractTextContentFromRawData_openaiEmptyDelta_returnsNull() {
        String rawData = "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":null}]}";
        String content = relayService.extractTextContentFromRawData(rawData, "openai");
        assertThat(content).isNull();
    }

    @Test
    void extractTextContentFromRawData_openaiNoDelta_returnsNull() {
        String rawData = "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}";
        String content = relayService.extractTextContentFromRawData(rawData, "openai");
        assertThat(content).isNull();
    }

    @Test
    void extractTextContentFromRawData_anthropicDeltaText() {
        String rawData = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello from Anthropic\"}}";
        String content = relayService.extractTextContentFromRawData(rawData, "anthropic");
        assertThat(content).isEqualTo("Hello from Anthropic");
    }

    @Test
    void extractTextContentFromRawData_anthropicNoText_returnsNull() {
        String rawData = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\"}}";
        String content = relayService.extractTextContentFromRawData(rawData, "anthropic");
        assertThat(content).isNull();
    }

    @Test
    void extractTextContentFromRawData_invalidJson_returnsNull() {
        String rawData = "not json";
        String content = relayService.extractTextContentFromRawData(rawData, "openai");
        assertThat(content).isNull();
    }

    @Test
    void extractTextContentFromRawData_gatewayMetaEvent_returnsNull() {
        String rawData = "{\"_gateway_meta\":true,\"channel\":\"A\"}";
        String content = relayService.extractTextContentFromRawData(rawData, "openai");
        assertThat(content).isNull();
    }

    @Test
    void extractTextContentFromRawData_routingProgress_returnsNull() {
        String rawData = "{\"_routing_progress\":true,\"phase\":\"trying\"}";
        String content = relayService.extractTextContentFromRawData(rawData, "openai");
        assertThat(content).isNull();
    }

    @Test
    void streamContentManager_isInjectedAndUsable() {
        // 验证 StreamContentManager 注入正常
        assertThat(streamContentManager).isNotNull();
        streamContentManager.appendContent("test-trace", "Hello");
        assertThat(streamContentManager.getContent("test-trace")).isEqualTo("Hello");
        streamContentManager.clearContent("test-trace");
    }

    private RoutingCandidate createMockCandidate() {
        Channel channel = new Channel();
        channel.setId(10L);
        channel.setName("A");
        channel.setEnabled(1);

        ChannelModel cm = new ChannelModel();
        cm.setId(100L);
        cm.setChannelId(10L);
        cm.setModelName("a1");
        cm.setEnabled(1);

        ChannelApiKey key = new ChannelApiKey();
        key.setId(1000L);
        key.setChannelId(10L);
        key.setKeyName("ak1");
        key.setEnabled(1);

        ModelChannelRel rel = new ModelChannelRel(1L, 100L);
        rel.setSortOrder(0);
        rel.setEnabled(1);

        return new RoutingCandidate(rel, channel, cm, key);
    }
}
