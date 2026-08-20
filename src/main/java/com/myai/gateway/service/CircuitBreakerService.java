package com.myai.gateway.service;

import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.entity.ModelChannelRel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 熔断服务门面
 * <p>对外提供统一的熔断操作接口，内部委托给各职责类：
 * <ul>
 *   <li>{@link CircuitCheck} - 熔断状态检查</li>
 *   <li>{@link CircuitTrigger} - 熔断触发</li>
 *   <li>{@link CircuitGate} - 门状态管理（探测恢复、续期、删除）</li>
 *   <li>{@link CircuitMark} - 熔断标记计算（管理界面展示）</li>
 * </ul>
 *
 * <p>采用两级模型（三级合并为两级）：
 * <ul>
 *   <li><b>渠道级（合并）</b> — 由 {@code (channelId, channelApiKeyId)} 二元组标识：
 *       全渠道熔断时 {@code channelApiKeyId = null}，按 API Key 熔断时 {@code channelApiKeyId} 非空。</li>
 *   <li><b>模型级</b> — 由 {@code (channelId, channelApiKeyId, channelModelId)} 三个字段联合标识。</li>
 * </ul>
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);

    private final CircuitCheck circuitCheck;
    private final CircuitTrigger circuitTrigger;
    private final CircuitGate circuitGate;
    private final CircuitMark circuitMark;
    private final ApplicationEventPublisher eventPublisher;

    public CircuitBreakerService(CircuitCheck circuitCheck, CircuitTrigger circuitTrigger,
                                 CircuitGate circuitGate, CircuitMark circuitMark,
                                 ApplicationEventPublisher eventPublisher) {
        this.circuitCheck = circuitCheck;
        this.circuitTrigger = circuitTrigger;
        this.circuitGate = circuitGate;
        this.circuitMark = circuitMark;
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

    // ==================== 熔断检查接口 ====================

    /**
     * 检查渠道是否被熔断（全渠道级，仅匹配旧版全渠道记录）
     * <p>仅匹配 {@code channelApiKeyId IS NULL} 的旧全渠道熔断记录，
     * 按 API Key 的熔断请使用 {@link #isChannelCircuitBroken(Long, Long)}。
     * <p><b>不看过期时间</b>：{@code isOpen=1} 即视为熔断，记录不会随到期自动失效——
     * 到期仅表示"需要探测"（见 {@code CircuitBreakerRecoveryService}），
     * 探测成功（删除记录）后才解除熔断。</p>
     */
    public boolean isChannelCircuitBroken(Long channelId) {
        return circuitCheck.isChannelCircuitBroken(channelId);
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
        return circuitCheck.isChannelCircuitBroken(channelId, channelApiKeyId);
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
        return circuitCheck.isModelCircuitBroken(channelModelId, channelApiKeyId);
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

    // ==================== 熔断触发接口 ====================

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
        circuitTrigger.triggerCircuitBreak(modelId, channelId, channelApiKeyId, channelModelId);
        // 发布事件，触发熔断短路缓存失效
        publishStateChangedEvent(channelId, channelApiKeyId, channelModelId);
    }

    // ==================== 门状态管理接口 ====================

    /**
     * 列出所有到期的熔断记录（门已到探测时间但尚未恢复）。
     * <p>查询条件 {@code isOpen=1 AND expireAt <= now}：这些记录对应的门仍关闭，
     * 等待定时任务探测后才能决定是开门（删除）还是续期（继续关闭）。
     * <p>单轮上限 50 条：避免极端情况下大量到期记录使探测轮次过长，
     * 阻塞 @Scheduled 共享调度线程；剩余记录下一轮继续处理。
     */
    public List<CircuitBreakerState> listExpiredStates() {
        return circuitGate.listExpiredStates();
    }

    /**
     * 列出指定渠道下所有到期的熔断记录（模型级 + 渠道级，不限 Key）。
     * <p>用于调用时异步触发探测：渠道内任一 Key 被真实请求命中（说明渠道上游可达），
     * 立即处理该渠道下所有到期记录，避免依赖周期全量扫描。
     * 熔断中的 Key 自身没有流量，只能靠同渠道健康 Key 的调用来触发恢复。
     *
     * @param channelId 渠道 ID（可为 null）
     */
    public List<CircuitBreakerState> listExpiredStatesByChannel(Long channelId) {
        return circuitGate.listExpiredStatesByChannel(channelId);
    }

    /**
     * 模型级探测成功后开门：删除模型级熔断记录。
     * <p>联动规则：若存在同 {@code (channelId, channelApiKeyId)} 的渠道级熔断记录，
     * 一并删除——渠道级门跟随其下模型恢复而恢复。
     * 仅当模型记录的 {@code channelApiKeyId} 非空时才联动（全渠道记录只能通过
     * 渠道级探测开门，单个 Key 的模型恢复不足以证明整个渠道所有 Key 都恢复）。
     */
    @Transactional
    public void recoverModelState(CircuitBreakerState state) {
        circuitGate.recoverModelState(state);
        // 发布事件，触发熔断短路缓存失效
        if (state != null && state.getId() != null) {
            publishStateChangedEvent(state.getChannelId(), state.getChannelApiKeyId(), state.getChannelModelId());
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
        circuitGate.renewState(state, durationSeconds);
    }

    /**
     * 删除熔断记录（开门）。
     * <p>渠道级门探测成功后的开门方式（到期不直接开门，需探测确认，见
     * {@code CircuitBreakerRecoveryService#handleChannelGate}）；
     * 也用于渠道/模型/Key 已被删除或禁用时的记录清理。
     */
    @Transactional
    public void removeState(CircuitBreakerState state) {
        circuitGate.removeState(state);
        // 发布事件，触发熔断短路缓存失效
        if (state != null && state.getId() != null) {
            publishStateChangedEvent(state.getChannelId(), state.getChannelApiKeyId(), state.getChannelModelId());
        }
    }

    /**
     * 清除过期的熔断状态
     * <p><b>注意：当前无调用方，切勿接入定时任务。</b>熔断记录不会随到期自动失效——
     * 到期仅表示需要探测（见 {@code CircuitBreakerRecoveryService}），
     * 本方法直接删除记录会绕过探测、提前开门，与新语义冲突。
     */
    @Transactional
    public void cleanExpiredStates() {
        circuitGate.cleanExpiredStates();
    }

    // ==================== 熔断标记接口 ====================

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
        return circuitMark.isFullyBroken(channelModelId, channelId, channelApiKeyId);
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
    public Map<Long, RelBrokenMark> computeRelBrokenMarks(Collection<ModelChannelRel> rels) {
        return circuitMark.computeRelBrokenMarks(rels);
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
    @Transactional
    public int manualRecover(Long channelModelId, Long channelId, Long channelApiKeyId) {
        int count = circuitMark.manualRecover(channelModelId, channelId, channelApiKeyId);
        if (count > 0) {
            // 发布事件，触发熔断短路缓存失效
            publishStateChangedEvent(channelId, channelApiKeyId, channelModelId);
        }
        return count;
    }

    // ==================== 工具方法 ====================

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

    /**
     * 关联熔断标记（管理界面展示用）。
     */
    public static class RelBrokenMark {
        /** 熔断级别：model-模型级，channel-渠道级，both-两者都有 */
        public String scope;
        /** 下次探测时间（相关熔断记录中最早的到期时间） */
        public LocalDateTime expireAt;
    }
}
