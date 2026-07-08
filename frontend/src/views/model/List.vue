<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title"><SvgIcon name="model" :size="18" /> {{ t('model.list.title') }}</div>
      <router-link to="/admin/model/form" class="btn btn-primary"><SvgIcon name="plus" :size="14" /> {{ t('model.list.add') }}</router-link>
    </div>

    <!-- Card Grid -->
    <div class="card-grid">
      <div v-for="m in models" :key="m.id" class="model-card">
        <!-- Card Head: model name + toggle -->
        <div class="card-head">
          <div class="card-title-area">
            <strong class="model-name" :class="{ 'is-hidden': m.hidden === 1 }">{{ m.modelName }}</strong>
            <span v-if="m.hidden === 1" class="hidden-badge">{{ t('model.list.hidden') }}</span>
          </div>
          <button
            class="toggle-btn"
            :class="m.enabled === 1 ? 'active' : 'inactive'"
            :title="m.enabled === 1 ? t('model.list.clickToDisable') : t('model.list.clickToEnable')"
            @click.stop="toggleEnabled(m)"
            :disabled="toggleLoading === m.id"
          >
            <span class="toggle-track">
              <span class="toggle-thumb"></span>
            </span>
            <span class="toggle-label">{{ m.enabled === 1 ? t('common.enabled') : t('common.disabled') }}</span>
          </button>
        </div>

        <!-- Description -->
        <div class="card-desc" :title="m.description || undefined">
          {{ m.description || '\u2014' }}
        </div>

        <!-- Meta badges -->
        <div class="card-meta">
          <span class="badge" :class="strategyBadge(m.strategy)">
            {{ strategyLabel(m.strategy) }}
          </span>
          <span class="mode-badge" :class="`mode-${m.relMode || 'self_add'}`">
            {{ (m.relMode || 'self_add') === 'inherit' ? t('model.rels.modeInherit') : t('model.rels.modeSelfAdd') }}
          </span>
          <span class="card-created">{{ formatLocalDateTimeFull(m.createdAt) }}</span>
        </div>

        <div class="card-divider"></div>

        <!-- Card Foot: action buttons + dropdown -->
        <div class="card-foot">
          <router-link
            :to="`/admin/model/rels/${m.id}`"
            class="action-btn"
            :title="t('model.list.manageRels')"
          >
            <SvgIcon name="link" :size="14" />
          </router-link>
          <router-link
            :to="`/admin/model/circuit-breaker/${m.id}`"
            class="action-btn"
            :title="t('model.list.config')"
          >
            <SvgIcon name="zap" :size="14" />
          </router-link>
          <router-link
            :to="`/admin/model/advanced/${m.id}`"
            class="action-btn"
            :title="t('model.list.advanced')"
          >
            <SvgIcon name="settings" :size="14" />
          </router-link>
          <div class="dropdown-wrapper" @click.stop>
            <button class="action-btn dropdown-toggle" @click="toggleDropdown(m.id!)" :title="t('model.list.actions')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="5" r="1.5" fill="currentColor" stroke="none"/>
                <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/>
                <circle cx="12" cy="19" r="1.5" fill="currentColor" stroke="none"/>
              </svg>
            </button>
            <div v-if="openDropdown === m.id" class="dropdown-menu" @click="closeDropdown">
              <router-link :to="`/admin/model/form/${m.id}`" class="dropdown-item">
                <SvgIcon name="edit" :size="14" /> {{ t('model.list.edit') }}
              </router-link>
              <button class="dropdown-item dropdown-danger" @click.stop="confirmDelete(m)">
                <SvgIcon name="trash" :size="14" /> {{ t('model.list.delete') }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Empty state -->
      <div v-if="!models.length" class="empty-state">
        {{ t('model.list.empty') }}
      </div>
    </div>
  </div>

  <!-- Dialog -->
  <Dialog
    v-model="visible"
    :title="title"
    :type="type"
    :confirm-class="confirmClass"
    @confirm="onConfirm"
  >
    {{ message }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { modelApi, type CustomModel } from '@/api/model'
import { formatLocalDateTimeFull } from '@/utils/date'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()

const models = ref<CustomModel[]>([])
const toggleLoading = ref<number | null>(null)
const openDropdown = ref<number | null>(null)

function toggleDropdown(id: number) {
  openDropdown.value = openDropdown.value === id ? null : id
}

function closeDropdown() {
  openDropdown.value = null
}

function onDocumentClick() {
  closeDropdown()
}

function strategyLabel(s?: string) {
  return s === 'random'
    ? t('model.list.strategyRandom')
    : s === 'round_robin'
      ? t('model.list.strategyRoundRobin')
      : t('model.list.strategyFailover')
}

function strategyBadge(s?: string) {
  return s === 'random'
    ? 'badge-info'
    : s === 'round_robin'
      ? 'badge-success'
      : 'badge-warning'
}

function confirmDelete(m: CustomModel) {
  closeDropdown()
  open({
    title: t('common.confirmDelete'),
    message: t('model.list.deleteConfirm').replace('{name}', m.modelName),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      modelApi.delete(m.id!).then(() => loadModels()).catch(e =>
        open({ title: t('error.deleteFailed'), message: e.message })
      )
    }
  })
}

async function toggleEnabled(m: CustomModel) {
  const newEnabled = m.enabled === 1 ? 0 : 1
  const action = newEnabled === 1 ? t('model.list.enableConfirm') : t('model.list.disableConfirm')
  open({
    title: t('model.list.toggleTitle'),
    message: t('model.list.toggleMessage').replace('{name}', m.modelName).replace('{action}', action),
    type: 'confirm',
    confirmClass: newEnabled === 1 ? 'btn-success' : 'btn-warning',
    onConfirm: async () => {
      toggleLoading.value = m.id!
      try {
        await modelApi.update(m.id!, { enabled: newEnabled })
        m.enabled = newEnabled
        open({ message: t('model.list.toggleSuccess') })
      } catch (e: any) {
        open({ title: t('error.updateFailed'), message: e.message })
      } finally {
        toggleLoading.value = null
      }
    }
  })
}

async function loadModels() {
  try {
    const res = await modelApi.list()
    models.value = res.data
  } catch (e: any) {
    open({ title: t('error.loadFailed'), message: e.message })
  }
}

onMounted(() => {
  loadModels()
  document.addEventListener('click', onDocumentClick)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<style scoped>
.card {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* ── Card Grid ── */
.card-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
  align-content: start;
}

/* ── Model Card ── */
.model-card {
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.model-card:hover {
  border-color: color-mix(in srgb, var(--accent-blue) 30%, var(--border-color));
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

/* Card Head */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.card-title-area {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.model-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'SF Mono', 'Fira Code', monospace;
}

.hidden-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--bg-hover);
  color: var(--text-muted);
  white-space: nowrap;
  flex-shrink: 0;
  user-select: none;
}

/* Description */
.card-desc {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-bottom: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  min-height: 18px;
}

/* Meta */
.card-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.card-created {
  font-size: 11px;
  color: var(--text-muted);
  margin-left: auto;
  white-space: nowrap;
}

/* Divider */
.card-divider {
  height: 1px;
  background: var(--border-color);
  margin-top: auto;
  margin-bottom: 10px;
}

/* Card Foot: actions */
.card-foot {
  display: flex;
  align-items: center;
  gap: 4px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 6px;
  color: var(--text-secondary);
  text-decoration: none;
  cursor: pointer;
  background: none;
  border: none;
  font-family: inherit;
  transition: all 0.15s;
}

.action-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

/* Dropdown */
.dropdown-wrapper {
  position: relative;
  margin-left: auto;
}

.dropdown-menu {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  min-width: 140px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 4px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  z-index: 100;
  overflow: hidden;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--text-primary);
  background: none;
  border: none;
  cursor: pointer;
  font-family: inherit;
  text-decoration: none;
  border-radius: 4px;
  transition: background 0.1s;
  white-space: nowrap;
}

.dropdown-item:hover {
  background: var(--bg-hover);
}

.dropdown-danger {
  color: var(--accent-red);
}

.dropdown-danger:hover {
  background: color-mix(in srgb, var(--accent-red) 12%, transparent);
}

/* Toggle Button (same as before, slightly compact) */
.toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 3px 6px;
  border-radius: 16px;
  transition: all 0.2s;
  flex-shrink: 0;
}
.toggle-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.toggle-btn:hover:not(:disabled) {
  background: var(--bg-hover);
}
.toggle-track {
  width: 28px;
  height: 16px;
  border-radius: 8px;
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
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: white;
  transition: transform 0.2s;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}
.toggle-btn.active .toggle-thumb {
  transform: translateX(12px);
}
.toggle-label {
  font-size: 11px;
  font-weight: 500;
  min-width: 34px;
}
.toggle-btn.active .toggle-label {
  color: var(--accent-green);
}
.toggle-btn.inactive .toggle-label {
  color: var(--text-muted);
}

/* Mode badge */
.mode-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
  letter-spacing: 0.3px;
  white-space: nowrap;
}
.mode-badge.mode-self_add {
  background: rgba(88, 166, 255, 0.15);
  color: var(--accent-blue, #58a6ff);
}
.mode-badge.mode-inherit {
  background: rgba(210, 153, 34, 0.15);
  color: #d29922;
}

/* Empty state */
.empty-state {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 14px;
}

/* ── Responsive ── */
@media (max-width: 768px) {
  .card-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .model-card {
    padding: 14px;
  }
}

@media (min-width: 769px) and (max-width: 1024px) {
  .card-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
