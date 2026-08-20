package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 熔断门管理器 - 负责熔断门的状态操作（探测恢复、续期、删除）
 */
@Component
public class CircuitGate {

    private static final Logger log = LoggerFactory.getLogger(CircuitGate.class);

    private final CircuitBreakerStateMapper stateMapper;

    public CircuitGate(CircuitBreakerStateMapper stateMapper) {
        this.stateMapper = stateMapper;
    }

    /**
     * 列出所有到期的熔断记录（门已到探测时间但尚未恢复）。
     * <p>查询条件 {@code isOpen=1 AND expireAt <= now}：这些记录对应的门仍关闭，
     * 等待定时任务探测后才能决定是开门（删除）还是续期（继续关闭）。
     * <p>单轮上限 50 条：避免极端情况下大量到期记录使探测轮次过长，
     * 阻塞 @Scheduled 共享调度线程；剩余记录下一轮继续处理。
     */
    public List<CircuitBreakerState> listExpiredStates() {
        return stateMapper.selectList(
                new LambdaQueryWrapper<CircuitBreakerState>()
                        .eq(CircuitBreakerState::getIsOpen, 1)
                        .le(CircuitBreakerState::getExpireAt, LocalDateTime.now())
                        .orderByAsc(CircuitBreakerState::getExpireAt)
                        .last("LIMIT 50"));
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
     * 模型级探测成功后开门：删除模型级熔断记录。
     * <p>联动规则：若存在同 {@code (channelId, channelApiKeyId)} 的渠道级熔断记录，
     * 一并删除——渠道级门跟随其下模型恢复而恢复。
     * 仅当模型记录的 {@code channelApiKeyId} 非空时才联动（全渠道记录只能通过
     * 渠道级探测开门，单个 Key 的模型恢复不足以证明整个渠道所有 Key 都恢复）。
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
     * 也用于渠道/模型/Key 已被删除或禁用时的记录清理。
     */
    @Transactional
    public void removeState(CircuitBreakerState state) {
        if (state == null || state.getId() == null) {
            return;
        }
        stateMapper.deleteById(state.getId());
        log.info("熔断门打开（删除记录） - channelId={}, channelApiKeyId={}, channelModelId={}",
                state.getChannelId(), state.getChannelApiKeyId(), state.getChannelModelId());
    }

    /**
     * 清除过期的熔断状态
     * <p><b>注意：当前无调用方，切勿接入定时任务。</b>熔断记录不会随到期自动失效——
     * 到期仅表示需要探测（见 {@code CircuitBreakerRecoveryService}），
     * 本方法直接删除记录会绕过探测、提前开门，与新语义冲突。
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
}
