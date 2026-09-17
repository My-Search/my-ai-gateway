/**
 * 仪表盘时间范围工具
 *
 * 「自定义时间段」由「日期 + 时间」两部分组成：
 * - 日期：yyyy-MM-dd，可为空（空即非法，由调用方提示）
 * - 时间：HH:mm:ss，可为空，落回各自的默认值（开始 00:00:00 / 结束 23:59:59）
 *
 * 组合后的字符串形如 2026-09-01T09:30:00，浏览器本地墙钟时间；
 * 后端按上海时区解释该墙钟时间（与既有口径一致）。
 */

/** 开始时间的默认值：当天 00:00:00 */
export const DEFAULT_START_TIME = '00:00:00'

/** 结束时间的默认值：当天 23:59:59（含该秒） */
export const DEFAULT_END_TIME = '23:59:59'

/** 自定义时间段的跨度上限（天），与后端 dashMaxRangeDays 保持一致 */
export const MAX_RANGE_DAYS = 366

/** 时间段校验结果 */
export type RangeError = 'missing' | 'inverted' | 'tooLong' | null

/** 把 "yyyy-MM-ddTHH:mm:ss" 拆成日期与时间两部分 */
export function splitDateTime(value: string): { date: string; time: string } {
  const s = (value ?? '').trim()
  if (!s) return { date: '', time: '' }
  const [date, time = ''] = s.replace(' ', 'T').split('T')
  return { date, time: time.slice(0, 8) }
}

/**
 * 把日期与时间拼成 "yyyy-MM-ddTHH:mm:ss"。
 * 时间为空时用 fallback 补齐（默认开始 00:00:00 / 结束 23:59:59）。
 */
export function joinDateTime(date: string, time: string, fallbackTime: string): string {
  const d = (date ?? '').trim()
  if (!d) return ''
  const t = normalizeTime(time) || fallbackTime
  return `${d}T${t}`
}

/** 把 "H:mm" / "HH:mm" / "HH:mm:ss" 规范成 "HH:mm:ss"，无法识别时返回空串 */
export function normalizeTime(time: string): string {
  const s = (time ?? '').trim()
  if (!s) return ''
  const m = s.match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/)
  if (!m) return ''
  const hh = Number(m[1])
  const mm = Number(m[2])
  const ss = m[3] ? Number(m[3]) : 0
  if (hh > 23 || mm > 59 || ss > 59) return ''
  return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
}

/**
 * 校验时间段（比较完整时间，精确到秒）。
 * 返回 null 表示合法，否则返回对应的错误类型，供调用方映射 i18n 文案。
 */
export function validateRange(from: string, to: string): RangeError {
  const a = (from ?? '').trim()
  const b = (to ?? '').trim()
  if (!isValidDateTime(a) || !isValidDateTime(b)) return 'missing'
  if (a > b) return 'inverted'
  if (spanSeconds(a, b) > MAX_RANGE_DAYS * 24 * 60 * 60) return 'tooLong'
  return null
}

/** 判断是否为合法的 "yyyy-MM-dd" 或 "yyyy-MM-ddTHH:mm[:ss]" */
export function isValidDateTime(value: string): boolean {
  const s = (value ?? '').trim()
  if (!s) return false
  const m = s.replace(' ', 'T').match(/^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?$/)
  if (!m) return false
  const year = Number(m[1])
  const month = Number(m[2])
  const day = Number(m[3])
  if (month < 1 || month > 12 || day < 1 || day > 31) return false
  // 用 Date 回读校验真实日历日（挡掉 2 月 30 日这类输入）
  const probe = new Date(year, month - 1, day)
  if (probe.getFullYear() !== year || probe.getMonth() !== month - 1 || probe.getDate() !== day) {
    return false
  }
  if (m[4] !== undefined) {
    const hh = Number(m[4])
    const mi = Number(m[5])
    const ss = m[6] !== undefined ? Number(m[6]) : 0
    if (hh > 23 || mi > 59 || ss > 59) return false
  }
  return true
}

/**
 * 计算两个 "yyyy-MM-ddTHH:mm:ss" 之间的秒数差。
 * 用本地墙钟构造 Date，避免把范围字符串当作 UTC 解析导致时区偏移。
 */
export function spanSeconds(from: string, to: string): number {
  const a = parseDateTime(from)
  const b = parseDateTime(to)
  if (!a || !b) return 0
  return Math.round((b.getTime() - a.getTime()) / 1000)
}

/** 把 "yyyy-MM-ddTHH:mm[:ss]" 解析为浏览器本地 Date，非法时返回 null */
export function parseDateTime(value: string): Date | null {
  const s = (value ?? '').trim().replace(' ', 'T')
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?$/)
  if (!m) return null
  const d = new Date(
    Number(m[1]), Number(m[2]) - 1, Number(m[3]),
    m[4] !== undefined ? Number(m[4]) : 0,
    m[5] !== undefined ? Number(m[5]) : 0,
    m[6] !== undefined ? Number(m[6]) : 0,
  )
  return Number.isNaN(d.getTime()) ? null : d
}

/** 把 Date 格式化为本地 "yyyy-MM-dd" */
export function formatDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 当天的本地日期 "yyyy-MM-dd" */
export function todayDate(): string {
  return formatDate(new Date())
}
