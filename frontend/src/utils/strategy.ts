import type { ModelStrategy } from '@/api/model'

/**
 * 归一入口模型的策略值：仅保留 failover（顺序）与 sticky（粘性）。
 * 历史值 random / round_robin 统一按粘性处理（数据库迁移会落库归一，
 * 这里兜底旧接口数据 / 旧缓存，保证展示与筛选一致）。
 */
export function normalizeStrategy(s?: string): ModelStrategy {
  return s === 'sticky' || s === 'random' || s === 'round_robin' ? 'sticky' : 'failover'
}