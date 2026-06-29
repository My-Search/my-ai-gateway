<template>
  <div class="json-tree">
    <JsonNode :data="parsedData" :depth="0" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JsonNode from './JsonNode.vue'

const props = defineProps<{
  json: string
}>()

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
