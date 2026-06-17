import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 全局加载状态 store
 * 用于管理页面切换时的加载模态显示
 */
export const useLoadingStore = defineStore('loading', () => {
  const isLoading = ref(false)
  const loadingText = ref('加载中...')

  /**
   * 显示加载模态
   * @param text 可选的加载提示文本
   */
  function show(text?: string) {
    loadingText.value = text || '加载中...'
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