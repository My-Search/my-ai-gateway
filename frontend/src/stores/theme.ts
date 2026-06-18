import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

/**
 * 主题管理 store
 * 负责深色/浅色主题切换，并将选择持久化到 localStorage
 */
export const useThemeStore = defineStore('theme', () => {
  const STORAGE_KEY = 'theme-preference'

  /**
   * 当前主题：'dark' | 'light'
   * 默认从 localStorage 读取，若无则跟随系统偏好，兜底深色
   */
  const theme = ref<'dark' | 'light'>('dark')

  /**
   * 是否为深色主题（computed-like convenience）
   */
  const isDark = ref(true)

  /**
   * 初始化主题
   * 读取 localStorage → 默认深色
   */
  function init() {
    const stored = localStorage.getItem(STORAGE_KEY) as 'dark' | 'light' | null
    apply(stored === 'dark' || stored === 'light' ? stored : 'dark')
  }

  /**
   * 切换主题
   */
  function toggle() {
    apply(theme.value === 'dark' ? 'light' : 'dark')
  }

  /**
   * 应用主题到 document
   */
  function apply(t: 'dark' | 'light') {
    theme.value = t
    isDark.value = t === 'dark'
    document.documentElement.setAttribute('data-theme', t)
    localStorage.setItem(STORAGE_KEY, t)
  }

  return {
    theme,
    isDark,
    init,
    toggle,
    apply,
  }
})
