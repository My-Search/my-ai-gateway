import { describe, it, expect } from 'vitest'
import { formatNumber, formatTokens } from './format'

describe('formatNumber', () => {
  it('空值返回 0', () => {
    expect(formatNumber(null)).toBe('0')
    expect(formatNumber(undefined)).toBe('0')
  })

  it('0 返回 0', () => {
    expect(formatNumber(0)).toBe('0')
  })

  it('小于 1000 的数字不加千分位', () => {
    expect(formatNumber(1)).toBe('1')
    expect(formatNumber(999)).toBe('999')
  })

  it('大于等于 1000 的数字加千分位', () => {
    expect(formatNumber(1000)).toBe('1,000')
    expect(formatNumber(1234567)).toBe('1,234,567')
  })

  it('负数格式化', () => {
    expect(formatNumber(-500)).toBe('-500')
    expect(formatNumber(-1234)).toBe('-1,234')
  })
})

describe('formatTokens', () => {
  it('空值和 0 返回 0', () => {
    expect(formatTokens(null)).toBe('0')
    expect(formatTokens(undefined)).toBe('0')
    expect(formatTokens(0)).toBe('0')
  })

  it('小于 1000 的数字原样显示', () => {
    expect(formatTokens(1)).toBe('1')
    expect(formatTokens(999)).toBe('999')
    expect(formatTokens(999.5)).toBe('999.5')
  })

  it('1000 到 999999 用 K 缩写', () => {
    expect(formatTokens(1000)).toBe('1.0K')
    expect(formatTokens(1500)).toBe('1.5K')
    expect(formatTokens(999999)).toBe('1000.0K')
  })

  it('100 万以上用 M 缩写', () => {
    expect(formatTokens(1_000_000)).toBe('1.0M')
    expect(formatTokens(1_500_000)).toBe('1.5M')
    expect(formatTokens(1_000_000.4)).toBe('1.0M')
  })

  it('负数不进入 K/M 缩写分支，走千分位格式化', () => {
    expect(formatTokens(-500)).toBe('-500')
    expect(formatTokens(-5000)).toBe('-5,000')
  })
})
