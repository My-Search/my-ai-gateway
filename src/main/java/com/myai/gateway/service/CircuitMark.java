package com.myai.gateway.service;

import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.entity.ModelChannelRel;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 熔断标记器 - 负责熔断状态的展示和批量计算
 */
@Component
public class CircuitMark {

    private final CircuitCheck circuitCheck;
    private final CircuitGate circuitGate;
    private final ModelService modelService;
    private final ChannelApiKeyService channelApiKeyService;

    public CircuitMark(CircuitCheck circuitCheck, CircuitGate circuitGate,
                       ModelService modelService, ChannelApiKeyService channelApiKeyService) {
        this.circuitCheck = circuitCheck;
        this.circuitGate = circuitGate;
        this.modelService = modelService;
        this.channelApiKeyService = channelApiKeyService;
    }

    /**
     * 判断渠道模型的所有调用途径（API Key）是否全部被熔断（管理界面展示用）。
     * <p>一个渠道模型可能绑定单个 Key，也可能不绑定 Key（请求时轮询渠道下所有可用 Key）。
     * 展示语义：仅当<b>所有</b>调用途径都被熔断时才显示"熔断中"——
     * 部分 Key 熔断时请求会自动路由到其余 Key，模型仍可用，不应显示熔断。
     * <ul>
     *   <li>绑定 Key（{@code channelApiKeyId} 非空）：该 Key 被熔断即全部不可用（唯一途径）；
     *       Key 不存在或已禁用则视为无熔断（配置问题而非熔断，不显示）。</li>
     *   <li>未绑定 Key：渠道下所有可用（启用）Key 都被熔断才算全部不可用；
     *       渠道无可用 Key 时同样不显示（非熔断范畴）。</li>
     * </ul>
     * <p>熔断记录不看过期时间（{@code isOpen=1} 即熔断），与请求侧路由语义一致；
     * 到期仅表示需要探测，探测成功删除记录后才解除。
     *
     * @param channelModelId  渠道模型 ID（可为 null）
     * @param channelId       渠道 ID（可为 null）
     * @param channelApiKeyId 渠道模型绑定的 API Key ID（null 表示未绑定，轮询所有可用 Key）
     * @return true=所有调用途径均被熔断；false=仍有可用途径或无法判定（无可用 Key/参数不全）
     */
    public boolean isFullyBroken(Long channelModelId, Long channelId, Long channelApiKeyId) {
        if (channelModelId == null || channelId == null) {
            return false;
        }
        List<ChannelApiKey> paths;
        if (channelApiKeyId != null) {
            ChannelApiKey key = channelApiKeyService.getById(channelApiKeyId);
            if (key == null || key.getEnabled() == null || key.getEnabled() != 1) {
                return false;
            }
            paths = List.of(key);
        } else {
            paths = channelApiKeyService.getAvailableApiKeys(channelId);
        }
        if (paths.isEmpty()) {
            return false;
        }
        // 加载该关联的全部生效熔断记录（模型级 + 渠道级，Key 范围与调用途径一致），内存判定
        List<CircuitBreakerState> states = circuitCheck.getActiveBrokenStates(channelModelId, channelId, channelApiKeyId);
        List<CircuitBreakerState> modelStates = states.stream()
                .filter(s -> s.getChannelModelId() != null).toList();
        List<CircuitBreakerState> channelStates = states.stream()
                .filter(s -> s.getChannelModelId() == null).toList();
        for (ChannelApiKey key : paths) {
            if (circuitCheck.isPathAvailable(channelId, key.getId(), modelStates, channelStates)) {
                return false; // 仍有可用途径
            }
        }
        return true;
    }

    /**
     * 批量计算关联列表的熔断展示标记（避免逐关联 N+1 查询）。
     * <p>一次加载：涉及的渠道模型（取绑定 Key）、涉及渠道的全部生效熔断记录
     * （{@code isOpen=1}，不看过期时间）、启用 Key（按渠道分组）与绑定 Key（含禁用）。
     * 判定语义与 {@link #isFullyBroken} 完全一致（全部调用途径熔断才标记），
     * 且与请求侧路由的可用性语义一致。
     *
     * @param rels 关联列表
     * @return relId → 熔断标记；未被熔断或无法判定的关联不在返回中（调用方置为正常）
     */
    public Map<Long, CircuitBreakerService.RelBrokenMark> computeRelBrokenMarks(Collection<ModelChannelRel> rels) {
        Map<Long, CircuitBreakerService.RelBrokenMark> result = new java.util.HashMap<>();
        if (rels == null || rels.isEmpty()) {
            return result;
        }
        // 1. 批量加载渠道模型（获取每个关联绑定的 Key）
        List<Long> cmIds = rels.stream()
                .map(ModelChannelRel::getChannelModelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ChannelModel> cmById = modelService.getChannelModelsByIds(cmIds).stream()
                .collect(Collectors.toMap(ChannelModel::getId, c -> c));
        // 2. 批量加载涉及渠道的所有生效熔断记录
        List<Long> channelIds = rels.stream()
                .map(ModelChannelRel::getChannelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<CircuitBreakerState> states = circuitCheck.listOpenStatesByChannelIds(channelIds);
        // 3. 批量加载 API Key：启用 Key（按渠道分组，用于未绑定 Key 的关联）+ 绑定 Key（含禁用，判断"配置问题"）
        Map<Long, List<ChannelApiKey>> enabledKeysByChannel = channelApiKeyService.listEnabledByChannelIds(channelIds);
        List<Long> boundKeyIds = cmById.values().stream()
                .map(ChannelModel::getChannelApiKeyId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ChannelApiKey> keysById = channelApiKeyService.getByIds(boundKeyIds).stream()
                .collect(Collectors.toMap(ChannelApiKey::getId, k -> k));

        for (ModelChannelRel rel : rels) {
            if (rel.getId() == null || rel.getChannelModelId() == null || rel.getChannelId() == null) {
                continue;
            }
            ChannelModel cm = cmById.get(rel.getChannelModelId());
            Long relKeyId = cm != null ? cm.getChannelApiKeyId() : null;
            CircuitBreakerService.RelBrokenMark mark = evaluateRelBroken(rel.getChannelModelId(), rel.getChannelId(), relKeyId,
                    states, enabledKeysByChannel.getOrDefault(rel.getChannelId(), List.of()), keysById);
            if (mark != null) {
                result.put(rel.getId(), mark);
            }
        }
        return result;
    }

    /**
     * 单个关联的熔断标记内存判定（与 {@link #isFullyBroken} 同一套语义）。
     *
     * @return null=未熔断或无法判定（配置问题/无可用 Key/参数不全）
     */
    private CircuitBreakerService.RelBrokenMark evaluateRelBroken(Long channelModelId, Long channelId, Long relKeyId,
                                            List<CircuitBreakerState> states,
                                            List<ChannelApiKey> enabledKeys,
                                            Map<Long, ChannelApiKey> keysById) {
        // 该关联相关的记录：模型级（本渠道模型）+ 渠道级（本渠道，channelModelId IS NULL）。
        // Key 匹配规则与 getActiveBrokenStates 一致：relKeyId 为空时匹配所有 Key；非空时匹配该 Key 或旧数据（apiKeyId IS NULL）
        List<CircuitBreakerState> modelStates = new java.util.ArrayList<>();
        List<CircuitBreakerState> channelStates = new java.util.ArrayList<>();
        for (CircuitBreakerState s : states) {
            if (!Objects.equals(s.getChannelId(), channelId)) {
                continue;
            }
            if (s.getChannelModelId() == null) {
                if (relKeyId == null || Objects.equals(s.getChannelApiKeyId(), relKeyId) || s.getChannelApiKeyId() == null) {
                    channelStates.add(s);
                }
            } else if (Objects.equals(s.getChannelModelId(), channelModelId)) {
                if (relKeyId == null || Objects.equals(s.getChannelApiKeyId(), relKeyId) || s.getChannelApiKeyId() == null) {
                    modelStates.add(s);
                }
            }
        }
        // 调用途径：绑定 Key 时唯一途径；未绑定时为渠道全部启用 Key
        List<ChannelApiKey> paths;
        if (relKeyId != null) {
            ChannelApiKey bound = keysById.get(relKeyId);
            if (bound == null || bound.getEnabled() == null || bound.getEnabled() != 1) {
                return null; // 绑定 Key 缺失/禁用：配置问题而非熔断，不显示
            }
            paths = List.of(bound);
        } else {
            paths = enabledKeys;
        }
        if (paths.isEmpty()) {
            return null;
        }
        // 任一途径可用 → 不显示熔断
        for (ChannelApiKey key : paths) {
            if (circuitCheck.isPathAvailable(channelId, key.getId(), modelStates, channelStates)) {
                return null;
            }
        }
        // 全部途径熔断：取作用域与最早到期时间（下次探测时间）
        CircuitBreakerService.RelBrokenMark mark = new CircuitBreakerService.RelBrokenMark();
        boolean hasChannel = !channelStates.isEmpty();
        boolean hasModel = !modelStates.isEmpty();
        mark.scope = hasChannel ? (hasModel ? "both" : "channel") : "model";
        mark.expireAt = Stream.concat(channelStates.stream(), modelStates.stream())
                .map(CircuitBreakerState::getExpireAt)
                .filter(Objects::nonNull)
                .min(java.util.Comparator.naturalOrder())
                .orElse(null);
        return mark;
    }

    /**
     * 手动解除指定渠道模型的熔断（管理界面操作）。
     * <p>删除该模型相关的全部生效熔断记录；若渠道级熔断存在（同渠道同 Key，含全渠道记录）
     * 一并解除——与模型探测恢复时的联动语义一致。
     *
     * @param channelModelId  渠道模型 ID
     * @param channelId       渠道 ID
     * @param channelApiKeyId 渠道 API Key ID
     * @return 解除的熔断记录数
     */
    public int manualRecover(Long channelModelId, Long channelId, Long channelApiKeyId) {
        List<CircuitBreakerState> states = circuitCheck.getActiveBrokenStates(channelModelId, channelId, channelApiKeyId);
        int count = 0;
        for (CircuitBreakerState state : states) {
            if (state.getId() != null) {
                circuitGate.removeState(state);
                count++;
            }
        }
        return count;
    }
}
