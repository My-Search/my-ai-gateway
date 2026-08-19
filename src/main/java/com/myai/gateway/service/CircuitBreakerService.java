package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.*;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final ApplicationEventPublisher eventPublisher;

    public CircuitBreakerService(CircuitBreakerStateMapper stateMapper,
                                  ModelService modelService,
                                  ChannelApiKeyService channelApiKeyService,
                                  ApplicationEventPublisher eventPublisher) {
        this.stateMapper = stateMapper;
        this.modelService = modelService;
        this.channelApiKeyService = channelApiKeyService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布熔断状态变更事件，触发缓存失效。
     * <p>仅在 channelApiKeyId 和 channelModelId 都有值时精确失效单个 key；
     * 其他情况（如全渠道熔断）失效整个命名空间以确保一致性。</p>
     */
    private void publishStateChangedEvent(Long channelId, Long channelApiKeyId, Long channelModelId) {
        try {
            eventPublisher.publishEvent(new CircuitBreakerStateChangedEvent(this, channelId, channelApiKeyId, channelModelId));
        } catch (Exception e) {
            // 事件发布失败不应影响熔断主逻辑
            log.debug("发布熔断状态变更事件失败: channelId={}, err={}", channelId, e.getMessage());
        }
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
     * 同样视为该模型下所有 API Key 均被熔断。</p>
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
        // 发布事件，触发熔断短路缓存失效
        publishStateChangedEvent(channelId, channelApiKeyId, channelModelId);
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

    // ==================== 门状态管理（定时探测恢复用） ====================

    /**
     * 列出所有到期的熔断记录（门已到探测时间但尚未恢复）。
     * <p>查询条件 {@code isOpen=1 AND expireAt <= now}：这些记录对应的门仍关闭，
     * 等待定时任务探测后才能决定是开门（删除）还是续期（继续关闭）。</p>
     */
    public List<CircuitBreakerState> listExpiredStates() {
        // 单轮上限 50 条：避免极端情况下大量到期记录使探测轮次过长，
        // 阻塞 @Scheduled 共享调度线程；剩余记录下一轮继续处理。
        return stateMapper.selectList(
                new LambdaQueryWrapper<CircuitBreakerState>()
                        .eq(CircuitBreakerState::getIsOpen, 1)
                        .le(CircuitBreakerState::getExpireAt, LocalDateTime.now())
                        .orderByAsc(CircuitBreakerState::getExpireAt)
                        .last("LIMIT 50"));
    }

    /**
     * 模型级探测成功后开门：删除模型级熔断记录。
     * <p>联动规则：若存在同 {@code (channelId, channelApiKeyId)} 的渠道级熔断记录，
     * 一并删除——渠道级门跟随其下模型恢复而恢复。
     * 仅当模型记录的 {@code channelApiKeyId} 非空时才联动（全渠道记录只能通过
     * 渠道级探测开门，单个 Key 的模型恢复不足以证明整个渠道所有 Key 都恢复）。</p>
     */
    @Transactional
    public void recoverModelState(CircuitBreakerState state) {
        if (state == null || state.getId() == null) {
            return;
        }
        // 1. 删除模型级记录（门开）
        int deletedRows = stateMapper.deleteById(state.getId());
        if (deletedRows == 0) {
            // 记录已被并发流程（如真实请求再次触发熔断）删除，联动删除不再适用
            log.debug("模型级记录已不存在，跳过联动 - stateId={}", state.getId());
            return;
        }
        log.info("模型级门打开 - channelModelId={}, channelId={}, channelApiKeyId={}",
                state.getChannelModelId(), state.getChannelId(), state.getChannelApiKeyId());

        // 发布事件，触发熔断短路缓存失效
        publishStateChangedEvent(state.getChannelId(), state.getChannelApiKeyId(), state.getChannelModelId());

        // 2. 联动删除同 (channelId, channelApiKeyId) 的渠道级记录（渠道门跟随模型恢复）
        //    条件：渠道记录的 openedAt 不晚于模型记录（只跟随"先于我熔断"的渠道门）。
        //    防止探测期间渠道门被真实请求重新触发后，旧模型记录误删新渠道门。
        if (state.getChannelId() != null && state.getChannelApiKeyId() != null
                && state.getOpenedAt() != null) {
            LambdaQueryWrapper<CircuitBreakerState> wrapper = new LambdaQueryWrapper<CircuitBreakerState>()
                    .eq(CircuitBreakerState::getChannelId, state.getChannelId())
                    .eq(CircuitBreakerState::getChannelApiKeyId, state.getChannelApiKeyId())
                    .isNull(CircuitBreakerState::getChannelModelId)
                    .le(CircuitBreakerState::getOpenedAt, state.getOpenedAt());
            int deleted = stateMapper.delete(wrapper);
            if (deleted > 0) {
                log.info("渠道级门跟随模型恢复打开 - channelId={}, channelApiKeyId={}",
                        state.getChannelId(), state.getChannelApiKeyId());
            }
        }
    }

    /**
     * 模型级探测失败后续期：门继续保持关闭，顺延到期时间。
     *
     * @param state           熔断记录
     * @param durationSeconds 续期时长（秒），取自该模型配置的熔断持续时间
     */
    @Transactional
    public void renewState(CircuitBreakerState state, int durationSeconds) {
        if (state == null || state.getId() == null) {
            return;
        }
        CircuitBreakerState update = new CircuitBreakerState();
        update.setId(state.getId());
        update.setExpireAt(LocalDateTime.now().plusSeconds(Math.max(durationSeconds, 1)));
        update.setFailCount((state.getFailCount() == null ? 0 : state.getFailCount()) + 1);
        update.setUpdatedAt(LocalDateTime.now());
        stateMapper.updateById(update);
        log.warn("探测失败，熔断续期 - channelModelId={}, channelId={}, channelApiKeyId={}, 续期{}s, 累计失败{}次",
                state.getChannelModelId(), state.getChannelId(), state.getChannelApiKeyId(),
                durationSeconds, update.getFailCount());
    }

    /**
     * 删除熔断记录（开门）。
     * <p>渠道级门探测成功后的开门方式（到期不直接开门，需探测确认，见
     * {@code CircuitBreakerRecoveryService#handleChannelGate}）；
     * 也用于渠道/模型/Key 已被删除或禁用时的记录清理。</p>
     */
    @Transactional
    public void removeState(CircuitBreakerState state) {
        if (state == null || state.getId() == null) {
            return;
        }
        stateMapper.deleteById(state.getId());
        log.info("熔断门打开（删除记录） - channelId={}, channelApiKeyId={}, channelModelId={}",
                state.getChannelId(), state.getChannelApiKeyId(), state.getChannelModelId());
        // 发布事件，触发熔断短路缓存失效
        publishStateChangedEvent(state.getChannelId(), state.getChannelApiKeyId(), state.getChannelModelId());
    }

    /**
     * 判断渠道模型的所有调用途径（API Key）是否全部被熔断（管理界面展示用）。
     * <p>一个渠道模型可能绑定单个 Key，也可能不绑定 Key（请求时轮询渠道下所有可用 Key）。
     * 展示语义：仅当<b>所有</b>调用途径都被熔断时才显示"熔断中"——
     * 部分 Key 熔断时请求会自动路由到其余 Key，模型仍可用，不应显示熔断。</p>
     * <ul>
     *   <li>绑定 Key（{@code channelApiKeyId} 非空）：该 Key 被熔断即全部不可用（唯一途径）；
     *       Key 不存在或已禁用则视为无熔断（配置问题而非熔断，不显示）。</li>
     *   <li>未绑定 Key：渠道下所有可用（启用）Key 都被熔断才算全部不可用；
     *       渠道无可用 Key 时同样不显示（非熔断范畴）。</li>
     * </ul>
     * <p>熔断记录不看过期时间（{@code isOpen=1} 即熔断），与请求侧路由语义一致；
     * 到期仅表示需要探测，探测成功删除记录后才解除。</p>
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
        List<CircuitBreakerState> states = getActiveBrokenStates(channelModelId, channelId, channelApiKeyId);
        List<CircuitBreakerState> modelStates = states.stream()
                .filter(s -> s.getChannelModelId() != null).toList();
        List<CircuitBreakerState> channelStates = states.stream()
                .filter(s -> s.getChannelModelId() == null).toList();
        for (ChannelApiKey key : paths) {
            if (isPathAvailable(channelId, key.getId(), modelStates, channelStates)) {
                return false; // 仍有可用途径
            }
        }
        return true;
    }

    /**
     * 判断某个调用途径（API Key）当前是否可用（内存判定，供熔断展示用）。
     * <p>与请求侧 {@link #isAvailable} 的三层语义一致：</p>
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
    private boolean isPathAvailable(Long channelId, Long keyId,
                                    List<CircuitBreakerState> modelStates,
                                    List<CircuitBreakerState> channelStates) {
        for (CircuitBreakerState s : channelStates) {
            if (s.getChannelApiKeyId() == null) {
                return false; // 全渠道熔断
            }
        }
        for (CircuitBreakerState s : channelStates) {
            if (Objects.equals(s.getChannelApiKeyId(), keyId)) {
                return false; // 渠道级按 Key 熔断
            }
        }
        for (CircuitBreakerState s : modelStates) {
            if (s.getChannelApiKeyId() == null || Objects.equals(s.getChannelApiKeyId(), keyId)) {
                return false; // 模型级熔断（该 Key 或旧数据覆盖全部 Key）
            }
        }
        return true;
    }

    /**
     * 批量计算关联列表的熔断展示标记（避免逐关联 N+1 查询）。
     * <p>一次加载：涉及的渠道模型（取绑定 Key）、涉及渠道的全部生效熔断记录
     * （{@code isOpen=1}，不看过期时间）、启用 Key（按渠道分组）与绑定 Key（含禁用）。
     * 判定语义与 {@link #isFullyBroken} 完全一致（全部调用途径熔断才标记），
     * 且与请求侧路由的可用性语义一致。</p>
     *
     * @param rels 关联列表
     * @return relId → 熔断标记；未被熔断或无法判定的关联不在返回中（调用方置为正常）
     */
    public Map<Long, RelBrokenMark> computeRelBrokenMarks(Collection<ModelChannelRel> rels) {
        Map<Long, RelBrokenMark> result = new java.util.HashMap<>();
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
                .collect(java.util.stream.Collectors.toMap(ChannelModel::getId, c -> c));
        // 2. 批量加载涉及渠道的所有生效熔断记录
        List<Long> channelIds = rels.stream()
                .map(ModelChannelRel::getChannelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<CircuitBreakerState> states = listOpenStatesByChannelIds(channelIds);
        // 3. 批量加载 API Key：启用 Key（按渠道分组，用于未绑定 Key 的关联）+ 绑定 Key（含禁用，判断"配置问题"）
        Map<Long, List<ChannelApiKey>> enabledKeysByChannel = channelApiKeyService.listEnabledByChannelIds(channelIds);
        List<Long> boundKeyIds = cmById.values().stream()
                .map(ChannelModel::getChannelApiKeyId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ChannelApiKey> keysById = channelApiKeyService.getByIds(boundKeyIds).stream()
                .collect(java.util.stream.Collectors.toMap(ChannelApiKey::getId, k -> k));

        for (ModelChannelRel rel : rels) {
            if (rel.getId() == null || rel.getChannelModelId() == null || rel.getChannelId() == null) {
                continue;
            }
            ChannelModel cm = cmById.get(rel.getChannelModelId());
            Long relKeyId = cm != null ? cm.getChannelApiKeyId() : null;
            RelBrokenMark mark = evaluateRelBroken(rel.getChannelModelId(), rel.getChannelId(), relKeyId,
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
    private RelBrokenMark evaluateRelBroken(Long channelModelId, Long channelId, Long relKeyId,
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
            if (isPathAvailable(channelId, key.getId(), modelStates, channelStates)) {
                return null;
            }
        }
        // 全部途径熔断：取作用域与最早到期时间（下次探测时间）
        RelBrokenMark mark = new RelBrokenMark();
        boolean hasChannel = !channelStates.isEmpty();
        boolean hasModel = !modelStates.isEmpty();
        mark.scope = hasChannel ? (hasModel ? "both" : "channel") : "model";
        mark.expireAt = java.util.stream.Stream.concat(channelStates.stream(), modelStates.stream())
                .map(CircuitBreakerState::getExpireAt)
                .filter(Objects::nonNull)
                .min(java.util.Comparator.naturalOrder())
                .orElse(null);
        return mark;
    }

    /**
     * 批量查询指定渠道下所有生效的熔断记录（模型级 + 渠道级，不限 Key）。
     * <p>{@code isOpen=1} 即返回，<b>不看过期时间</b>（到期仅表示需要探测，
     * 探测成功删除记录后才解除），供管理界面批量计算熔断标记。</p>
     *
     * @param channelIds 渠道 ID 列表
     */
    public List<CircuitBreakerState> listOpenStatesByChannelIds(Collection<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return List.of();
        }
        return stateMapper.selectList(new LambdaQueryWrapper<CircuitBreakerState>()
                .in(CircuitBreakerState::getChannelId, channelIds)
                .eq(CircuitBreakerState::getIsOpen, 1));
    }

    /**
     * 关联熔断标记（管理界面展示用）。
     */
    public static class RelBrokenMark {
        /** 熔断级别：model-模型级，channel-渠道级，both-两者都有 */
        public String scope;
        /** 下次探测时间（相关熔断记录中最早的到期时间） */
        public LocalDateTime expireAt;
    }

    /**
     * 列出指定渠道下所有到期的熔断记录（模型级 + 渠道级，不限 Key）。
     * <p>用于调用时异步触发探测：渠道内任一 Key 被真实请求命中（说明渠道上游可达），
     * 立即处理该渠道下所有到期记录，避免依赖周期全量扫描。
     * 熔断中的 Key 自身没有流量，只能靠同渠道健康 Key 的调用来触发恢复。</p>
     *
     * @param channelId 渠道 ID（可为 null）
     */
    public List<CircuitBreakerState> listExpiredStatesByChannel(Long channelId) {
        if (channelId == null) {
            return List.of();
        }
        return stateMapper.selectList(
                new LambdaQueryWrapper<CircuitBreakerState>()
                        .eq(CircuitBreakerState::getChannelId, channelId)
                        .eq(CircuitBreakerState::getIsOpen, 1)
                        .le(CircuitBreakerState::getExpireAt, LocalDateTime.now())
                        .orderByAsc(CircuitBreakerState::getExpireAt));
    }

    /**
     * 查询指定渠道模型当前生效的所有熔断记录（模型级 + 渠道级）。
     * <p>用于管理界面展示熔断级别/到期时间（与 {@link #isFullyBroken} 配合），
     * 以及手动解除熔断（{@link #manualRecover}）：</p>
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

    /**
     * 手动解除指定渠道模型的熔断（管理界面操作）。
     * <p>删除该模型相关的全部生效熔断记录；若渠道级熔断存在（同渠道同 Key，含全渠道记录）
     * 一并解除——与模型探测恢复时的联动语义一致。</p>
     *
     * @return 解除的熔断记录数
     */
    @Transactional
    public int manualRecover(Long channelModelId, Long channelId, Long channelApiKeyId) {
        List<CircuitBreakerState> states = getActiveBrokenStates(channelModelId, channelId, channelApiKeyId);
        int count = 0;
        for (CircuitBreakerState state : states) {
            if (state.getId() != null) {
                stateMapper.deleteById(state.getId());
                count++;
            }
        }
        if (count > 0) {
            log.info("手动解除熔断 - channelModelId={}, channelId={}, channelApiKeyId={}, 解除{}条",
                    channelModelId, channelId, channelApiKeyId, count);
            // 发布事件，触发熔断短路缓存失效
            publishStateChangedEvent(channelId, channelApiKeyId, channelModelId);
        }
        return count;
    }

    /**
     * 清除过期的熔断状态
     * <p><b>注意：当前无调用方，切勿接入定时任务。</b>熔断记录不会随到期自动失效——
     * 到期仅表示需要探测（见 {@code CircuitBreakerRecoveryService}），
     * 本方法直接删除记录会绕过探测、提前开门，与新语义冲突。</p>
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