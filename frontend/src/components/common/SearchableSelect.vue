<template>
  <div class="searchable-select" ref="containerRef">
    <div class="searchable-select-input-wrapper">
      <input
        type="text"
        class="form-control"
        :placeholder="placeholder"
        :value="searchText"
        @input="onSearchInput"
        @focus="isOpen = true"
        @blur="onBlur"
        style="width: 300px;"
      />
      <span v-if="isOpen" class="dropdown-arrow">▼</span>
    </div>
    <ul v-if="isOpen && filteredOptions.length" class="searchable-select-dropdown">
      <li
        v-for="option in filteredOptions"
        :key="option.value"
        :class="{ active: option.value === modelValue }"
        @mousedown="selectOption(option)"
      >
        {{ option.label }}
      </li>
    </ul>
    <div v-if="isOpen && !filteredOptions.length" class="searchable-select-empty">
      无匹配结果
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'

interface SelectOption {
  value: number
  label: string
}

const props = defineProps<{
  modelValue: number
  options: SelectOption[]
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number]
}>()

const searchText = ref('')
const isOpen = ref(false)
const containerRef = ref<HTMLElement | null>(null)

const filteredOptions = computed(() => {
  if (!searchText.value) return props.options
  const keyword = searchText.value.toLowerCase()
  return props.options.filter(opt => opt.label.toLowerCase().includes(keyword))
})

// 同步外部 modelValue 变化到输入框显示（如父组件重置为 0 时清空）
watch(() => props.modelValue, (newVal) => {
  if (newVal === 0) {
    searchText.value = ''
  } else {
    const match = props.options.find(o => o.value === newVal)
    if (match) searchText.value = match.label
  }
})

function onSearchInput(e: Event) {
  const target = e.target as HTMLInputElement
  searchText.value = target.value
  isOpen.value = true
}

// 选中选项：更新父组件值，显示选中项标签
function selectOption(option: SelectOption) {
  emit('update:modelValue', option.value)
  searchText.value = option.label
  isOpen.value = false
}

function onBlur() {
  // Delay to allow click event to fire before closing
  setTimeout(() => {
    isOpen.value = false
  }, 150)
}

function handleClickOutside(e: MouseEvent) {
  if (containerRef.value && !containerRef.value.contains(e.target as Node)) {
    isOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
.searchable-select {
  position: relative;
  display: inline-block;
}

.searchable-select-input-wrapper {
  position: relative;
  display: inline-block;
}

.dropdown-arrow {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 12px;
  color: var(--text-muted, #888);
  pointer-events: none;
}

.searchable-select-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin: 4px 0 0 0;
  padding: 0;
  list-style: none;
  background: var(--bg-card, #1e1e2e);
  border: 1px solid var(--border-color, #333);
  border-radius: 6px;
  max-height: 300px;
  overflow-y: auto;
  z-index: 100;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.searchable-select-dropdown li {
  padding: 8px 12px;
  cursor: pointer;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.searchable-select-dropdown li:hover {
  background: var(--bg-hover, #2a2a3e);
}

.searchable-select-dropdown li.active {
  background: var(--primary-color, #4a9eff);
  color: #fff;
}

.searchable-select-empty {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin: 4px 0 0 0;
  padding: 12px;
  background: var(--bg-card, #1e1e2e);
  border: 1px solid var(--border-color, #333);
  border-radius: 6px;
  text-align: center;
  color: var(--text-muted, #888);
  font-size: 14px;
  z-index: 100;
}
</style>
