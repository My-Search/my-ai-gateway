package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 熔断检查器 - 负责所有熔断状态的查询和判断
 */
@Component
public class CircuitCheck {

    private final CircuitBreakerStateMapper stateMapper;

    public CircuitCheck(CircuitBreakerStateMapper stateMapper) {
        this.stateMapper = stateMapper;
    }

    /**
     * 检查渠道是否被熔断（全渠道级，仅匹配旧版全渠道记录）
     * <p>仅匹配 {@code channelApiKeyId IS NULL} 的旧全渠道熔断记录，
     * 按 API Key 的熔断请使用 {@link #isChannelCircuitBroken(Long, Long)}。
     * <p><b>不看过期时间</b>：{@code isOpen=1} 即视为熔断，记录不会随到期自动失效——
     * 到期仅表示"需要探测"（见 {@code CircuitBreakerRecoveryService}），
     * 探测成功（删除记录）后才解除熔断。</p>
     */
    public boolean isChannelCircuitBroken(Long channelId) {
        LambdaQueryWrapper<CircuitBreakerState> wrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getChannelId, channelId)
                .isNull(CircuitBreakerState::getChannelApiKeyId)
                .eq(CircuitBreakerState::getIsOpen, 1);
        return stateMapper.selectCount(wrapper) > 0;
    }

    /**
     * 检查渠道（按 API Key 级别）是否被熔断
     * <p>合并原渠道级与 API Key 级的熔断检查。由 {@code (channelId, channelApiKeyId)} 二元组标识，
     * 同时确保 {@code channelModelId IS NULL} 以排除模型级记录。
     *
     * @param channelId       渠道 ID
     * @param channelApiKeyId 渠道 API Key ID（可为 {@code null}，表示全渠道熔断）
     * <p><b>不看过期时间</b>：{@code isOpen=1} 即视为熔断，到期仅表示需要探测，
     * 探测成功后才解除（见 {@link #isChannelCircuitBroken(Long)} 的说明）。</p>
     */
    public boolean isChannelCircuitBroken(Long channelId, Long channelApiKeyId) {
        LambdaQueryWrapper<CircuitBreakerState> wrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                .eq(CircuitBreakerState::getChannelId, channelId)
                .eq(channelApiKeyId != null, CircuitBreakerState::getChannelApiKeyId, channelApiKeyId)
                .isNull(CircuitBreakerState::getChannelModelId)
                .eq(CircuitBreakerState::getIsOpen, 1);
        return stateMapper.selectCount(wrapper) > 0;
    }

    /**
     * 检查指定渠道模型是否被熔断（模型级）
     * <p>按 {@code (channelModelId, channelApiKeyId)} 组合判断。为兼容旧数据，若存在
     * 仅有 {@code channelModelId} 的旧熔断记录（{@code channelApiKeyId IS NULL}），
     * 同样视为该模型下所有 API Key 均被熔断。
     *
     * @param channelModelId  渠道模型 ID
     * @param channelApiKeyId 渠道 API Key ID（不可为 {@code null}）
     * <p><b>不看过期时间</b>：{@code isOpen=1} 即视为熔断，到期仅表示需要探测，
     * 探测成功后才解除（见 {@link #isChannelCircuitBroken(Long)} 的说明）。</p>
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
                .eq(CircuitBreakerState::getIsOpen, 1);
        return stateMapper.selectCount(wrapper) > 0;
    }

    /**
     * 判断某个调用途径（API Key）当前是否可用（内存判定，供熔断展示用）。
     * <p>与请求侧 {@link com.myai.gateway.service.CircuitBreakerService#isAvailable} 的三层语义一致：</p>
     * <ol>
     *   <li>全渠道熔断（渠道级且 apiKeyId IS NULL）→ 所有 Key 不可用；</li>
     *   <li>渠道级按 Key 熔断（渠道级且 apiKeyId = 该 Key）→ 该 Key 不可用；</li>
     *   <li>模型级熔断（模型级且 apiKeyId = 该 Key 或为旧数据 IS NULL 覆盖全部 Key）→ 该途径不可用。</li>
     * </ol>
     *
     * @param channelId     渠道 ID
     * @param keyId         调用途径的 API Key ID
     * @param modelStates   该关联的模型级熔断记录（已按 Key 范围过滤）
     * @param channelStates 该关联的渠道级熔断记录（已按 Key 范围过滤）
     */
    public boolean isPathAvailable(Long channelId, Long keyId,
                                   List<CircuitBreakerState> modelStates,
                                   List<CircuitBreakerState> channelStates) {
        for (CircuitBreakerState s : channelStates) {
            if (s.getChannelApiKeyId() == null) {
                return false; // 全渠道熔断
            }
        }
        for (CircuitBreakerState s : channelStates) {
            if (java.util.Objects.equals(s.getChannelApiKeyId(), keyId)) {
                return false; // 渠道级按 Key 熔断
            }
        }
        for (CircuitBreakerState s : modelStates) {
            if (s.getChannelApiKeyId() == null || java.util.Objects.equals(s.getChannelApiKeyId(), keyId)) {
                return false; // 模型级熔断（该 Key 或旧数据覆盖全部 Key）
            }
        }
        return true;
    }

    /**
     * 批量查询指定渠道下所有生效的熔断记录（模型级 + 渠道级，不限 Key）。
     * <p>{@code isOpen=1} 即返回，<b>不看过期时间</b>（到期仅表示需要探测，
     * 探测成功删除记录后才解除），供管理界面批量计算熔断标记。</p>
     *
     * @param channelIds 渠道 ID 列表
     */
    public List<CircuitBreakerState> listOpenStatesByChannelIds(java.util.Collection<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return List.of();
        }
        return stateMapper.selectList(new LambdaQueryWrapper<CircuitBreakerState>()
                .in(CircuitBreakerState::getChannelId, channelIds)
                .eq(CircuitBreakerState::getIsOpen, 1));
    }

    /**
     * 查询指定渠道模型当前生效的所有熔断记录（模型级 + 渠道级）。
     * <p>用于管理界面展示熔断级别/到期时间（与 {@link com.myai.gateway.service.CircuitBreakerService#isFullyBroken} 配合），
     * 以及手动解除熔断（{@link com.myai.gateway.service.CircuitBreakerService#manualRecover}）：</p>
     * <ul>
     *   <li>模型级：{@code channelModelId} 匹配，且 {@code apiKeyId} 匹配或记录为旧数据（apiKeyId IS NULL 覆盖全部 Key）</li>
     *   <li>渠道级：{@code channelId} 匹配（channelModelId IS NULL），且 {@code apiKeyId} 匹配或为全渠道记录（apiKeyId IS NULL）</li>
     * </ul>
     * <p><b>不看过期时间</b>：{@code isOpen=1} 即视为熔断中（到期仅表示需要探测，
     * 探测成功删除记录后才解除），与请求侧路由判定语义一致。</p>
     *
     * @param channelModelId  渠道模型 ID（可为 null）
     * @param channelId       渠道 ID（可为 null）
     * @param channelApiKeyId 渠道 API Key ID（null 表示匹配该范围内的所有 Key）
     */
    public List<CircuitBreakerState> getActiveBrokenStates(Long channelModelId, Long channelId, Long channelApiKeyId) {
        List<CircuitBreakerState> result = new java.util.ArrayList<>();
        // keyId 为 null（渠道模型未指定 Key）时不加 Key 条件，匹配该范围内所有 Key 的熔断记录；
        // keyId 非空时匹配该 Key 或旧数据（apiKeyId IS NULL 覆盖全部 Key）
        if (channelModelId != null) {
            result.addAll(stateMapper.selectList(
                    new LambdaQueryWrapper<CircuitBreakerState>()
                            .eq(CircuitBreakerState::getChannelModelId, channelModelId)
                            .and(channelApiKeyId != null, k -> k.eq(CircuitBreakerState::getChannelApiKeyId, channelApiKeyId)
                                    .or()
                                    .isNull(CircuitBreakerState::getChannelApiKeyId))
                            .eq(CircuitBreakerState::getIsOpen, 1)));
        }
        if (channelId != null) {
            result.addAll(stateMapper.selectList(
                    new LambdaQueryWrapper<CircuitBreakerState>()
                            .eq(CircuitBreakerState::getChannelId, channelId)
                            .isNull(CircuitBreakerState::getChannelModelId)
                            .and(channelApiKeyId != null, k -> k.eq(CircuitBreakerState::getChannelApiKeyId, channelApiKeyId)
                                    .or()
                                    .isNull(CircuitBreakerState::getChannelApiKeyId))
                            .eq(CircuitBreakerState::getIsOpen, 1)));
        }
        return result;
    }
}
