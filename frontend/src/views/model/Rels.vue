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
          <button
            class="btn btn-sm"
            :class="selectionMode && selectedCount > 0 ? 'btn-danger' : 'btn-secondary'"
            :disabled="!selectionMode && rels.length === 0"
            @click="onBatchSelectClick"
          >
            <SvgIcon :name="selectionMode ? (selectedCount > 0 ? 'trash' : 'x') : 'list'" :size="14" />
            {{ !selectionMode
              ? t('model.rels.batchSelect')
              : (selectedCount > 0 ? `${t('model.rels.removeSelected')} (${selectedCount})` : t('model.rels.cancelBatchSelect')) }}
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
          <!-- 源名可点击：点击跳到父模型关联页 -->
          <router-link
            v-if="model?.inheritFromModelId"
            :to="'/admin/model/rels/' + model.inheritFromModelId"
            class="source-name source-name-link"
            :title="t('model.rels.goToParentRels')"
          >
            {{ inheritFromModelName }}
          </router-link>
          <strong v-else class="source-name">{{ inheritFromModelName }}</strong>
          <button
            class="icon-action"
            :disabled="switchingMode"
            @click="openSourcePicker"
            :title="t('model.rels.changeSource')"
            :aria-label="t('model.rels.changeSource')"
          >
            <SvgIcon name="edit" :size="12" />
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

    <!-- Loading state -->
    <div v-if="loading" class="page-loading">
      <LoadingSpinner :size="18" :text="t('common.loading')" />
    </div>

    <template v-else>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th v-if="selectionMode" class="col-check">
              <input
                type="checkbox"
                class="rel-checkbox"
                :checked="isAllSelected"
                :indeterminate="isIndeterminate"
                :disabled="!rels.length"
                :aria-label="t('model.rels.selectAll')"
                :title="t('model.rels.selectAll')"
                @change="toggleSelectAll"
              />
            </th>
            <th>{{ t('model.rels.sort') }}</th>
            <th>{{ t('model.rels.channel') }}</th>
            <th>{{ t('model.rels.model') }}</th>
            <th>{{ t('model.rels.inputTypes') }}</th>
            <th>{{ t('model.rels.responseTime') }}</th>
            <th>{{ t('model.rels.outputSpeed') }}</th>
            <th>{{ t('model.rels.circuitBreaker') }}</th>
            <th>{{ t('model.rels.reasoningEffort') }}</th>
            <th>{{ t('model.rels.actions') }}</th>
          </tr>
        </thead>
        <tbody ref="tbodyRef">
          <tr
            v-for="(rel, index) in rels"
            :key="rel.id"
            :data-index="index"
            :class="{ 'row-disabled': isRelUnavailable(rel), 'row-selected': selectedRelIds.has(rel.id) }"
          >
            <td v-if="selectionMode" class="col-check">
              <input
                type="checkbox"
                class="rel-checkbox"
                :checked="selectedRelIds.has(rel.id)"
                :aria-label="t('model.rels.selectRow')"
                :title="t('model.rels.selectRow')"
                @change="toggleSelectRel(rel)"
              />
            </td>
            <td>
              <span v-if="currentMode === 'self_add'" class="drag-handle" :title="t('model.rels.dragSort')">≡</span>
              <span v-else class="sort-index">{{ index + 1 }}</span>
            </td>
            <td>
              <span :class="{ 'text-disabled': isRelUnavailable(rel) }">{{ rel.channelName }}</span>
              <span v-if="rel.channelEnabled !== 1" class="badge badge-disabled">{{ t('common.disabled') }}</span>
              <span v-if="rel.apiKeyAvailable === 0" class="badge badge-no-key">{{ t('model.rels.noApiKey') }}</span>
            </td>
            <td>
              <code class="model-tag" :class="{ 'text-disabled': isRelUnavailable(rel) }">{{ rel.channelModelName }}</code>
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
              <span v-if="rel.outputSpeed != null" class="resp-time">
                {{ rel.outputSpeed.toFixed(1) }} <span class="sample-count">tokens/s</span>
              </span>
              <span v-else class="resp-time-none">{{ t('model.rels.noData') }}</span>
            </td>
            <td>
              <span v-if="rel.circuitBroken === 1" class="cb-broken">
                <span class="badge badge-broken">
                  {{ t('model.rels.broken') }}
                  <template v-if="rel.circuitBrokenScope === 'channel'">（{{ t('model.rels.brokenChannel') }}）</template>
                  <template v-else-if="rel.circuitBrokenScope === 'model'">（{{ t('model.rels.brokenModel') }}）</template>
                  <template v-else-if="rel.circuitBrokenScope === 'both'">（{{ t('model.rels.brokenBoth') }}）</template>
                </span>
                <span class="cb-hint" @click="toggleProbeHint($event, rel)">
                  <SvgIcon name="question" :size="12" class="cb-hint-icon" />
                </span>
                <button class="btn btn-sm btn-secondary cb-recover-btn" @click="recoverRel(rel)">
                  <SvgIcon name="check" :size="12" /> {{ t('model.rels.recover') }}
                </button>
              </span>
              <span v-else class="text-muted">{{ t('model.rels.brokenNone') }}</span>
            </td>
            <td>
              <input
                v-if="currentMode === 'self_add'"
                class="form-control effort-select"
                type="text"
                :value="rel.reasoningEffort ?? ''"
                :list="effortDatalistId"
                :placeholder="t('model.rels.effortCustomPlaceholder')"
                :title="t('model.rels.effortCustomHint')"
                @change="updateEffort(rel, ($event.target as HTMLInputElement).value)"
              />
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
            <td :colspan="selectionMode ? 10 : 9" style="text-align:center;color:var(--text-muted);padding:40px;">{{ t('model.rels.noRels') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <!-- 思考强度预设选项：输入框仍可输入任意自定义值 -->
    <datalist :id="effortDatalistId">
      <option v-for="e in EFFORT_PRESETS" :key="e" :value="e" />
    </datalist>
    </template>
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

  <!-- 探测机制说明气泡：点击问号图标切换显示；含该关联最近一次探测时间与结果 -->
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
        <template v-if="probeHintRel?.circuitBrokenLastProbeAt">
          <div class="probe-hint-title">{{ t('model.rels.lastProbeTitle') }}</div>
          <div class="probe-hint-row">
            <span class="probe-hint-label">{{ t('model.rels.lastProbeTime') }}</span>
            <span>{{ formatLocalDateTimeFull(probeHintRel.circuitBrokenLastProbeAt) }}</span>
          </div>
          <div v-if="probeHintRel.circuitBrokenLastProbeStatus != null" class="probe-hint-row">
            <span class="probe-hint-label">{{ t('model.rels.lastProbeStatus') }}</span>
            <span>{{ probeHintRel.circuitBrokenLastProbeStatus }}</span>
          </div>
          <template v-if="probeHintRel.circuitBrokenLastProbeDetail">
            <div class="probe-hint-detail-label">{{ t('model.rels.lastProbeDetail') }}</div>
            <pre class="probe-hint-detail">{{ probeHintRel.circuitBrokenLastProbeDetail }}</pre>
          </template>
        </template>
        <div v-else class="probe-hint-empty">{{ t('model.rels.lastProbeNone') }}</div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { useToast } from '@/composables/useToast'
import { modelApi, type CustomModel, type ModelChannelRel, type RelMode } from '@/api/model'
import { formatLocalDateTimeFull } from '@/utils/date'
import SearchableSelect from '@/components/common/SearchableSelect.vue'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import Sortable from 'sortablejs'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { visible: dialogVisible, title: dialogTitle, message: dialogMessage, type: dialogType, confirmClass: dialogConfirmClass, onConfirm: onDialogConfirm, open: openDialog } = useDialog()
const { showToast } = useToast()
const model = ref<CustomModel | null>(null)
const rels = ref<ModelChannelRel[]>([])
const loading = ref(true)
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

/* ---------- 思考强度（支持自定义输入） ---------- */
/** 常用思考强度预设，供 datalist 下拉快速选择；输入框允许任意自定义值（如 deepseek-reasoner:medium） */
const EFFORT_PRESETS = ['low', 'medium', 'high', 'xhigh', 'max'] as const
/** 所有行共用的 datalist id（每页只有一个关联列表，固定 id 即可） */
const effortDatalistId = 'rel-effort-presets'

/* ---------- 多选删除 ---------- */
/**
 * 进入式多选：点击「多选删除」进入（勾选列随之显示），
 * 取消多选 / 删除成功 / 数据重载 / 打开源选择器时退出，勾选列恢复隐藏。
 */
const selectionMode = ref(false)
const selectedRelIds = ref<Set<number>>(new Set())

const selectedCount = computed(() => selectedRelIds.value.size)

const isAllSelected = computed(() =>
  rels.value.length > 0 && rels.value.every(r => selectedRelIds.value.has(r.id))
)

const isIndeterminate = computed(() =>
  selectedRelIds.value.size > 0 && !isAllSelected.value
)

function toggleSelectAll(e: Event) {
  const checked = (e.target as HTMLInputElement).checked
  selectedRelIds.value = checked ? new Set(rels.value.map(r => r.id)) : new Set()
}

function toggleSelectRel(rel: ModelChannelRel) {
  const next = new Set(selectedRelIds.value)
  if (next.has(rel.id)) {
    next.delete(rel.id)
  } else {
    next.add(rel.id)
  }
  selectedRelIds.value = next
}

function clearSelection() {
  selectedRelIds.value = new Set()
}

function exitSelectionMode() {
  selectionMode.value = false
  clearSelection()
}

/** 「多选删除」按钮：未进入→进入多选；多选中且无勾选→取消多选；有勾选→移除选中 */
function onBatchSelectClick() {
  if (!selectionMode.value) {
    if (currentMode.value !== 'self_add' || rels.value.length === 0) return
    selectionMode.value = true
    return
  }
  if (selectedCount.value === 0) {
    exitSelectionMode()
    return
  }
  removeSelectedRels()
}

function removeSelectedRels() {
  if (currentMode.value !== 'self_add') return
  const ids = [...selectedRelIds.value]
  if (ids.length === 0) return
  openDialog({
    title: t('common.confirmDelete'),
    message: t('model.rels.deleteSelectedConfirm', { n: ids.length }),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        // 与单条删除一致：若已调整过顺序（未保存），先持久化，避免 loadData 刷新丢失排序
        if (!(await persistOrder())) return
        const res = await modelApi.batchRemoveRels(ids)
        if (res.data.success) {
          exitSelectionMode()
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

/** 关联不可用：渠道被禁用或无可用 API Key（行效果与禁用一致，仅标签不同） */
function isRelUnavailable(rel: ModelChannelRel): boolean {
  return rel.channelEnabled !== 1 || rel.apiKeyAvailable === 0
}

function formatRespTime(ms: number): string {
  return (ms / 1000).toFixed(2) + 's'
}

function effortLabel(value: string): string {
  return value // 直接显示原始值（预设 low/medium/high/... 或自定义输入值）
}

/** 探测说明气泡状态：visible 是否显示；pos 为 fixed 定位坐标（基于图标位置计算）及方位 */
const probeHintVisible = ref(false)
const probeHintPos = ref({ x: 0, y: 0, below: true })
/** 气泡内展示的关联（最近一次探测信息来自该行） */
const probeHintRel = ref<ModelChannelRel | null>(null)
/** 气泡根元素：用于区分「气泡内滚动」与「页面滚动」，避免内部滚动误关闭气泡 */
const probeHintRef = ref<HTMLElement | null>(null)

/**
 * 点击问号图标切换探测说明气泡。
 * 主流程：若气泡已显示则关闭；否则记录该行关联（气泡内展示其最近一次探测信息）并取图标位置，
 * 顶部空间不足时显示在下方，然后打开气泡。
 * 图标点击在事件阶段被标记 stopPropagation，避免触发 document 上的关闭监听。
 */
function toggleProbeHint(e: MouseEvent, rel: ModelChannelRel) {
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
  probeHintRel.value = rel
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
onBeforeUnmount(closeProbeHint)

function recoverRel(rel: ModelChannelRel) {
  openDialog({
    title: t('model.rels.recoverTitle'),
    message: t('model.rels.recoverConfirm'),
    type: 'confirm',
    confirmClass: 'btn-warning',
    onConfirm: async () => {
      try {
        const res = await modelApi.clearRelCircuitBreaker(rel.id)
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
  loading.value = true
  try {
    const res = await modelApi.getRels(id)
    model.value = res.data.model
    rels.value = res.data.rels.sort((a, b) => a.sortOrder - b.sortOrder)
    originalRelIds.value = rels.value.map(r => r.id)
    isDirty.value = false
    exitSelectionMode()
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
  } finally {
    loading.value = false
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
        // 若已调整过顺序（未保存），先持久化顺序，避免删除后 loadData 刷新丢失排序
        if (!(await persistOrder())) return
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

/**
 * 保存行的思考强度（支持自定义输入值）。
 * 主流程：trim 后为空则清除（存 null），否则原样保存；后端不做枚举校验，任意字符串均透传给上游。
 */
async function updateEffort(rel: ModelChannelRel, value: string) {
  const effort = value.trim() || null
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
    animation: 100,
    easing: 'cubic-bezier(0.25, 0.46, 0.45, 0.94)',
    ghostClass: 'sortable-ghost',
    dragClass: 'sortable-drag',
    forceFallback: true,
    fallbackClass: 'sortable-fallback',
    fallbackOnBody: true,
    fallbackTolerance: 3,
    delay: 0,
    delayOnTouchOnly: true,
    onEnd: (evt) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined || evt.oldIndex === evt.newIndex) return

      const newRels = [...rels.value]
      const [moved] = newRels.splice(evt.oldIndex, 1)
      newRels.splice(evt.newIndex, 0, moved)
      _skipSortableReinit = true
      rels.value = newRels

      const currentIds = rels.value.map(r => r.id)
      isDirty.value = currentIds.join(',') !== originalRelIds.value.join(',')
    }
  })
}

/**
 * 仅保存当前顺序（不重载数据），供删除等需要先持久化顺序再执行后续操作的场景复用。
 * @returns 是否保存成功
 */
async function persistOrder(): Promise<boolean> {
  if (currentMode.value !== 'self_add') return false
  if (!isDirty.value) return true // 顺序无改动，无需保存
  isSaving.value = true
  try {
    const sortedRelIds = rels.value.map(r => r.id)
    const res = await modelApi.batchUpdateSortOrders(sortedRelIds)
    if (res.data.success) {
      isDirty.value = false
      return true
    }
    openDialog({ title: t('model.rels.saveFailed'), message: res.data.error || t('error.unknown') })
    return false
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : String(e)
    openDialog({ title: t('model.rels.saveFailed'), message })
    return false
  } finally {
    isSaving.value = false
  }
}

async function saveOrder() {
  if (!(await persistOrder())) return
  await loadData()
}

/* ---------- 模式切换 ----------
 * 流程设计：
 * - 切到 self_add：弹模式确认框，确认后调 setRelMode('self_add') → 切回时恢复之前保留的自有 rels
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
      // 后端检测到循环继承时会自动解除闭环并继续切换，此处给出提示
      if (res.data.cycleBrokenModel) {
        showToast(
          t('model.rels.cycleBrokenTip').replace('{name}', res.data.cycleBrokenModel.modelName),
          { type: 'warning', duration: 4000 }
        )
      }
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
  exitSelectionMode()
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

// 仅在数据首次加载或模式切换时重建 Sortable，避免拖拽 onEnd 触发重建。
let _skipSortableReinit = false
watch(rels, () => {
  if (_skipSortableReinit) { _skipSortableReinit = false; return }
  nextTick(() => initSortable())
})

// 模式变化时也要重新初始化（initSortable 内部会判断）
watch(currentMode, () => {
  nextTick(() => initSortable())
})

// 同一路由记录（/admin/model/rels/:id）间切换时组件实例会被复用，onMounted 不会再次触发。
// 必须监听 id 变化重新加载，否则点击"前往父模型关联"后 URL 变了但页面内容仍是旧模型。
watch(() => route.params.id, async (newId, oldId) => {
  if (newId === oldId) return
  showSourcePicker.value = false
  pendingSourceId.value = 0
  selectedModelIds.value = []
  inheritableModels.value = []
  await loadData()
  await nextTick()
  initSortable()
})

onMounted(async () => {
  await loadData()
  await nextTick()
  initSortable()
})
</script>

<style scoped>
/* Page loading state */
.page-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 13px;
}

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

/* 无可用密钥标记：渠道下无启用 API Key（或全部禁用/指定 Key 不可用） */
.badge-no-key {
  margin-left: 8px;
  font-size: 10px;
  padding: 2px 6px;
  background: color-mix(in srgb, var(--accent-yellow) 20%, transparent);
  color: var(--accent-yellow);
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
/* 关联的源模型名可点击：进入父模型关联页 */
.source-name-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
  color: var(--accent-blue);
  cursor: pointer;
  text-decoration: none;
  border-bottom: 1px solid color-mix(in srgb, var(--accent-blue) 40%, transparent);
  transition: color 0.15s ease, border-color 0.15s ease;
}
.source-name-link:hover {
  color: color-mix(in srgb, var(--accent-blue) 80%, white);
  border-bottom-color: currentColor;
}
.source-name-link:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-blue) 45%, transparent);
  border-radius: 3px;
}
/* 无边框图标动作按钮：仅显示 icon，hover 时才出现浅底色 */
.icon-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 4px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  border-radius: 4px;
  cursor: pointer;
  transition: color 0.15s ease, background-color 0.15s ease;
}
.icon-action:hover:not(:disabled) {
  color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 12%, transparent);
}
.icon-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.icon-action:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-blue) 45%, transparent);
}

.readonly-tip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

/* 移动端：操作栏内的弹性布局优化。
   action-bar 已改为纵向排列，此处进一步保证：
   1) 长只读提示按整行排版并正常换行，不再被压成竖排单字；
   2) 源名/按钮等元素可换行，避免溢出卡片。 */
@media (max-width: 768px) {
  .readonly-tip {
    flex: 1 1 100%;
    align-items: flex-start;
    line-height: 1.6;
  }
  .readonly-tip .svg-icon {
    flex-shrink: 0;
    margin-top: 3px;
  }
  .source-divider {
    display: none;
  }
  .source-label,
  .source-name {
    flex-shrink: 0;
  }
  /* 触控目标放大：源名与图标按钮便于手指点按 */
  .source-name-link {
    min-height: 32px;
    padding: 0 2px;
  }
  .icon-action {
    min-width: 32px;
    min-height: 32px;
    padding: 4px;
  }
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
  will-change: transform;
  transform: translateZ(0);
}

.sortable-drag td {
  cursor: grabbing;
}

.effort-select {
  font-size: 12px;
  padding: 3px 6px;
  border-radius: 4px;
  min-width: 90px;
  max-width: 130px;
}

.effort-select::placeholder {
  color: var(--text-muted);
  opacity: 0.7;
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

/* Circuit breaker status */
.cb-broken {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

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
.cb-hint:hover .cb-hint-icon {
  opacity: 1;
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
.probe-hint-pop.below {
  transform: translate(-50%, 0);
}

/* 最近一次探测信息：时间/状态码/响应详情（响应内容原样展示） */
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
.probe-hint-detail-label {
  margin-top: 4px;
}
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

.cb-recover-btn {
  padding: 1px 8px;
  font-size: 11px;
  white-space: nowrap;
}

/* 多选列 */
th.col-check,
td.col-check {
  width: 36px;
  min-width: 36px;
  text-align: center;
}

/* 主题化复选框：appearance:none 自绘，明暗主题均使用主题变量
   （未选中=表面色+边框色；选中/半选=accent-blue 填充，与 btn-primary 一致） */
.rel-checkbox {
  appearance: none;
  -webkit-appearance: none;
  position: relative;
  width: 15px;
  height: 15px;
  margin: 0;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background-color: var(--bg-tertiary);
  cursor: pointer;
  vertical-align: middle;
  transition: background-color 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}

.rel-checkbox:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--accent-blue) 55%, var(--border-color));
}

.rel-checkbox:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-blue) 55%, transparent);
}

.rel-checkbox:checked,
.rel-checkbox:indeterminate {
  border-color: var(--accent-blue);
  background-color: var(--accent-blue);
}

.rel-checkbox:checked {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath d='M3.5 8.5l3 3 6-7' fill='none' stroke='%23fff' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-size: 11px;
  background-position: center;
  background-repeat: no-repeat;
}

.rel-checkbox:indeterminate {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath d='M3.5 8h9' fill='none' stroke='%23fff' stroke-width='2' stroke-linecap='round'/%3E%3C/svg%3E");
  background-size: 11px;
  background-position: center;
  background-repeat: no-repeat;
}

.rel-checkbox:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

/* 选中行高亮：比全局 tr:hover td 特异性更高，悬停时不丢失选中底色 */
tbody tr.row-selected td {
  background-color: color-mix(in srgb, var(--accent-blue) 8%, transparent);
}
</style>
