import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

type ThemeMode = 'dark' | 'light' | 'system'
type Theme = 'dark' | 'light'

const STORAGE_KEY_MODE = 'theme-mode'
const STORAGE_KEY_EFFECTIVE = 'theme-effective'

/**
 * 主题管理 store
 *
 * 设计思路（移植自 FreeLLMAPI 的 oklch 主题系统）：
 * - 支持三种模式：'dark' / 'light' / 'system'
 * - 'system' 模式跟随操作系统偏好（prefers-color-scheme）
 * - 切换时短暂添加 .theme-transitioning class 实现平滑过渡
 * - 持久化到 localStorage
 */
export const useThemeStore = defineStore('theme', () => {
  // ── 状态 ──

  /** 用户选中的模式：'dark' | 'light' | 'system' */
  const mode = ref<ThemeMode>('system')

  /** 实际生效的主题（由 mode + 系统偏好计算得出） */
  const effectiveTheme = ref<Theme>('dark')

  // ── 系统偏好检测 ──

  let mediaQuery: MediaQueryList | null = null
  let mediaHandler: ((this: MediaQueryList, ev: MediaQueryListEvent) => void) | null = null

  /**
   * 获取系统深色模式偏好
   */
  function getSystemPrefersDark(): boolean {
    if (typeof window === 'undefined') return false
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }

  /**
   * 根据 mode 计算实际要应用的 theme
   */
  function resolveTheme(m: ThemeMode): Theme {
    if (m === 'system') {
      return getSystemPrefersDark() ? 'dark' : 'light'
    }
    return m
  }

  // ── 应用到 DOM ──

  let transitionTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 应用主题到 document.documentElement
   * @param smooth 是否启用平滑过渡动画
   */
  function applyTheme(t: Theme, smooth = false) {
    effectiveTheme.value = t
    const root = document.documentElement

    if (smooth) {
      // 清除旧的 timer（防止连续点击残留）
      if (transitionTimer) {
        clearTimeout(transitionTimer)
        transitionTimer = null
      }
      // 先移除再添加，确保 transition 重新触发
      root.classList.remove('theme-transitioning')
      // 强制回流
      void root.offsetHeight
      root.classList.add('theme-transitioning')
      // 300ms 后移除
      transitionTimer = setTimeout(() => {
        root.classList.remove('theme-transitioning')
        transitionTimer = null
      }, 350)
    }

    root.setAttribute('data-theme', t)
    localStorage.setItem(STORAGE_KEY_EFFECTIVE, t)
  }

  /**
   * 根据 mode 解析并应用主题
   */
  function applyFromMode(m: ThemeMode, smooth = false) {
    const t = resolveTheme(m)
    applyTheme(t, smooth)
  }

  // ── 监听系统偏好变化 ──

  function setupSystemListener() {
    // 清理旧 listener
    if (mediaQuery && mediaHandler) {
      mediaQuery.removeEventListener('change', mediaHandler)
    }

    if (typeof window !== 'undefined') {
      mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
      mediaHandler = () => {
        // 仅在 system 模式下自动跟随
        if (mode.value === 'system') {
          applyFromMode('system', true)
        }
      }
      mediaQuery.addEventListener('change', mediaHandler)
    }
  }

  // ── 公开方法 ──

  /**
   * 初始化主题
   * 优先读取 localStorage → 默认 system
   */
  function init() {
    const stored = localStorage.getItem(STORAGE_KEY_MODE) as ThemeMode | null
    mode.value = stored === 'dark' || stored === 'light' || stored === 'system'
      ? stored
      : 'system'

    setupSystemListener()
    applyFromMode(mode.value, false)
  }

  /**
   * 切换主题（dark ⇄ light，system → dark → light → system 循环）
   *
   * 点击顺序：system → dark → light → system → ...
   */
  function cycle() {
    const order: ThemeMode[] = ['system', 'dark', 'light']
    const idx = order.indexOf(mode.value)
    const next = order[(idx + 1) % order.length]
    mode.value = next
    localStorage.setItem(STORAGE_KEY_MODE, next)
    applyFromMode(next, true)
  }

  /**
   * 传统的亮/暗切换（兼容旧调用方）
   * dark → light → dark → ...
   */
  function toggle() {
    const current = effectiveTheme.value
    const next: Theme = current === 'dark' ? 'light' : 'dark'
    mode.value = next
    localStorage.setItem(STORAGE_KEY_MODE, next)
    applyTheme(next, true)
  }

  /**
   * 直接设为指定模式
   */
  function setMode(m: ThemeMode) {
    mode.value = m
    localStorage.setItem(STORAGE_KEY_MODE, m)
    applyFromMode(m, true)
  }

  /**
   * 直接设为指定主题（强制，不保留 mode 概念）
   */
  function apply(t: Theme) {
    mode.value = t
    localStorage.setItem(STORAGE_KEY_MODE, t)
    applyTheme(t, true)
  }

  // ── 计算属性 ──

  /** 是否为深色主题 */
  const isDark = computed(() => effectiveTheme.value === 'dark')

  /** 当前主题（同 effectiveTheme，简称） */
  const theme = computed(() => effectiveTheme.value)

  return {
    mode,
    theme,
    effectiveTheme,
    isDark,
    init,
    toggle,
    cycle,
    setMode,
    apply,
  }
})
