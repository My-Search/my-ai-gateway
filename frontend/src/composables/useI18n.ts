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
 * t('log.list.retry', { count: 3 }) // → '重试 3'
 * ```
 */
export function useI18n() {
  const localeStore = useLocaleStore()

  function t(key: string, params?: Record<string, string | number>): string {
    const lang = localeStore.locale
    let text = messages[lang]?.[key] ?? key
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        text = text.replace(`{${k}}`, String(v))
      }
    }
    return text
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
