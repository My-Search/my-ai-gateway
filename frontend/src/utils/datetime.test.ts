import { describe, it, expect } from 'vitest'
import {
  DEFAULT_END_TIME,
  DEFAULT_START_TIME,
  MAX_RANGE_DAYS,
  formatDate,
  isValidDateTime,
  joinDateTime,
  normalizeTime,
  parseDateTime,
  spanSeconds,
  splitDateTime,
  todayDate,
  validateRange,
} from './datetime'

describe('splitDateTime', () => {
  it('拆分日期与时间', () => {
    expect(splitDateTime('2026-09-01T09:30:15')).toEqual({ date: '2026-09-01', time: '09:30:15' })
  })

  it('仅有日期时时间为空', () => {
    expect(splitDateTime('2026-09-01')).toEqual({ date: '2026-09-01', time: '' })
  })

  it('兼容空格分隔与空值', () => {
    expect(splitDateTime('2026-09-01 09:30:15')).toEqual({ date: '2026-09-01', time: '09:30:15' })
    expect(splitDateTime('')).toEqual({ date: '', time: '' })
  })
})

describe('joinDateTime', () => {
  it('按默认值补齐时间：开始 00:00:00', () => {
    expect(joinDateTime('2026-09-01', '', DEFAULT_START_TIME)).toBe('2026-09-01T00:00:00')
  })

  it('按默认值补齐时间：结束 23:59:59', () => {
    expect(joinDateTime('2026-09-17', '', DEFAULT_END_TIME)).toBe('2026-09-17T23:59:59')
  })

  it('保留用户输入的时间', () => {
    expect(joinDateTime('2026-09-01', '09:30:00', DEFAULT_START_TIME)).toBe('2026-09-01T09:30:00')
  })

  it('用户清空时间时回落默认值', () => {
    expect(joinDateTime('2026-09-01', '  ', DEFAULT_START_TIME)).toBe('2026-09-01T00:00:00')
  })

  it('日期为空时整体为空（交由校验提示必填）', () => {
    expect(joinDateTime('', '09:30:00', DEFAULT_START_TIME)).toBe('')
  })
})

describe('normalizeTime', () => {
  it('补全秒并补零', () => {
    expect(normalizeTime('9:5')).toBe('')
    expect(normalizeTime('9:30')).toBe('09:30:00')
    expect(normalizeTime('09:30')).toBe('09:30:00')
    expect(normalizeTime('09:30:15')).toBe('09:30:15')
  })

  it('非法时间返回空串', () => {
    expect(normalizeTime('24:00')).toBe('')
    expect(normalizeTime('09:60')).toBe('')
    expect(normalizeTime('abc')).toBe('')
    expect(normalizeTime('')).toBe('')
  })
})

describe('validateRange', () => {
  it('合法区间返回 null', () => {
    expect(validateRange('2026-09-01T00:00:00', '2026-09-17T23:59:59')).toBeNull()
    expect(validateRange('2026-09-01T09:30:00', '2026-09-01T12:00:00')).toBeNull()
  })

  it('缺失或非法输入报 missing', () => {
    expect(validateRange('', '2026-09-17T23:59:59')).toBe('missing')
    expect(validateRange('2026-09-01T00:00:00', '')).toBe('missing')
    expect(validateRange('2026-02-30T00:00:00', '2026-09-17T23:59:59')).toBe('missing')
  })

  it('开始晚于结束报 inverted（按完整时间比较）', () => {
    expect(validateRange('2026-09-17T10:00:00', '2026-09-17T09:00:00')).toBe('inverted')
    // 同一天，日期相同但时间先后必须能区分出来
    expect(validateRange('2026-09-17T00:00:00', '2026-09-16T23:59:59')).toBe('inverted')
  })

  it('同一天内开始等于结束视为合法', () => {
    expect(validateRange('2026-09-01T09:30:00', '2026-09-01T09:30:00')).toBeNull()
  })

  it('超过 366 天报 tooLong', () => {
    expect(validateRange('2020-01-01T00:00:00', '2026-09-17T00:00:00')).toBe('tooLong')
    // 恰好上限则允许
    const start = new Date(2026, 0, 1, 0, 0, 0)
    const end = new Date(start.getTime() + MAX_RANGE_DAYS * 24 * 3600 * 1000)
    expect(validateRange(
      `${formatDate(start)}T00:00:00`,
      `${formatDate(end)}T00:00:00`,
    )).toBeNull()
  })
})

describe('isValidDateTime', () => {
  it('接受日期与日期时间', () => {
    expect(isValidDateTime('2026-09-01')).toBe(true)
    expect(isValidDateTime('2026-09-01T09:30')).toBe(true)
    expect(isValidDateTime('2026-09-01T09:30:15')).toBe(true)
    expect(isValidDateTime('2026-09-01 09:30:15')).toBe(true)
  })

  it('拒绝不存在的日历日与越界时间', () => {
    expect(isValidDateTime('2026-02-30')).toBe(false)
    expect(isValidDateTime('2026-13-01')).toBe(false)
    expect(isValidDateTime('2026-09-01T24:00:00')).toBe(false)
    expect(isValidDateTime('2026-09-01T09:61:00')).toBe(false)
    expect(isValidDateTime('garbage')).toBe(false)
  })
})

describe('spanSeconds / parseDateTime', () => {
  it('按本地墙钟计算跨度，跨天正确', () => {
    expect(spanSeconds('2026-09-01T00:00:00', '2026-09-01T23:59:59')).toBe(86399)
    expect(spanSeconds('2026-09-01T00:00:00', '2026-09-02T00:00:00')).toBe(86400)
  })

  it('解析为本地时间（不受 UTC 解析影响）', () => {
    const d = parseDateTime('2026-09-01T09:30:15')
    expect(d).not.toBeNull()
    expect(d!.getFullYear()).toBe(2026)
    expect(d!.getMonth()).toBe(8)
    expect(d!.getDate()).toBe(1)
    expect(d!.getHours()).toBe(9)
    expect(d!.getMinutes()).toBe(30)
    expect(d!.getSeconds()).toBe(15)
  })

  it('非法输入返回 null', () => {
    expect(parseDateTime('')).toBeNull()
    expect(parseDateTime('nope')).toBeNull()
  })
})

describe('todayDate', () => {
  it('返回本地当天的 yyyy-MM-dd', () => {
    expect(todayDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(todayDate()).toBe(formatDate(new Date()))
  })
})
