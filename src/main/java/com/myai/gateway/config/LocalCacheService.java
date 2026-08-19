package com.myai.gateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 本地缓存服务（Caffeine 封装）
 *
 * <p>用于热点配置的进程内缓存，降低对 SQLite 的重复穿透查询。
 * 设计约束（保证行为不变）：</p>
 * <ul>
 *   <li>缓存为<b>短 TTL</b>（默认 5 秒），即使未显式失效也会很快自动过期回到数据库最新值，</li>
 *   <li>写操作会调用 {@link #invalidate} 主动失效，保证后续读立即拿到最新数据，</li>
 *   <li>缓存是<b>可选能力</b>：未注入 {@link LocalCacheService} 时（如单元测试直接 new Service）
 *       走原始逻辑，行为与未加缓存前完全一致。</li>
 * </ul>
 */
@Component
public class LocalCacheService {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheService.class);

    /** 缓存命名空间 → Caffeine 实例 */
    private final java.util.concurrent.ConcurrentHashMap<String, Cache<String, Object>> caches =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 命名空间首次创建时使用的 TTL（用于检测同一命名空间传入不一致 TTL 的误用） */
    private final java.util.concurrent.ConcurrentHashMap<String, Duration> namespaceTtls =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 已对 TTL 不一致告警过的命名空间（仅首次告警，避免日志刷屏） */
    private final java.util.Set<String> ttlWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 默认 TTL：5 秒（热点配置读多写少，5s 足够消除重复查询，且开销可忽略） */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

    // ==================== 跨 Service 共享的缓存命名空间 ====================
    // 读方（如 ModelService）与写方（如 ChannelService）共用常量，避免字符串散落。
    public static final String NS_MODEL_BY_ID = "ModelService.modelById";
    public static final String NS_MODEL_BY_NAME = "ModelService.modelByName";
    public static final String NS_CHANNEL_MODEL_BY_ID = "ModelService.channelModelById";
    public static final String NS_CHANNEL_BY_ID = "ModelService.channelById";
    public static final String NS_API_KEY_BY_CHANNEL = "ChannelApiKeyService.byChannel";
    public static final String NS_CIRCUIT_STATE_BY_SCOPE = "CandidateRouter.circuitState";

    /**
     * 获取或计算缓存值。
     *
     * @param namespace 缓存命名空间（同 Service 内一类数据的键前缀）
     * @param key       缓存键（同一空间内唯一）
     * @param loader    缓存未命中时的加载函数
     * @return 缓存值或 loader 计算结果；null 结果不会被缓存。
     *         <b>返回对象为跨请求/跨线程共享引用，调用方不得对其做任何修改</b>
     *         （包括实体 setter、List 元素变更等），否则修改会泄漏给其他请求。
     */
    public <T> T get(String namespace, String key, Supplier<T> loader) {
        return get(namespace, key, DEFAULT_TTL, loader);
    }

    /**
     * 获取或计算缓存值（可指定 TTL）。
     * <p>注意：TTL 参数仅在命名空间<b>首次创建</b>缓存实例时生效；后续对同一命名空间传入不同 TTL
     * 会被忽略，并以 WARN 日志告警（每个命名空间仅首次告警）。</p>
     *
     * @param namespace 缓存命名空间
     * @param key       缓存键
     * @param ttl       缓存过期时长（每次创建缓存实例时生效）
     * @param loader    缓存未命中时的加载函数
     * @return 缓存值或 loader 计算结果；null 结果不会被缓存。
     *         <b>返回对象为跨请求/跨线程共享引用，调用方不得对其做任何修改。</b>
     */
    public <T> T get(String namespace, String key, Duration ttl, Supplier<T> loader) {
        Cache<String, Object> cache = cacheFor(namespace, ttl);
        try {
            Object cached = cache.get(key, k -> loader.get());
            @SuppressWarnings("unchecked")
            T result = (T) cached;
            return result;
        } catch (ClassCastException e) {
            // 防御：类型异常直接回退 loader，避免污染调用方
            log.warn("缓存类型异常，回退加载: namespace={}, key={}, err={}", namespace, key, e.getMessage());
            cache.invalidate(key);
            return loader.get();
        }
    }

    /**
     * 使某命名空间下的单个键失效（写操作后调用，保证读到最新数据）。
     */
    public void invalidate(String namespace, String key) {
        Cache<String, Object> cache = caches.get(namespace);
        if (cache != null) {
            cache.invalidate(key);
        }
    }

    /**
     * 使某命名空间全部缓存失效（批量写操作后调用）。
     */
    public void invalidateAll(String namespace) {
        Cache<String, Object> cache = caches.get(namespace);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * 获取所有缓存命名空间的统计信息（命中率、淘汰次数等），供监控/健康检查使用。
     */
    public Map<String, CacheStats> getAllCacheStats() {
        java.util.Map<String, CacheStats> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Cache<String, Object>> entry : caches.entrySet()) {
            result.put(entry.getKey(), entry.getValue().stats());
        }
        return result;
    }

    private Cache<String, Object> cacheFor(String namespace) {
        return cacheFor(namespace, DEFAULT_TTL);
    }

    private Cache<String, Object> cacheFor(String namespace, Duration ttl) {
        Cache<String, Object> existing = caches.get(namespace);
        if (existing != null) {
            // 命名空间已存在：TTL 以首次创建时为准，传入不一致 TTL 时告警（仅首次，避免日志刷屏）
            Duration firstTtl = namespaceTtls.get(namespace);
            if (firstTtl != null && !firstTtl.equals(ttl) && ttlWarned.add(namespace)) {
                log.warn("缓存命名空间 TTL 不一致: namespace={}, 首次创建 TTL={}s, 本次传入 TTL={}s（忽略本次值）",
                        namespace, firstTtl.toSeconds(), ttl.toSeconds());
            }
            return existing;
        }
        return caches.computeIfAbsent(namespace, ns -> {
            namespaceTtls.put(ns, ttl);
            Cache<String, Object> cache = Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(ttl)
                    .recordStats()
                    .build();
            log.debug("创建缓存命名空间: namespace={}, ttl={}s", ns, ttl.toSeconds());
            return cache;
        });
    }
}
