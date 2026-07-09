<template>
  <button
    class="toggle-btn"
    :class="[modelValue ? 'active' : 'inactive', size === 'sm' ? 'toggle-btn-sm' : '']"
    @click="$emit('update:modelValue', !modelValue)"
    :disabled="disabled"
    :title="titleProp || undefined"
  >
    <span class="toggle-track">
      <span class="toggle-thumb"></span>
    </span>
    <span v-if="showLabel" class="toggle-label">
      <slot name="label">
        {{ modelValue ? activeLabel : inactiveLabel }}
      </slot>
    </span>
  </button>
</template>

<script setup lang="ts">
/**
 * 通用开关按钮
 *
 * 统一 model/List 和 channel/List 中重复的 toggle-btn 实现。
 *
 * 使用方式：
 * ```vue
 * <!-- 基础用法 -->
 * <ToggleSwitch v-model="isEnabled" />
 *
 * <!-- 带标签，自定义文字 -->
 * <ToggleSwitch v-model="isEnabled" active-label="启用" inactive-label="禁用" />
 *
 * <!-- 小尺寸 -->
 * <ToggleSwitch v-model="isEnabled" size="sm" />
 *
 * <!-- 无标签（仅图标） -->
 * <ToggleSwitch v-model="isEnabled" :show-label="false" />
 * ```
 */
interface Props {
  modelValue: boolean
  disabled?: boolean
  activeLabel?: string
  inactiveLabel?: string
  showLabel?: boolean
  size?: 'default' | 'sm'
  title?: string
}

const props = withDefaults(defineProps<Props>(), {
  showLabel: true,
  size: 'default',
  activeLabel: '',
  inactiveLabel: '',
})

const titleProp = props.title

defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<style scoped>
.toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 3px 6px;
  border-radius: 16px;
  transition: all 0.2s;
  flex-shrink: 0;
  font-family: inherit;
}

.toggle-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.toggle-btn:hover:not(:disabled) {
  background: var(--bg-hover);
}

.toggle-track {
  width: 32px;
  height: 18px;
  border-radius: 9px;
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}

.toggle-btn.active .toggle-track {
  background: var(--accent-green);
}

.toggle-btn.inactive .toggle-track {
  background: var(--text-muted);
}

.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: white;
  transition: transform 0.2s;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}

.toggle-btn.active .toggle-thumb {
  transform: translateX(14px);
}

.toggle-label {
  font-size: 12px;
  font-weight: 500;
  min-width: 40px;
  white-space: nowrap;
}

.toggle-btn.active .toggle-label {
  color: var(--accent-green);
}

.toggle-btn.inactive .toggle-label {
  color: var(--text-muted);
}

/* ── Small variant ── */
.toggle-btn-sm .toggle-track {
  width: 28px;
  height: 16px;
}

.toggle-btn-sm .toggle-thumb {
  width: 12px;
  height: 12px;
}

.toggle-btn-sm.active .toggle-thumb {
  transform: translateX(12px);
}

.toggle-btn-sm .toggle-label {
  font-size: 11px;
  min-width: 32px;
}
</style>
