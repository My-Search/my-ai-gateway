<template>
  <div class="model-mgr">
    <!-- ── Page Header ── -->
    <div class="mgr-header">
      <div class="header-left">
        <h2>{{ t('model.list.title') }}</h2>
        <p class="header-subtitle">{{ t('model.list.subtitle') }}</p>
      </div>
      <router-link to="/admin/model/form" class="btn btn-primary">
        <SvgIcon name="plus" :size="14" /> {{ t('model.list.add') }}
      </router-link>
    </div>

    <!-- ── Filter Bar ── -->
    <div class="mgr-filter-bar">
      <div class="filter-search">
        <SvgIcon name="search" :size="14" class="search-icon" />
        <input
          v-model="searchQuery"
          type="text"
          class="filter-input"
          :placeholder="t('model.list.searchPlaceholder')"
        />
      </div>
      <div class="filter-selects">
        <select v-model="statusFilter" class="filter-select">
          <option value="all">{{ t('model.list.allStatus') }}</option>
          <option value="enabled">{{ t('model.list.enabled') }}</option>
          <option value="disabled">{{ t('model.list.disabled') }}</option>
        </select>
        <select v-model="typeFilter" class="filter-select">
          <option value="all">{{ t('model.list.allTypes') }}</option>
          <option value="failover">{{ t('model.list.strategyFailover') }}</option>
          <option value="random">{{ t('model.list.strategyRandom') }}</option>
          <option value="round_robin">{{ t('model.list.strategyRoundRobin') }}</option>
        </select>
      </div>
      <div class="filter-view">
        <button
          class="view-btn"
          :class="{ active: viewMode === 'grid' }"
          @click="viewMode = 'grid'"
        >
          <SvgIcon name="grid" :size="16" />
        </button>
        <button
          class="view-btn"
          :class="{ active: viewMode === 'list' }"
          @click="viewMode = 'list'"
        >
          <SvgIcon name="list" :size="16" />
        </button>
      </div>
    </div>

    <!-- ── Loading ── -->
    <div v-if="loading" class="mgr-loading">
      <LoadingSpinner size="28" />
    </div>

    <!-- ── Card Grid ── -->
    <template v-else>
      <div v-if="filteredCards.length === 0" class="mgr-empty">
        {{ t('model.list.empty') }}
      </div>

      <div v-else :class="['mgr-grid', viewMode === 'list' ? 'mgr-list' : '']">
        <div
          v-for="card in filteredCards"
          :key="card.model.id"
          class="model-card"
          :class="{ 'card-disabled': card.model.enabled !== 1 }"
        >
          <!-- Card Top: icon + name + toggle -->
          <div class="card-top">
            <div class="card-icon-wrap" :style="{ background: iconGradient(card.model.modelName) }">
              <span class="card-icon-letter">{{ card.model.modelName.charAt(0).toUpperCase() }}</span>
            </div>
            <div class="card-name-area">
              <div class="card-name-row">
                <strong class="card-name" :class="{ 'is-hidden': card.model.hidden === 1 }">
                  {{ card.model.modelName }}
                </strong>
                <span v-if="card.model.hidden === 1" class="hidden-badge">{{ t('model.list.hidden') }}</span>
              </div>
              <div class="card-tags">
                <span class="tag" :class="strategyTagClass(card.model.strategy)">
                  {{ strategyLabel(card.model.strategy) }}
                </span>
                <span class="tag" :class="card.model.relMode === 'inherit' ? 'tag-inherit' : 'tag-self'">
                  {{ card.model.relMode === 'inherit' ? t('model.rels.modeInherit') : t('model.rels.modeSelfAdd') }}
                </span>
              </div>
            </div>
            <ToggleSwitch
              :model-value="card.model.enabled === 1"
              :active-label="t('common.enabled')"
              :inactive-label="t('common.disabled')"
              size="sm"
              :show-label="false"
              :disabled="toggleLoading === card.model.id"
              @update:model-value="toggleEnabled(card.model)"
            />
          </div>

          <!-- Stats Row -->
          <div class="card-stats">
            <div class="stat-item">
              <span class="stat-label">{{ t('model.list.todayRequests') }}</span>
              <span class="stat-value">{{ card.stats?.requests ?? '-' }}</span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-item">
              <span class="stat-label">{{ t('model.list.successRate') }}</span>
              <span class="stat-value" :class="rateColor(card.stats?.successRate)">
                {{ card.stats?.successRate != null ? card.stats.successRate + '%' : '-' }}
              </span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-item">
              <span class="stat-label">{{ t('model.list.avgResponse') }}</span>
              <span class="stat-value">{{ card.stats?.avgResponseTime != null ? card.stats.avgResponseTime + 'ms' : '-' }}</span>
            </div>
          </div>

          <!-- Sparkline -->
          <div class="card-sparkline" v-if="card.trend && card.trend.length >= 2">
            <svg :viewBox="'0 0 100 24'" class="sparkline-svg" preserveAspectRatio="none">
              <path :d="sparklinePaths(card.trend, 100, 24).area" fill="var(--sparkline-area)" stroke="none" />
              <path :d="sparklinePaths(card.trend, 100, 24).line" fill="none" stroke="var(--sparkline-line)" stroke-width="0.8" vector-effect="non-scaling-stroke" />
            </svg>
          </div>

          <!-- Card Actions -->
          <div class="card-actions">
            <router-link
              :to="`/admin/model/rels/${card.model.id}`"
              class="action-btn"
              :title="t('model.list.manageRels')"
            >
              <SvgIcon name="link" :size="14" />
            </router-link>
            <router-link
              :to="`/admin/model/circuit-breaker/${card.model.id}`"
              class="action-btn"
              :title="t('model.list.config')"
            >
              <SvgIcon name="zap" :size="14" />
            </router-link>
            <router-link
              :to="`/admin/model/advanced/${card.model.id}`"
              class="action-btn"
              :title="t('model.list.advanced')"
            >
              <SvgIcon name="settings" :size="14" />
            </router-link>
            <div class="action-spacer"></div>
            <div class="dropdown-wrapper" @click.stop>
              <button class="action-btn" @click="toggleDropdown(card.model.id!)" :title="t('model.list.actions')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="5" r="1.5" fill="currentColor" stroke="none"/>
                  <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/>
                  <circle cx="12" cy="19" r="1.5" fill="currentColor" stroke="none"/>
                </svg>
              </button>
              <div v-if="openDropdown === card.model.id" class="dropdown-menu" @click="closeDropdown">
                <router-link :to="`/admin/model/form/${card.model.id}`" class="dropdown-item">
                  <SvgIcon name="edit" :size="14" /> {{ t('model.list.edit') }}
                </router-link>
                <button class="dropdown-item dropdown-danger" @click.stop="confirmDelete(card.model)">
                  <SvgIcon name="trash" :size="14" /> {{ t('model.list.delete') }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { modelApi, type CustomModel, type ModelStatsItem } from '@/api/model'
import { sparklinePaths } from '@/utils/sparkline'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'

/* ══════════════════════════════════════
   Types
   ══════════════════════════════════════ */
interface ModelCardData {
  model: CustomModel
  stats?: ModelStatsItem
  trend: number[]
}

/* ══════════════════════════════════════
   State
   ══════════════════════════════════════ */
const { t } = useI18n()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()

const cards = ref<ModelCardData[]>([])
const loading = ref(true)
const toggleLoading = ref<number | null>(null)
const openDropdown = ref<number | null>(null)

// Filter state
const searchQuery = ref('')
const statusFilter = ref('all')
const typeFilter = ref('all')
const viewMode = ref<'grid' | 'list'>('grid')

/* ══════════════════════════════════════
   Computed
   ══════════════════════════════════════ */
const filteredCards = computed(() => {
  return cards.value.filter(card => {
    // Search by name
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase()
      if (!card.model.modelName.toLowerCase().includes(q)) return false
    }
    // Status filter
    if (statusFilter.value === 'enabled' && card.model.enabled !== 1) return false
    if (statusFilter.value === 'disabled' && card.model.enabled === 1) return false
    // Type filter
    if (typeFilter.value !== 'all' && card.model.strategy !== typeFilter.value) return false
    return true
  })
})

/* ══════════════════════════════════════
   Icon gradients
   ══════════════════════════════════════ */
const iconPalette = [
  'linear-gradient(135deg, #58a6ff, #1a5fb4)',
  'linear-gradient(135deg, #98c379, #3b6e22)',
  'linear-gradient(135deg, #e5c07b, #b8860b)',
  'linear-gradient(135deg, #c678dd, #7c3a9e)',
  'linear-gradient(135deg, #56b6c2, #1a7a8a)',
  'linear-gradient(135deg, #e06c75, #b33b3b)',
  'linear-gradient(135deg, #d4a0f0, #8b5cf6)',
  'linear-gradient(135deg, #7ee787, #2d7d46)',
]

function iconGradient(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = ((hash << 5) - hash) + name.charCodeAt(i)
    hash |= 0
  }
  return iconPalette[Math.abs(hash) % iconPalette.length]
}

/* ══════════════════════════════════════
   Helpers
   ══════════════════════════════════════ */
function strategyLabel(s?: string) {
  return s === 'random'
    ? t('model.list.strategyRandom')
    : s === 'round_robin'
      ? t('model.list.strategyRoundRobin')
      : t('model.list.strategyFailover')
}

function strategyTagClass(s?: string) {
  return s === 'random'
    ? 'tag-info'
    : s === 'round_robin'
      ? 'tag-success'
      : 'tag-warning'
}

function rateColor(rate?: number): string {
  if (rate == null) return ''
  if (rate >= 99) return 'rate-perfect'
  if (rate >= 95) return 'rate-good'
  if (rate >= 80) return 'rate-ok'
  return 'rate-poor'
}

/* ══════════════════════════════════════
   Dropdown
   ══════════════════════════════════════ */
function toggleDropdown(id: number) {
  openDropdown.value = openDropdown.value === id ? null : id
}

function closeDropdown() {
  openDropdown.value = null
}

function onDocumentClick() {
  closeDropdown()
}

/* ══════════════════════════════════════
   Actions
   ══════════════════════════════════════ */
function confirmDelete(m: CustomModel) {
  closeDropdown()
  open({
    title: t('common.confirmDelete'),
    message: t('model.list.deleteConfirm').replace('{name}', m.modelName),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      modelApi.delete(m.id!).then(() => loadData()).catch(e =>
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

/* ══════════════════════════════════════
   Data loading
   ══════════════════════════════════════ */
async function loadData() {
  loading.value = true
  try {
    const [modelsRes, statsRes] = await Promise.all([
      modelApi.list(),
      modelApi.getStats()
    ])
    const models = modelsRes.data
    const statsList = statsRes.data.stats ?? []
    const trends = statsRes.data.trends ?? {}

    // Build stats lookup by modelName
    const statsByModel = new Map<string, ModelStatsItem>()
    for (const s of statsList) {
      statsByModel.set(s.modelName, s)
    }

    cards.value = models.map(m => ({
      model: m,
      stats: statsByModel.get(m.modelName),
      trend: trends[m.modelName] ?? []
    }))
  } catch (e: any) {
    open({ title: t('error.loadFailed'), message: e.message })
  } finally {
    loading.value = false
  }
}

/* ══════════════════════════════════════
   Lifecycle
   ══════════════════════════════════════ */
onMounted(() => {
  loadData()
  document.addEventListener('click', onDocumentClick)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<style scoped>
/* ══════════════════════════════════════
   Layout
   ══════════════════════════════════════ */
.model-mgr {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 0;
}

/* ── Header ── */
.mgr-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.header-left h2 {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

.header-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.4;
}

/* ── Filter Bar ── */
.mgr-filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-search {
  position: relative;
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 180px;
  max-width: 320px;
}

.filter-search .search-icon {
  position: absolute;
  left: 10px;
  color: var(--text-muted);
  pointer-events: none;
}

.filter-input {
  width: 100%;
  padding: 7px 10px 7px 32px;
  font-size: 13px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius);
  color: var(--text-primary);
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s;
}

.filter-input:focus {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 15%, transparent);
}

.filter-input::placeholder {
  color: var(--text-muted);
}

.filter-selects {
  display: flex;
  gap: 8px;
}

.filter-select {
  padding: 7px 28px 7px 10px;
  font-size: 13px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius);
  color: var(--text-primary);
  font-family: inherit;
  outline: none;
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M0 0l5 6 5-6z' fill='%23888'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 10px center;
  transition: border-color 0.15s;
}

.filter-select:focus {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 15%, transparent);
}

/* ── View Toggle ── */
.filter-view {
  display: flex;
  gap: 2px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius);
  padding: 2px;
}

.view-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 28px;
  border: none;
  background: none;
  color: var(--text-muted);
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.15s;
}

.view-btn:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.view-btn.active {
  color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 12%, transparent);
}

/* ── Loading ── */
.mgr-loading {
  display: flex;
  justify-content: center;
  padding: 60px 0;
}

/* ── Empty ── */
.mgr-empty {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 14px;
}

/* ══════════════════════════════════════
   Card Grid
   ══════════════════════════════════════ */
.mgr-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

/* ── Model Card ── */
.model-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md, 10px);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.model-card:hover {
  border-color: color-mix(in srgb, var(--accent-blue) 35%, var(--border-color));
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.card-disabled {
  opacity: 0.65;
}

/* ── Card Top: icon + name + toggle ── */
.card-top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-icon-wrap {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-icon-letter {
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  line-height: 1;
  user-select: none;
}

.card-name-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.card-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.card-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'SF Mono', 'Fira Code', monospace;
}

.card-name.is-hidden {
  color: var(--text-muted);
}

.hidden-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--bg-hover);
  color: var(--text-muted);
  white-space: nowrap;
  flex-shrink: 0;
}

/* ── Card Tags ── */
.card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tag {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 8px;
  font-weight: 500;
  letter-spacing: 0.2px;
  white-space: nowrap;
}

.tag-warning {
  background: color-mix(in srgb, var(--accent-yellow) 15%, transparent);
  color: var(--accent-yellow);
}

.tag-info {
  background: color-mix(in srgb, var(--accent-blue) 15%, transparent);
  color: var(--accent-blue);
}

.tag-success {
  background: color-mix(in srgb, var(--accent-green) 15%, transparent);
  color: var(--accent-green);
}

.tag-inherit {
  background: color-mix(in srgb, #d29922 15%, transparent);
  color: #d29922;
}

.tag-self {
  background: color-mix(in srgb, var(--accent-blue) 15%, transparent);
  color: var(--accent-blue);
}

/* ── Stats Row ── */
.card-stats {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 8px 0;
  border-top: 1px solid var(--border-color);
  border-bottom: 1px solid var(--border-color);
}

.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 0;
}

.stat-label {
  font-size: 10px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.4px;
  white-space: nowrap;
}

.stat-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.stat-divider {
  width: 1px;
  height: 24px;
  background: var(--border-color);
  flex-shrink: 0;
}

/* Success rate colors */
.rate-perfect { color: var(--accent-green); }
.rate-good { color: var(--accent-green); }
.rate-ok { color: var(--accent-yellow); }
.rate-poor { color: var(--accent-red); }

/* ── Sparkline ── */
.card-sparkline {
  height: 32px;
  margin: 0 -4px;
}

.sparkline-svg {
  width: 100%;
  height: 100%;
  display: block;
}

/* — Sparkline color tokens — */
.model-mgr {
  --sparkline-line: rgba(88, 166, 255, 0.55);
  --sparkline-area: rgba(88, 166, 255, 0.06);
}

/* ── Card Actions ── */
.card-actions {
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

.action-spacer {
  flex: 1;
}

/* Dropdown */
.dropdown-wrapper {
  position: relative;
}

.dropdown-menu {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  min-width: 140px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius);
  padding: 4px;
  box-shadow: var(--shadow-lg);
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

/* ══════════════════════════════════════
   List View Mode
   ══════════════════════════════════════ */
.mgr-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mgr-list .model-card {
  display: grid;
  grid-template-columns: auto 1fr auto auto;
  grid-template-rows: auto auto;
  gap: 8px 16px;
  padding: 12px 16px;
}

.mgr-list .card-top {
  grid-column: 1 / 2;
  grid-row: 1 / 3;
}

.mgr-list .card-stats {
  grid-column: 2 / 3;
  grid-row: 1 / 2;
  border: none;
  padding: 0;
  gap: 12px;
}

.mgr-list .card-stats .stat-item {
  flex-direction: row;
  gap: 6px;
}

.mgr-list .card-stats .stat-divider {
  display: none;
}

.mgr-list .card-actions {
  grid-column: 3 / 4;
  grid-row: 1 / 3;
}

.mgr-list .card-sparkline {
  grid-column: 2 / 3;
  grid-row: 2 / 3;
  margin: 0;
  height: 28px;
  max-width: 200px;
}

/* ══════════════════════════════════════
   Responsive
   ══════════════════════════════════════ */
@media (max-width: 768px) {
  .mgr-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .mgr-filter-bar {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 8px;
  }

  /* Row 1: 搜索框独占整行 */
  .filter-search {
    max-width: none;
    grid-column: 1 / -1;
  }

  /* Row 2 左列：两个下拉筛选并排 */
  .filter-selects {
    display: flex;
    flex-direction: row;
    gap: 8px;
    min-width: 0;
  }

  .filter-select {
    width: auto;
    flex: none;
  }

  /* Row 2 右列：视图切换按钮 */
  .filter-view {
    align-self: center;
  }

  /* List view not suitable on mobile, stick to cards */
  .mgr-list .model-card {
    grid-template-columns: auto 1fr auto;
    grid-template-rows: auto auto auto;
  }

  .mgr-list .card-top {
    grid-column: 1 / 2;
    grid-row: 1 / 2;
  }

  .mgr-list .card-stats {
    grid-column: 1 / 4;
    grid-row: 2 / 3;
    border-top: 1px solid var(--border-color);
    padding-top: 8px;
    justify-content: space-around;
  }

  .mgr-list .card-actions {
    grid-column: 3 / 4;
    grid-row: 1 / 2;
  }

  .mgr-list .card-sparkline {
    grid-column: 1 / 4;
    grid-row: 3 / 4;
    max-width: none;
  }
}

@media (min-width: 769px) and (max-width: 1024px) {
  .mgr-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
