<template>
  <div class="toast-container" aria-live="polite">
    <TransitionGroup name="toast" tag="div">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="toast-item"
        :data-type="toast.type"
        role="status"
      >
        <div class="toast-content">
          <span class="toast-icon" aria-hidden="true">
            <SvgIcon :name="ICONS[toast.type]" :size="16" />
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
import { toasts, closeToast, type ToastType } from '@/composables/useToast'
import SvgIcon from './SvgIcon.vue'

/** 各语义类型对应的图标 */
const ICONS: Record<ToastType, string> = {
  success: 'check',
  warning: 'alert',
  error: 'alert'
}
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

/* ── 语义色 token ──
 * 每种类型只声明 --toast-accent，正文/图标/边框/进度条统一引用它，
 * 避免为每种类型重复整块规则。注意不要用 --primary：
 * 它在浅色主题下接近纯黑、深色主题下接近纯白，属于中性色而非语义色。 */
.toast-item                        { --toast-accent: #10b981; }  /* success */
.toast-item[data-type="warning"]   { --toast-accent: #f59e0b; }
.toast-item[data-type="error"]     { --toast-accent: #ef4444; }

.toast-item {
  position: relative;
  overflow: hidden;
  color: var(--text-primary);
  background: color-mix(in srgb, var(--bg-secondary) 92%, var(--toast-accent) 8%);
  border: 1px solid color-mix(in srgb, var(--toast-accent) 42%, var(--border-color));
  border-radius: 12px;
  box-shadow: var(--shadow-lg), 0 0 0 1px color-mix(in srgb, var(--toast-accent) 12%, transparent);
  backdrop-filter: blur(12px);
  pointer-events: auto;
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
  color: var(--toast-accent);
  background: color-mix(in srgb, var(--toast-accent) 14%, transparent);
  border-radius: 50%;
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

/* 进度条使用语义色，浅色主题下呈现清晰的绿/黄/红 */
.toast-progress {
  height: 3px;
  background: var(--toast-accent);
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
