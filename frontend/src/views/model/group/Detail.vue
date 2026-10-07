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
            <th>{{ t('group.detail.inputTypes') }}</th>
            <th>{{ t('group.detail.contextLength') }}</th>
            <th>{{ t('group.detail.circuitBreaker') }}</th>
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
              <span v-if="member.circuitBroken === 1" class="badge badge-broken">{{ t('group.detail.broken') }}</span>
              <span v-else class="text-muted">{{ t('group.detail.brokenNone') }}</span>
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
            <td colspan="9" class="empty-cell">{{ t('group.detail.noMembers') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <datalist :id="effortDatalistId">
      <option v-for="e in EFFORT_PRESETS" :key="e" :value="e" />
    </datalist>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { useToast } from '@/composables/useToast'
import { groupApi, type ModelGroup, type ModelGroupMember } from '@/api/group'
import type { CustomModel } from '@/api/model'
import { formatTokens } from '@/utils/format'
import SearchableSelect from '@/components/common/SearchableSelect.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import Sortable from 'sortablejs'

const { t } = useI18n()
const route = useRoute()
const { open } = useDialog()
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
  open({
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
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(248, 81, 73, 0.12);
  color: #f85149;
  border: 1px solid rgba(248, 81, 73, 0.3);
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
