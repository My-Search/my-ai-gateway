/**
 * Sparkline SVG 路径生成工具
 * 将数值数组转换为 SVG path 的 line 和 area 路径
 */

const FLAT_SPARKLINE = { line: 'M 0 15 L 100 15', area: '' }

/**
 * 生成 sparkline 的 SVG path 数据
 * @param data 数值数组
 * @param width SVG viewBox 宽度（默认 100）
 * @param height SVG viewBox 高度（默认 30）
 * @returns { line: string; area: string }
 */
export function sparklinePaths(
  data: number[],
  width = 100,
  height = 30
): { line: string; area: string } {
  if (!data || data.length < 2) return FLAT_SPARKLINE
  const max = Math.max(...data)
  const min = Math.min(...data)
  const range = max - min
  // 全部为 0 或所有值相等时画中间水平平线
  if (range === 0) return FLAT_SPARKLINE
  const step = width / (data.length - 1)
  let linePath = ''
  const points: { x: number; y: number }[] = []
  data.forEach((val, i) => {
    const x = i * step
    const y = height - ((val - min) / range) * height
    points.push({ x, y })
    linePath += (i === 0 ? 'M' : 'L') + ` ${x.toFixed(1)} ${y.toFixed(1)}`
  })
  const first = points[0]
  const last = points[points.length - 1]
  const areaPath = linePath + ` L ${last.x.toFixed(1)} ${height} L ${first.x.toFixed(1)} ${height} Z`
  return { line: linePath, area: areaPath }
}

/**
 * 按模型名分组的 sparkline 路径生成
 * @param trends Record<modelName, requests[]>
 * @param width SVG viewBox 宽度
 * @param height SVG viewBox 高度
 * @returns Record<modelName, { line: string; area: string }>
 */
export function sparklinePathsMap(
  trends: Record<string, number[]>,
  width = 100,
  height = 30
): Record<string, { line: string; area: string }> {
  const result: Record<string, { line: string; area: string }> = {}
  for (const [name, data] of Object.entries(trends)) {
    result[name] = sparklinePaths(data, width, height)
  }
  return result
}
