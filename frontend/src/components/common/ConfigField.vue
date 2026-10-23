<template>
  <div class="cfg-field" :class="{ 'cfg-field--stack': stack }">
    <div v-if="!hideLabel" class="cfg-field-label">
      <div class="cfg-field-title">
        <slot name="label">{{ label }}</slot>
        <span v-if="required" class="req" aria-hidden="true">*</span>
        <slot name="labelExtra" />
      </div>
      <p v-if="hint || $slots.hint" class="cfg-field-hint">
        <slot name="hint">{{ hint }}</slot>
      </p>
    </div>
    <div
      class="cfg-field-control"
      :class="{
        'cfg-field-control--wide': wide,
        'cfg-field-control--inline': inline,
        'cfg-field--narrow-label': hideLabel
      }"
    >
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 配置字段行：左侧标签 + 说明，右侧控件。
 * 窄屏下自动堆叠（label 在上，控件在下）。
 */
withDefaults(defineProps<{
  label?: string
  hint?: string
  required?: boolean
  wide?: boolean
  inline?: boolean
  stack?: boolean
  hideLabel?: boolean
}>(), {
  label: '',
  hint: '',
  required: false,
  wide: false,
  inline: false,
  stack: false,
  hideLabel: false
})
</script>

<style scoped>
/* .cfg-field* 的静态样式定义在 assets/styles/index.css */
.cfg-field--narrow-label {
  grid-column: 1 / -1;
}
</style>
