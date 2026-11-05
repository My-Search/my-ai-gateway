/**
 * 过滤表达式解析与匹配，供下拉候选过滤等场景使用。
 *
 * 三种匹配方式（可组合，全部命中才算匹配）：
 * - 普通匹配："glm 最新模型" — 空格分词，每个词都必须出现（不区分大小写）；
 * - 正则匹配："/AI|人工智能/" — 用斜杠包裹正则表达式，未显式写 flags 时默认
 *   忽略大小写，也可写 "/AI/i" 指定；
 * - 混合匹配："/AI|人工智能/ 资讯" — 正则段与普通关键词并存。
 *
 * 正则段内的 `/` 需写成 `\/`；不成对的斜杠按普通关键词处理。
 */

export interface ParsedFilter {
  /** 普通关键词（已转小写） */
  keywords: string[]
  /** 编译通过的正则段（flags 中剥离了无意义的 g） */
  regexes: RegExp[]
  /** 正则编译失败时的错误信息；非 null 时匹配恒为 false */
  error: string | null
}

/** 正则段：/pattern/flags，pattern 支持 \/ 转义 */
const REGEX_SEGMENT = /\/((?:\\.|[^/\\])+)\/([gimsuy]*)/g

function pushKeywords(out: string[], text: string) {
  for (const kw of text.toLowerCase().split(/\s+/)) {
    if (kw) out.push(kw)
  }
}

/** 把过滤表达式拆成普通关键词与正则段；正则编译失败不抛出，记录到 error。 */
export function parseFilter(expr: string): ParsedFilter {
  const keywords: string[] = []
  const regexes: RegExp[] = []
  let error: string | null = null
  const source = expr ?? ''
  const segment = new RegExp(REGEX_SEGMENT.source, 'g')
  let last = 0
  let m: RegExpExecArray | null
  while ((m = segment.exec(source)) !== null) {
    pushKeywords(keywords, source.slice(last, m.index))
    try {
      // g 标志会让 test 有状态，过滤场景剥离；未指定 flags 时默认忽略大小写
      regexes.push(new RegExp(m[1], (m[2] || 'i').replace(/g/g, '')))
    } catch {
      error = error ?? `/${m[1]}/`
    }
    last = segment.lastIndex
  }
  pushKeywords(keywords, source.slice(last))
  return { keywords, regexes, error }
}

/**
 * 判断 text 是否命中过滤表达式：空表达式恒为 true（不过滤），
 * 存在编译失败的正则段时恒为 false。
 */
export function matchFilter(expr: string, text: string): boolean {
  const parsed = parseFilter(expr)
  if (parsed.error) return false
  if (parsed.keywords.length === 0 && parsed.regexes.length === 0) return true
  const lower = (text ?? '').toLowerCase()
  for (const kw of parsed.keywords) {
    if (!lower.includes(kw)) return false
  }
  for (const re of parsed.regexes) {
    re.lastIndex = 0
    if (!re.test(text ?? '')) return false
  }
  return true
}
