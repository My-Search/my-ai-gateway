package com.myai.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalCacheService 单元测试
 * 验证基础缓存语义：命中缓存不重复加载、失效后重新加载、不同命名空间隔离。
 */
class LocalCacheServiceTest {

    private LocalCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new LocalCacheService();
    }

    @Test
    void get_secondCallHitsCache_loaderInvokedOnlyOnce() {
        AtomicInteger loads = new AtomicInteger();
        String ns = "ns";
        String key = "k1";

        String first = cacheService.get(ns, key, () -> "v" + loads.incrementAndGet());
        String second = cacheService.get(ns, key, () -> "v" + loads.incrementAndGet());

        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void get_differentKeys_loaderInvokedPerKey() {
        AtomicInteger loads = new AtomicInteger();

        cacheService.get("ns", "a", () -> "va" + loads.incrementAndGet());
        cacheService.get("ns", "b", () -> "vb" + loads.incrementAndGet());

        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void get_differentNamespaces_isolated() {
        AtomicInteger loads = new AtomicInteger();

        String v1 = cacheService.get("ns1", "k", () -> "1-" + loads.incrementAndGet());
        String v2 = cacheService.get("ns2", "k", () -> "2-" + loads.incrementAndGet());

        assertThat(v1).isEqualTo("1-1");
        assertThat(v2).isEqualTo("2-2");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void invalidate_singleKey_reloadsValue() {
        AtomicInteger loads = new AtomicInteger();

        cacheService.get("ns", "k", () -> "v" + loads.incrementAndGet());
        cacheService.invalidate("ns", "k");
        String after = cacheService.get("ns", "k", () -> "v" + loads.incrementAndGet());

        assertThat(after).isEqualTo("v2");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void invalidateAll_namespaceCleared() {
        AtomicInteger loads = new AtomicInteger();

        cacheService.get("ns", "a", () -> "v" + loads.incrementAndGet());
        cacheService.get("ns", "b", () -> "v" + loads.incrementAndGet());
        cacheService.invalidateAll("ns");

        cacheService.get("ns", "a", () -> "v" + loads.incrementAndGet());
        cacheService.get("ns", "b", () -> "v" + loads.incrementAndGet());

        assertThat(loads.get()).isEqualTo(4);
    }
}
