import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 语言管理 store
 * 负责中/英文切换，并将选择持久化到 localStorage
 */
export const useLocaleStore = defineStore('locale', () => {
  const STORAGE_KEY = 'locale-preference'

  /** 当前语言：'zh-CN' | 'en-US' */
  const locale = ref<'zh-CN' | 'en-US'>('zh-CN')

  /**
   * 初始化语言
   * 读取 localStorage → 默认 zh-CN
   */
  function init() {
    const stored = localStorage.getItem(STORAGE_KEY) as 'zh-CN' | 'en-US' | null
    apply(stored === 'zh-CN' || stored === 'en-US' ? stored : 'zh-CN')
  }

  /**
   * 切换语言
   */
  function toggle() {
    apply(locale.value === 'zh-CN' ? 'en-US' : 'zh-CN')
  }

  /**
   * 应用语言到 document
   */
  function apply(l: 'zh-CN' | 'en-US') {
    locale.value = l
    document.documentElement.setAttribute('lang', l)
    localStorage.setItem(STORAGE_KEY, l)
  }

  return {
    locale,
    init,
    toggle,
    apply,
  }
})
