import { describe, it, expect } from 'vitest'

/**
 * 图标完整性守卫
 *
 * 背景：图标由 vite-plugin-svg-icons 在构建期扫描 src/assets/icons 生成
 * `<symbol id="icon-<name>">` 注入页面。若代码引用了不存在的图标名，
 * 构建不会报错、页面也不报错，只是静默渲染成空白 —— 本地难发现，
 * 往往部署后才暴露（例：calendar 因 .gitignore 未入库、alert 从未创建）。
 *
 * 这里用源码静态扫描兜住这一类问题：任何被引用到的图标名，都必须在
 * src/assets/icons 下存在同名 .svg。
 */

// 所有图标文件（键形如 '../assets/icons/calendar.svg'）
const iconModules = import.meta.glob('../assets/icons/*.svg', { eager: true })
const iconFiles = new Set(
  Object.keys(iconModules).map((p) => p.split('/').pop()!.replace(/\.svg$/, '')),
)

// 全部组件/逻辑源码，用于扫描图标引用
const sources = import.meta.glob('../**/*.{vue,ts}', {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>

/** 取出 <SvgIcon ...> 标签的完整文本（含跨行的动态绑定） */
function svgIconTags(code: string): string[] {
  return code.match(/<SvgIcon\b[^>]*?\/?>/gs) ?? []
}

/** 收集某段文本中被引用的图标名 */
function referencedIcons(tag: string): string[] {
  const names: string[] = []

  // 1) 静态写法：name="calendar"
  for (const m of tag.matchAll(/\b(?<!:)name\s*=\s*"([^"]+)"/g)) names.push(m[1])

  // 2) 动态写法：:name="cond ? 'sun' : 'moon'" → 取表达式里的字符串字面量。
  //    纯标识符（如 :name="icon"，由父组件透传）无法静态判定，跳过。
  const dyn = tag.match(/:name\s*=\s*"([^"]*)"/)
  if (dyn) {
    for (const m of dyn[1].matchAll(/'([^']+)'|"([^"]+)"/g)) names.push(m[1] ?? m[2])
  }
  return names
}

/** 图标名合法形态：小写字母开头，仅含小写字母/数字/短横线 */
const ICON_SLUG = /^[a-z][a-z0-9-]*$/

/** 从 script 部分收集「语义 → 图标」映射项，如 `warning: 'alert'` */
function semanticIconMap(code: string): string[] {
  const found: string[] = []
  for (const line of code.split(/\r?\n/)) {
    // 仅匹配语义键，避免把普通业务字段误当图标名
    for (const m of line.matchAll(/\b(?:success|warning|error|info|danger)\s*:\s*['"]([a-z0-9-]+)['"]/g)) {
      found.push(m[1])
    }
  }
  return found
}

describe('图标完整性守卫', () => {
  it('src/assets/icons 下存在图标文件', () => {
    expect(iconFiles.size).toBeGreaterThan(0)
  })

  it('模板中引用的所有图标都存在对应 .svg（静态 + 动态绑定）', () => {
    const missing: string[] = []
    for (const [path, code] of Object.entries(sources)) {
      // 只扫描组件模板；测试文件自身不算
      if (/\.(test|spec)\.ts$/.test(path)) continue
      for (const tag of svgIconTags(code)) {
        for (const name of referencedIcons(tag)) {
          if (!ICON_SLUG.test(name)) continue
          if (!iconFiles.has(name)) missing.push(`${path} → SvgIcon name="${name}"`)
        }
      }
    }
    expect(missing, `以下图标被引用但缺少 src/assets/icons/<name>.svg：\n${missing.join('\n')}`).toEqual([])
  })

  it('语义映射表（如 ToastContainer 的 ICONS）引用的图标都存在', () => {
    const missing: string[] = []
    for (const [path, code] of Object.entries(sources)) {
      if (/\.(test|spec)\.ts$/.test(path)) continue
      for (const name of semanticIconMap(code)) {
        if (!iconFiles.has(name)) missing.push(`${path} → '${name}'`)
      }
    }
    expect(missing, `语义映射表引用了不存在的图标：\n${missing.join('\n')}`).toEqual([])
  })

  it('calendar 图标存在（时间段下拉依赖，曾因 .gitignore 漏提交）', () => {
    expect(iconFiles.has('calendar')).toBe(true)
  })
})
