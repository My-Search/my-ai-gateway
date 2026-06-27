import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 全局加载状态 store
 * 用于管理页面切换时的加载模态显示
 */
export const useLoadingStore = defineStore('loading', () => {
  const isLoading = ref(false)
  const loadingText = ref('')

  /**
   * 显示加载模态
   * @param text 可选的加载提示文本（不传或传空时由 LoadingModal 按 i18n 显示）
   */
  function show(text?: string) {
    loadingText.value = text ?? ''
    isLoading.value = true
  }

  /**
   * 隐藏加载模态
   */
  function hide() {
    isLoading.value = false
  }

  return {
    isLoading,
    loadingText,
    show,
    hide
  }
})