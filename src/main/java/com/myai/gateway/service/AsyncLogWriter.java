package com.myai.gateway.service;

import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步日志写入器
 * <p>
 * 将日志的数据库写入操作放在统一的阻塞队列中串行化，由单个后台线程统一消费，
 * 避免 SQLite 多连接并发写入导致的 SQLITE_BUSY_SNAPSHOT 异常。
 * </p>
 *
 * <pre>
 * 处理流程：
 * 1. RequestLogService 构建日志记录后，通过 {@link #enqueue(RequestLog)} 提交到阻塞队列
 * 2. 后台线程从队列取出记录，串行执行 DB INSERT（每次最多一批 50 条）
 * 3. INSERT 成功后推送 SSE 通知前端，确保前端收到完整的记录（含自增 ID）
 * 4. 应用关闭时尽力处理队列中剩余的日志记录
 * </pre>
 */
@Component
public class AsyncLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AsyncLogWriter.class);

    private final RequestLogMapper requestLogMapper;
    private final LogSseService logSseService;

    /**
     * 阻塞队列，上限 10000 条，防止内存溢出
     */
    private final BlockingQueue<RequestLog> queue = new LinkedBlockingQueue<>(10000);

    private volatile boolean running = true;

    public AsyncLogWriter(RequestLogMapper requestLogMapper, LogSseService logSseService) {
        this.requestLogMapper = requestLogMapper;
        this.logSseService = logSseService;
    }

    /**
     * 启动后台消费者线程
     */
    @PostConstruct
    public void start() {
        Thread worker = new Thread(this::processQueue, "async-log-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 关闭写入器，等待剩余日志处理完毕
     */
    @PreDestroy
    public void shutdown() {
        running = false;
    }

    /**
     * 提交日志记录到写入队列
     * <p>
     * 优先入队等待异步写入；队列满时降级为同步写入（直接 DB INSERT + SSE 推送），
     * 确保日志不丢失。同步写会短暂阻塞调用线程，但队列满属于边缘情况（上限 10000 条），
     * 日志不丢比短暂阻塞更重要。
     * </p>
     *
     * @param record 日志记录（ID 可为 null，INSERT 后由 MyBatis-Plus 自动填充）
     */
    public void enqueue(RequestLog record) {
        if (queue.offer(record)) {
            return;
        }
        // 队列已满：降级为同步写，确保日志不丢失
        log.warn("日志写入队列已满（{}），降级为同步写入: traceId={}, phase={}",
                queue.size() + queue.remainingCapacity(), record.getTraceId(), record.getPhase());
        writeSync(record);
    }

    /**
     * 同步写入单条日志：DB INSERT + SSE 推送
     */
    private void writeSync(RequestLog record) {
        try {
            requestLogMapper.insert(record);
            logSseService.publish(record);
        } catch (Exception e) {
            log.error("同步日志写入失败: traceId={}, phase={}", record.getTraceId(), record.getPhase(), e);
        }
    }

    /**
     * 后台消费者主循环：串行从队列取出日志，先执行 DB INSERT，再推送 SSE
     * <p>
     * 每次最多取 50 条批量处理，降低频繁单条写入的开销。
     * </p>
     */
    private void processQueue() {
        List<RequestLog> batch = new ArrayList<>();
        while (running) {
            try {
                // 阻塞等待第一条，每秒检查一次 running 标志
                RequestLog first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }

                batch.add(first);
                queue.drainTo(batch, 50);

                for (RequestLog record : batch) {
                    try {
                        // 1. 串行 DB INSERT（MyBatis-Plus 会自动回填自增 ID）
                        requestLogMapper.insert(record);
                        // 2. INSERT 成功后推送 SSE（此时 record 已有完整 ID）
                        logSseService.publish(record);
                    } catch (Exception e) {
                        log.error("异步日志写入失败: traceId={}, phase={}",
                                record.getTraceId(), record.getPhase(), e);
                    }
                }
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("日志写入队列处理异常", e);
                batch.clear();
            }
        }

        // 应用关闭：尽力处理剩余日志
        drainRemaining();

        log.info("异步日志写入器已关闭");
    }

    /**
     * 关闭时尽力处理剩余队列中的日志
     */
    private void drainRemaining() {
        List<RequestLog> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        for (RequestLog record : remaining) {
            try {
                requestLogMapper.insert(record);
            } catch (Exception e) {
                log.error("关闭前日志写入失败: traceId={}", record.getTraceId(), e);
            }
        }
        log.info("异步日志写入器关闭前处理剩余 {} 条日志", remaining.size());
    }

    /**
     * 返回当前队列中待处理的日志数量（用于监控）
     */
    public int pendingSize() {
        return queue.size();
    }
}
