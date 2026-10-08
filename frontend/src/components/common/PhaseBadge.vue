<template>
  <span class="phase" :class="`phase-${phase}`">
    <slot>{{ label }}</slot>
    <span v-if="count && count > 1" class="retry-count">(x{{ count }})</span>
  </span>
</template>

<script setup lang="ts">
/**
 * 日志阶段标签
 *
 * 统一展示 Dashboard 和 log/List 中重复的 `.phase` / `.phase-start` / `.phase-*` 样式。
 *
 * 使用方式：
 * ```vue
 * <PhaseBadge phase="start" />
 * <PhaseBadge phase="fail" :count="3" />
 * ```
 */
import { computed } from 'vue'
import { useI18n } from '@/composables/useI18n'

type Phase = 'start' | 'retry' | 'reroute' | 'success' | 'fail' | 'skip' | 'interrupted'

interface Props {
  phase: Phase
  count?: number
  /** 覆盖默认 label（默认自动根据 phase 匹配） */
  label?: string
}

const props = defineProps<Props>()
const { t } = useI18n()

const label = computed(() => props.label || t(`log.phase.${props.phase}`) || props.phase)
</script>

<style scoped>
.phase {
  display: inline-block;
  padding: 1px 6px;
  border-radius: var(--radius-sm, 3px);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  line-height: 1.5;
}

.phase-start {
  background: color-mix(in srgb, var(--accent-blue) 20%, transparent);
  color: var(--accent-blue);
}

.phase-retry {
  background: color-mix(in srgb, var(--accent-yellow) 20%, transparent);
  color: var(--accent-yellow);
}

.phase-reroute {
  background: color-mix(in srgb, var(--accent-purple) 20%, transparent);
  color: var(--accent-purple);
}

.phase-success {
  background: color-mix(in srgb, var(--accent-green) 20%, transparent);
  color: var(--accent-green);
}

.phase-fail {
  background: color-mix(in srgb, var(--accent-red) 20%, transparent);
  color: var(--accent-red);
}

.phase-skip {
  background: color-mix(in srgb, var(--text-muted) 20%, transparent);
  color: var(--text-muted);
}

/* 中断：与失败同为「未成功终结」，统一用红色提示 */
.phase-interrupted {
  background: color-mix(in srgb, var(--accent-red) 20%, transparent);
  color: var(--accent-red);
}

.retry-count {
  font-weight: 400;
  opacity: 0.85;
  margin-left: 2px;
}
</style>
