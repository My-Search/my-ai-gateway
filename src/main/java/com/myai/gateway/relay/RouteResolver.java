package com.myai.gateway.relay;

import com.myai.gateway.entity.*;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 路由解析组件
 * <p>负责模型路由配置解析、路由候选构建、熔断跳过日志。</p>
 */
public class RouteResolver {

    private static final Logger log = LoggerFactory.getLogger(RouteResolver.class);

    private final ModelService modelService;
    private final CircuitBreakerService circuitBreakerService;
    private final ChannelApiKeyService channelApiKeyService;
    private final RelayLogger relayLogger;

    public RouteResolver(ModelService modelService,
                         CircuitBreakerService circuitBreakerService,
                         ChannelApiKeyService channelApiKeyService,
                         RelayLogger relayLogger) {
        this.modelService = modelService;
        this.circuitBreakerService = circuitBreakerService;
        this.channelApiKeyService = channelApiKeyService;
        this.relayLogger = relayLogger;
    }

    /**
     * 模型路由上下文：缓存单次请求中不变的路由配置。
     */
    public record RoutingContext(Long modelId, String strategy, int maxAttempts, int retryCount) {
        /** 模型不存在时的默认上下文 */
        public static RoutingContext defaultEmpty() {
            return new RoutingContext(null, "failover", 1, 0);
        }
    }

    /**
     * 一次性解析模型的路由配置（modelId / strategy / maxAttempts / retryCount）。
     */
    public RoutingContext resolveModelRouting(String modelName) {
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
     * 根据模型名解析自定义模型 ID
     */
    public Long resolveModelId(String modelName) {
        Model model = modelService.getByModelName(modelName);
        return model != null ? model.getId() : null;
    }

    /**
     * 获取可用的路由候选列表
     */
    public List<RoutingCandidate> getAvailableCandidates(InternalRequest req) {
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
            // 渠道级熔断在循环外检查一次
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
     */
    public List<ChannelApiKey> getApiKeysForCandidate(Long channelId, Long specifiedKeyId) {
        if (specifiedKeyId != null) {
            ChannelApiKey key = channelApiKeyService.getById(specifiedKeyId);
            return key != null ? List.of(key) : Collections.emptyList();
        }
        List<ChannelApiKey> keys = channelApiKeyService.getAvailableApiKeys(channelId);
        return keys != null ? keys : Collections.emptyList();
    }

    /**
     * 记录被跳过的路由候选到请求日志（熔断、图片能力不匹配等）
     */
    public void logSkippedCandidatesFromResult(String traceId, Long gatewayApiKeyId,
                                                InternalRequest req,
                                                List<RoutingCandidate> candidates,
                                                RoutingContext ctx) {
        Long customModelId = ctx.modelId();
        if (customModelId == null) return;

        List<ModelChannelRel> rels = modelService.getChannelRels(customModelId);
        for (ModelChannelRel rel : rels) {
            if (rel.getEnabled() == null || rel.getEnabled() != 1) continue;
            ChannelModel channelModel = modelService.getChannelModelById(rel.getChannelModelId());
            if (channelModel == null || channelModel.getEnabled() == null || channelModel.getEnabled() != 1) continue;
            Channel channel = modelService.getChannelById(channelModel.getChannelId());
            if (channel == null || channel.getEnabled() == null || channel.getEnabled() != 1) continue;

            boolean channelLevelBroken = circuitBreakerService.isChannelCircuitBroken(channel.getId());
            List<ChannelApiKey> apiKeys = getApiKeysForCandidate(channel.getId(), channelModel.getChannelApiKeyId());
            for (ChannelApiKey apiKey : apiKeys) {
                if (apiKey.getEnabled() == null || apiKey.getEnabled() != 1) continue;

                boolean channelBroken = channelLevelBroken
                        || circuitBreakerService.isChannelCircuitBroken(channel.getId(), apiKey.getId());
                boolean modelBroken = circuitBreakerService.isModelCircuitBroken(channelModel.getId(), apiKey.getId());
                if (channelBroken || modelBroken) {
                    String scope = channelBroken ? "渠道级熔断" : "模型级熔断";
                    relayLogger.logPhase(traceId, gatewayApiKeyId,
                            new RoutingCandidate(rel, channel, channelModel, apiKey),
                            req, "skip", scope + "跳过 " + channel.getName() + "/"
                                    + apiKey.getKeyName() + "/" + channelModel.getModelName(), 0);
                }
            }
        }
    }
}
