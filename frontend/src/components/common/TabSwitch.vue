<template>
  <div class="tab-switch" :class="[variant ? `tab-switch--${variant}` : '']">
    <button
      v-for="tab in tabs"
      :key="tab.value"
      :class="['tab-btn', { active: modelValue === tab.value }]"
      @click="$emit('update:modelValue', tab.value)"
      :disabled="tab.disabled"
    >
      <SvgIcon v-if="tab.icon" :name="tab.icon" :size="14" />
      {{ tab.label }}
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 通用 Tab 切换条
 *
 * 代替各页面重复书写的 `.tab-switch` / `.tab-btn` / `.tab-btn.active` 样式。
 *
 * 使用方式：
 * ```vue
 * <TabSwitch v-model="tab" :tabs="[
 *   { value: 'entry', label: '入口模型' },
 *   { value: 'channel', label: '渠道模型' },
 * ]" />
 *
 * <!-- 变体：period-btn 风格（用于时间周期切换） -->
 * <TabSwitch v-model="period" variant="period" :tabs="[
 *   { value: 'today', label: '今日' },
 *   { value: 'week', label: '本周' },
 * ]" />
 * ```
 */
import SvgIcon from './SvgIcon.vue'

export interface TabItem {
  value: string | number
  label: string
  icon?: string
  disabled?: boolean
}

interface Props {
  modelValue: string | number
  tabs: TabItem[]
  /** 变体：默认常规，'period' 为紧凑型时间周期切换 */
  variant?: 'period'
}

defineProps<Props>()

defineEmits<{
  'update:modelValue': [value: string | number]
}>()
</script>

<style scoped>
.tab-switch {
  display: flex;
  gap: 4px;
  background: var(--bg-primary);
  border-radius: var(--radius, 6px);
  padding: 2px;
}

.tab-btn {
  padding: 4px 10px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
  border-radius: var(--radius-sm, 4px);
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: inherit;
  line-height: 1.5;
}

.tab-btn:hover:not(:disabled) {
  color: var(--text-primary);
}

.tab-btn.active {
  background: var(--bg-secondary);
  color: var(--text-primary);
  box-shadow: 0 1px 2px rgba(0,0,0,0.1);
}

.tab-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* ── Period variant（紧凑型，用于时间周期切换） ── */
.tab-switch--period .tab-btn {
  padding: 3px 8px;
  font-size: 11px;
}

.tab-switch--period .tab-btn.active {
  color: var(--accent-blue);
}
</style>
