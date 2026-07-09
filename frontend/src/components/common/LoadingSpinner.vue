<template>
  <span class="loading-spinner" :style="computedStyle">
    <SvgIcon v-if="icon" :name="icon" :size="size" />
    <span v-else class="spinner-ring" :style="{ width: size + 'px', height: size + 'px' }"></span>
    <slot>
      <span v-if="text" class="loading-text">{{ text }}</span>
    </slot>
  </span>
</template>

<script setup lang="ts">
/**
 * 轻量内联加载指示器
 *
 * 用于列表、卡片、按钮等场景的内联加载态，代替各页面重复书写的
 * `<span class="loading-spinner"></span>` + `@keyframes spin`。
 *
 * 使用方式：
 * ```vue
 * <LoadingSpinner />                          <!-- 默认 14px 圆环 + (slot) -->
 * <LoadingSpinner text="加载中..." />          <!-- 带文字 -->
 * <LoadingSpinner :size="18" text="提交中" />  <!-- 自定义尺寸 -->
 * <LoadingSpinner icon="loading" :size="24" /> <!-- 使用 SvgIcon 加载图标 -->
 * ```
 */
import { computed } from 'vue'
import SvgIcon from './SvgIcon.vue'

interface Props {
  /** 加载文字（可选） */
  text?: string
  /** 图标尺寸（px），默认 14 */
  size?: number
  /** 使用 SvgIcon 名称替代默认环形旋转动画 */
  icon?: string
}

const props = withDefaults(defineProps<Props>(), {
  size: 14,
})

const computedStyle = computed(() => ({
  fontSize: `${props.size}px`,
}))
</script>

<style scoped>
.loading-spinner {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  vertical-align: middle;
  color: var(--text-muted);
}

.spinner-ring {
  display: inline-block;
  border: 2px solid var(--border-color);
  border-top-color: var(--accent-blue);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  vertical-align: middle;
  flex-shrink: 0;
}

.loading-text {
  font-size: 1em;
  color: inherit;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
