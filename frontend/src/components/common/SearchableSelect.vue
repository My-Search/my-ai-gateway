<template>
  <div class="searchable-select" ref="containerRef">
    <div class="searchable-select-input-wrapper">
      <!-- 多选模式：有选中项时显示勾选数量 -->
      <input
        v-if="multiple && selectedCount > 0 && !isOpen"
        type="text"
        class="form-control"
        :value="selectedCountText"
        readonly
        @focus="onFocusMulti"
        style="cursor: pointer;"
      />
      <!-- 单选模式 / 多选无选中 / 多选展开搜索时 -->
      <input
        v-else
        type="text"
        class="form-control"
        :placeholder="multiple && selectedCount > 0 ? t('common.searchMore') : placeholder"
        :value="searchText"
        @input="onSearchInput"
        @focus="isOpen = true"
        @blur="onBlur"
      />
      <span v-if="isOpen" class="dropdown-arrow">▼</span>
    </div>
    <ul v-if="isOpen && filteredOptions.length" class="searchable-select-dropdown">
      <li
        v-for="option in filteredOptions"
        :key="option.value"
        :class="{ active: isSelected(option.value), checked: isSelected(option.value) }"
        @mousedown.prevent="onOptionClick(option)"
      >
        <span v-if="multiple" class="checkbox-icon">{{ isSelected(option.value) ? '☑' : '☐' }}</span>
        {{ option.label }}
      </li>
    </ul>
    <div v-if="isOpen && !filteredOptions.length" class="searchable-select-empty">
      {{ t('common.noMatch') }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

interface SelectOption {
  value: number
  label: string
}

const props = withDefaults(defineProps<{
  modelValue: number | number[]
  options: SelectOption[]
  placeholder?: string
  multiple?: boolean
  width?: number
  dropdownWidth?: number
}>(), {
  width: 300,
  dropdownWidth: 400
})

const emit = defineEmits<{
  'update:modelValue': [value: number | number[]]
}>()

const searchText = ref('')
const isOpen = ref(false)
const containerRef = ref<HTMLElement | null>(null)

// 多选模式下已选数量
const selectedCount = computed(() => {
  if (!props.multiple) return 0
  return Array.isArray(props.modelValue) ? props.modelValue.length : 0
})

const selectedCountText = computed(() =>
  t('common.selectedCount').replace('{count}', String(selectedCount.value))
)

const filteredOptions = computed(() => {
  if (!searchText.value) return props.options
  const keyword = searchText.value.toLowerCase()
  return props.options.filter(opt => opt.label.toLowerCase().includes(keyword))
})

// 判断选项是否被选中
function isSelected(value: number): boolean {
  if (props.multiple) {
    return Array.isArray(props.modelValue) && props.modelValue.includes(value)
  }
  return props.modelValue === value
}

// 同步外部 modelValue 变化到输入框显示（如父组件重置为 0 时清空）
// immediate: true 确保组件通过 v-if 条件挂载时也能同步初始选中值的显示
watch(() => props.modelValue, (newVal) => {
  if (props.multiple) {
    // 多选模式：清空搜索框
    if (Array.isArray(newVal) && newVal.length === 0) {
      searchText.value = ''
    }
  } else {
    if (newVal === 0) {
      searchText.value = ''
    } else {
      const match = props.options.find(o => o.value === newVal)
      if (match) searchText.value = match.label
    }
  }
}, { immediate: true })

function onSearchInput(e: Event) {
  const target = e.target as HTMLInputElement
  searchText.value = target.value
  isOpen.value = true
}

// 多选模式下点击输入框展开
function onFocusMulti() {
  isOpen.value = true
}

// 选项点击处理
function onOptionClick(option: SelectOption) {
  if (props.multiple) {
    // 多选模式：切换选中状态
    const current = Array.isArray(props.modelValue) ? [...props.modelValue] : []
    const idx = current.indexOf(option.value)
    if (idx >= 0) {
      current.splice(idx, 1)
    } else {
      current.push(option.value)
    }
    emit('update:modelValue', current)
    // 保持下拉打开，不清空搜索
  } else {
    // 单选模式
    emit('update:modelValue', option.value)
    searchText.value = option.label
    isOpen.value = false
  }
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
  width: 100%;
  max-width: 300px;
}

.searchable-select-input-wrapper {
  position: relative;
  display: block;
  width: 100%;
}

.searchable-select-input-wrapper .form-control {
  width: 100%;
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
  margin: 4px 0 0 0;
  padding: 0;
  list-style: none;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  max-height: 300px;
  overflow-y: auto;
  z-index: 100;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  min-width: v-bind(dropdownWidth + 'px');
  max-width: calc(100vw - 32px);
}

.searchable-select-dropdown li {
  padding: 8px 12px;
  cursor: pointer;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
  gap: 8px;
}

.checkbox-icon {
  font-size: 16px;
  line-height: 1;
  flex-shrink: 0;
}

.searchable-select-dropdown li:hover {
  background: var(--bg-hover);
}

.searchable-select-dropdown li.active {
  background: var(--accent-blue);
  color: #fff;
}

.searchable-select-empty {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin: 4px 0 0 0;
  padding: 12px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  text-align: center;
  color: var(--text-muted, #888);
  font-size: 14px;
  z-index: 100;
}
</style>
