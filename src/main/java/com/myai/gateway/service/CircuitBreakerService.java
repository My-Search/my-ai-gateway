package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.*;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 熔断服务
 * 管理熔断状态的检查和更新
 *
 * <p>采用两级模型（三级合并为两级）：</p>
 * <ul>
 *   <li><b>渠道级（合并）</b> — 由 {@code (channelId, channelApiKeyId)} 二元组标识：
 *       全渠道熔断时 {@code channelApiKeyId = null}，按 API Key 熔断时 {@code channelApiKeyId} 非空。
 *       查询时需加 {@code channelModelId IS NULL} 条件以排除模型级记录。</li>
 *   <li><b>模型级</b> — 由 {@code (channelId, channelApiKeyId, channelModelId)} 三个字段联合标识。</li>
 * </ul>
 *
 * <p>历史兼容：旧三级方法标记为 {@code @Deprecated}，内部逻辑仍保留原始查询方式以正确匹配旧数据。</p>
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);

    private final CircuitBreakerStateMapper stateMapper;
    private final ModelService modelService;
    private final ChannelApiKeyService channelApiKeyService;

    public CircuitBreakerService(CircuitBreakerStateMapper stateMapper,
                                  ModelService modelService,
                                  ChannelApiKeyService channelApiKeyService) {
        this.stateMapper = stateMapper;
        this.modelService = modelService;
        this.channelApiKeyService = channelApiKeyService;
    }

    /**
     * 检查渠道是否被熔断（全渠道级，仅匹配旧版全渠道记录）
     * <p>仅匹配 {@code channelApiKeyId IS NULL} 的旧全渠道熔断记录，
     * 按 API Key 的熔断请使用 {@link #isChannelCircuitBroken(Long, Long)}。
     */
    public boolean isChannelCircuitBroken(Long channelId) {
        LambdaQueryWrapper<CircuitBreakerState> wrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getChannelId, channelId)
                .isNull(CircuitBreakerState::getChannelApiKeyId)
                .eq(CircuitBreakerState::getIsOpen, 1)
                .gt(CircuitBreakerState::getExpireAt, LocalDateTime.now());
        return stateMapper.selectCount(wrapper) > 0;
    }

    /**
     * 检查渠道（按 API Key 级别）是否被熔断
     * <p>合并原渠道级与 API Key 级的熔断检查。由 {@code (channelId, channelApiKeyId)} 二元组标识，
     * 同时确保 {@code channelModelId IS NULL} 以排除模型级记录。
     *
     * @param channelId       渠道 ID
     * @param channelApiKeyId 渠道 API Key ID（可为 {@code null}，表示全渠道熔断）
     */
    public boolean isChannelCircuitBroken(Long channelId, Long channelApiKeyId) {
        LambdaQueryWrapper<CircuitBreakerState> wrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getChannelId, channelId)
                .eq(channelApiKeyId != null, CircuitBreakerState::getChannelApiKeyId, channelApiKeyId)
                .isNull(CircuitBreakerState::getChannelModelId)
                .eq(CircuitBreakerState::getIsOpen, 1)
                .gt(CircuitBreakerState::getExpireAt, LocalDateTime.now());
        return stateMapper.selectCount(wrapper) > 0;
    }

    /**
     * 检查指定渠道模型是否被熔断（模型级）
     * <p>按 {@code (channelModelId, channelApiKeyId)} 组合判断。为兼容旧数据，若存在
     * 仅有 {@code channelModelId} 的旧熔断记录（{@code channelApiKeyId IS NULL}），
     * 同样视为该模型下所有 API Key 均被熔断。</p>
     *
     * @param channelModelId  渠道模型 ID
     * @param channelApiKeyId 渠道 API Key ID（不可为 {@code null}）
     */
    public boolean isModelCircuitBroken(Long channelModelId, Long channelApiKeyId) {
        if (channelModelId == null) {
            return false;
        }
        LambdaQueryWrapper<CircuitBreakerState> wrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getChannelModelId, channelModelId)
                .and(w -> w.eq(CircuitBreakerState::getChannelApiKeyId, channelApiKeyId)
                        .or()
                        .isNull(CircuitBreakerState::getChannelApiKeyId))
                .eq(CircuitBreakerState::getIsOpen, 1)
                .gt(CircuitBreakerState::getExpireAt, LocalDateTime.now());
        return stateMapper.selectCount(wrapper) > 0;
    }

    /**
     * 检查渠道模型是否可用
     * 需要同时检查渠道级（含合并的 API Key 级）和模型级熔断
     */
    public boolean isAvailable(Long channelModelId, Long channelId, Long channelApiKeyId) {
        // 1. 全渠道熔断（旧记录，channelApiKeyId IS NULL）
        if (isChannelCircuitBroken(channelId)) {
            return false;
        }
        // 2. 渠道级熔断（按 API Key，合并新旧渠道级 & API Key 级）
        if (channelApiKeyId != null && isChannelCircuitBroken(channelId, channelApiKeyId)) {
            return false;
        }
        // 3. 模型级熔断（按 channelModelId + channelApiKeyId）
        if (isModelCircuitBroken(channelModelId, channelApiKeyId)) {
            return false;
        }
        return true;
    }

    /**
     * 检查渠道模型是否可用（兼容旧调用）
     */
    public boolean isAvailable(Long channelModelId, Long channelId) {
        return isAvailable(channelModelId, channelId, null);
    }

    /**
     * 触发熔断
     * 根据熔断配置来决定熔断范围
     *
     * 熔断层级逻辑：
     * - channel：渠道级熔断（合并原 apikey 级），按 {@code (channelId, channelApiKeyId)}
     *   标识，该 API Key 下所有模型不可用
     * - model：模型级熔断，按 {@code (channelId, channelApiKeyId, channelModelId)} 标识，
     *   只熔断该 API Key 的该模型
     *
     * @param modelId           自定义模型 ID
     * @param channelId         渠道 ID
     * @param channelApiKeyId   渠道 API Key ID
     * @param channelModelId    渠道模型 ID
     */
    @Transactional
    public void triggerCircuitBreak(Long modelId, Long channelId, Long channelApiKeyId, Long channelModelId) {
        CircuitBreakerConfig config = modelService.getCircuitBreakerConfig(modelId);
        if (config == null || config.getEnabled() == null || config.getEnabled() != 1) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plusSeconds(config.getCircuitBreakDuration());
        String scope = config.getCircuitBreakScope();

        if ("channel".equals(scope)) {
            // 渠道级熔断（全渠道或按 API Key，由 channelApiKeyId 是否为空决定）
            log.warn("触发渠道级熔断 - 渠道ID: {}, API Key ID: {}, 持续时间: {}s",
                    channelId, channelApiKeyId, config.getCircuitBreakDuration());
            triggerChannelCircuitBreak(channelId, channelApiKeyId, now, expireAt);
        } else {
            // 模型级熔断（默认）：按 (channelId, channelApiKeyId, channelModelId) 标识
            log.warn("触发模型级熔断 - 渠道ID: {}, API Key ID: {}, 渠道模型ID: {}, 持续时间: {}s",
                    channelId, channelApiKeyId, channelModelId, config.getCircuitBreakDuration());
            triggerModelCircuitBreak(channelId, channelApiKeyId, channelModelId, now, expireAt);
        }
    }

    /**
     * 触发渠道级熔断（全渠道或按 API Key）
     * <p>由 {@code (channelId, channelApiKeyId)} 二元组标识熔断记录。
     * 当 {@code channelApiKeyId} 为 {@code null} 时表示全渠道熔断，否则表示按 API Key 熔断。
     */
    private void triggerChannelCircuitBreak(Long channelId, Long channelApiKeyId, LocalDateTime now, LocalDateTime expireAt) {
        // 清除该渠道（+ API Key）之前的熔断状态
        LambdaQueryWrapper<CircuitBreakerState> deleteWrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getChannelId, channelId);
        if (channelApiKeyId != null) {
            deleteWrapper.eq(CircuitBreakerState::getChannelApiKeyId, channelApiKeyId);
        }
        stateMapper.delete(deleteWrapper);

        CircuitBreakerState state = new CircuitBreakerState();
        state.setChannelId(channelId);
        state.setChannelApiKeyId(channelApiKeyId);
        state.setIsOpen(1);
        state.setFailCount(1);
        state.setOpenedAt(now);
        state.setExpireAt(expireAt);
        stateMapper.insert(state);

        // 熔断后将 API Key 移到排序末尾（隐式排序）
        if (channelApiKeyId != null) {
            channelApiKeyService.moveToEnd(channelId, channelApiKeyId);
        }
    }

    /**
     * 触发模型级熔断
     * <p>按 {@code (channelId, channelApiKeyId, channelModelId)} 三元组标识，
     * 只影响特定 API Key 下的特定模型。</p>
     */
    private void triggerModelCircuitBreak(Long channelId, Long channelApiKeyId, Long channelModelId,
                                          LocalDateTime now, LocalDateTime expireAt) {
        // 仅清除该 (channelModelId, channelApiKeyId) 组合的既有熔断状态，
        // 避免误删同一模型下其他 API Key 的熔断记录；旧记录（仅有 channelModelId）
        // 继续保留并由 isModelCircuitBroken 兼容识别。
        LambdaQueryWrapper<CircuitBreakerState> deleteWrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getChannelModelId, channelModelId);
        if (channelApiKeyId != null) {
            deleteWrapper.eq(CircuitBreakerState::getChannelApiKeyId, channelApiKeyId);
        }
        stateMapper.delete(deleteWrapper);

        CircuitBreakerState state = new CircuitBreakerState();
        state.setChannelId(channelId);
        state.setChannelApiKeyId(channelApiKeyId);
        state.setChannelModelId(channelModelId);
        state.setIsOpen(1);
        state.setFailCount(1);
        state.setOpenedAt(now);
        state.setExpireAt(expireAt);
        stateMapper.insert(state);

        // 熔断后将 API Key 移到排序末尾（隐式排序）
        if (channelApiKeyId != null) {
            channelApiKeyService.moveToEnd(channelId, channelApiKeyId);
        }
    }

    /**
     * 清除过期的熔断状态（定时任务调用）
     */
    @Transactional
    public void cleanExpiredStates() {
        LambdaQueryWrapper<CircuitBreakerState> wrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getIsOpen, 1)
                .lt(CircuitBreakerState::getExpireAt, LocalDateTime.now());
        List<CircuitBreakerState> expired = stateMapper.selectList(wrapper);
        if (!expired.isEmpty()) {
            stateMapper.delete(wrapper);
            log.info("清理了 {} 个过期的熔断状态", expired.size());
        }
    }

    /**
     * 获取熔断配置的熔断范围描述
     */
    public String getCircuitBreakScopeDesc(CircuitBreakerConfig config) {
        if (config == null || config.getEnabled() != 1) {
            return "熔断已禁用";
        }
        return switch (config.getCircuitBreakScope()) {
            case "channel" -> "渠道级（按 API Key 熔断）";
            default -> "模型级（仅该特定模型）";
        };
    }
}