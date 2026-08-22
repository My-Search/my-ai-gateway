<template>
  <div class="toast-container" aria-live="polite">
    <TransitionGroup name="toast" tag="div">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="toast-item"
        :class="{ 'toast-error': toast.isError }"
        role="status"
      >
        <div class="toast-content">
          <span class="toast-icon" aria-hidden="true">
            <SvgIcon :name="toast.isError ? 'alert' : 'check'" :size="16" />
          </span>
          <span class="toast-message">{{ toast.message }}</span>
          <button class="toast-close" type="button" aria-label="关闭" @click="closeToast(toast.id)">×</button>
        </div>
        <div class="toast-progress" :style="{ animationDuration: `${toast.duration}ms` }" />
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { toasts, closeToast } from '@/composables/useToast'
import SvgIcon from './SvgIcon.vue'
</script>

<style scoped>
.toast-container {
  position: fixed;
  top: 24px;
  right: 24px;
  z-index: 11000;
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(360px, calc(100vw - 32px));
  pointer-events: none;
}
.toast-item {
  position: relative;
  overflow: hidden;
  color: var(--text-primary);
  background: color-mix(in srgb, var(--bg-secondary) 92%, var(--primary) 8%);
  border: 1px solid color-mix(in srgb, var(--primary) 42%, var(--border-color));
  border-radius: 12px;
  box-shadow: var(--shadow-lg), 0 0 0 1px color-mix(in srgb, var(--primary) 12%, transparent);
  backdrop-filter: blur(12px);
  pointer-events: auto;
}
.toast-error {
  background: color-mix(in srgb, var(--bg-secondary) 92%, #ef4444 8%);
  border-color: color-mix(in srgb, #ef4444 58%, var(--border-color));
  box-shadow: var(--shadow-lg), 0 0 0 1px color-mix(in srgb, #ef4444 16%, transparent);
}
.toast-content {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
  padding: 10px 12px 10px 14px;
}
.toast-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  color: #10b981;
  background: color-mix(in srgb, #10b981 14%, transparent);
  border-radius: 50%;
}
.toast-error .toast-icon {
  color: #ef4444;
  background: color-mix(in srgb, #ef4444 14%, transparent);
}
.toast-message { flex: 1; font-size: 14px; font-weight: 500; line-height: 1.5; }
.toast-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  color: var(--text-muted);
  font-size: 19px;
  line-height: 1;
  cursor: pointer;
  background: transparent;
  border: 0;
  opacity: 0.8;
}
.toast-close:hover { opacity: 1; }
.toast-progress {
  height: 3px;
  background: color-mix(in srgb, var(--primary) 78%, #10b981 22%);
  transform-origin: left;
  animation: toast-progress linear forwards;
}
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(20px); }
@keyframes toast-progress {
  from { transform: scaleX(1); }
  to { transform: scaleX(0); }
}
</style>
