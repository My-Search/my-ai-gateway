<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="visible" class="dialog-overlay" @click.self="onCancel">
        <div class="dialog-box" role="dialog" aria-modal="true" :style="{ maxWidth: props.width }">
          <div class="dialog-header">
            <span class="dialog-title">{{ title }}</span>
            <button v-if="showClose" class="dialog-close" @click="onCancel" aria-label="关闭">
              <SvgIcon name="x" :size="18" />
            </button>
          </div>
          <div class="dialog-body">
            <slot>{{ message }}</slot>
          </div>
          <div class="dialog-footer">
            <button
              v-if="type === 'confirm'"
              class="btn btn-secondary"
              @click="onCancel"
            >
              <SvgIcon name="x" :size="14" /> {{ cancelText }}
            </button>
            <button
              class="btn"
              :class="confirmClass"
              @click="onConfirm"
            >
              <SvgIcon name="check" :size="14" /> {{ confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 通用弹框组件
 *
 * 使用方式（模板方式）：
 *   <Dialog v-model="showDialog" title="提示" type="confirm" @confirm="handleConfirm">
 *     确定要删除吗？
 *   </Dialog>
 *
 * 使用方式（函数方式，推荐简单场景）：
 *   const { open, close } = useDialog({ title: '提示', type: 'confirm' })
 *   open().then(confirmed => { ... })
 */
import { computed } from 'vue'

interface Props {
  modelValue: boolean
  title?: string
  message?: string
  type?: 'alert' | 'confirm'
  confirmText?: string
  cancelText?: string
  confirmClass?: string
  showClose?: boolean
  width?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '提示',
  message: '',
  type: 'alert',
  confirmText: '确定',
  cancelText: '取消',
  confirmClass: 'btn-primary',
  showClose: true,
  width: '420px'
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

function onConfirm() {
  visible.value = false
  emit('confirm')
}

function onCancel() {
  visible.value = false
  emit('cancel')
}
</script>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  padding: 16px;
}

.dialog-box {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  width: 100%;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  overflow: hidden;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
}

.dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.dialog-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s;
}

.dialog-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.dialog-body {
  padding: 20px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px 16px;
}

/* Transition */
.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.2s ease;
}

.dialog-enter-active .dialog-box,
.dialog-leave-active .dialog-box {
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.dialog-enter-from .dialog-box,
.dialog-leave-to .dialog-box {
  opacity: 0;
  transform: scale(0.96) translateY(-8px);
}
</style>
