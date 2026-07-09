<template>
  <button
    class="copy-btn"
    @click="handleCopy"
    :title="title || t('common.copy')"
  >
    <SvgIcon name="copy" :size="14" />
  </button>
</template>

<script setup lang="ts">
/**
 * 通用复制按钮
 *
 * 点击后将指定文本复制到剪贴板，并自动弹出成功/失败 toast。
 * 统一替代各页面重复书写的 copy-btn 样式 + navigator.clipboard 逻辑。
 *
 * 使用方式：
 * ```vue
 * <CopyButton :text="apiKey" />
 * <CopyButton :text="apiKey" title="复制密钥" />
 * ```
 */
import { useI18n } from '@/composables/useI18n'
import SvgIcon from './SvgIcon.vue'

interface Props {
  /** 要复制的文本 */
  text: string
  /** 按钮 tooltip（可选，默认使用 common.copy） */
  title?: string
}

const props = defineProps<Props>()
const { t } = useI18n()

async function handleCopy() {
  try {
    await navigator.clipboard.writeText(props.text)
    showToast(t('common.copySuccess'), false)
  } catch {
    showToast(t('common.copyFailed'), true)
  }
}

function showToast(msg: string, isError: boolean) {
  const toast = document.createElement('div')
  toast.textContent = msg
  Object.assign(toast.style, {
    position: 'fixed',
    bottom: '24px',
    left: '50%',
    transform: 'translateX(-50%)',
    background: isError ? '#f87171' : '#10b981',
    color: '#fff',
    padding: '8px 20px',
    borderRadius: '8px',
    fontSize: '14px',
    zIndex: '9999',
    transition: 'opacity .3s',
  })
  document.body.appendChild(toast)
  setTimeout(() => {
    toast.style.opacity = '0'
    setTimeout(() => toast.remove(), 300)
  }, 1500)
}
</script>

<style scoped>
.copy-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  cursor: pointer;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  padding: 0;
  vertical-align: middle;
}

.copy-btn:hover {
  color: var(--accent-blue);
  border-color: var(--accent-blue);
}
</style>
