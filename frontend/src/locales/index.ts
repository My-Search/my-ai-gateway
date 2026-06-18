import zhCN from './zh-CN'
import enUS from './en-US'

export type MessageKey = keyof typeof zhCN

export const messages: Record<string, Record<string, string>> = {
  'zh-CN': zhCN,
  'en-US': enUS,
}

export { zhCN, enUS }
