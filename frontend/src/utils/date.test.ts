import { describe, it, expect } from 'vitest'
import { shanghaiMonthRange, shanghaiYearMonth } from './date'

describe('shanghaiYearMonth', () => {
  it('UTC 9 月 30 日 16:30 已属于上海 10 月', () => {
    expect(shanghaiYearMonth(new Date('2026-09-30T16:30:00Z'))).toEqual({ year: 2026, month: 10 })
  })

  it('UTC 9 月 30 日 15:59 仍属于上海 9 月', () => {
    expect(shanghaiYearMonth(new Date('2026-09-30T15:59:59Z'))).toEqual({ year: 2026, month: 9 })
  })

  it('跨年边界：UTC 12 月 31 日 16:30 属于上海次年 1 月', () => {
    expect(shanghaiYearMonth(new Date('2026-12-31T16:30:00Z'))).toEqual({ year: 2027, month: 1 })
  })
})

describe('shanghaiMonthRange', () => {
  it('上海 2026 年 10 月的边界换算为 UTC', () => {
    const range = shanghaiMonthRange(2026, 10)
    expect(range.start).toBe('2026-09-30T16:00:00.000Z')
    // endTime 为包含语义，取次月首前一秒
    expect(range.end).toBe('2026-10-31T15:59:59.000Z')
  })

  it('2 月边界正确（含闰年）', () => {
    expect(shanghaiMonthRange(2028, 2).end).toBe('2028-02-29T15:59:59.000Z')
    expect(shanghaiMonthRange(2027, 2).end).toBe('2027-02-28T15:59:59.000Z')
  })

  it('12 月的结束边界跨到次年 1 月', () => {
    expect(shanghaiMonthRange(2026, 12).end).toBe('2026-12-31T15:59:59.000Z')
  })
})
