import { describe, it, expect } from 'vitest'
import { matchFilter, parseFilter } from './filterMatch'

/**
 * 过滤表达式匹配：普通（空格分词 AND）/ 正则（斜杠包裹）/ 混合（正则 + 普通词）。
 * 与入口模型关联页、SearchableSelect 的候选过滤共用同一实现。
 */
describe('parseFilter', () => {
  it('空表达式无关键词无正则', () => {
    expect(parseFilter('')).toEqual({ keywords: [], regexes: [], error: null })
    expect(parseFilter('   ')).toEqual({ keywords: [], regexes: [], error: null })
  })

  it('普通关键词转小写并去空', () => {
    expect(parseFilter('GLM 最新 模型').keywords).toEqual(['glm', '最新', '模型'])
  })

  it('正则段被识别并默认忽略大小写', () => {
    const parsed = parseFilter('/AI|人工智能/')
    expect(parsed.keywords).toEqual([])
    expect(parsed.regexes).toHaveLength(1)
    expect(parsed.regexes[0].test('glm-4-Air')).toBe(true)
    expect(parsed.regexes[0].test('gpt-4o')).toBe(false)
  })

  it('显式 flags 覆盖默认 i，g 被剥离', () => {
    const parsed = parseFilter('/AI/i')
    expect(parsed.regexes[0].flags).toBe('i')
    const g = parseFilter('/AI/gi')
    expect(g.regexes[0].flags).toBe('i')
    expect(g.error).toBeNull()
  })

  it('混合表达式拆出正则段与关键词', () => {
    const parsed = parseFilter('/AI|人工智能/ 资讯')
    expect(parsed.keywords).toEqual(['资讯'])
    expect(parsed.regexes).toHaveLength(1)
    expect(parsed.error).toBeNull()
  })

  it('正则段内的转义斜杠不截断表达式', () => {
    const parsed = parseFilter('/gpt\\/4/')
    expect(parsed.regexes).toHaveLength(1)
    expect(parsed.regexes[0].test('openai/gpt/4o')).toBe(true)
  })

  it('非法正则记录 error，不成对斜杠按普通词处理', () => {
    expect(parseFilter('/[a-/').error).not.toBeNull()
    const unpaired = parseFilter('glm/4')
    expect(unpaired.error).toBeNull()
    expect(unpaired.keywords).toEqual(['glm/4'])
    expect(unpaired.regexes).toHaveLength(0)
  })
})

describe('matchFilter', () => {
  const candidates = [
    'GLM-4.6 最新模型',
    'glm-4-air',
    'gpt-4o',
    'deepseek-ai/DeepSeek-V3',
    'qwen 人工智能助手'
  ]

  it('空表达式返回 true（不过滤）', () => {
    expect(matchFilter('', 'anything')).toBe(true)
    expect(matchFilter('   ', 'anything')).toBe(true)
  })

  it('普通匹配：所有词都必须包含（不区分大小写）', () => {
    expect(matchFilter('glm 最新模型', candidates[0])).toBe(true)
    expect(matchFilter('glm 最新', candidates[1])).toBe(false)
    expect(matchFilter('DEEPSEEK v3', candidates[3])).toBe(true)
  })

  it('正则匹配：斜杠包裹的表达式按正则求值', () => {
    expect(matchFilter('/AI|人工智能/', candidates[3])).toBe(true)
    expect(matchFilter('/AI|人工智能/', candidates[4])).toBe(true)
    expect(matchFilter('/AI|人工智能/', candidates[2])).toBe(false)
  })

  it('混合匹配：正则与普通词同时命中', () => {
    expect(matchFilter('/AI|人工智能/ 资讯', 'AI 资讯站')).toBe(true)
    expect(matchFilter('/AI|人工智能/ 资讯', 'AI 新闻站')).toBe(false)
    expect(matchFilter('/^glm/ 最新', candidates[0])).toBe(true)
    expect(matchFilter('/^glm/ 最新', 'new-glm 最新')).toBe(false)
  })

  it('正则编译失败时恒为 false', () => {
    expect(matchFilter('/[a-/', 'gpt')).toBe(false)
  })
})
