/**
 * 数字格式化工具函数
 *
 * 统一各页面重复书写的 formatNumber / formatTokens 逻辑，
 * 避免同名函数在不同页面实现不一致（如依赖运行环境 locale 的 toLocaleString 差异）。
 */

/**
 * 格式化数字 - 千分位分隔
 * @param n 数字（允许 undefined/null）
 * @returns 千分位字符串，空值返回 '0'
 */
export function formatNumber(n: number | undefined | null): string {
  if (n == null) return '0'
  return n.toLocaleString()
}

/**
 * 格式化 token 数量 - 大数字用 K/M 缩写，小数字千分位
 * @param n 数字（允许 undefined/null）
 * @returns 缩写字符串，空值或 0 返回 '0'
 */
export function formatTokens(n: number | undefined | null): string {
  if (n == null || n === 0) return '0'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return n.toLocaleString()
}

/**
 * 格式化耗时 - 统一以秒为单位显示，保留 2 位小数
 * @param ms 毫秒数（允许 undefined/null）
 * @returns 秒字符串（如 '1.25s'），空值返回 '-'
 */
export function formatSeconds(ms: number | undefined | null): string {
  if (ms == null) return '-'
  return (ms / 1000).toFixed(2) + 's'
}
