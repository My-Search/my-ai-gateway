<template>
  <div v-if="visible" class="modal-overlay" @click.self="handleClose">
    <div class="modal-box mcr-modal">
      <div class="modal-header">
        <div class="modal-title">
          <SvgIcon name="eye" :size="16" />
          {{ t('modelConfigRule.title') }}
        </div>
        <button class="modal-close" @click="handleClose">&times;</button>
      </div>

      <div class="mcr-desc">{{ t('modelConfigRule.desc') }}</div>

      <!-- Section 1: models.dev local cache file -->
      <div class="mcr-section">
        <div class="mcr-sync">
          <div class="mcr-sync-info">
            <div class="mcr-sync-title">{{ t('modelConfigRule.dbSection') }}</div>
            <div class="mcr-sync-line">
              <SvgIcon name="refresh" :size="13" />
              <span v-if="syncStatus">
                {{ t('modelConfigRule.cacheStatus', {
                  count: syncStatus.count,
                  time: syncStatus.updatedAt ? formatLocalDateTimeFull(syncStatus.updatedAt) : t('modelConfigRule.cacheNever')
                }) }}
              </span>
              <span v-else>{{ t('modelConfigRule.cacheUnknown') }}</span>
            </div>
            <div v-if="syncStatus?.file" class="mcr-sync-path" :title="syncStatus.path || syncStatus.file">
              {{ syncStatus.path || syncStatus.file }}
            </div>
            <div v-if="syncStatus?.lastError" class="mcr-sync-error" :title="syncStatus.lastError">
              {{ t('modelConfigRule.lastUpdateFailed', { error: syncStatus.lastError }) }}
            </div>
          </div>
          <div class="mcr-sync-actions">
            <button class="btn btn-sm btn-secondary" :disabled="reloading" @click="reloadCache"
                    :title="t('modelConfigRule.reloadHint')">
              <LoadingSpinner v-if="reloading" :size="12" />
              <SvgIcon v-else name="refresh" :size="12" />
              {{ reloading ? t('modelConfigRule.reloading') : t('modelConfigRule.reload') }}
            </button>
            <button class="btn btn-sm btn-secondary" :disabled="downloading" @click="downloadCache"
                    :title="t('modelConfigRule.downloadHint')">
              <LoadingSpinner v-if="downloading" :size="12" />
              <SvgIcon v-else name="download" :size="12" />
              {{ downloading ? t('modelConfigRule.downloading') : t('modelConfigRule.download') }}
            </button>
          </div>
        </div>
      </div>

      <!-- Section 2: custom rules -->
      <div class="mcr-section">
        <div class="mcr-section-header">
          <span class="mcr-section-title">{{ t('modelConfigRule.ruleSection') }}</span>
          <button class="btn btn-sm btn-primary" @click="openAddRule">
            <SvgIcon name="plus" :size="12" /> {{ t('modelConfigRule.addRule') }}
          </button>
        </div>

        <div v-if="loading" class="mcr-loading">{{ t('common.loading') }}</div>
        <div v-else-if="!rules.length" class="mcr-empty">{{ t('modelConfigRule.noRules') }}</div>

        <div v-else class="mcr-rule-list">
          <div v-for="rule in rules" :key="rule.id" class="mcr-rule-item">
            <div class="mcr-rule-info">
              <code class="mcr-pattern">{{ rule.pattern }}</code>
              <span v-if="rule.appendType" class="mcr-badge badge badge-info">{{ rule.appendType }}</span>
              <span v-if="rule.contextLength" class="mcr-badge badge badge-warning">ctx {{ formatTokens(rule.contextLength) }}</span>
            </div>
            <div class="mcr-rule-actions">
              <button class="mcr-icon-btn" :title="t('common.edit')" @click="openEditRule(rule)">
                <SvgIcon name="edit" :size="13" />
              </button>
              <button class="mcr-icon-btn mcr-icon-btn--danger" :title="t('common.delete')" @click="confirmDelete(rule)">
                <SvgIcon name="trash" :size="13" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Add/Edit Rule Form -->
      <div v-if="showForm" class="mcr-section mcr-form-section">
        <div class="mcr-section-header">
          <span class="mcr-section-title">{{ editingRule ? t('modelConfigRule.editRule') : t('modelConfigRule.addRule') }}</span>
        </div>

        <div class="form-group">
          <label>{{ t('modelConfigRule.pattern') }}</label>
          <input v-model="form.pattern" class="form-control" :placeholder="t('modelConfigRule.patternPlaceholder')"
                 @keydown.enter.prevent="saveRule" />
        </div>

        <!-- Input modalities -->
        <div class="form-group">
          <label>{{ t('modelConfigRule.appendType') }}</label>
          <div class="mcr-tag-input">
            <div class="mcr-tags">
              <span v-for="(tag, idx) in appendTypeTags" :key="idx" class="mcr-tag">
                {{ tag }}
                <span class="mcr-tag-remove" @click="removeAppendTypeTag(idx)">
                  <SvgIcon name="x" :size="10" />
                </span>
              </span>
              <input v-model="appendTypeInput" class="mcr-tag-input-field" placeholder="image, video, ..."
                     @keydown.enter.prevent="addAppendTypeTag" @keydown.delete.prevent="removeLastTag" />
            </div>
          </div>
          <div class="form-hint">{{ t('modelConfigRule.appendTypeHint') }}</div>
        </div>

        <!-- Context window: preset dropdown drives the value; input only editable for custom -->
        <div class="form-group">
          <label>{{ t('modelConfigRule.contextLength') }}</label>
          <div class="mcr-context-row">
            <input v-model="contextInput" type="number" class="form-control"
                   min="1" step="1024" :placeholder="t('modelConfigRule.ctxCustomPlaceholder')"
                   :disabled="!isCustomContext" />
            <select class="form-control mcr-context-preset" :value="presetValue" @change="onPresetChange">
              <option value="">{{ t('modelConfigRule.ctxNone') }}</option>
              <option v-for="p in contextPresets" :key="p.value" :value="String(p.value)">{{ t(p.labelKey) }}</option>
              <option value="custom">{{ t('modelConfigRule.ctxCustom') }}</option>
            </select>
          </div>
          <div class="form-hint">{{ t('modelConfigRule.contextLengthHint') }}</div>
        </div>

        <div v-if="formError" class="mcr-test-error">{{ formError }}</div>

        <!-- Test Data -->
        <div class="form-group">
          <label>{{ t('modelConfigRule.testData') }}</label>
          <div class="mcr-test-input-wrap">
            <input v-model="testInput" class="form-control" :placeholder="t('modelConfigRule.testDataPlaceholder')"
                   @keydown.enter.prevent="addTestData" />
            <button class="btn btn-sm btn-secondary" @click="addTestData" :disabled="!testInput.trim()">
              <SvgIcon name="plus" :size="12" /> {{ t('common.add') }}
            </button>
          </div>
          <div v-if="testData.length" class="mcr-test-tags">
            <span v-for="(item, idx) in testData" :key="idx" class="mcr-test-tag"
                  :class="{ 'mcr-test-tag--match': testResults[idx]?.matched === true,
                           'mcr-test-tag--no-match': testResults[idx]?.matched === false }">
              {{ item }}
              <span class="mcr-test-tag-remove" @click="removeTestData(idx)">
                <SvgIcon name="x" :size="10" />
              </span>
              <span v-if="testResults[idx]?.matched === true" class="mcr-test-status mcr-test-status--pass">&#10003;</span>
              <span v-if="testResults[idx]?.matched === false" class="mcr-test-status mcr-test-status--fail">&#10007;</span>
            </span>
          </div>
        </div>

        <div v-if="testRunning" class="mcr-test-running">{{ t('common.loading') }}</div>
        <div v-if="testError" class="mcr-test-error">{{ testError }}</div>

        <!-- Real-model match results: what actually gets applied on save -->
        <div v-if="tested && !testError" class="mcr-real-test">
          <div class="mcr-real-test-title">
            {{ t('modelConfigRule.realModels', { matched: matchedModels.length, total: totalModels }) }}
          </div>
          <div v-if="matchedModels.length" class="mcr-real-test-list">
            <div v-for="m in matchedModels" :key="m.modelName" class="mcr-real-test-row">
              <code class="mcr-real-test-name">{{ m.modelName }}</code>
              <span class="mcr-real-test-tags">
                <span class="mcr-chip mcr-chip--input">{{ m.input || 'text' }}</span>
                <span class="mcr-chip mcr-chip--ctx">
                  {{ m.contextLength ? formatTokens(m.contextLength) : t('modelConfigRule.contextUnknown') }}
                </span>
                <span class="mcr-chip" :class="'mcr-chip--' + m.contextSource">
                  {{ t('modelConfigRule.source' + sourceKey(m.contextSource)) }}
                </span>
              </span>
              <span v-if="m.catalogInput || m.catalogContextLength" class="mcr-real-test-base">
                {{ t('modelConfigRule.baseline') }}
                {{ m.catalogContextLength ? formatTokens(m.catalogContextLength) : '-' }} · {{ m.catalogInput || '-' }}
              </span>
            </div>
          </div>
          <div v-else class="mcr-real-test-empty">{{ t('modelConfigRule.noRealMatch') }}</div>
        </div>

        <div class="mcr-form-actions">
          <button class="btn btn-sm btn-secondary" @click="cancelForm">{{ t('common.cancel') }}</button>
          <button class="btn btn-sm btn-secondary" @click="runTest"
                  :disabled="!form.pattern.trim() || testData.length === 0 || testRunning">
            <SvgIcon name="refresh" :size="12" /> {{ t('modelConfigRule.runTest') }}
          </button>
          <button class="btn btn-sm btn-primary" @click="saveRule" :disabled="!form.pattern.trim() || saving">
            {{ saving ? t('common.saving') : t('common.save') }}
          </button>
        </div>
      </div>
    </div>
  </div>

  <!-- Inner Dialog for confirm/alert -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    type="confirm"
    @confirm="onDialogConfirm"
    @cancel="dialogVisible = false"
  >
    {{ dialogMessage }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from '@/composables/useI18n'
import {
  modelConfigRuleApi, modelsDevApi,
  type ModelConfigRule, type RuleTestResult, type MatchedModel, type ModelsDevStatus
} from '@/api/modelConfigRule'
import { formatLocalDateTimeFull } from '@/utils/date'
import { formatTokens } from '@/utils/format'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = ref(false)
const rules = ref<ModelConfigRule[]>([])
const loading = ref(false)
const showForm = ref(false)
const editingRule = ref<ModelConfigRule | null>(null)
const saving = ref(false)
const formError = ref('')

const form = ref<{ pattern: string; contextLength: number }>({
  pattern: '',
  contextLength: 0
})

/** 常见上下文窗口预设（下拉在右驱动取值，输入框仅在“自定义”时可编辑） */
const contextPresets = [
  { value: 128000, labelKey: 'modelConfigRule.ctxPreset128' },
  { value: 200000, labelKey: 'modelConfigRule.ctxPreset200' },
  { value: 1000000, labelKey: 'modelConfigRule.ctxPreset1m' },
  { value: 2000000, labelKey: 'modelConfigRule.ctxPreset2m' }
] as const

/** 用户显式选择了“自定义”下拉项 */
const customContext = ref(false)

const isCustomContext = computed(() =>
  customContext.value ||
  (form.value.contextLength > 0 && !contextPresets.some(p => p.value === form.value.contextLength))
)

/** 下拉选中项：不设置 / 命中的预设 / 自定义（含已填的自定义值） */
const presetValue = computed(() => {
  if (isCustomContext.value) return 'custom'
  return form.value.contextLength > 0 ? String(form.value.contextLength) : ''
})

/** 输入框与 form.contextLength 双向绑定，仅自定义模式下可写 */
const contextInput = computed<number | string>({
  get: () => form.value.contextLength || '',
  set: (v) => {
    const n = Number(v)
    form.value.contextLength = Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
  }
})

function onPresetChange(e: Event) {
  const sel = (e.target as HTMLSelectElement).value
  if (sel === '') {
    form.value.contextLength = 0
    customContext.value = false
  } else if (sel === 'custom') {
    customContext.value = true
  } else {
    customContext.value = false
    form.value.contextLength = Number(sel)
  }
}

const appendTypeInput = ref('')
const appendTypeTags = ref<string[]>([])

const testInput = ref('')
const testData = ref<string[]>([])
const testResults = ref<RuleTestResult[]>([])
const testRunning = ref(false)
const testError = ref('')
const tested = ref(false)
const matchedModels = ref<MatchedModel[]>([])
const totalModels = ref(0)

// models.dev local cache file
const syncStatus = ref<ModelsDevStatus | null>(null)
const reloading = ref(false)
const downloading = ref(false)

// Dialog state
const dialogVisible = ref(false)
const dialogTitle = ref('')
const dialogMessage = ref('')
let dialogCallback: (() => void) | null = null

watch(() => props.modelValue, (val) => {
  visible.value = val
  if (val) {
    loadRules()
    loadCacheStatus()
  }
})

watch(visible, (val) => {
  emit('update:modelValue', val)
})

function handleClose() {
  visible.value = false
  resetForm()
}

async function loadRules() {
  loading.value = true
  try {
    const res = await modelConfigRuleApi.list()
    if (res.data && (res.data as any).success === false) {
      throw new Error((res.data as any).error || t('common.fail'))
    }
    rules.value = res.data as ModelConfigRule[]
  } catch (e: any) {
    console.warn('加载模型配置规则失败', e)
    showDialog(t('common.fail'), e.message)
  } finally {
    loading.value = false
  }
}

function openAddRule() {
  editingRule.value = null
  form.value = { pattern: '', contextLength: 0 }
  customContext.value = false
  // 默认给出最常见的三种模态，用户可删减
  appendTypeTags.value = ['image', 'video', 'audio']
  appendTypeInput.value = ''
  resetTestState()
  showForm.value = true
}

function openEditRule(rule: ModelConfigRule) {
  editingRule.value = rule
  form.value = { pattern: rule.pattern, contextLength: rule.contextLength || 0 }
  customContext.value = false
  appendTypeTags.value = rule.appendType ? rule.appendType.split(',').filter(Boolean) : []
  appendTypeInput.value = ''
  resetTestState()
  showForm.value = true
}

function cancelForm() {
  showForm.value = false
  editingRule.value = null
}

function addTestData() {
  const val = testInput.value.trim()
  if (!val) return
  if (!testData.value.includes(val)) {
    testData.value.push(val)
  }
  testInput.value = ''
}

function removeTestData(idx: number) {
  testData.value.splice(idx, 1)
  testResults.value.splice(idx, 1)
}

function addAppendTypeTag() {
  const val = appendTypeInput.value.trim()
  if (!val) return
  if (!appendTypeTags.value.includes(val)) {
    appendTypeTags.value.push(val)
  }
  appendTypeInput.value = ''
}

function removeAppendTypeTag(idx: number) {
  appendTypeTags.value.splice(idx, 1)
}

function removeLastTag() {
  if (appendTypeInput.value === '' && appendTypeTags.value.length > 0) {
    appendTypeTags.value.pop()
  }
}

async function runTest() {
  if (!form.value.pattern.trim() || testData.value.length === 0) return
  testRunning.value = true
  testError.value = ''
  tested.value = false
  try {
    const res = await modelConfigRuleApi.test(form.value.pattern, testData.value)
    if (res.data.success && res.data.data) {
      testResults.value = res.data.data
      matchedModels.value = res.data.matchedModels ?? []
      totalModels.value = res.data.totalModels ?? 0
      tested.value = true
    } else {
      testError.value = res.data.error || t('common.fail')
    }
  } catch (e: any) {
    testError.value = e.message
  } finally {
    testRunning.value = false
  }
}

async function saveRule() {
  formError.value = ''
  if (!form.value.pattern.trim()) {
    showDialog(t('common.prompt'), t('modelConfigRule.patternRequired'))
    return
  }
  const appendType = appendTypeTags.value.join(',')
  const contextLength = Number(form.value.contextLength) || 0
  if (isCustomContext.value && contextLength <= 0) {
    formError.value = t('modelConfigRule.ctxCustomRequired')
    return
  }
  if (!appendType && contextLength <= 0) {
    formError.value = t('modelConfigRule.atLeastOneRequired')
    return
  }
  if (contextLength < 0) {
    formError.value = t('modelConfigRule.contextLengthInvalid')
    return
  }
  saving.value = true
  const payload = { pattern: form.value.pattern.trim(), appendType, contextLength }
  try {
    const res = editingRule.value?.id
      ? await modelConfigRuleApi.update(editingRule.value.id, payload)
      : await modelConfigRuleApi.create(payload)
    // 后端业务失败走 HTTP 200 + success:false，不检查会误报“保存成功”
    if (res.data.success === false) {
      throw new Error(res.data.error || t('common.fail'))
    }
    showDialog(t('common.success'), t('modelConfigRule.saveSuccess'))
    showForm.value = false
    editingRule.value = null
    await loadRules()
  } catch (e: any) {
    showDialog(t('modelConfigRule.saveFailed'), e.message)
  } finally {
    saving.value = false
  }
}

function confirmDelete(rule: ModelConfigRule) {
  showDialog(t('common.confirmDelete'), t('modelConfigRule.deleteConfirm'), async () => {
    try {
      const res = await modelConfigRuleApi.delete(rule.id!)
      if (res.data.success === false) {
        throw new Error(res.data.error || t('common.fail'))
      }
      showDialog(t('common.success'), t('modelConfigRule.deleteSuccess'))
      await loadRules()
    } catch (e: any) {
      showDialog(t('common.fail'), e.message)
    }
  })
}

function resetTestState() {
  testData.value = []
  testResults.value = []
  testError.value = ''
  tested.value = false
  matchedModels.value = []
  totalModels.value = 0
  formError.value = ''
}

function resetForm() {
  showForm.value = false
  editingRule.value = null
  form.value = { pattern: '', contextLength: 0 }
  appendTypeTags.value = []
  appendTypeInput.value = ''
  resetTestState()
}

/** 来源标识 → i18n 键后缀（rule/catalog/none） */
function sourceKey(source: MatchedModel['contextSource']): string {
  switch (source) {
    case 'rule': return 'Rule'
    case 'catalog': return 'Catalog'
    default: return 'None'
  }
}

// ---------------- models.dev local cache file ----------------

async function loadCacheStatus() {
  try {
    const res = await modelsDevApi.status()
    syncStatus.value = res.data
  } catch (e) {
    console.warn('加载 models.dev 缓存状态失败', e)
    syncStatus.value = null
  }
}

/** 只重新读取本地文件，不联网 */
async function reloadCache() {
  reloading.value = true
  try {
    const res = await modelsDevApi.reload()
    if (res.data.success) {
      showDialog(t('common.success'), t('modelConfigRule.reloadSuccess', { count: res.data.count ?? 0 }))
      await loadCacheStatus()
      await loadRules()
    } else {
      showDialog(t('modelConfigRule.reloadFailed'), res.data.error || t('common.fail'))
    }
  } catch (e: any) {
    showDialog(t('modelConfigRule.reloadFailed'), e.message)
  } finally {
    reloading.value = false
  }
}

/** 下载最新数据写入本地文件 */
async function downloadCache() {
  downloading.value = true
  try {
    const res = await modelsDevApi.refresh()
    if (res.data.success) {
      // 实际使用的地址与配置的主地址不同说明走了备用镜像
      const usedMirror = !!res.data.sourceUrl && !!syncStatus.value?.sourceUrl && res.data.sourceUrl !== syncStatus.value.sourceUrl
      const key = usedMirror ? 'modelConfigRule.downloadSuccessFallback' : 'modelConfigRule.downloadSuccess'
      showDialog(t('common.success'), t(key, { count: res.data.count ?? 0 }))
      await loadCacheStatus()
      await loadRules()
    } else {
      showDialog(t('modelConfigRule.downloadFailed'), res.data.error || t('common.fail'))
    }
  } catch (e: any) {
    showDialog(t('modelConfigRule.downloadFailed'), e.message)
  } finally {
    downloading.value = false
  }
}

function showDialog(title: string, message: string, callback?: () => void) {
  dialogTitle.value = title
  dialogMessage.value = message
  dialogCallback = callback || null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogCallback?.()
  dialogCallback = null
}
</script>

<style scoped>
.modal-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.6); z-index: 1000;
  display: flex; align-items: flex-start; justify-content: center;
  padding-top: 60px;
}
.modal-box.mcr-modal {
  width: 620px; max-width: 92vw; max-height: 80vh; overflow-y: auto;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 12px; padding: 24px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.4);
}
.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px;
}
.modal-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 16px; font-weight: 600;
}
.modal-close {
  background: none; border: none; color: var(--text-muted);
  cursor: pointer; font-size: 20px; padding: 0 4px;
}
.modal-close:hover { color: var(--text-primary); }
.mcr-desc {
  font-size: 12px; color: var(--text-muted); line-height: 1.6;
  margin-bottom: 12px;
}
.mcr-sync {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
  font-size: 12px; color: var(--text-muted);
  padding: 10px;
  background: var(--bg-primary); border: 1px solid var(--border-color); border-radius: 6px;
}
.mcr-sync-info { display: flex; flex-direction: column; gap: 3px; min-width: 0; flex: 1; }
.mcr-sync-title { font-size: 12px; font-weight: 500; }
.mcr-sync-line { display: flex; align-items: center; gap: 6px; }
.mcr-sync-path {
  font-family: var(--font-mono, monospace); font-size: 11px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  opacity: 0.75;
}
.mcr-sync-error {
  font-size: 12px; color: #d1242f;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mcr-sync-actions { display: flex; gap: 6px; flex-shrink: 0; }
.mcr-section { margin-bottom: 16px; }
.mcr-section-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 10px;
}
.mcr-section-title { font-size: 14px; font-weight: 600; }
.mcr-loading, .mcr-empty {
  text-align: center; padding: 20px; color: var(--text-muted); font-size: 13px;
}
.mcr-rule-list {
  display: flex; flex-direction: column; gap: 6px;
  max-height: 240px; overflow-y: auto;
}
.mcr-rule-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 6px; font-size: 13px;
}
.mcr-rule-info {
  display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0;
}
.mcr-pattern {
  font-family: var(--font-mono, monospace); font-size: 12px;
  padding: 2px 6px; background: var(--bg-secondary);
  border-radius: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mcr-badge { font-size: 11px; flex-shrink: 0; }
.mcr-rule-actions {
  display: flex; gap: 4px; flex-shrink: 0;
}
.mcr-icon-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; border: none; background: transparent;
  cursor: pointer; border-radius: 4px; color: var(--text-muted);
}
.mcr-icon-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.mcr-icon-btn--danger:hover { color: var(--accent-red); }

.mcr-form-section {
  background: transparent; border: 1px solid var(--border-color); border-radius: 8px; padding: 16px;
  margin-top: 8px;
}
.mcr-test-input-wrap {
  display: flex; gap: 6px;
}
.mcr-context-row {
  display: flex; gap: 8px; align-items: center;
}
.mcr-context-row input { flex: 1; min-width: 0; }
.mcr-context-row .mcr-context-preset { flex: 1.2; min-width: 0; }
.mcr-test-input-wrap .form-control { flex: 1; }
.mcr-test-tags {
  display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px;
}
.mcr-test-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 8px; border-radius: 6px; font-size: 12px;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  transition: all 0.15s;
}
.mcr-test-tag--match {
  border-color: #2ea043; background: rgba(46,160,67,0.1);
}
.mcr-test-tag--no-match {
  border-color: var(--border-color);
}
.mcr-test-tag-remove {
  cursor: pointer; opacity: 0.5; display: inline-flex; align-items: center;
}
.mcr-test-tag-remove:hover { opacity: 1; color: var(--accent-red); }
.mcr-test-status {
  font-size: 11px; font-weight: 700;
}
.mcr-test-status--pass { color: #2ea043; }
.mcr-test-status--fail { color: var(--text-muted); }
.mcr-test-running { font-size: 12px; color: var(--text-muted); padding: 4px 0; }
.mcr-test-error {
  font-size: 12px; color: var(--accent-red);
  background: rgba(248,81,73,0.1); padding: 6px 10px; border-radius: 4px; margin-top: 4px;
}
.mcr-real-test {
  margin-top: 8px; padding: 8px 10px;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 6px;
}
.mcr-real-test-title {
  font-size: 12px; font-weight: 600; color: var(--text-muted);
}
.mcr-real-test-list {
  display: flex; flex-direction: column; gap: 5px;
  margin-top: 6px; max-height: 160px; overflow-y: auto;
}
.mcr-real-test-row {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  font-size: 12px;
}
.mcr-real-test-name {
  font-family: var(--font-mono, monospace); font-size: 12px;
  padding: 1px 5px; border-radius: 4px;
  background: rgba(46,160,67,0.1); border: 1px solid rgba(46,160,67,0.35);
  color: #2ea043;
}
.mcr-real-test-tags { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.mcr-chip {
  padding: 1px 5px; border-radius: 4px; font-size: 11px;
  background: rgba(255,255,255,0.07);
}
.mcr-chip--input { color: var(--accent-blue, #58a6ff); }
.mcr-chip--ctx { color: #d29922; }
.mcr-chip--rule { background: rgba(46,160,67,0.18); color: #2ea043; }
.mcr-chip--catalog { background: rgba(88,166,255,0.15); color: var(--accent-blue, #58a6ff); }
.mcr-chip--none { opacity: 0.6; }
.mcr-real-test-base { font-size: 11px; color: var(--text-muted); }
.mcr-real-test-empty { font-size: 12px; color: var(--accent-red); margin-top: 6px; }
.mcr-form-actions {
  display: flex; gap: 6px; justify-content: flex-end; margin-top: 12px;
}

/* Tag Input */
.mcr-tag-input {
  display: flex; flex-wrap: wrap; gap: 6px;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 6px; padding: 6px 8px;
  transition: border-color 0.15s;
}
.mcr-tag-input:focus-within {
  border-color: var(--accent-blue, #58a6ff);
}
.mcr-tags {
  display: flex; flex-wrap: wrap; gap: 4px; flex: 1;
}
.mcr-tag {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 2px 6px; border-radius: 4px; font-size: 12px;
  background: rgba(88,166,255,0.12); border: 1px solid rgba(88,166,255,0.25);
  color: var(--accent-blue, #58a6ff); line-height: 1.5;
}
.mcr-tag-remove {
  cursor: pointer; opacity: 0.5; display: inline-flex; align-items: center;
  margin-left: 1px;
}
.mcr-tag-remove:hover { opacity: 1; color: var(--accent-red); }
.mcr-tag-input-field {
  flex: 1; min-width: 80px; border: none; outline: none;
  background: transparent; font-size: 13px; color: var(--text-primary);
  padding: 2px 0;
}
.mcr-tag-input-field::placeholder { color: var(--text-muted); font-size: 12px; }

/* Badge */
.badge-info {
  background: rgba(88,166,255,0.15);
  color: var(--accent-blue, #58a6ff);
}
</style>
