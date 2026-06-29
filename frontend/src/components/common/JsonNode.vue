<template>
  <div class="json-node" :style="{ paddingLeft: depth > 0 ? '16px' : '0' }">
    <!-- 对象 -->
    <template v-if="isObject(data)">
      <div :class="['json-node-row', { 'json-root': depth === 0 }]" @click="toggle">
        <span class="json-toggle" :class="{ expanded: expanded }">▶</span>
        <span class="json-key">{{ label !== undefined ? label + ': ' : '' }}</span>
        <span class="json-preview">{{ expanded ? '' : '{ ... }' }}</span>
        <span v-if="!expanded" class="json-bracket-count">{{ Object.keys(data).length }} keys</span>
      </div>
      <div v-if="expanded" class="json-children">
        <JsonNode
          v-for="(val, key) in data"
          :key="key"
          :data="val"
          :label="key"
          :depth="depth + 1"
        />
        <div class="json-close-brace">{{ depth === 0 ? '}' : '}' }}</div>
      </div>
    </template>

    <!-- 数组 -->
    <template v-else-if="Array.isArray(data)">
      <div class="json-node-row" @click="toggle">
        <span class="json-toggle" :class="{ expanded: expanded }">▶</span>
        <span class="json-key">{{ label !== undefined ? label + ': ' : '' }}</span>
        <span class="json-preview">{{ expanded ? '' : `[ ${arrayPreview(data)} ]` }}</span>
        <span v-if="!expanded" class="json-bracket-count">{{ data.length }} items</span>
      </div>
      <div v-if="expanded" class="json-children">
        <JsonNode
          v-for="(item, idx) in data"
          :key="idx"
          :data="item"
          :label="String(idx)"
          :depth="depth + 1"
        />
        <div class="json-close-brace">]</div>
      </div>
    </template>

    <!-- 原始值 -->
    <div v-else class="json-node-row json-leaf">
      <span v-if="label !== undefined" class="json-key">{{ label }}: </span>
      <span class="json-value" :class="valueClass">{{ formatPrimitive(data) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

const props = withDefaults(defineProps<{
  data: any
  label?: string
  depth?: number
}>(), {
  depth: 0,
})

/** 仅根节点默认展开，子节点默认折叠；用户可点击 ▶ 展开/折叠 */
const expanded = ref(props.depth === 0)

function toggle() {
  if (isObject(props.data) || Array.isArray(props.data)) {
    expanded.value = !expanded.value
  }
}

function isObject(val: any): val is Record<string, any> {
  return val !== null && typeof val === 'object' && !Array.isArray(val)
}

function objectPreview(obj: Record<string, any>): string {
  const keys = Object.keys(obj)
  if (keys.length === 0) return ''
  const preview = keys.slice(0, 3).map(k => {
    const v = obj[k]
    if (isObject(v)) return `${k}: {…}`
    if (Array.isArray(v)) return `${k}: […]`
    return `${k}: ${formatPrimitiveShort(v)}`
  }).join(', ')
  return keys.length > 3 ? preview + ', …' : preview
}

function arrayPreview(arr: any[]): string {
  if (arr.length === 0) return ''
  const preview = arr.slice(0, 3).map(item => {
    if (isObject(item)) return '{…}'
    if (Array.isArray(item)) return '[…]'
    return formatPrimitiveShort(item)
  }).join(', ')
  return arr.length > 3 ? preview + ', …' : preview
}

function formatPrimitive(val: any): string {
  if (val === null) return 'null'
  if (val === undefined) return 'undefined'
  if (typeof val === 'string') return `"${val}"`
  return String(val)
}

function formatPrimitiveShort(val: any): string {
  if (val === null) return 'null'
  if (typeof val === 'string') {
    return val.length > 30 ? `"${val.substring(0, 30)}…"` : `"${val}"`
  }
  return String(val)
}

const valueClass = computed(() => {
  const val = props.data
  if (val === null) return 'json-null'
  if (typeof val === 'string') return 'json-string'
  if (typeof val === 'number') return 'json-number'
  if (typeof val === 'boolean') return 'json-boolean'
  return ''
})
</script>

<style scoped>
.json-node {
  position: relative;
}

.json-node-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  cursor: default;
  padding: 1px 0;
  border-radius: 2px;
  transition: background 0.1s;
}
.json-node-row:hover {
  background: var(--bg-hover);
}

.json-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 16px;
  flex-shrink: 0;
  font-size: 8px;
  color: var(--text-muted);
  cursor: pointer;
  transition: transform 0.15s;
  user-select: none;
}
.json-toggle.expanded {
  transform: rotate(90deg);
}

.json-key {
  color: var(--accent-blue, #5b9bd5);
  flex-shrink: 0;
  white-space: nowrap;
}

.json-preview {
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.json-bracket-count {
  font-size: 10px;
  color: var(--text-muted);
  opacity: 0.6;
  margin-left: 6px;
  white-space: nowrap;
}

.json-children {
  border-left: 1px solid var(--border-color);
  margin-left: 6px;
}

.json-close-brace {
  color: var(--text-muted);
  padding-left: 16px;
  user-select: none;
}

.json-leaf {
  padding-left: 14px; /* 对齐展开按钮留白 */
}

.json-value {
  word-break: break-all;
  white-space: pre-wrap;
}

.json-string { color: var(--accent-green, #7ab55c); }
.json-number { color: var(--accent-blue, #5b9bd5); }
.json-boolean { color: var(--accent-purple, #9b86cc); }
.json-null { color: var(--text-muted); font-style: italic; }
</style>
