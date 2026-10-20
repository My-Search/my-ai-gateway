/**
 * 后端时间工具函数
 *
 * 后端使用 UTC 时间，序列化格式为 "yyyy-MM-dd'T'HH:mm:ss'Z'"（ISO 8601 带 Z 后缀）。
 * 这些函数将 UTC 时间字符串解析后转换为浏览器本地时区显示。
 */

/**
 * 将后端 UTC 日期字符串解析为浏览器本地 Date 对象
 *
 * 后端统一返回 UTC 时间，格式如 "2024-06-19T10:30:00Z"。
 * 兼容旧版格式 "2024-06-19 10:30:00"。
 */
function parseDate(dateStr: string | undefined | null): Date | null {
  if (!dateStr) return null
  let iso: string
  if (dateStr.endsWith('Z')) {
    // 新格式: "2024-06-19T10:30:00Z" 直接使用
    iso = dateStr
  } else {
    // 旧格式: "2024-06-19 10:30:00" → 补上 Z
    iso = dateStr.replace(' ', 'T') + 'Z'
  }
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  return d
}

/**
 * 格式化日期 - 仅显示本地时间的 HH:mm
 * 对应原有: dateStr.substring(11, 16)
 */
export function formatLocalTime(dateStr: string | undefined | null): string {
  const d = parseDate(dateStr)
  if (!d) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

/**
 * 格式化日期 - 显示本地时间的 MM-DD HH:mm
 * 对应原有: dateStr.substring(5, 16)
 */
export function formatLocalDateTime(dateStr: string | undefined | null): string {
  const d = parseDate(dateStr)
  if (!d) return ''
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hh}:${mm}`
}

/**
 * 格式化日期 - 显示本地时间的 HH:mm:ss
 * 对应原有: dateStr.substring(11, 19)
 */
export function formatLocalFullTime(dateStr: string | undefined | null): string {
  const d = parseDate(dateStr)
  if (!d) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

/**
 * 格式化日期 - 显示本地完整时间 YYYY-MM-DD HH:mm:ss
 * 用于替代列表中原始显示的日期字符串
 */
export function formatLocalDateTimeFull(dateStr: string | undefined | null): string {
  const d = parseDate(dateStr)
  if (!d) return ''
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hh}:${mm}:${ss}`
}

/**
 * 格式化日期 - 显示本地日期 YYYY-MM-DD
 */
export function formatLocalDate(dateStr: string | undefined | null): string {
  const d = parseDate(dateStr)
  if (!d) return ''
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 网关统计口径时区：Asia/Shanghai（中国无夏令时，固定 +8） */
const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000

/**
 * 返回「上海时区的当前年月」。
 *
 * 请求日志页的月份导航、使用历史图表与日志列表筛选必须共用同一口径，
 * 否则在月初 00:00–08:00（上海）会出现「列表有数据、图表却提示当月暂无数据」。
 */
export function shanghaiYearMonth(now: Date = new Date()): { year: number; month: number } {
  const shifted = new Date(now.getTime() + SHANGHAI_OFFSET_MS)
  return { year: shifted.getUTCFullYear(), month: shifted.getUTCMonth() + 1 }
}

/**
 * 返回上海时区某个自然月对应的 UTC ISO 边界，供日志列表接口过滤。
 *
 * 日志列表接口的 endTime 是「包含」语义，且后端 datetime() 会把时间截断到秒，
 * 所以结束边界取次月 1 日 00:00:00 的前一秒：这样 23:59:59.xxx 仍属于本月，
 * 而次月 00:00:00 起的数据不会被重复计入（与图表的半开窗口一致）。
 */
export function shanghaiMonthRange(year: number, month: number): { start: string; end: string } {
  // Date.UTC 先按 UTC 构造月首，再减 8 小时得到上海月首对应的 UTC 瞬时。
  const startMs = Date.UTC(year, month - 1, 1) - SHANGHAI_OFFSET_MS
  const endMs = Date.UTC(year, month, 1) - SHANGHAI_OFFSET_MS - 1000
  return {
    start: new Date(startMs).toISOString(),
    end: new Date(endMs).toISOString(),
  }
}
