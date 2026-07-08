<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('promptInjection.title').replace('{name}', modelName || '') }}</div>
      <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>

    <div class="action-bar">
      <div class="left">
        <button class="btn btn-primary btn-sm" @click="openCreateForm">
          <SvgIcon name="plus" :size="14" /> {{ t('promptInjection.addRule') }}
        </button>
      </div>
      <div class="right">
        <span class="rule-count">{{ t('promptInjection.ruleCount').replace('{count}', String(rules.length)) }}</span>
      </div>
    </div>

    <!-- Injection Rules Table -->
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>{{ t('promptInjection.name') }}</th>
            <th>{{ t('promptInjection.injectRole') }}</th>
            <th>{{ t('promptInjection.injectPosition') }}</th>
            <th>{{ t('promptInjection.content') }}</th>
            <th>{{ t('promptInjection.priority') }}</th>
            <th>{{ t('promptInjection.status') }}</th>
            <th>{{ t('promptInjection.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rule in sortedRules" :key="rule.id">
            <td>
              <strong>{{ rule.name || '\\u2014' }}</strong>
            </td>
            <td>
              <span class="role-badge" :class="`role-${rule.injectRole}`">{{ roleLabel(rule.injectRole) }}</span>
            </td>
            <td>
              <span class="position-badge" :class="`pos-${rule.injectPosition}`">{{ positionLabel(rule.injectPosition) }}</span>
            </td>
            <td style="max-width:200px;">
              <div class="content-preview">{{ rule.content }}</div>
            </td>
            <td style="text-align:center;">
              <span class="priority-badge">{{ rule.priority }}</span>
            </td>
            <td>
              <button
                class="toggle-btn"
                :class="rule.enabled === 1 ? 'active' : 'inactive'"
                :title="rule.enabled === 1 ? t('promptInjection.clickToDisable') : t('promptInjection.clickToEnable')"
                @click.stop="toggleEnabled(rule)"
                :disabled="toggleLoading === rule.id"
              >
                <span class="toggle-track">
                  <span class="toggle-thumb"></span>
                </span>
                <span class="toggle-label">{{ rule.enabled === 1 ? t('common.enabled') : t('common.disabled') }}</span>
              </button>
            </td>
            <td>
              <div style="display:flex;gap:6px;">
                <button class="btn btn-sm btn-secondary" @click="openEditForm(rule)"><SvgIcon name="edit" :size="14" /> {{ t('common.edit') }}</button>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(rule)"><SvgIcon name="trash" :size="14" /> {{ t('common.delete') }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="!rules.length">
            <td colspan="7" style="text-align:center;color:var(--text-muted);padding:40px;">
              {{ t('promptInjection.noRules') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Mobile card list -->
    <div class="mobile-card-list">
      <div v-if="!rules.length" class="empty-state">
        {{ t('promptInjection.noRules') }}
      </div>
      <div v-for="rule in sortedRules" :key="rule.id" class="mobile-card">
        <div class="mobile-card-header">
          <strong class="mobile-card-title">{{ rule.name || '\\u2014' }}</strong>
          <button
            class="toggle-btn toggle-btn-sm"
            :class="rule.enabled === 1 ? 'active' : 'inactive'"
            @click.stop="toggleEnabled(rule)"
            :disabled="toggleLoading === rule.id"
          >
            <span class="toggle-track">
              <span class="toggle-thumb"></span>
            </span>
            <span class="toggle-label">{{ rule.enabled === 1 ? t('common.enabled') : t('common.disabled') }}</span>
          </button>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('promptInjection.injectRole') }}:</span>
          <span class="role-badge" :class="`role-${rule.injectRole}`">{{ roleLabel(rule.injectRole) }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('promptInjection.injectPosition') }}:</span>
          <span class="position-badge" :class="`pos-${rule.injectPosition}`">{{ positionLabel(rule.injectPosition) }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('promptInjection.content') }}:</span>
        </div>
        <div class="mobile-card-content">{{ rule.content }}</div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('promptInjection.priority') }}:</span>
          <span class="priority-badge">{{ rule.priority }}</span>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-actions">
          <button class="btn btn-sm btn-secondary" @click="openEditForm(rule)"><SvgIcon name="edit" :size="14" /> {{ t('common.edit') }}</button>
          <button class="btn btn-sm btn-danger" @click="confirmDelete(rule)"><SvgIcon name="trash" :size="14" /> {{ t('common.delete') }}</button>
        </div>
      </div>
    </div>
  </div>

  <!-- Add/Edit Rule Dialog -->
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="formVisible" class="dialog-overlay" @click.self="cancelForm">
        <div class="dialog-box pi-dialog" role="dialog" aria-modal="true">
          <div class="dialog-header">
            <span class="dialog-title">{{ editingRule ? t('promptInjection.editRule') : t('promptInjection.addRule') }}</span>
            <button class="dialog-close" @click="cancelForm" :aria-label="t('common.close')">
              <SvgIcon name="x" :size="18" />
            </button>
          </div>
          <div class="dialog-body">
            <form @submit.prevent="handleSave">
              <!-- Name -->
              <div class="form-group">
                <label for="pi-name">{{ t('promptInjection.name') }}</label>
                <input id="pi-name" v-model="form.name" class="form-control" :placeholder="t('promptInjection.namePlaceholder')" />
              </div>

              <!-- Inject Role -->
              <div class="form-group">
                <label for="pi-role">{{ t('promptInjection.injectRole') }}</label>
                <select id="pi-role" v-model="form.injectRole" class="form-control">
                  <option value="system">System</option>
                  <option value="user">User</option>
                  <option value="assistant">Assistant</option>
                </select>
                <div class="form-hint">{{ t('promptInjection.injectRoleHint') }}</div>
              </div>

              <!-- Inject Position -->
              <div class="form-group">
                <label for="pi-position">{{ t('promptInjection.injectPosition') }}</label>
                <select id="pi-position" v-model="form.injectPosition" class="form-control">
                  <option value="prepend">{{ t('promptInjection.positionPrepend') }}</option>
                  <option value="append">{{ t('promptInjection.positionAppend') }}</option>
                  <option value="replace_system">{{ t('promptInjection.positionReplaceSystem') }}</option>
                </select>
                <div class="form-hint">{{ t('promptInjection.injectPositionHint') }}</div>
              </div>

              <!-- Content -->
              <div class="form-group">
                <label for="pi-content">{{ t('promptInjection.content') }} *</label>
                <textarea id="pi-content" v-model="form.content" class="form-control pi-content-input"
                          :placeholder="t('promptInjection.contentPlaceholder')" rows="5" required></textarea>
              </div>

              <!-- Priority -->
              <div class="form-row">
                <div class="form-group" style="flex:1;">
                  <label for="pi-priority">{{ t('promptInjection.priority') }}</label>
                  <input id="pi-priority" v-model.number="form.priority" type="number" class="form-control" min="0" max="999" />
                </div>
                <div class="form-group" style="flex:1;">
                  <label>{{ t('promptInjection.status') }}</label>
                  <div class="switch-group" style="margin-top:6px;">
                    <label class="switch">
                      <input type="checkbox" v-model="form.enabled" :true-value="1" :false-value="0" />
                      <span class="switch-slider"></span>
                    </label>
                    <span class="switch-label">{{ form.enabled === 1 ? t('common.on') : t('common.off') }}</span>
                  </div>
                </div>
              </div>

              <div class="dialog-footer-inner">
                <button type="button" class="btn btn-secondary" @click="cancelForm">
                  <SvgIcon name="x" :size="14" /> {{ t('common.cancel') }}
                </button>
                <button type="submit" class="btn btn-primary" :disabled="saving">
                  <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

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
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { promptInjectionApi, type PromptInjection, type InjectRole, type InjectPosition } from '@/api/promptInjection'
import { modelApi, type CustomModel } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const modelId = computed(() => Number(route.params.id))
const modelName = ref('')

const rules = ref<PromptInjection[]>([])
const toggleLoading = ref<number | null>(null)
const saving = ref(false)

/* ---------- Form state ---------- */
const formVisible = ref(false)
const editingRule = ref<PromptInjection | null>(null)
const form = ref<{
  name: string
  injectRole: InjectRole
  injectPosition: InjectPosition
  content: string
  enabled: number
  priority: number
}>({
  name: '',
  injectRole: 'system',
  injectPosition: 'prepend',
  content: '',
  enabled: 1,
  priority: 0
})

/* ---------- Dialog state ---------- */
const {
  visible: dialogVisible,
  title: dialogTitle,
  message: dialogMessage,
  type: dialogType,
  confirmClass: dialogConfirmClass,
  open: openDialog,
  onConfirm: onDialogConfirm
} = useDialog()

/* ---------- Computed ---------- */
const sortedRules = computed(() => {
  return [...rules.value].sort((a, b) => {
    if (a.priority !== b.priority) return a.priority - b.priority
    return (a.id || 0) - (b.id || 0)
  })
})

function roleLabel(role: InjectRole): string {
  return role.charAt(0).toUpperCase() + role.slice(1)
}

function positionLabel(pos: InjectPosition): string {
  switch (pos) {
    case 'prepend': return t('promptInjection.positionPrepend')
    case 'append': return t('promptInjection.positionAppend')
    case 'replace_system': return t('promptInjection.positionReplaceSystem')
    default: return pos
  }
}

/* ---------- Load ---------- */
async function loadModelName() {
  try {
    const res = await modelApi.get(modelId.value)
    modelName.value = res.data.modelName || ''
  } catch {
    modelName.value = ''
  }
}

async function loadRules() {
  try {
    const res = await promptInjectionApi.listByModelId(modelId.value)
    rules.value = res.data
  } catch (e: any) {
    openDialog({ title: t('error.loadFailed'), message: e.message })
  }
}

/* ---------- Create / Edit ---------- */
function resetForm() {
  form.value = {
    name: '',
    injectRole: 'system',
    injectPosition: 'prepend',
    content: '',
    enabled: 1,
    priority: 0
  }
}

function openCreateForm() {
  editingRule.value = null
  resetForm()
  formVisible.value = true
}

function openEditForm(rule: PromptInjection) {
  editingRule.value = rule
  form.value = {
    name: rule.name || '',
    injectRole: rule.injectRole,
    injectPosition: rule.injectPosition,
    content: rule.content,
    enabled: rule.enabled,
    priority: rule.priority
  }
  formVisible.value = true
}

function cancelForm() {
  formVisible.value = false
  editingRule.value = null
}

async function handleSave() {
  if (!form.value.content.trim()) return
  saving.value = true
  try {
    if (editingRule.value?.id) {
      await promptInjectionApi.update(editingRule.value.id, form.value)
    } else {
      await promptInjectionApi.create(modelId.value, form.value)
    }
    formVisible.value = false
    editingRule.value = null
    openDialog({ title: t('common.success'), message: t('promptInjection.saveSuccess') })
    await loadRules()
  } catch (e: any) {
    openDialog({ title: t('promptInjection.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}

/* ---------- Toggle enabled ---------- */
async function toggleEnabled(rule: PromptInjection) {
  const newEnabled = rule.enabled === 1 ? 0 : 1
  toggleLoading.value = rule.id!
  try {
    await promptInjectionApi.update(rule.id!, { enabled: newEnabled })
    rule.enabled = newEnabled
  } catch (e: any) {
    openDialog({ title: t('error.updateFailed'), message: e.message })
  } finally {
    toggleLoading.value = null
  }
}

/* ---------- Delete ---------- */
function confirmDelete(rule: PromptInjection) {
  openDialog({
    title: t('common.confirmDelete'),
    message: t('promptInjection.deleteConfirm').replace('{name}', rule.name || rule.injectRole + ' rule'),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        await promptInjectionApi.delete(rule.id!)
        openDialog({ title: t('common.success'), message: t('promptInjection.deleteSuccess') })
        await loadRules()
      } catch (e: any) {
        openDialog({ title: t('promptInjection.deleteFailed'), message: e.message })
      }
    }
  })
}

onMounted(async () => {
  await loadModelName()
  await loadRules()
})
</script>

<style scoped>
/* ---------- Badges ---------- */
.role-badge {
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 10px;
  font-weight: 500;
  white-space: nowrap;
}
.role-badge.role-system {
  background: rgba(88, 166, 255, 0.15);
  color: var(--accent-blue, #58a6ff);
}
.role-badge.role-user {
  background: rgba(46, 160, 67, 0.15);
  color: #2ea043;
}
.role-badge.role-assistant {
  background: rgba(210, 153, 34, 0.15);
  color: #d29922;
}

.position-badge {
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 10px;
  font-weight: 500;
  white-space: nowrap;
}
.position-badge.pos-prepend {
  background: rgba(88, 166, 255, 0.12);
  color: var(--accent-blue, #58a6ff);
}
.position-badge.pos-append {
  background: rgba(163, 113, 247, 0.12);
  color: #a371f7;
}
.position-badge.pos-replace_system {
  background: rgba(248, 81, 73, 0.12);
  color: #f85149;
}

.priority-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  background: var(--bg-tertiary);
  border-radius: 6px;
  color: var(--text-secondary);
}

.rule-count {
  font-size: 12px;
  color: var(--text-muted);
}

/* Content preview */
.content-preview {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-secondary);
  font-family: var(--font-mono, monospace);
  max-width: 200px;
}

/* ---------- Toggle ---------- */
.toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 20px;
  transition: all 0.2s;
}
.toggle-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.toggle-btn:hover:not(:disabled) {
  background: var(--bg-hover);
}
.toggle-track {
  width: 32px;
  height: 18px;
  border-radius: 9px;
  position: relative;
  transition: background 0.2s;
}
.toggle-btn.active .toggle-track {
  background: var(--accent-green);
}
.toggle-btn.inactive .toggle-track {
  background: var(--text-muted);
}
.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: white;
  transition: transform 0.2s;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}
.toggle-btn.active .toggle-thumb {
  transform: translateX(14px);
}
.toggle-label {
  font-size: 12px;
  font-weight: 500;
  min-width: 40px;
}
.toggle-btn.active .toggle-label {
  color: var(--accent-green);
}
.toggle-btn.inactive .toggle-label {
  color: var(--text-muted);
}

/* Small toggle for mobile */
.toggle-btn-sm .toggle-track {
  width: 28px;
  height: 16px;
}
.toggle-btn-sm .toggle-thumb {
  width: 12px;
  height: 12px;
}
.toggle-btn-sm .toggle-label {
  font-size: 11px;
  min-width: 32px;
}
.toggle-btn-sm.active .toggle-thumb {
  transform: translateX(12px);
}

/* ---------- Dialog form ---------- */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  padding: 16px;
}
.dialog-box.pi-dialog {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  width: 100%;
  max-width: 520px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  overflow: hidden;
  max-height: 90vh;
  overflow-y: auto;
}
.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
}
.dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.dialog-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s;
}
.dialog-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.dialog-body {
  padding: 20px;
}
.dialog-footer-inner {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
}

/* Form rows */
.form-row {
  display: flex;
  gap: 12px;
}

.pi-content-input {
  font-family: var(--font-mono, monospace);
  font-size: 13px;
  line-height: 1.6;
  resize: vertical;
}

/* Switch group inside form */
.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
}
.switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
}
.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}
.switch-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--border-color, #444);
  transition: 0.3s;
  border-radius: 22px;
}
.switch-slider::before {
  position: absolute;
  content: "";
  height: 16px;
  width: 16px;
  left: 3px;
  bottom: 3px;
  background-color: var(--bg-primary, #fff);
  transition: 0.3s;
  border-radius: 50%;
}
.switch input:checked + .switch-slider {
  background-color: var(--accent-blue, #58a6ff);
}
.switch input:checked + .switch-slider::before {
  transform: translateX(18px);
}
.switch-label {
  font-size: 13px;
  color: var(--text-secondary);
}

/* ---------- Mobile card list ---------- */
.mobile-card-list {
  display: none;
  flex-direction: column;
  gap: 12px;
}
.mobile-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 16px;
  transition: border-color 0.2s;
}
.mobile-card:hover {
  border-color: rgba(88, 166, 255, 0.15);
}
.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.mobile-card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.mobile-card-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
}
.mobile-card-label {
  color: var(--text-muted);
  flex-shrink: 0;
}
.mobile-card-content {
  font-size: 12px;
  color: var(--text-secondary);
  font-family: var(--font-mono, monospace);
  padding: 8px 10px;
  background: var(--bg-tertiary);
  border-radius: 6px;
  margin-bottom: 8px;
  line-height: 1.6;
  word-break: break-all;
}
.mobile-card-divider {
  height: 1px;
  background: var(--border-color);
  margin: 12px 0;
}
.mobile-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

/* ---------- Transition ---------- */
.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.2s ease;
}
.dialog-enter-active .dialog-box,
.dialog-leave-active .dialog-box {
  transition: transform 0.2s ease, opacity 0.2s ease;
}
.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}
.dialog-enter-from .dialog-box,
.dialog-leave-to .dialog-box {
  opacity: 0;
  transform: scale(0.96) translateY(-8px);
}

/* ---------- Responsive ---------- */
@media (max-width: 768px) {
  .table-container {
    display: none;
  }
  .mobile-card-list {
    display: flex;
  }
}
@media (min-width: 769px) {
  .mobile-card-list {
    display: none;
  }
}
</style>
