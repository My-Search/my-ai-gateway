<template>
  <Teleport to="body">
    <Transition name="loading">
      <div v-if="visible" class="loading-modal-overlay">
        <div class="loading-modal-content">
          <div class="loading-spinner">
            <SvgIcon name="loading" :size="36" />
          </div>
          <p class="loading-text">{{ text }}</p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 全局加载模态组件
 * 用于页面切换时显示加载状态
 * 
 * 使用方式：
 * 1. 通过 v-show 或 v-if 控制显示（配合 useLoadingStore）
 * 2. 组件会自动全屏显示，带半透明遮罩
 */
import { computed } from 'vue'
import { useLoadingStore } from '@/stores/loading'
import { useI18n } from '@/composables/useI18n'
import SvgIcon from './SvgIcon.vue'

const { t } = useI18n()
const loadingStore = useLoadingStore()

const visible = computed(() => loadingStore.isLoading)
const text = computed(() => loadingStore.loadingText || t('common.loading'))
</script>

<style scoped>
.loading-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(2px);
}

.loading-modal-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 32px 48px;
  background: var(--bg-secondary);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
}

.loading-spinner {
  color: var(--primary-color);
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.loading-text {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
  white-space: nowrap;
}

/* Transition */
.loading-enter-active,
.loading-leave-active {
  transition: opacity 0.2s ease;
}

.loading-enter-from,
.loading-leave-to {
  opacity: 0;
}
</style>
