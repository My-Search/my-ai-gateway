<template>
  <div v-if="visible" class="modal-overlay" @click.self="handleClose">
    <div class="modal-box mmr-modal">
      <div class="modal-header">
        <div class="modal-title">
          <SvgIcon name="eye" :size="16" />
          {{ t('multimodal.title') }}
        </div>
        <button class="modal-close" @click="handleClose">&times;</button>
      </div>

      <div class="mmr-desc">{{ t('multimodal.desc') }}</div>

      <!-- Rule List -->
      <div class="mmr-section">
        <div class="mmr-section-header">
          <span class="mmr-section-title">{{ t('multimodal.title') }}</span>
          <button class="btn btn-sm btn-primary" @click="openAddRule">
            <SvgIcon name="plus" :size="12" /> {{ t('multimodal.addRule') }}
          </button>
        </div>

        <div v-if="loading" class="mmr-loading">{{ t('common.loading') }}</div>

        <div v-else-if="!rules.length" class="mmr-empty">
          {{ t('multimodal.noRules') }}
        </div>

        <div v-else class="mmr-rule-list">
          <div v-for="rule in rules" :key="rule.id" class="mmr-rule-item">
            <div class="mmr-rule-info">
              <code class="mmr-pattern">{{ rule.pattern }}</code>
              <span class="mmr-append-badge badge badge-info">+{{ rule.appendType }}</span>
            </div>
            <div class="mmr-rule-actions">
              <button class="mmr-icon-btn" :title="t('common.edit')" @click="openEditRule(rule)">
                <SvgIcon name="edit" :size="13" />
              </button>
              <button class="mmr-icon-btn mmr-icon-btn--danger" :title="t('common.delete')" @click="confirmDelete(rule)">
                <SvgIcon name="trash" :size="13" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Add/Edit Rule Form -->
      <div v-if="showForm" class="mmr-section mmr-form-section">
        <div class="mmr-section-header">
          <span class="mmr-section-title">{{ editingRule ? t('multimodal.editRule') : t('multimodal.addRule') }}</span>
        </div>

        <div class="form-group">
          <label>{{ t('multimodal.pattern') }}</label>
          <input v-model="form.pattern" class="form-control" :placeholder="t('multimodal.patternPlaceholder')"
                 @keydown.enter.prevent="saveRule" />
        </div>

        <div class="form-group">
          <label>{{ t('multimodal.appendType') }}</label>
          <input v-model="form.appendType" class="form-control" placeholder="image" />
          <div class="form-hint">{{ t('multimodal.appendTypeHint') }}</div>
        </div>

        <!-- Test Data -->
        <div class="form-group">
          <label>{{ t('multimodal.testData') }}</label>
          <div class="mmr-test-input-wrap">
            <input v-model="testInput" class="form-control" :placeholder="t('multimodal.testDataPlaceholder')"
                   @keydown.enter.prevent="addTestData" />
            <button class="btn btn-sm btn-secondary" @click="addTestData" :disabled="!testInput.trim()">
              <SvgIcon name="plus" :size="12" /> {{ t('common.add') }}
            </button>
          </div>
          <div v-if="testData.length" class="mmr-test-tags">
            <span v-for="(item, idx) in testData" :key="idx" class="mmr-test-tag"
                  :class="{ 'mmr-test-tag--match': testResults[idx]?.matched === true,
                           'mmr-test-tag--no-match': testResults[idx]?.matched === false }">
              {{ item }}
              <span class="mmr-test-tag-remove" @click="removeTestData(idx)">
                <SvgIcon name="x" :size="10" />
              </span>
              <span v-if="testResults[idx]?.matched === true" class="mmr-test-status mmr-test-status--pass">&#10003;</span>
              <span v-if="testResults[idx]?.matched === false" class="mmr-test-status mmr-test-status--fail">&#10007;</span>
            </span>
          </div>
        </div>

        <div v-if="testRunning" class="mmr-test-running">{{ t('common.loading') }}</div>

        <div v-if="testError" class="mmr-test-error">{{ testError }}</div>

        <div class="mmr-form-actions">
          <button class="btn btn-sm btn-secondary" @click="cancelForm">{{ t('common.cancel') }}</button>
          <button class="btn btn-sm btn-secondary" @click="runTest" :disabled="!form.pattern.trim() || testData.length === 0 || testRunning">
            <SvgIcon name="refresh" :size="12" /> {{ t('multimodal.runTest') }}
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
import { ref, watch } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { multimodalApi, type MultiModalRule, type RuleTestResult } from '@/api/multimodal'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = ref(false)
const rules = ref<MultiModalRule[]>([])
const loading = ref(false)
const showForm = ref(false)
const editingRule = ref<MultiModalRule | null>(null)
const saving = ref(false)

const form = ref<{ pattern: string; appendType: string }>({
  pattern: '',
  appendType: 'image'
})

const testInput = ref('')
const testData = ref<string[]>([])
const testResults = ref<RuleTestResult[]>([])
const testRunning = ref(false)
const testError = ref('')

// Dialog state
const dialogVisible = ref(false)
const dialogTitle = ref('')
const dialogMessage = ref('')
let dialogCallback: (() => void) | null = null

watch(() => props.modelValue, (val) => {
  visible.value = val
  if (val) {
    loadRules()
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
    const res = await multimodalApi.list()
    rules.value = res.data
  } catch (e: any) {
    console.warn('加载多模态规则失败', e)
  } finally {
    loading.value = false
  }
}

function openAddRule() {
  editingRule.value = null
  form.value = { pattern: '', appendType: 'image' }
  testData.value = []
  testResults.value = []
  testError.value = ''
  showForm.value = true
}

function openEditRule(rule: MultiModalRule) {
  editingRule.value = rule
  form.value = { pattern: rule.pattern, appendType: rule.appendType }
  testData.value = []
  testResults.value = []
  testError.value = ''
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

async function runTest() {
  if (!form.value.pattern.trim() || testData.value.length === 0) return
  testRunning.value = true
  testError.value = ''
  try {
    const res = await multimodalApi.test(form.value.pattern, testData.value)
    if (res.data.success && res.data.data) {
      testResults.value = res.data.data
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
  if (!form.value.pattern.trim()) {
    showDialog(t('common.prompt'), t('multimodal.patternRequired'))
    return
  }
  saving.value = true
  try {
    if (editingRule.value?.id) {
      await multimodalApi.update(editingRule.value.id, form.value)
      showDialog(t('common.success'), t('multimodal.saveSuccess'))
    } else {
      await multimodalApi.create(form.value)
      showDialog(t('common.success'), t('multimodal.saveSuccess'))
    }
    showForm.value = false
    editingRule.value = null
    await loadRules()
  } catch (e: any) {
    showDialog(t('multimodal.saveFailed'), e.message)
  } finally {
    saving.value = false
  }
}

function confirmDelete(rule: MultiModalRule) {
  showDialog(t('common.confirmDelete'), t('multimodal.deleteConfirm'), async () => {
    try {
      await multimodalApi.delete(rule.id!)
      showDialog(t('common.success'), t('multimodal.deleteSuccess'))
      await loadRules()
    } catch (e: any) {
      showDialog(t('common.fail'), e.message)
    }
  })
}

function resetForm() {
  showForm.value = false
  editingRule.value = null
  form.value = { pattern: '', appendType: 'image' }
  testData.value = []
  testResults.value = []
  testError.value = ''
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
.modal-box.mmr-modal {
  width: 560px; max-width: 92vw; max-height: 80vh; overflow-y: auto;
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
.mmr-desc {
  font-size: 12px; color: var(--text-muted); line-height: 1.6;
  margin-bottom: 16px; padding-bottom: 12px;
  border-bottom: 1px solid var(--border-color);
}
.mmr-section { margin-bottom: 16px; }
.mmr-section-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 10px;
}
.mmr-section-title { font-size: 14px; font-weight: 600; }
.mmr-loading, .mmr-empty {
  text-align: center; padding: 20px; color: var(--text-muted); font-size: 13px;
}
.mmr-rule-list {
  display: flex; flex-direction: column; gap: 6px;
  max-height: 240px; overflow-y: auto;
}
.mmr-rule-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; background: var(--bg-tertiary);
  border-radius: 6px; font-size: 13px;
}
.mmr-rule-info {
  display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0;
}
.mmr-pattern {
  font-family: var(--font-mono, monospace); font-size: 12px;
  padding: 2px 6px; background: var(--bg-secondary);
  border-radius: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mmr-append-badge { font-size: 11px; flex-shrink: 0; }
.mmr-rule-actions {
  display: flex; gap: 4px; flex-shrink: 0;
}
.mmr-icon-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; border: none; background: transparent;
  cursor: pointer; border-radius: 4px; color: var(--text-muted);
}
.mmr-icon-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.mmr-icon-btn--danger:hover { color: var(--accent-red); }

.mmr-form-section {
  background: var(--bg-tertiary); border-radius: 8px; padding: 16px;
  margin-top: 8px;
}
.mmr-test-input-wrap {
  display: flex; gap: 6px;
}
.mmr-test-input-wrap .form-control { flex: 1; }
.mmr-test-tags {
  display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px;
}
.mmr-test-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 8px; border-radius: 6px; font-size: 12px;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  transition: all 0.15s;
}
.mmr-test-tag--match {
  border-color: #2ea043; background: rgba(46,160,67,0.1);
}
.mmr-test-tag--no-match {
  border-color: var(--border-color);
}
.mmr-test-tag-remove {
  cursor: pointer; opacity: 0.5; display: inline-flex; align-items: center;
}
.mmr-test-tag-remove:hover { opacity: 1; color: var(--accent-red); }
.mmr-test-status {
  font-size: 11px; font-weight: 700;
}
.mmr-test-status--pass { color: #2ea043; }
.mmr-test-status--fail { color: var(--text-muted); }
.mmr-test-running { font-size: 12px; color: var(--text-muted); padding: 4px 0; }
.mmr-test-error {
  font-size: 12px; color: var(--accent-red);
  background: rgba(248,81,73,0.1); padding: 6px 10px; border-radius: 4px; margin-top: 4px;
}
.mmr-form-actions {
  display: flex; gap: 6px; justify-content: flex-end; margin-top: 12px;
}

/* Badge */
.badge-info {
  background: rgba(88,166,255,0.15);
  color: var(--accent-blue, #58a6ff);
}
</style>
