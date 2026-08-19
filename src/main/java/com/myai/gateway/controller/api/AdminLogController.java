package com.myai.gateway.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import com.myai.gateway.service.LogSseService;
import com.myai.gateway.service.RequestLogService;
import com.myai.gateway.service.StatsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 管理后台「日志」REST API 控制器
 * <p>从原 {@link AdminApiController} 拆分而来（P2 架构：巨型类拆分），
 * 承载请求日志的分页查询、树形聚合、用量图表、原始请求数据加载与 SSE 实时推送接口。路径前缀与行为与原实现完全一致。</p>
 */
@RestController
@RequestMapping("/admin/api")
public class AdminLogController {

    private static final Logger log = LoggerFactory.getLogger(AdminLogController.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * SSE 日志推送的共享线程池，避免每个连接创建一个独立线程。
     * <p>
     * 核心线程数 = CPU 数，最大 32 个，空闲 60s 回收。
     * 当超过 32 个并发连接时，后续连接会阻塞等待（SSE 连接数通常远小于此值）。
     * </p>
     */
    private static final ExecutorService ssePollExecutor = new ThreadPoolExecutor(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            32,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "sse-reader");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private final RequestLogService requestLogService;
    private final LogSseService logSseService;
    private final StatsService statsService;
    private final ObjectMapper objectMapper;
    private final RequestLogMapper requestLogMapper;

    public AdminLogController(RequestLogService requestLogService,
                              LogSseService logSseService,
                              StatsService statsService,
                              ObjectMapper objectMapper,
                              RequestLogMapper requestLogMapper) {
        this.requestLogService = requestLogService;
        this.logSseService = logSseService;
        this.statsService = statsService;
        this.objectMapper = objectMapper;
        this.requestLogMapper = requestLogMapper;
    }

    @PreDestroy
    public void shutdownSsePool() {
        ssePollExecutor.shutdown();
        try {
            if (!ssePollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ssePollExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ssePollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @GetMapping(value = "/logs", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> listLogs(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) Long gatewayApiKeyId,
            @RequestParam(required = false) String apiKeyName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        // 获取总数（带过滤）
        boolean hasFilters = (modelName != null && !modelName.isEmpty())
                || gatewayApiKeyId != null
                || (apiKeyName != null && !apiKeyName.isEmpty())
                || startTime != null
                || endTime != null;
        long totalTraces;
        List<RequestLog> logs;
        if (hasFilters) {
            totalTraces = requestLogService.getFilteredTraceCount(modelName, gatewayApiKeyId, apiKeyName, startTime, endTime);
            logs = requestLogService.getFilteredLogsByPage(offset, limit, modelName, gatewayApiKeyId, apiKeyName, startTime, endTime);
        } else {
            totalTraces = requestLogService.getTraceCount();
            logs = requestLogService.getLogsByPage(offset, limit);
        }
        Map<String, List<RequestLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(RequestLog::getTraceId));
        List<Map<String, Object>> treeData = new ArrayList<>();

        for (Map.Entry<String, List<RequestLog>> entry : grouped.entrySet()) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("traceId", entry.getKey());
            List<RequestLog> sortedLogs = entry.getValue().stream()
                    .sorted(Comparator.comparing(RequestLog::getCreatedAt))
                    .collect(Collectors.toList());
            trace.put("logs", sortedLogs);

            // 单次遍历替代 5 次 stream()，O(n) 代替 O(5n)
            int retryCount = 0, successCount = 0, failCount = 0;
            String traceModelName = null;
            long totalTime = 0;
            for (RequestLog l : sortedLogs) {
                String phase = l.getPhase();
                if ("retry".equals(phase)) retryCount++;
                else if ("success".equals(phase)) successCount++;
                else if ("fail".equals(phase)) failCount++;
                if (traceModelName == null && l.getModelName() != null) {
                    traceModelName = l.getModelName();
                }
                if (("success".equals(phase) || "fail".equals(phase))
                        && l.getResponseTimeMs() != null) {
                    totalTime += l.getResponseTimeMs();
                }
            }
            trace.put("retryCount", retryCount);
            trace.put("successCount", successCount);
            trace.put("failCount", failCount);
            trace.put("modelName", traceModelName != null ? traceModelName : "");
            trace.put("totalTimeMs", totalTime);

            if (!sortedLogs.isEmpty()) {
                trace.put("startTime", sortedLogs.get(0).getCreatedAt().format(DT_FMT));
                trace.put("endTime", sortedLogs.get(sortedLogs.size() - 1).getCreatedAt().format(DT_FMT));
            }
            treeData.add(trace);
        }

        treeData.sort((a, b) -> {
            var timeA = (String) a.get("endTime");
            var timeB = (String) b.get("endTime");
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });

        // 查询哪些 trace 的 start 日志包含有效的原始请求数据（未被 save level 清除）
        if (!treeData.isEmpty()) {
            List<String> allTraceIds = treeData.stream()
                    .map(t -> (String) t.get("traceId"))
                    .collect(Collectors.toList());
            List<RequestLog> startLogsWithData = requestLogMapper.selectList(
                    new LambdaQueryWrapper<RequestLog>()
                            .select(RequestLog::getTraceId)
                            .in(RequestLog::getTraceId, allTraceIds)
                            .eq(RequestLog::getPhase, "start")
                            .and(w -> w.isNotNull(RequestLog::getRequestHeaders)
                                    .or().isNotNull(RequestLog::getRequestBody)));
            Set<String> traceIdsWithData = startLogsWithData.stream()
                    .map(RequestLog::getTraceId)
                    .collect(Collectors.toSet());
            for (Map<String, Object> trace : treeData) {
                trace.put("hasRequestData", traceIdsWithData.contains(trace.get("traceId")));
            }
        }

        // 返回分页数据
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", treeData);
        result.put("total", totalTraces);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("hasMore", offset + treeData.size() < totalTraces);

        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/logs/clean", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> cleanLogs() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            requestLogService.cleanOldLogs(30);
            result.put("success", true);
            result.put("message", "已清理 30 天前的日志");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * "请求日志"页面顶部"使用历史"堆叠柱状图数据。
     * <p>
     * 按 modelType 分支聚合指定月份的 token 用量，返回 days/models/values 矩阵。
     * modelType=entry 时按 model_name（入口模型）聚合；modelType=channel 时按 channel_model_name（渠道模型）聚合。
     * 仅统计成功请求的 token（与本系统其他用量统计保持一致口径）。
     * </p>
     *
     * @param year            目标年份（默认当前年）
     * @param month           目标月份 1-12（默认当前月）
     * @param modelType       模型类型：entry（入口模型，默认）或 channel（渠道模型）
     * @param modelName       可选：按入口模型名过滤
     * @param gatewayApiKeyId 可选：按网关 API Key 主键过滤（与 apiKeyName 同时存在时优先使用）
     * @param apiKeyName      可选：按 API Key 名过滤（兼容旧调用，对应渠道 API Key 名）
     */
    @GetMapping(value = "/logs/usage-chart", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> logUsageChart(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false, defaultValue = "entry") String modelType,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) Long gatewayApiKeyId,
            @RequestParam(required = false) String apiKeyName) {
        java.time.LocalDate now = java.time.LocalDate.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        if (m < 1 || m > 12) {
            return ResponseEntity.ok(Map.of("error", "month 必须在 1-12 之间"));
        }
        return ResponseEntity.ok(statsService.getLogUsageChart(y, m, modelType, modelName, gatewayApiKeyId, apiKeyName));
    }

    /**
     * 日志实时推送 SSE 端点
     * GET /admin/api/logs/stream
     * <p>
     * 采用 CPA 模式：每个 SSE 连接分配一个独立队列，由专用分发线程统一写入，
     * 确保生产线程（AsyncLogWriter）零阻塞。前端通过 EventSource 连接后，
     * 服务端主动推送 event: log / data: {...RequestLog JSON...}
     * </p>
     *
     * <pre>
     * AsyncLogWriter → centralQueue.offer()       ← 一次入队，零等待
     *                        ↓
     *                  分发线程 (批量 500 条)        ← 单线程批量分发
     *                        ↓
     *                  per-subscriber 独立队列       ← 每人一个队列，互不干扰
     *                        ↓
     *                  SSE 轮询线程 → emitter.send()  ← 前端可见
     * </pre>
     */
    @GetMapping(value = "/logs/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamLogs() {
        // 禁用墙钟超时，避免有数据输出时仍被强制断开。
        // 连接关闭完全由客户端断开（onCompletion）或异常（onError）驱动。
        SseEmitter emitter = new SseEmitter(0L);

        LogSseService.SubscriberQueue sq = logSseService.subscribe();

        ssePollExecutor.execute(() -> {
            try {
                while (true) {
                    // 1s 超时让线程在 unsubscribe 后最多 1 秒内退出（5s → 1s）
                    RequestLog record = sq.poll(1, TimeUnit.SECONDS);
                    if (record == null) {
                        if (!sq.isActive()) {
                            break;
                        }
                        continue;
                    }
                    String json = objectMapper.writeValueAsString(record);
                    emitter.send(SseEmitter.event()
                            .name("log")
                            .data(json, MediaType.APPLICATION_JSON));
                }
            } catch (IOException e) {
                // 连接已关闭，正常结束
            } catch (Exception e) {
                if (!"Broken pipe".equals(e.getMessage())) {
                    log.debug("SSE 推送异常（连接可能已断开）: {}", e.getMessage());
                }
            } finally {
                logSseService.unsubscribe(sq);
            }
        });

        emitter.onCompletion(() -> logSseService.unsubscribe(sq));
        emitter.onTimeout(() -> logSseService.unsubscribe(sq));
        emitter.onError(e -> logSseService.unsubscribe(sq));

        return emitter;
    }

    /**
     * 最小化 SSE 测试端点
     */
    @GetMapping(value = "/sse-test")
    public SseEmitter sseTest() {
        SseEmitter emitter = new SseEmitter(0L);
        // 立即发送消息并完成，finally 确保任意发送失败后都能正确完成 emitter
        try {
            emitter.send(SseEmitter.event().name("test").data("{\"msg\":\"hello\"}"));
            emitter.send(SseEmitter.event().name("test").data("{\"msg\":\"world\"}"));
        } catch (IOException e) {
            log.error("sseTest error", e);
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // emitter 已处于错误状态，忽略
            }
        }
        return emitter;
    }

    /**
     * 按日志 ID 获取原始请求数据（requestHeaders / requestBody）
     * <p>
     * 列表接口 {@code GET /logs} 已排除大字段，此接口用于点击"查看原始请求"时按需加载。
     * 如果数据已被定时清理（request_body_ttl_hours），则返回 null。
     * </p>
     *
     * @param logId 日志主键
     * @return { requestHeaders, requestBody }，数据不存在或已过期时两个字段均为 null
     */
    @GetMapping(value = "/logs/{logId}/request-data", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> getRequestData(@PathVariable Long logId) {
        RequestLog logEntry = requestLogService.getRequestDataByLogId(logId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (logEntry != null) {
            result.put("requestHeaders", logEntry.getRequestHeaders());
            result.put("requestBody", logEntry.getRequestBody());
        } else {
            result.put("requestHeaders", null);
            result.put("requestBody", null);
        }
        return ResponseEntity.ok(result);
    }
}
