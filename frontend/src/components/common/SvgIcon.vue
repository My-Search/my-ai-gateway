<template>
  <svg :class="['svg-icon', className]" :style="svgStyle" aria-hidden="true">
    <use :xlink:href="iconName" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  name: string
  className?: string
  size?: number | string
  color?: string
}>(), {
  size: 16
})

const iconName = computed(() => `#icon-${props.name}`)

const svgStyle = computed(() => {
  const style: Record<string, string> = {}
  if (props.size) {
    const sizeVal = typeof props.size === 'number' ? `${props.size}px` : props.size
    style.width = sizeVal
    style.height = sizeVal
  }
  if (props.color) {
    style.color = props.color
  }
  return style
})
</script>

<style scoped>
.svg-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  fill: currentColor;
  vertical-align: middle;
}
</style>
