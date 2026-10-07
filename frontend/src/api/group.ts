import http from './index'
import type { CustomModel } from './model'

/** 小组路由方式 */
export type GroupStrategy = 'failover' | 'random' | 'round_robin'

/** 模型小组：能力相近的渠道模型聚合，拥有自己的路由方式 */
export interface ModelGroup {
  id?: number
  name: string
  description?: string
  /** 组内路由方式：failover | random（按权重加权）| round_robin */
  strategy?: GroupStrategy
  /** 会话粘性：1=按消息前缀哈希固定成员（命中上游 prompt cache） */
  sticky?: number
  enabled?: number
  createdAt?: string
  updatedAt?: string
  /** 成员数（列表接口返回） */
  memberCount?: number | null
}

/** 小组成员：一个渠道模型及其组内权重 / 默认思考强度 */
export interface ModelGroupMember {
  id: number
  groupId: number
  channelModelId: number
  /** 组内随机权重（strategy=random 与 sticky 哈希环生效），<=0 视为 1 */
  weight?: number
  reasoningEffort?: string | null
  sortOrder: number
  enabled?: number
  channelModelName?: string
  channelName?: string
  channelType?: string
  channelId?: number
  channelEnabled?: number
  apiKeyAvailable?: number
  input?: string
  contextLength?: number | null
  /** 该成员 24h 性能样本均值（与入口模型关联行同口径） */
  ttftMs?: number | null
  sampleCount?: number | null
  outputSpeed?: number | null
  circuitBroken?: number
  circuitBrokenScope?: 'model' | 'channel' | 'both' | null
  circuitBrokenLastProbeAt?: string | null
  circuitBrokenLastProbeStatus?: number | null
  circuitBrokenLastProbeDetail?: string | null
  circuitBrokenProtocols?: { keyId: number; keyName: string; protocol: string }[] | null
}

/** 入口模型 -> 小组 关联（与渠道模型关联共用 sort_order 序号空间） */
export interface ModelGroupRel {
  id: number
  modelId: number
  groupId: number
  sortOrder: number
  enabled?: number
  groupName?: string
  groupStrategy?: GroupStrategy
  groupSticky?: number
  groupEnabled?: number
  groupDescription?: string
  memberCount?: number
  /** 可路由成员数（静态可路由：成员启用 + 渠道模型启用 + 渠道启用 + 有可用 Key） */
  availableCount?: number
  /** 熔断成员数（可路由成员中处于熔断状态的个数），熔断列展示 n/m 用 */
  brokenCount?: number
  /** 可路由成员输入模态并集（text 优先、去重，逗号分隔） */
  input?: string
  /** 可路由成员的最大正值上下文；全未知为 null */
  maxContextLength?: number | null
  ttftMs?: number | null
  sampleCount?: number | null
  outputSpeed?: number | null
  /** 1 = 组内可路由成员全部熔断 */
  circuitBroken?: number
  /** 全部熔断时的聚合熔断级别：model / channel / both（成员混合取最广一档） */
  circuitBrokenScope?: 'model' | 'channel' | 'both' | null
  /** 可路由成员的上游模型名（去重、按成员顺序），模型列逐行展示 */
  memberModelNames?: string[]
}

export interface GroupDetail {
  group: ModelGroup
  members: ModelGroupMember[]
  availableModels: any[]
  /** 引用该小组的入口模型（删除前提示影响面） */
  usedByModels: CustomModel[]
}

export const groupApi = {
  list() {
    return http.get<ModelGroup[]>('/model-groups')
  },
  get(id: number) {
    return http.get<GroupDetail>(`/model-groups/${id}`)
  },
  create(data: Partial<ModelGroup>) {
    return http.post<{ success: boolean; id?: number; error?: string }>('/model-groups', data)
  },
  update(id: number, data: Partial<ModelGroup>) {
    return http.put<{ success: boolean; error?: string }>(`/model-groups/${id}`, data)
  },
  remove(id: number) {
    return http.delete<{ success: boolean; error?: string }>(`/model-groups/${id}`)
  },
  /** 批量添加成员 */
  addMembers(groupId: number, channelModelIds: number[]) {
    return http.post<{ success: boolean; count: number; error?: string }>(`/model-groups/${groupId}/members`, { channelModelIds })
  },
  removeMember(memberId: number) {
    return http.delete<{ success: boolean; error?: string }>(`/model-groups/members/${memberId}`)
  },
  batchRemoveMembers(memberIds: number[]) {
    return http.post<{ success: boolean; count: number; error?: string }>('/model-groups/members/batch-delete', { memberIds })
  },
  /** 成员拖拽排序（整表提交） */
  updateMembersSort(sortedMemberIds: number[]) {
    return http.put<{ success: boolean; error?: string }>('/model-groups/members/sort', { sortedMemberIds })
  },
  /** 更新成员权重 / 思考强度 / 启用状态 */
  updateMember(memberId: number, data: { weight?: number; reasoningEffort?: string | null; enabled?: number }) {
    return http.put<{ success: boolean; error?: string }>(`/model-groups/members/${memberId}`, data)
  },
  /**
   * 解除某个渠道模型的熔断状态。
   * 小组成员没有自己的关联行，熔断按 (渠道, 渠道模型, Key) 记录，因此按渠道模型解除。
   */
  clearChannelModelCircuitBreaker(channelModelId: number) {
    return http.delete<{ success: boolean; recovered?: number; error?: string }>(`/channel-models/${channelModelId}/circuit-breaker`)
  },
  /**
   * 入口模型关联小组 / 解除关联（与渠道模型关联共用 sort_order 序号空间）
   */
  addModelRel(modelId: number, groupIds: number[]) {
    return http.post<{ success: boolean; count: number; error?: string }>(`/models/${modelId}/rels`, { groupIds })
  },
  removeModelRel(relId: number) {
    return http.delete<{ success: boolean; error?: string }>(`/models/group-rels/${relId}`)
  },
  batchRemoveModelRels(relIds: number[]) {
    return http.post<{ success: boolean; count: number; error?: string }>('/models/rels/batch-delete', { relIds: relIds.map(id => 'g:' + id) })
  }
}
