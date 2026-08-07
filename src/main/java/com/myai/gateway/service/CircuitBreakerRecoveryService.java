package com.myai.gateway.service;

import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.relay.CircuitBreakerProbeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 熔断门恢复服务
 * <p>熔断后「门」不会随到期自动打开，由本服务负责探测后决定：</p>
 * <ul>
 *   <li><b>调用时触发探测</b>（{@link #triggerProbeByChannel}）：渠道内任一 API Key 请求成功时，
 *       投递探测信号——有流量时立即恢复，不依赖周期扫描。</li>
 *   <li><b>周期全量探测</b>（{@link #scanExpiredGates}）：由定时任务按配置间隔
 *       （默认 30 分钟）触发，兜底处理所有到期记录。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：单个常驻守护线程（{@code cb-probe-worker}）消费任务队列——</p>
 * <ul>
 *   <li>无任务时线程阻塞在 {@link BlockingQueue#take()} 上睡眠，零 CPU 消耗；</li>
 *   <li>触发探测时仅向队列 {@link BlockingQueue#offer} 一个渠道 ID 唤醒线程，<b>不创建/销毁线程</b>；</li>
 *   <li>同一渠道<b>节流</b>（系统配置 {@code circuit_breaker_probe_throttle_seconds}，默认 6 秒，0=不节流）：
 *       间隔内重复触发的信号被丢弃，避免高流量下每个请求都触发探测。</li>
 * </ul>
 *
 * <p>处理语义：</p>
 * <ul>
 *   <li><b>模型级门</b>（记录含 channelModelId）：到期后探测该模型，
 *       成功 → 删记录开门（并联动打开同 Key 的渠道级门）；失败 → 按熔断配置时长续期，门继续关。</li>
 *   <li><b>渠道级门</b>（记录不含 channelModelId）：到期后探测该渠道（任一启用模型 + 门对应 Key），
 *       成功 → 开门；失败 → 续期。也可被其下模型的恢复联动提前打开。
 *       <b>不随到期自动开门</b>：到期仅表示需要探测，探测成功才取消熔断状态。</li>
 * </ul>
 */
@Service
public class CircuitBreakerRecoveryService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRecoveryService.class);

    /** 全量扫描的队列标记（渠道 ID 从 1 开始的自增正数，-1 不会冲突） */
    private static final long FULL_SCAN_MARK = -1L;

    private final CircuitBreakerService circuitBreakerService;
    private final CircuitBreakerProbeService probeService;
    private final ModelService modelService;
    private final ChannelApiKeyService channelApiKeyService;
    private final AdminConfigService adminConfigService;

    /** 任务队列：工作线程无事可做时阻塞在此（睡眠），投递任务时被唤醒 */
    private final BlockingQueue<Long> taskQueue = new LinkedBlockingQueue<>();
    /** 渠道 ID -> 上次实际执行探测的时间戳（毫秒），用于节流判断 */
    private final ConcurrentHashMap<Long, Long> lastProbeAt = new ConcurrentHashMap<>();

    private final Thread worker;

    public CircuitBreakerRecoveryService(CircuitBreakerService circuitBreakerService,
                                         CircuitBreakerProbeService probeService,
                                         ModelService modelService,
                                         ChannelApiKeyService channelApiKeyService,
                                         AdminConfigService adminConfigService) {
        this.circuitBreakerService = circuitBreakerService;
        this.probeService = probeService;
        this.modelService = modelService;
        this.channelApiKeyService = channelApiKeyService;
        this.adminConfigService = adminConfigService;
        this.worker = new Thread(this::workerLoop, "cb-probe-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 渠道内任一 API Key 请求成功时投递探测信号（fire-and-forget）。
     * <p>调用成功说明渠道上游可达，立即验证该渠道下所有到期门：
     * 模型级门探测（成功开门/失败续期），渠道级门到期直接开门。
     * 熔断中的 Key 自身没有流量，只能靠同渠道健康 Key 的调用来触发恢复。</p>
     * <p>仅向队列投递一个渠道 ID（无锁、不阻塞、不创建线程），
     * 由工作线程按 6s 节流决定是否实际探测。</p>
     *
     * @param channelId 渠道 ID
     */
    public void triggerProbeByChannel(Long channelId) {
        if (channelId == null) {
            return;
        }
        taskQueue.offer(channelId); // 队列理论不会满（个人网关规模），满时丢弃本次信号即可
    }

    /**
     * 周期全量扫描所有到期熔断记录（异步投递，由定时任务触发）。
     * <p>与触发式探测共用同一工作线程与 {@link #processExpiredState} 处理逻辑，
     * 不受节流限制（兜底任务必须执行）。</p>
     */
    public void scanExpiredGates() {
        taskQueue.offer(FULL_SCAN_MARK);
    }

    /**
     * 工作线程主循环：无任务时睡眠，有任务时唤醒处理。
     */
    private void workerLoop() {
        log.info("熔断探测工作线程启动");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                long task = taskQueue.take(); // 无任务时阻塞睡眠
                if (task == FULL_SCAN_MARK) {
                    doFullScan();
                } else {
                    maybeProbeChannel(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("熔断探测工作线程异常", e);
            }
        }
        log.info("熔断探测工作线程退出");
    }

    /**
     * 触发式探测：节流检查通过才实际执行。
     * <p>节流间隔每次从系统配置读取（{@code circuit_breaker_probe_throttle_seconds}，
     * 默认 6 秒，0=不节流），改配置即时生效。</p>
     */
    private void maybeProbeChannel(long channelId) {
        long throttleMs = (long) (adminConfigService.getCircuitBreakerProbeThrottleSeconds() * 1000);
        long now = System.currentTimeMillis();
        Long prev = lastProbeAt.get(channelId);
        if (prev != null && now - prev < throttleMs) {
            return; // 节流：间隔内重复信号丢弃
        }
        lastProbeAt.put(channelId, now);
        List<CircuitBreakerState> expired;
        try {
            expired = circuitBreakerService.listExpiredStatesByChannel(channelId);
        } catch (Exception e) {
            log.error("触发式查询到期熔断记录失败 - channelId={}: {}", channelId, e.getMessage());
            return;
        }
        if (expired.isEmpty()) {
            return;
        }
        log.info("调用触发熔断探测 - channelId={}, 到期记录{}条", channelId, expired.size());
        for (CircuitBreakerState state : expired) {
            try {
                processExpiredState(state);
            } catch (Exception e) {
                log.error("触发式处理熔断门失败，跳过 - stateId={}: {}", state.getId(), e.getMessage());
            }
        }
    }

    /**
     * 全量扫描：处理所有到期记录（不节流）。
     */
    private void doFullScan() {
        List<CircuitBreakerState> expired;
        try {
            expired = circuitBreakerService.listExpiredStates();
        } catch (Exception e) {
            log.error("扫描到期熔断记录失败", e);
            return;
        }
        if (expired.isEmpty()) {
            return;
        }
        log.info("熔断恢复全量扫描 - 共 {} 扇门到期待处理", expired.size());
        for (CircuitBreakerState state : expired) {
            try {
                processExpiredState(state);
            } catch (Exception e) {
                log.error("处理熔断门失败，跳过 - stateId={}: {}", state.getId(), e.getMessage());
            }
        }
    }

    /**
     * 处理单条到期熔断记录：模型级门探测/续期/清理，渠道级门探测/续期/清理。
     * <p>两条路径都不随到期自动开门：到期仅表示需要探测，探测成功（删除记录）才取消熔断。</p>
     */
    private void processExpiredState(CircuitBreakerState state) {
        if (state == null || state.getId() == null) {
            return;
        }
        if (state.getChannelModelId() != null) {
            handleModelGate(state);
        } else {
            handleChannelGate(state);
        }
    }

    /**
     * 处理渠道级门：到期后探测该渠道（任一启用模型 + 门对应的 Key）。
     * <p>渠道级门表示整个渠道（或该渠道某 Key）不可用，无独立于模型的探测目标——
     * 同一渠道上游对所有模型一致，任一启用模型的探测结果即可代表渠道可达性：</p>
     * <ul>
     *   <li>按 Key 熔断（记录含 channelApiKeyId）：探测该 Key；Key 已删/禁用 → 记录清理。</li>
     *   <li>全渠道熔断（记录不含 apiKeyId）：探测渠道任一启用 Key。</li>
     *   <li>探测成功 → 开门（删除记录）；失败 → 续期（门继续关，到期时间顺延）。</li>
     *   <li>渠道/模型已删除或禁用、无可用 Key/模型 → 记录无意义，直接清理开门。</li>
     * </ul>
     */
    private void handleChannelGate(CircuitBreakerState state) {
        Long channelId = state.getChannelId();
        if (channelId == null) {
            circuitBreakerService.removeState(state);
            return;
        }
        Channel channel = modelService.getChannelById(channelId);
        if (channel == null || !isEnabled(channel.getEnabled())) {
            log.info("渠道已删除或禁用，直接清理熔断记录 - channelId={}", channelId);
            circuitBreakerService.removeState(state);
            return;
        }
        // 探测 Key：按 Key 熔断时用该 Key，全渠道门取任一启用 Key
        ChannelApiKey apiKey = resolveChannelGateProbeKey(state, channel);
        if (apiKey == null) {
            log.info("无可用 API Key 可探测，直接清理熔断记录 - channelId={}", channelId);
            circuitBreakerService.removeState(state);
            return;
        }
        // 探测模型：渠道下任一启用模型
        ChannelModel channelModel = modelService.getFirstEnabledChannelModelByChannelId(channelId);
        if (channelModel == null) {
            log.info("无可用渠道模型可探测，直接清理熔断记录 - channelId={}", channelId);
            circuitBreakerService.removeState(state);
            return;
        }
        boolean alive = probeService.probe(channel, channelModel, apiKey);
        if (alive) {
            circuitBreakerService.removeState(state);
        } else {
            int duration = modelService.getCircuitBreakDurationByChannelModelId(channelModel.getId());
            circuitBreakerService.renewState(state, duration);
            log.warn("渠道级探测失败，门保持关闭并续期{}s - channel={}, key={}",
                    duration, channel.getName(), apiKey.getKeyName());
        }
    }

    /**
     * 解析渠道级门探测用的 API Key。
     * <p>记录带 apiKeyId 时用该 Key（须仍启用）；全渠道记录（apiKeyId 为空）
     * 取渠道任一启用 Key。</p>
     */
    private ChannelApiKey resolveChannelGateProbeKey(CircuitBreakerState state, Channel channel) {
        if (state.getChannelApiKeyId() != null) {
            ChannelApiKey apiKey = channelApiKeyService.getById(state.getChannelApiKeyId());
            if (apiKey != null && isEnabled(apiKey.getEnabled())) {
                return apiKey;
            }
            return null;
        }
        List<ChannelApiKey> keys = channelApiKeyService.getAvailableApiKeys(channel.getId());
        if (keys != null && !keys.isEmpty()) {
            return keys.get(0);
        }
        return null;
    }

    /**
     * 处理模型级门：解析实体 → 探测 → 成功开门（联动渠道门）/ 失败续期
     */
    private void handleModelGate(CircuitBreakerState state) {
        Long channelModelId = state.getChannelModelId();

        // 1. 实体不可用（已删除/禁用）→ 记录无意义，直接开门清理
        ChannelModel channelModel = modelService.getChannelModelById(channelModelId);
        if (channelModel == null || !isEnabled(channelModel.getEnabled())) {
            log.info("渠道模型已删除或禁用，直接清理熔断记录 - channelModelId={}", channelModelId);
            circuitBreakerService.removeState(state);
            return;
        }
        Channel channel = modelService.getChannelById(channelModel.getChannelId());
        if (channel == null || !isEnabled(channel.getEnabled())) {
            log.info("渠道已删除或禁用，直接清理熔断记录 - channelId={}", channelModel.getChannelId());
            circuitBreakerService.removeState(state);
            return;
        }

        // 2. 解析探测用 API Key
        ChannelApiKey apiKey = resolveProbeApiKey(state, channel);
        if (apiKey == null) {
            log.info("无可用 API Key 可探测，直接清理熔断记录 - channelId={}, channelModelId={}",
                    channel.getId(), channelModelId);
            circuitBreakerService.removeState(state);
            return;
        }

        // 3. 探测
        boolean alive = probeService.probe(channel, channelModel, apiKey);
        if (alive) {
            // 门开：模型恢复 + 联动恢复同 Key 的渠道级门（日志在 service 内部记录）
            circuitBreakerService.recoverModelState(state);
        } else {
            // 门继续关：按该模型配置的熔断时长续期
            int duration = modelService.getCircuitBreakDurationByChannelModelId(channelModelId);
            circuitBreakerService.renewState(state, duration);
            log.warn("熔断探测失败，门保持关闭并续期{}s - channel={}, model={}, key={}",
                    duration, channel.getName(), channelModel.getModelName(), apiKey.getKeyName());
        }
    }

    /**
     * 解析模型级门探测用的 API Key。
     * <p>记录带 apiKeyId 时用该 Key（须仍启用）；旧记录（apiKeyId 为空）退化为
     * 取渠道任一启用 Key。</p>
     */
    private ChannelApiKey resolveProbeApiKey(CircuitBreakerState state, Channel channel) {
        if (state.getChannelApiKeyId() != null) {
            ChannelApiKey apiKey = channelApiKeyService.getById(state.getChannelApiKeyId());
            if (apiKey != null && isEnabled(apiKey.getEnabled())) {
                return apiKey;
            }
            return null;
        }
        List<ChannelApiKey> keys = channelApiKeyService.getAvailableApiKeys(channel.getId());
        if (keys != null && !keys.isEmpty()) {
            return keys.get(0);
        }
        return null;
    }

    private boolean isEnabled(Integer enabled) {
        return enabled != null && enabled == 1;
    }

    @Override
    public void destroy() {
        worker.interrupt();
    }
}
