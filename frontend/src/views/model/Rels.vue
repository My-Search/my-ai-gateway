<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        {{ t('model.rels.title').replace('{name}', model?.modelName || '') }}
        <span v-if="model" class="mode-badge" :class="`mode-${model.relMode || 'self_add'}`">
          {{ (model.relMode || 'self_add') === 'inherit' ? t('model.rels.modeInherit') : t('model.rels.modeSelfAdd') }}
        </span>
      </div>
      <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>

    <div class="action-bar">
      <!-- 左侧：模式相关控件（自添加=添加/排序；继承=只读提示；源选择器独立显示） -->
      <div class="left">
        <!-- 自添加模式：添加 + 排序 -->
        <template v-if="uiMode === 'self_add'">
          <SearchableSelect
            v-model="selectedModelIds"
            :options="selectOptions"
            :placeholder="t('model.rels.selectModel')"
            :multiple="true"
            :width="300"
            :dropdown-width="500"
          />
          <button class="btn btn-primary btn-sm" :disabled="selectedModelIds.length === 0" @click="addRel">
            <SvgIcon name="link" :size="14" /> {{ t('model.rels.addRel') }}
          </button>
          <button v-if="isDirty" class="btn btn-primary btn-sm" :disabled="isSaving" @click="saveOrder">
            <SvgIcon name="check" :size="14" /> {{ isSaving ? t('common.saving') : t('model.rels.saveOrder') }}
          </button>
        </template>

        <!-- 继承模式：只读提示 -->
        <template v-else>
          <span class="readonly-tip">
            <SvgIcon name="info" :size="14" /> {{ t('model.rels.inheritReadonlyTip') }}
          </span>
        </template>

        <!-- 继承模式下：显示源名 + 改源按钮（独立于源选择器状态） -->
        <template v-if="currentMode === 'inherit' && inheritFromModelName && !showSourcePicker">
          <span class="source-divider">|</span>
          <span class="source-label">{{ t('model.rels.inheritFrom') }}:</span>
          <strong class="source-name">{{ inheritFromModelName }}</strong>
          <code class="badge-readonly">{{ t('model.rels.readonly') }}</code>
          <button class="btn btn-sm btn-secondary" :disabled="switchingMode" @click="openSourcePicker">
            <SvgIcon name="edit" :size="12" /> {{ t('model.rels.changeSource') }}
          </button>
        </template>

        <!-- 源选择器（首次切换或修改源时显示，独立于 currentMode） -->
        <template v-if="showSourcePicker">
          <span class="source-divider">|</span>
          <SearchableSelect
            v-model="pendingSourceId"
            :options="inheritableOptions"
            :placeholder="t('model.rels.selectInheritSource')"
            :width="240"
          />
          <button class="btn btn-sm btn-primary" :disabled="!pendingSourceId || switchingMode" @click="confirmInheritSource">
            <SvgIcon name="check" :size="12" /> {{ t('model.rels.applySource') }}
          </button>
          <button class="btn btn-sm btn-secondary" :disabled="switchingMode" @click="cancelSourcePicker">
            <SvgIcon name="x" :size="12" /> {{ t('common.cancel') }}
          </button>
        </template>
      </div>

      <!-- 右侧：模式切换（始终可见） -->
      <div class="right">
        <span class="mode-switch-label">{{ t('model.rels.modeSwitch') }}</span>
        <div class="mode-tabs" role="tablist">
          <button
            class="mode-tab"
            :class="{ active: uiMode === 'self_add' }"
            :disabled="switchingMode"
            @click="onSwitchMode('self_add')"
            role="tab"
            :title="t('model.rels.modeSelfAdd')"
          >
            <SvgIcon name="list" :size="14" /> {{ t('model.rels.modeSelfAdd') }}
          </button>
          <button
            class="mode-tab"
            :class="{ active: uiMode === 'inherit' }"
            :disabled="switchingMode"
            @click="onSwitchMode('inherit')"
            role="tab"
            :title="t('model.rels.modeInherit')"
          >
            <SvgIcon name="link" :size="14" /> {{ t('model.rels.modeInherit') }}
          </button>
        </div>
      </div>
    </div>

    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>{{ t('model.rels.sort') }}</th>
            <th>{{ t('model.rels.channel') }}</th>
            <th>{{ t('model.rels.model') }}</th>
            <th>{{ t('model.rels.inputTypes') }}</th>
            <th>{{ t('model.rels.responseTime') }}</th>
            <th>{{ t('model.rels.reasoningEffort') }}</th>
            <th>{{ t('model.rels.actions') }}</th>
          </tr>
        </thead>
        <tbody ref="tbodyRef">
          <tr v-for="(rel, index) in rels" :key="rel.id" :data-index="index" :class="{ 'row-disabled': rel.channelEnabled !== 1 }">
            <td>
              <span v-if="currentMode === 'self_add'" class="drag-handle" :title="t('model.rels.dragSort')">≡</span>
              <span v-else class="sort-index">{{ index + 1 }}</span>
            </td>
            <td>
              <span :class="{ 'text-disabled': rel.channelEnabled !== 1 }">{{ rel.channelName }}</span>
              <span v-if="rel.channelEnabled !== 1" class="badge badge-disabled">{{ t('common.disabled') }}</span>
            </td>
            <td>
              <code class="model-tag" :class="{ 'text-disabled': rel.channelEnabled !== 1 }">{{ rel.channelModelName }}</code>
            </td>
            <td>
              <span v-if="rel.input" class="input-tags">
                <span v-for="type in (rel.input || '').split(',')" :key="type" class="input-tag" :class="'input-tag--' + type">{{ type }}</span>
              </span>
              <span v-else class="text-muted">text</span>
            </td>
            <td>
              <span v-if="rel.ttftMs != null" class="resp-time">
                {{ formatRespTime(rel.ttftMs) }}
                <span v-if="rel.sampleCount != null" class="sample-count">({{ rel.sampleCount }})</span>
              </span>
              <span v-else class="resp-time-none">{{ t('model.rels.noData') }}</span>
            </td>
            <td>
              <select
                v-if="currentMode === 'self_add'"
                class="form-control effort-select"
                :value="rel.reasoningEffort ?? ''"
                @change="updateEffort(rel, ($event.target as HTMLSelectElement).value)"
              >
                <option value="">{{ t('model.rels.effortDefault') }}</option>
                <option value="low">low</option>
                <option value="medium">medium</option>
                <option value="high">high</option>
                <option value="xhigh">xhigh</option>
                <option value="max">max</option>
              </select>
              <span v-else class="text-muted">
                {{ rel.reasoningEffort ? effortLabel(rel.reasoningEffort) : '--' }}
              </span>
            </td>
            <td>
              <button v-if="currentMode === 'self_add'" class="btn btn-sm btn-danger" @click="removeRel(rel)"><SvgIcon name="trash" :size="14" /> {{ t('model.rels.delete') }}</button>
              <span v-else class="text-muted">--</span>
            </td>
          </tr>
          <tr v-if="!rels.length">
            <td colspan="7" style="text-align:center;color:var(--text-muted);padding:40px;">{{ t('model.rels.noRels') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- 模式切换二次确认 -->
  <Dialog
    v-model="switchDialog.visible"
    :title="switchDialog.title"
    :type="switchDialog.type"
    :confirm-class="switchDialog.confirmClass"
    @confirm="onSwitchDialogConfirm"
  >
    {{ switchDialog.message }}
  </Dialog>

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
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { modelApi, type CustomModel, type ModelChannelRel, type RelMode } from '@/api/model'
import SearchableSelect from '@/components/common/SearchableSelect.vue'
import Dialog from '@/components/common/Dialog.vue'
import Sortable from 'sortablejs'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const model = ref<CustomModel | null>(null)
const rels = ref<ModelChannelRel[]>([])
const availableModels = ref<any[]>([])
const inheritableModels = ref<CustomModel[]>([])
const inheritFromModelName = ref<string | null>(null)
const selectedModelIds = ref<number[]>([])
const showSourcePicker = ref(false)
// 用 0 而非 null：SearchableSelect 的 modelValue 类型不允许 null
const pendingSourceId = ref<number>(0)
const switchingMode = ref(false)
const currentMode = ref<RelMode>('self_add')

const tbodyRef = ref<HTMLElement | null>(null)
let sortableInstance: Sortable | null = null

const isDirty = ref(false)
const originalRelIds = ref<number[]>([])
const isSaving = ref(false)

function formatRespTime(ms: number): string {
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's'
  return ms + 'ms'
}

function effortLabel(value: string): string {
  return value // 直接显示原始值 low/medium/high/xhigh/max
}

/* ---------- Dialog state (common) ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref(t('common.prompt'))
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
const dialogConfirmClass = ref('btn-primary')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  confirmClass?: string
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? t('common.prompt')
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogConfirmClass.value = opts.confirmClass ?? 'btn-primary'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}

/* ---------- Switch-mode confirm dialog ---------- */
type SwitchMode = RelMode | null
const switchDialog = ref<{
  visible: boolean
  title: string
  message: string
  type: 'alert' | 'confirm'
  confirmClass: string
  pending: SwitchMode
}>({
  visible: false,
  title: '',
  message: '',
  type: 'confirm',
  confirmClass: 'btn-primary',
  pending: null
})

function openSwitchConfirm(mode: RelMode) {
  let msg = ''
  let cls = 'btn-primary'
  if (mode === 'inherit') {
    msg = t('model.rels.switchToInheritConfirm')
    cls = 'btn-warning'
  } else {
    msg = t('model.rels.switchToSelfAddConfirm')
  }
  switchDialog.value = {
    visible: true,
    title: t('model.rels.switchModeTitle'),
    message: msg,
    type: 'confirm',
    confirmClass: cls,
    pending: mode
  }
}

function onSwitchDialogConfirm() {
  const target = switchDialog.value.pending
  switchDialog.value.pending = null
  if (target) doSetMode(target)
}
/* ------------------------------ */

const selectOptions = computed(() => {
  // Filter out already-linked models
  const existingIds = new Set(rels.value.map(r => r.channelModelId))
  return availableModels.value
    .filter(am => !existingIds.has(am.id))
    .map(am => ({
      value: am.id,
      label: `${am.modelName} (${am.channelName || ''})`
    }))
})

const inheritableOptions = computed(() => {
  return inheritableModels.value.map(m => ({
    value: m.id!,
    label: m.modelName
  }))
})

/**
 * UI 显示用的模式：选源过程中临时切到 inherit 形态，让"添加关联"按钮消失。
 * - currentMode 表示后端的真实模式
 * - uiMode 表示 UI 应该呈现什么形态
 */
const uiMode = computed<RelMode>(() => {
  if (showSourcePicker.value) return 'inherit'
  return currentMode.value
})

async function loadData() {
  const id = Number(route.params.id)
  try {
    const res = await modelApi.getRels(id)
    model.value = res.data.model
    rels.value = res.data.rels.sort((a, b) => a.sortOrder - b.sortOrder)
    originalRelIds.value = rels.value.map(r => r.id)
    isDirty.value = false
    availableModels.value = res.data.availableModels
    inheritFromModelName.value = res.data.inheritFromModelName ?? null
    const mode = (model.value.relMode as RelMode) || 'self_add'
    currentMode.value = mode
  } catch (e: any) {
    // 404：模型被删，跳回列表（不能重试）
    if (e.status === 404) {
      openDialog({ title: t('error.loadFailed'), message: e.message, type: 'alert' })
      router.push('/admin/model/list')
      return
    }
    // 其它错误（500 / 网络）：留页 + 提供重试入口
    // 避免像之前那样"加载失败就静默弹回"，让用户能看到错误并主动重试
    openDialog({
      title: t('error.loadFailed'),
      message: `${e.message}\n\n点击「确定」重新加载，点击「取消」留在当前页。`,
      type: 'confirm',
      onConfirm: () => loadData()
    })
  }
}

async function loadInheritableModels() {
  const id = Number(route.params.id)
  try {
    const res = await modelApi.getInheritableModels(id)
    inheritableModels.value = res.data
  } catch (e: any) {
    // 静默失败：仅在用户进入继承源选择时才需要
    inheritableModels.value = []
  }
}

async function addRel() {
  if (selectedModelIds.value.length === 0) return
  if (currentMode.value !== 'self_add') return
  const id = Number(route.params.id)
  try {
    const res = await modelApi.batchAddRels(id, selectedModelIds.value)
    if (res.data.success) {
      selectedModelIds.value = []
      await loadData()
    } else {
      openDialog({ title: t('model.rels.addFailed'), message: res.data.error || t('error.unknown') })
    }
  } catch (e: any) {
    openDialog({ title: t('model.rels.addFailed'), message: e.message })
  }
}

function removeRel(rel: ModelChannelRel) {
  if (currentMode.value !== 'self_add') return
  openDialog({
    title: t('common.confirmDelete'),
    message: t('model.rels.deleteConfirm'),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        const res = await modelApi.removeRel(rel.id)
        if (res.data.success) {
          await loadData()
        } else {
          openDialog({ title: t('model.rels.deleteFailed'), message: res.data.error || t('error.unknown') })
        }
      } catch (e: any) {
        openDialog({ title: t('model.rels.deleteFailed'), message: e.message })
      }
    }
  })
}

async function updateEffort(rel: ModelChannelRel, value: string) {
  const effort = value || null
  try {
    const res = await modelApi.updateRelReasoningEffort(rel.id, effort)
    if (res.data.success) {
      rel.reasoningEffort = effort
    } else {
      openDialog({ title: t('error.updateFailed'), message: res.data.error || t('error.unknown') })
    }
  } catch (e: any) {
    openDialog({ title: t('error.updateFailed'), message: e.message })
  }
}

function initSortable() {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
  // 继承模式下禁用排序
  if (currentMode.value !== 'self_add') return
  const tbody = tbodyRef.value
  if (!tbody) return

  sortableInstance = new Sortable(tbody, {
    handle: '.drag-handle',
    animation: 150,
    easing: 'cubic-bezier(0.25, 0.46, 0.45, 0.94)',
    ghostClass: 'sortable-ghost',
    dragClass: 'sortable-drag',
    onEnd: (evt) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined || evt.oldIndex === evt.newIndex) return

      const newRels = [...rels.value]
      const [moved] = newRels.splice(evt.oldIndex, 1)
      newRels.splice(evt.newIndex, 0, moved)
      rels.value = newRels

      const currentIds = rels.value.map(r => r.id)
      isDirty.value = JSON.stringify(currentIds) !== JSON.stringify(originalRelIds.value)
    }
  })
}

async function saveOrder() {
  if (currentMode.value !== 'self_add') return
  isSaving.value = true
  try {
    const sortedRelIds = rels.value.map(r => r.id)
    const res = await modelApi.batchUpdateSortOrders(sortedRelIds)
    if (res.data.success) {
      isDirty.value = false
      await loadData()
    } else {
      openDialog({ title: t('model.rels.saveFailed'), message: res.data.error || t('error.unknown') })
    }
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : String(e)
    openDialog({ title: t('model.rels.saveFailed'), message })
  } finally {
    isSaving.value = false
  }
}

/* ---------- 模式切换 ----------
 * 流程设计：
 * - 切到 self_add：弹模式确认框，确认后调 setRelMode('self_add') → 源 rels 复制为自有 rels
 * - 切到 inherit：分两种情况
 *   1) 已有继承源（model.inheritFromModelId）：弹模式确认框，确认后沿用旧源
 *   2) 没有继承源：跳过模式确认框，直接进源选择器，选源后调 setRelMode('inherit', sourceId)
 * 这样能避免"先确认模式再选源"导致的"切换没反应"假象。
 */
function onSwitchMode(target: RelMode) {
  if (switchingMode.value) return
  if (target === currentMode.value) return

  if (target === 'inherit' && !model.value?.inheritFromModelId) {
    // 首次切到 inherit：直接进源选择器
    openSourcePicker()
    return
  }
  // 其它情况：弹模式确认框
  openSwitchConfirm(target)
}

/**
 * 执行模式切换公共逻辑。
 * @param mode       目标模式
 * @param sourceId   继承源 ID（未传时自动从 model.value.inheritFromModelId 获取）
 */
async function doSetMode(mode: RelMode, sourceId?: number) {
  switchingMode.value = true
  try {
    if (sourceId === undefined && mode === 'inherit' && model.value?.inheritFromModelId) {
      sourceId = model.value.inheritFromModelId
    }
    const res = await modelApi.setRelMode(Number(route.params.id), mode, sourceId)
    if (res.data.success) {
      showSourcePicker.value = false
      pendingSourceId.value = 0
      await loadData()
    } else {
      openDialog({ title: t('error.updateFailed'), message: res.data.error || t('error.unknown') })
    }
  } catch (e: any) {
    openDialog({ title: t('error.updateFailed'), message: e?.response?.data?.error || e.message || t('error.unknown') })
  } finally {
    switchingMode.value = false
  }
}

async function openSourcePicker() {
  showSourcePicker.value = true
  pendingSourceId.value = model.value?.inheritFromModelId ?? 0
  if (inheritableModels.value.length === 0) {
    await loadInheritableModels()
  }
}

function cancelSourcePicker() {
  showSourcePicker.value = false
  pendingSourceId.value = 0
}

async function confirmInheritSource() {
  if (!pendingSourceId.value) return
  await doSetMode('inherit', pendingSourceId.value)
}

// Initialize Sortable after data is loaded and DOM is updated
watch(rels, () => {
  nextTick(() => initSortable())
})

// 模式变化时也要重新初始化（initSortable 内部会判断）
watch(currentMode, () => {
  nextTick(() => initSortable())
})

onMounted(async () => {
  await loadData()
  await nextTick()
  initSortable()
})
</script>

<style scoped>
/* Ensure all table cells are vertically centered */
table td {
  vertical-align: middle;
}

/* Row disabled state */
.row-disabled {
  opacity: 0.5;
  background-color: var(--bg-muted);
}

.text-disabled {
  color: var(--text-muted);
  text-decoration: line-through;
}

.text-muted {
  color: var(--text-muted);
  font-size: 12px;
}

.badge-disabled {
  margin-left: 8px;
  font-size: 10px;
  padding: 2px 6px;
  background: var(--text-muted);
  color: var(--bg-primary);
  border-radius: 4px;
}

/* Mode badge in title */
.mode-badge {
  margin-left: 10px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 10px;
  vertical-align: middle;
  font-weight: 500;
  letter-spacing: 0.3px;
}
.mode-badge.mode-self_add {
  background: rgba(88, 166, 255, 0.15);
  color: var(--accent-blue, #58a6ff);
}
.mode-badge.mode-inherit {
  background: rgba(210, 153, 34, 0.15);
  color: #d29922;
}

/* Mode switch (right side of action-bar) */
.mode-switch-label {
  color: var(--text-muted);
  font-size: 12px;
  margin-right: 4px;
  white-space: nowrap;
}

.mode-tabs {
  display: inline-flex;
  align-items: center;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 2px;
  gap: 2px;
}
.mode-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  background: transparent;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  border-radius: 4px;
  white-space: nowrap;
  outline: none;
  transition: background-color 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}
.mode-tab:not(.active):hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.mode-tab.active {
  background: color-mix(in srgb, var(--accent-blue) 18%, transparent);
  color: var(--accent-blue);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--accent-blue) 35%, transparent);
}
.mode-tab:focus-visible {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-blue) 55%, transparent);
}
.mode-tab.active:focus-visible {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-blue) 70%, transparent);
}
.mode-tab:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.source-divider {
  color: var(--text-muted);
  margin: 0 4px;
  user-select: none;
}
.source-label {
  color: var(--text-muted);
  font-size: 13px;
}
.source-name {
  font-size: 13px;
  color: var(--text-primary);
}
.badge-readonly {
  font-size: 10px;
  padding: 2px 6px;
  background: var(--text-muted);
  color: var(--bg-primary);
  border-radius: 4px;
  font-family: inherit;
}

.readonly-tip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

/* 继承模式下显示静态序号 */
.sort-index {
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* SortableJS drag states */
.sortable-ghost {
  opacity: 0.35;
  background-color: color-mix(in srgb, var(--accent-blue) 10%, transparent);
}

.sortable-ghost td {
  box-shadow: inset 0 2px 0 var(--accent-blue);
}

.sortable-drag {
  opacity: 0.8;
  background-color: var(--bg-card);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

.sortable-drag td {
  cursor: grabbing;
}

.effort-select {
  font-size: 12px;
  padding: 3px 6px;
  border-radius: 4px;
  min-width: 90px;
  max-width: 120px;
}

.effort-select:focus {
  border-color: var(--accent-blue);
  outline: none;
}

.drag-handle {
  cursor: grab;
  color: var(--text-muted);
  user-select: none;
  font-size: 18px;
  line-height: 1;
  touch-action: none; /* Prevent touch scroll on handle */
}

.drag-handle:active {
  cursor: grabbing;
}

/* Input type tags */
.input-tags {
  display: inline-flex;
  gap: 3px;
  flex-wrap: nowrap;
  white-space: nowrap;
}
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
.input-tag--text {
  background: rgba(88, 166, 255, 0.12);
  color: var(--accent-blue, #58a6ff);
}
.input-tag--image {
  background: rgba(46, 160, 67, 0.12);
  color: #2ea043;
}
.text-muted {
  color: var(--text-muted);
  font-size: 12px;
}

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
</style>
