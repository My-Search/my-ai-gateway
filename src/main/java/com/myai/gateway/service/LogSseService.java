package com.myai.gateway.service;

import com.myai.gateway.entity.RequestLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 日志 SSE 广播服务
 * <p>
 * 采用 CPA 项目的高性能无锁分发模式：publisher 只写入中央队列（零阻塞），
 * 专用分发线程批量读取并分发给每个订阅者的独立队列，实现：
 * <ul>
 *   <li><b>生产端零阻塞</b>：{@code async-log-writer} 线程只做一次 {@code centralQueue.offer()}，
 *       不持有任何订阅者锁，慢订阅者不会拖慢生产者</li>
 *   <li><b>批量分发</b>：分发线程一次最多读取 500 条，分摊线程上下文切换开销</li>
 *   <li><b>队列满自动丢弃最旧</b>：生产者和慢消费者队列满时均丢弃最旧记录，
 *       确保最新日志总能到达前端，同时不影响 DB 持久化</li>
 * </ul>
 * </p>
 *
 * <pre>
 * 数据流：
 *   AsyncLogWriter (生产者)
 *        │ centralQueue.offer(record)    ← 一次 put，零等待
 *        ▼
 *   ┌──────────────────┐
 *   │  中央队列 (10000) │              ← BlockingQueue，满时自动丢弃最旧
 *   └──────┬───────────┘
 *          │ dispatchLoop() 批量读取 (最多 500 条/次)
 *          ▼
 *   ┌──────────────────┐
 *   │  分发线程 (专用)   │              ← 单线程，避免并发分发竞争
 *   └──────┬───────────┘
 *          │ sq.queue.offer(record)     ← 逐个订阅者写入其独立队列
 *          ▼
 *   ┌──────────────────┐
 *   │ SSE 订阅者队列    │  ← 每个前端连接一个独立队列（容量 512），满时丢弃最旧
 *   └──────┬───────────┘
 *          │ sq.poll()                  ← SSE 端点线程从自己的队列读取
 *          ▼
 *   ┌──────────────────┐
 *   │  SseEmitter      │  ← 推送到前端浏览器
 *   └──────────────────┘
 * </pre>
 */
@Service
public class LogSseService {

    private static final Logger log = LoggerFactory.getLogger(LogSseService.class);

    /** 中央队列容量 */
    private static final int CENTRAL_QUEUE_CAPACITY = 10000;

    /** 分发线程一次批量读取的最大条数 */
    private static final int DISPATCHER_BATCH_SIZE = 500;

    /** 每个 SSE 订阅者独立队列的容量 */
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 512;

    /**
     * 中央日志队列
     * <p>publisher 只向此队列写入，不感知订阅者。</p>
     */
    private final BlockingQueue<RequestLog> centralQueue = new LinkedBlockingQueue<>(CENTRAL_QUEUE_CAPACITY);

    /**
     * 订阅者队列列表
     * <p>CopyOnWriteArrayList 适合读多写少的场景（加入/移除订阅者少，分发循环高频迭代）。</p>
     */
    private final List<SubscriberQueue> subscribers = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;

    private Thread dispatcherThread;

    /**
     * 每个 SSE 订阅者对应一个独立队列实例
     */
    public static class SubscriberQueue {

        private final BlockingQueue<RequestLog> queue = new LinkedBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        volatile boolean active = true;

        /**
         * 从订阅者队列获取一条日志，等待指定时间
         *
         * @param timeout 等待超时
         * @param unit    时间单位
         * @return 日志记录，超时返回 null
         * @throws InterruptedException 线程中断
         */
        public RequestLog poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        /**
         * 关闭订阅者队列，标记为非活跃并清空缓存
         */
        void close() {
            active = false;
            queue.clear();
        }
    }

    @PostConstruct
    public void start() {
        dispatcherThread = new Thread(this::dispatchLoop, "log-sse-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        log.info("SSE 日志分发线程已启动");
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
        }
        log.info("SSE 日志分发线程已关闭");
    }

    /**
     * 发布一条新日志到中央队列
     * <p>
     * 队列满时使用 {@link BlockingQueue#put(Object)} 阻塞写入，不丢弃任何记录。
     * 生产线程在队列腾出空间前短暂等待，确保日志不丢失。
     * </p>
     *
     * @param record 日志记录
     */
    public void publish(RequestLog record) {
        try {
            centralQueue.put(record);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("写入中央队列被中断，丢弃日志记录 traceId={}", record.getTraceId());
        }
    }

    /**
     * 创建并注册一个新的订阅者队列
     * <p>每个 SSE 连接调用一次，返回的 {@link SubscriberQueue} 用于获取日志。</p>
     *
     * @return 订阅者队列实例
     */
    public SubscriberQueue subscribe() {
        SubscriberQueue sq = new SubscriberQueue();
        subscribers.add(sq);
        return sq;
    }

    /**
     * 取消订阅，移除并关闭订阅者队列
     *
     * @param sq 订阅者队列
     */
    public void unsubscribe(SubscriberQueue sq) {
        sq.close();
        subscribers.remove(sq);
    }

    /**
     * 分发线程主循环
     * <p>
     * 1. 阻塞等待中央队列有数据（每秒检查一次 running 标志）<br>
     * 2. 批量取出（最多 {@link #DISPATCHER_BATCH_SIZE} 条）<br>
     * 3. 遍历所有活跃订阅者，逐条写入其独立队列<br>
     * 4. 订阅者队列满时丢弃最旧记录，确保慢消费者不阻塞分发<br>
     * </p>
     */
    private void dispatchLoop() {
        List<RequestLog> batch = new ArrayList<>();
        while (running) {
            try {
                // 阻塞等待第一条日志（每秒超时一次，以便检查 running 标志）
                RequestLog first = centralQueue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }

                batch.add(first);
                centralQueue.drainTo(batch, DISPATCHER_BATCH_SIZE);

                // 分发到所有活跃订阅者
                for (SubscriberQueue sq : subscribers) {
                    if (!sq.active) {
                        continue;
                    }
                    for (RequestLog record : batch) {
                        if (!sq.queue.offer(record)) {
                            // 订阅者队列满 → 丢弃最旧一条再入队
                            sq.queue.poll();
                            sq.queue.offer(record);
                        }
                    }
                }

                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("SSE 日志分发线程异常", e);
                batch.clear();
            }
        }
    }
}
