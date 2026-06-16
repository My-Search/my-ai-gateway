package com.myai.gateway.relay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StreamContentManager 单元测试
 * 验证流式内容的累积、获取和清理
 */
class StreamContentManagerTest {

    private final StreamContentManager manager = new StreamContentManager();

    @Test
    void appendAndGetContent_returnsAccumulatedContent() {
        manager.appendContent("trace-1", "Hello");
        manager.appendContent("trace-1", " world");
        manager.appendContent("trace-1", "!");

        String content = manager.getContent("trace-1");
        assertThat(content).isEqualTo("Hello world!");
    }

    @Test
    void getAndClearContent_returnsContentAndRemovesEntry() {
        manager.appendContent("trace-1", "Hello");

        String content = manager.getAndClearContent("trace-1");
        assertThat(content).isEqualTo("Hello");

        // 再次获取应为 null（已清除）
        String afterClear = manager.getAndClearContent("trace-1");
        assertThat(afterClear).isNull();
    }

    @Test
    void getAndClearContent_forNonExistentTrace_returnsNull() {
        String content = manager.getAndClearContent("non-existent");
        assertThat(content).isNull();
    }

    @Test
    void getContent_forNonExistentTrace_returnsNull() {
        String content = manager.getContent("non-existent");
        assertThat(content).isNull();
    }

    @Test
    void hasContent_returnsTrueWhenContentExists() {
        manager.appendContent("trace-1", "Hello");
        assertThat(manager.hasContent("trace-1")).isTrue();
    }

    @Test
    void hasContent_returnsFalseWhenNoContent() {
        assertThat(manager.hasContent("trace-1")).isFalse();
    }

    @Test
    void hasContent_returnsFalseAfterClearContent() {
        manager.appendContent("trace-1", "Hello");
        manager.clearContent("trace-1");
        assertThat(manager.hasContent("trace-1")).isFalse();
    }

    @Test
    void clearContent_doesNotAffectOtherTraces() {
        manager.appendContent("trace-1", "Hello");
        manager.appendContent("trace-2", "World");

        manager.clearContent("trace-1");

        assertThat(manager.hasContent("trace-1")).isFalse();
        assertThat(manager.getContent("trace-2")).isEqualTo("World");
    }

    @Test
    void getAndClearContent_onlyClearsSpecificTrace() {
        manager.appendContent("trace-1", "Hello");
        manager.appendContent("trace-2", "World");

        String content1 = manager.getAndClearContent("trace-1");

        assertThat(content1).isEqualTo("Hello");
        assertThat(manager.getContent("trace-2")).isEqualTo("World");
    }

    @Test
    void multipleTraces_isolatedFromEachOther() {
        manager.appendContent("trace-A", "Content A");
        manager.appendContent("trace-B", "Content B");

        assertThat(manager.getContent("trace-A")).isEqualTo("Content A");
        assertThat(manager.getContent("trace-B")).isEqualTo("Content B");
    }

    @Test
    void appendContent_withEmptyContent_doesNothing() {
        manager.appendContent("trace-1", "");

        assertThat(manager.hasContent("trace-1")).isFalse();
        assertThat(manager.getContent("trace-1")).isNull();
    }
}
