<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        {{ t('group.detail.title').replace('{name}', group?.name || '') }}
        <span v-if="group" class="badge badge-strategy">{{ strategyLabel(group.strategy || 'random') }}</span>
        <span v-if="group?.sticky === 1" class="badge badge-sticky">{{ t('group.list.stickyOn') }}</span>
      </div>
      <div class="header-actions">
        <router-link :to="'/admin/model/group/form/' + (group?.id || '')" class="btn btn-secondary btn-sm">
          <SvgIcon name="edit" :size="14" /> {{ t('group.detail.editGroup') }}
        </router-link>
        <router-link to="/admin/model/group/list" class="btn btn-secondary">
          <SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}
        </router-link>
      </div>
    </div>

    <!-- 引用该小组的入口模型 -->
    <div v-if="usedByModels.length" class="used-by">
      <SvgIcon name="info" :size="14" />
      <span>{{ t('group.detail.usedBy') }}：</span>
      <router-link
        v-for="m in usedByModels"
        :key="m.id"
        :to="'/admin/model/rels/' + m.id"
        class="used-by-link"
      >{{ m.modelName }}</router-link>
    </div>

    <div class="action-bar">
      <SearchableSelect
        v-model="selectedModelIds"
        :options="selectOptions"
        :placeholder="t('group.detail.selectModel')"
        :multiple="true"
        :width="300"
        :dropdown-width="500"
      />
      <button class="btn btn-primary btn-sm" :disabled="selectedModelIds.length === 0" @click="addMembers">
        <SvgIcon name="plus" :size="14" /> {{ t('group.detail.addMembers') }}
      </button>
      <button v-if="isDirty" class="btn btn-primary btn-sm" :disabled="isSaving" @click="saveOrder">
        <SvgIcon name="check" :size="14" /> {{ isSaving ? t('common.saving') : t('group.detail.saveOrder') }}
      </button>
      <span class="strategy-hint">
        <SvgIcon name="info" :size="13" />
        {{ group?.sticky === 1 ? t('group.detail.stickyMemberHint') : t('group.detail.weightHint') }}
      </span>
    </div>

    <div v-if="loading" class="page-loading">
      <LoadingSpinner :size="18" :text="t('common.loading')" />
    </div>

    <div v-else class="table-container">
      <table>
        <thead>
          <tr>
            <th>{{ t('group.detail.sort') }}</th>
            <th>{{ t('group.detail.channel') }}</th>
            <th>{{ t('group.detail.model') }}</th>
            <th>{{ t('group.detail.weight') }}</th>
            <th>{{ t('model.rels.inputTypes') }}</th>
            <th>{{ t('model.rels.contextLength') }}</th>
            <th>{{ t('model.rels.responseTime') }}</th>
            <th>{{ t('model.rels.outputSpeed') }}</th>
            <th>{{ t('model.rels.circuitBreaker') }}</th>
            <th>{{ t('group.detail.reasoningEffort') }}</th>
            <th>{{ t('group.detail.actions') }}</th>
          </tr>
        </thead>
        <tbody ref="tbodyRef">
          <tr
            v-for="member in members"
            :key="member.id"
            :class="{ 'row-disabled': isMemberUnavailable(member) }"
          >
            <td>
              <span class="drag-handle" :title="t('group.detail.dragSort')">≡</span>
            </td>
            <td class="channel-cell">
              <span :class="{ 'text-disabled': isMemberUnavailable(member) }">{{ member.channelName }}</span>
              <span v-if="member.channelEnabled !== 1" class="badge badge-disabled">{{ t('common.disabled') }}</span>
              <span v-if="member.apiKeyAvailable === 0" class="badge badge-no-key">{{ t('group.detail.noApiKey') }}</span>
            </td>
            <td><code class="model-tag" :class="{ 'text-disabled': isMemberUnavailable(member) }">{{ member.channelModelName }}</code></td>
            <td>
              <input
                type="number"
                class="form-control weight-input"
                :value="member.weight ?? 1"
                min="1" max="1000"
                :title="t('group.detail.weightTitle')"
                @change="updateWeight(member, ($event.target as HTMLInputElement).value)"
              />
            </td>
            <td>
              <span v-if="member.input" class="input-tags">
                <span v-for="type in (member.input || '').split(',')" :key="type" class="input-tag" :class="'input-tag--' + type">{{ type }}</span>
              </span>
              <span v-else class="text-muted">text</span>
            </td>
            <td>
              <span v-if="member.contextLength" class="input-tag input-tag--ctx">{{ formatTokens(member.contextLength) }}</span>
              <span v-else class="text-muted">-</span>
            </td>
            <td>
              <span v-if="member.ttftMs != null" class="resp-time">
                {{ formatRespTime(member.ttftMs) }}
                <span v-if="member.sampleCount != null" class="sample-count">({{ member.sampleCount }})</span>
              </span>
              <span v-else class="resp-time-none">{{ t('model.rels.noData') }}</span>
            </td>
            <td>
              <span v-if="member.outputSpeed != null" class="resp-time">
                {{ member.outputSpeed.toFixed(1) }} <span class="sample-count">tokens/s</span>
              </span>
              <span v-else class="resp-time-none">{{ t('model.rels.noData') }}</span>
            </td>
            <td>
              <span v-if="member.circuitBroken === 1" class="cb-broken">
                <span class="badge badge-broken">
                  {{ t('model.rels.broken') }}
                  <template v-if="member.circuitBrokenScope === 'channel'">（{{ t('model.rels.brokenChannel') }}）</template>
                  <template v-else-if="member.circuitBrokenScope === 'model'">（{{ t('model.rels.brokenModel') }}）</template>
                  <template v-else-if="member.circuitBrokenScope === 'both'">（{{ t('model.rels.brokenBoth') }}）</template>
                </span>
                <span class="cb-hint" @click="toggleProbeHint($event, member)">
                  <SvgIcon name="question" :size="12" class="cb-hint-icon" />
                </span>
                <button class="btn btn-sm btn-secondary cb-recover-btn" @click="recoverMember(member)">
                  <SvgIcon name="check" :size="12" /> {{ t('model.rels.recover') }}
                </button>
              </span>
              <span v-else class="text-muted">{{ t('model.rels.brokenNone') }}</span>
            </td>
            <td>
              <input
                class="form-control effort-select"
                type="text"
                :value="member.reasoningEffort ?? ''"
                :list="effortDatalistId"
                :placeholder="t('group.detail.effortPlaceholder')"
                @change="updateEffort(member, ($event.target as HTMLInputElement).value)"
              />
            </td>
            <td>
              <button class="btn btn-sm btn-danger" @click="removeMember(member)">
                <SvgIcon name="trash" :size="14" /> {{ t('common.delete') }}
              </button>
            </td>
          </tr>
          <tr v-if="!members.length">
            <td colspan="11" class="empty-cell">{{ t('group.detail.noMembers') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <datalist :id="effortDatalistId">
      <option v-for="e in EFFORT_PRESETS" :key="e" :value="e" />
    </datalist>
  </div>

  <!-- Common Dialog -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    :confirm-class="dialogConfirmClass"
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
  </Dialog>

  <!-- 探测机制说明气泡：点击问号图标切换显示；含该成员最近一次探测时间与结果 -->
  <Teleport to="body">
    <div
      v-if="probeHintVisible"
      ref="probeHintRef"
      class="probe-hint-pop"
      :class="{ below: probeHintPos.below }"
      :style="{ left: probeHintPos.x + 'px', top: probeHintPos.y + 'px' }"
      @click.stop
    >
      <div>{{ t('model.rels.brokenProbeHint') }}</div>
      <div class="probe-hint-probe">
        <template v-if="probeHintMember?.circuitBrokenLastProbeAt">
          <div class="probe-hint-title">{{ t('model.rels.lastProbeTitle') }}</div>
          <div class="probe-hint-row">
            <span class="probe-hint-label">{{ t('model.rels.lastProbeTime') }}</span>
            <span>{{ formatLocalDateTimeFull(probeHintMember.circuitBrokenLastProbeAt) }}</span>
          </div>
          <div v-if="probeHintMember.circuitBrokenLastProbeStatus != null" class="probe-hint-row">
            <span class="probe-hint-label">{{ t('model.rels.lastProbeStatus') }}</span>
            <span>{{ probeHintMember.circuitBrokenLastProbeStatus }}</span>
          </div>
          <template v-if="probeHintMember.circuitBrokenLastProbeDetail">
            <div class="probe-hint-detail-label">{{ t('model.rels.lastProbeDetail') }}</div>
            <pre class="probe-hint-detail">{{ probeHintMember.circuitBrokenLastProbeDetail }}</pre>
          </template>
        </template>
        <div v-else class="probe-hint-empty">{{ t('model.rels.lastProbeNone') }}</div>
      </div>
      <div class="probe-hint-probe">
        <div class="probe-hint-title">{{ t('model.rels.protocolTitle') }}</div>
        <template v-if="probeHintMember?.circuitBrokenProtocols?.length">
          <div v-for="kp in probeHintMember.circuitBrokenProtocols" :key="kp.keyId" class="probe-hint-row">
            <span class="probe-hint-label">{{ kp.keyName || ('#' + kp.keyId) }}</span>
            <span>{{ protocolLabel(kp.protocol) }}</span>
          </div>
        </template>
        <div v-else class="probe-hint-empty">{{ t('model.rels.protocolNone') }}</div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { useToast } from '@/composables/useToast'
import { groupApi, type ModelGroup, type ModelGroupMember } from '@/api/group'
import type { CustomModel } from '@/api/model'
import { formatLocalDateTimeFull } from '@/utils/date'
import { formatTokens } from '@/utils/format'
import SearchableSelect from '@/components/common/SearchableSelect.vue'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import Sortable from 'sortablejs'

const { t } = useI18n()
const route = useRoute()
const { visible: dialogVisible, title: dialogTitle, message: dialogMessage, type: dialogType, confirmClass: dialogConfirmClass, onConfirm: onDialogConfirm, open: openDialog } = useDialog()
const { showToast } = useToast()

const group = ref<ModelGroup | null>(null)
const members = ref<ModelGroupMember[]>([])
const availableModels = ref<any[]>([])
const usedByModels = ref<CustomModel[]>([])
const loading = ref(true)
const selectedModelIds = ref<number[]>([])

const tbodyRef = ref<HTMLElement | null>(null)
let sortableInstance: Sortable | null = null
const isDirty = ref(false)
const originalMemberIds = ref<number[]>([])
const isSaving = ref(false)

const EFFORT_PRESETS = ['low', 'medium', 'high', 'xhigh', 'max'] as const
const effortDatalistId = 'group-member-effort-presets'

function strategyLabel(s: string): string {
  const key = {
    failover: 'group.strategy.failover',
    random: 'group.strategy.random',
    round_robin: 'group.strategy.roundRobin'
  }[s]
  return key ? t(key) : s
}

const selectOptions = computed(() => {
  const existing = new Set(members.value.map(m => m.channelModelId))
  return availableModels.value
    .filter((am: any) => !existing.has(am.id))
    .map((am: any) => ({ value: am.id, label: `${am.modelName} (${am.channelName || ''})` }))
})

function isMemberUnavailable(m: ModelGroupMember): boolean {
  return m.channelEnabled !== 1 || m.apiKeyAvailable === 0
}

function formatRespTime(ms: number): string {
  return (ms / 1000).toFixed(2) + 's'
}

async function loadData() {
  const id = Number(route.params.id)
  loading.value = true
  try {
    const res = await groupApi.get(id)
    group.value = res.data.group
    members.value = res.data.members.sort((a, b) => a.sortOrder - b.sortOrder)
    originalMemberIds.value = members.value.map(m => m.id)
    isDirty.value = false
    availableModels.value = res.data.availableModels
    usedByModels.value = res.data.usedByModels || []
  } catch (e: any) {
    if (e.status === 404) {
      showToast(t('group.detail.notFound'), { type: 'error' })
      return
    }
    showToast(e.message || t('error.loadFailed'), { type: 'error' })
  } finally {
    loading.value = false
  }
}

async function addMembers() {
  if (!selectedModelIds.value.length || !group.value?.id) return
  try {
    const res = await groupApi.addMembers(group.value.id, selectedModelIds.value)
    if (res.data.success) {
      selectedModelIds.value = []
      await loadData()
    } else {
      showToast(res.data.error || t('error.unknown'), { type: 'error' })
    }
  } catch (e: any) {
    showToast(e.message || t('error.unknown'), { type: 'error' })
  }
}

function removeMember(member: ModelGroupMember) {
  openDialog({
    title: t('common.confirmDelete'),
    message: t('group.detail.removeConfirm').replace('{name}', member.channelModelName || ''),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        // 与入口模型关联页一致：先持久化未保存的排序，避免刷新丢序
        if (!(await persistOrder())) return
        const res = await groupApi.removeMember(member.id)
        if (res.data.success) {
          await loadData()
        } else {
          showToast(res.data.error || t('error.unknown'), { type: 'error' })
        }
      } catch (e: any) {
        showToast(e.message || t('error.unknown'), { type: 'error' })
      }
    }
  })
}

/** 熔断协议 slug → 展示文案（未知值原样回退显示） */
const PROTOCOL_LABEL_KEYS: Record<string, string> = {
  'openai-chat': 'model.rels.protocolOpenaiChat',
  'anthropic-messages': 'model.rels.protocolAnthropicMessages',
  'openai-responses': 'model.rels.protocolOpenaiResponses',
  'embeddings': 'model.rels.protocolEmbeddings'
}

function protocolLabel(protocol: string): string {
  const key = PROTOCOL_LABEL_KEYS[protocol]
  return key ? t(key) : protocol
}

/** 探测说明气泡状态：visible 是否显示；pos 为 fixed 定位坐标（基于图标位置计算）及方位 */
const probeHintVisible = ref(false)
const probeHintPos = ref({ x: 0, y: 0, below: true })
/** 气泡内展示的成员（最近一次探测信息来自该行） */
const probeHintMember = ref<ModelGroupMember | null>(null)
/** 气泡根元素：用于区分「气泡内滚动」与「页面滚动」，避免内部滚动误关闭气泡 */
const probeHintRef = ref<HTMLElement | null>(null)

/**
 * 点击问号图标切换探测说明气泡。
 * 主流程：若气泡已显示则关闭；否则记录该行成员（气泡内展示其最近一次探测信息）并取图标位置，
 * 顶部空间不足时显示在下方，然后打开气泡。
 */
function toggleProbeHint(e: MouseEvent, member: ModelGroupMember) {
  e.stopPropagation()
  if (probeHintVisible.value) {
    probeHintVisible.value = false
    return
  }
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const below = rect.top < 64
  probeHintPos.value = {
    x: rect.left + rect.width / 2,
    y: below ? rect.bottom + 8 : rect.top - 8,
    below
  }
  probeHintMember.value = member
  probeHintVisible.value = true
}

/** 关闭气泡（点击气泡外任意位置 / 滚动 / 窗口缩放时触发；气泡内部滚动不关闭） */
function closeProbeHint(e?: Event) {
  if (e && e.type === 'scroll' && e.target instanceof Node && probeHintRef.value?.contains(e.target)) {
    return
  }
  probeHintVisible.value = false
}

// 气泡打开期间挂载全局关闭监听，关闭后移除；组件卸载时确保监听清理。
watch(probeHintVisible, (visible) => {
  if (visible) {
    document.addEventListener('click', closeProbeHint)
    document.addEventListener('scroll', closeProbeHint, true)
    window.addEventListener('resize', closeProbeHint)
  } else {
    document.removeEventListener('click', closeProbeHint)
    document.removeEventListener('scroll', closeProbeHint, true)
    window.removeEventListener('resize', closeProbeHint)
  }
})

function recoverMember(member: ModelGroupMember) {
  const cmID = member.channelModelId
  if (!cmID) return
  openDialog({
    title: t('model.rels.recoverTitle'),
    message: t('group.detail.recoverConfirm'),
    type: 'confirm',
    confirmClass: 'btn-warning',
    onConfirm: async () => {
      try {
        const res = await groupApi.clearChannelModelCircuitBreaker(cmID)
        if (res.data.success) {
          await loadData()
        } else {
          openDialog({ title: t('model.rels.recoverFailed'), message: res.data.error || t('error.unknown') })
        }
      } catch (e: any) {
        openDialog({ title: t('model.rels.recoverFailed'), message: e.message })
      }
    }
  })
}

async function updateWeight(member: ModelGroupMember, raw: string) {
  const n = Math.floor(Number(raw))
  if (!Number.isFinite(n) || n < 1) {
    showToast(t('group.detail.weightInvalid'), { type: 'warning' })
    return
  }
  const weight = Math.min(n, 1000)
  try {
    const res = await groupApi.updateMember(member.id, { weight })
    if (res.data.success) {
      member.weight = weight
    } else {
      showToast(res.data.error || t('error.unknown'), { type: 'error' })
    }
  } catch (e: any) {
    showToast(e.message || t('error.unknown'), { type: 'error' })
  }
}

async function updateEffort(member: ModelGroupMember, value: string) {
  const effort = value.trim() || null
  try {
    const res = await groupApi.updateMember(member.id, { reasoningEffort: effort })
    if (res.data.success) {
      member.reasoningEffort = effort
    } else {
      showToast(res.data.error || t('error.unknown'), { type: 'error' })
    }
  } catch (e: any) {
    showToast(e.message || t('error.unknown'), { type: 'error' })
  }
}

function initSortable() {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
  const tbody = tbodyRef.value
  if (!tbody) return
  sortableInstance = new Sortable(tbody, {
    handle: '.drag-handle',
    animation: 100,
    forceFallback: true,
    fallbackClass: 'sortable-fallback',
    fallbackTolerance: 3,
    delay: 0,
    delayOnTouchOnly: true,
    onEnd: (evt: { oldIndex?: number; newIndex?: number }) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined || evt.oldIndex === evt.newIndex) return
      const next = [...members.value]
      const [moved] = next.splice(evt.oldIndex, 1)
      next.splice(evt.newIndex, 0, moved)
      _skipSortableReinit = true
      members.value = next
      isDirty.value = members.value.map(m => m.id).join(',') !== originalMemberIds.value.join(',')
    }
  })
}

async function persistOrder(): Promise<boolean> {
  if (!isDirty.value) return true
  isSaving.value = true
  try {
    const res = await groupApi.updateMembersSort(members.value.map(m => m.id))
    if (res.data.success) {
      isDirty.value = false
      return true
    }
    showToast(res.data.error || t('error.unknown'), { type: 'error' })
    return false
  } catch (e: any) {
    showToast(e.message || t('error.unknown'), { type: 'error' })
    return false
  } finally {
    isSaving.value = false
  }
}

async function saveOrder() {
  if (!(await persistOrder())) return
  await loadData()
}

let _skipSortableReinit = false
watch(members, () => {
  if (_skipSortableReinit) { _skipSortableReinit = false; return }
  nextTick(() => initSortable())
})

watch(() => route.params.id, async (newId, oldId) => {
  if (newId === oldId) return
  selectedModelIds.value = []
  await loadData()
  await nextTick()
  initSortable()
})

onMounted(async () => {
  await loadData()
  await nextTick()
  initSortable()
})

onBeforeUnmount(() => {
  sortableInstance?.destroy()
  sortableInstance = null
  closeProbeHint()
})
</script>

<style scoped>
.header-actions { display: flex; gap: 8px; }
.badge-strategy {
  margin-left: 10px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 10px;
  background: rgba(88, 166, 255, 0.15);
  color: var(--accent-blue, #58a6ff);
}
.badge-sticky {
  margin-left: 6px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 10px;
  background: rgba(63, 185, 80, 0.15);
  color: #3fb950;
}
.used-by {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 8px 16px;
  color: var(--text-muted);
  font-size: 12px;
  border-bottom: 1px solid var(--border-color);
}
.used-by-link {
  color: var(--accent-blue);
  text-decoration: none;
}
.used-by-link:hover { text-decoration: underline; }
.action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.action-bar .strategy-hint {
  margin-left: auto;
}
.strategy-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-muted);
  font-size: 12px;
}
.page-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 13px;
}
table td { vertical-align: middle; }
.channel-cell { white-space: nowrap; }
.row-disabled { opacity: 0.5; background-color: var(--bg-muted); }
.text-disabled { color: var(--text-muted); text-decoration: line-through; }
.text-muted { color: var(--text-muted); font-size: 12px; }
.badge-disabled, .badge-no-key {
  margin-left: 8px;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
}
.badge-disabled { background: var(--text-muted); color: var(--bg-primary); }
.badge-no-key { background: color-mix(in srgb, var(--accent-yellow) 20%, transparent); color: var(--accent-yellow); }
.badge-broken {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
  background: rgba(248, 81, 73, 0.12);
  color: #f85149;
  border: 1px solid rgba(248, 81, 73, 0.3);
}
/* 熔断态：徽章 + 探测气泡入口 + 解除按钮横向排列 */
.cb-broken {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.cb-hint {
  display: inline-flex;
  align-items: center;
  padding: 2px 4px;
  cursor: pointer;
  color: var(--text-muted);
}
.cb-hint-icon {
  transition: opacity 0.15s ease;
  opacity: 0.7;
}
.cb-hint:hover .cb-hint-icon { opacity: 1; }
.cb-recover-btn {
  padding: 1px 8px;
  font-size: 11px;
  white-space: nowrap;
}
/* 性能列：TTFT / 生成速度（与入口模型关联页同款读法） */
.resp-time {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.resp-time-none {
  color: var(--text-muted);
  font-size: 12px;
}
.sample-count {
  color: var(--text-muted);
  font-size: 11px;
  margin-left: 2px;
}

/* 探测说明气泡：fixed 定位挂载到 body，z-index 高于页面层级 */
.probe-hint-pop {
  position: fixed;
  z-index: 3000;
  max-width: 280px;
  padding: 8px 12px;
  border-radius: 6px;
  background: rgba(30, 30, 35, 0.95);
  color: #f0f0f0;
  font-size: 12px;
  line-height: 1.5;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  transform: translate(-50%, -100%);
  pointer-events: auto;
  white-space: normal;
}
.probe-hint-pop.below { transform: translate(-50%, 0); }
.probe-hint-probe {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid rgba(255, 255, 255, 0.15);
}
.probe-hint-title {
  font-weight: 600;
  margin-bottom: 2px;
}
.probe-hint-row {
  display: flex;
  gap: 6px;
  margin-top: 2px;
}
.probe-hint-label,
.probe-hint-detail-label {
  flex: none;
  color: #a3a3ab;
}
.probe-hint-detail-label { margin-top: 4px; }
.probe-hint-detail {
  margin: 2px 0 0;
  padding: 6px;
  max-height: 180px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  background: rgba(0, 0, 0, 0.28);
  border-radius: 4px;
  font-size: 11px;
  font-family: inherit;
  line-height: 1.45;
}
.probe-hint-empty {
  margin-top: 4px;
  color: #a3a3ab;
}
.weight-input {
  width: 76px;
  font-size: 12px;
  padding: 3px 6px;
}
.effort-select {
  font-size: 12px;
  padding: 3px 6px;
  border-radius: 4px;
  min-width: 90px;
  max-width: 130px;
}
.drag-handle {
  cursor: grab;
  color: var(--text-muted);
  user-select: none;
  font-size: 18px;
  line-height: 1;
  touch-action: none;
  margin-right: 6px;
}
.drag-handle:active { cursor: grabbing; }
.input-tags { display: inline-flex; gap: 3px; white-space: nowrap; }
.input-tag {
  display: inline-flex;
  align-items: center;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
  line-height: 1.5;
  text-transform: lowercase;
}
.input-tag--text { background: rgba(88, 166, 255, 0.12); color: var(--accent-blue, #58a6ff); }
.input-tag--image { background: rgba(46, 160, 67, 0.12); color: #2ea043; }
.input-tag--ctx { background: rgba(198, 120, 221, 0.12); color: var(--accent-purple, #c678dd); text-transform: none; }
.empty-cell { text-align: center; color: var(--text-muted); padding: 40px; }
.sortable-fallback { opacity: 0.8; background-color: var(--bg-card); box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12); }
</style>
