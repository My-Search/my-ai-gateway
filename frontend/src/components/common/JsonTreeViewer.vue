<template>
  <div class="json-tree">
    <JsonNode :data="parsedData" :depth="0" :max-depth="maxDepth" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JsonNode from './JsonNode.vue'

const props = withDefaults(defineProps<{
  json: string
  /** 默认展开的最大深度（0=只展开根，1=展开第一层子节点，以此类推） */
  maxDepth?: number
}>(), {
  maxDepth: 0,
})

const parsedData = computed(() => {
  if (!props.json) return null
  try {
    return JSON.parse(props.json)
  } catch {
    return props.json
  }
})
</script>

<style scoped>
.json-tree {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
}
</style>
