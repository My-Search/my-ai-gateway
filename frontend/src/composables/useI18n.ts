import { useLocaleStore } from '@/stores/locale'
import zhCN from '@/locales/zh-CN'
import enUS from '@/locales/en-US'

const messages: Record<string, Record<string, string>> = {
  'zh-CN': zhCN,
  'en-US': enUS,
}

/**
 * 轻量级 i18n composable
 * 返回 t(key) 函数，根据当前语言取翻译文本
 *
 * @example
 * ```ts
 * const { t } = useI18n()
 * t('nav.overview') // → '概览' / 'Overview'
 * ```
 */
export function useI18n() {
  const localeStore = useLocaleStore()

  function t(key: string, fallback?: string): string {
    const lang = localeStore.locale
    return messages[lang]?.[key] ?? fallback ?? key
  }

  return { t }
}

/** 获取所有支持的语言列表（供语言切换 UI 使用） */
export function useLanguages() {
  return [
    { value: 'zh-CN', label: '简体中文' },
    { value: 'en-US', label: 'English' },
  ]
}
