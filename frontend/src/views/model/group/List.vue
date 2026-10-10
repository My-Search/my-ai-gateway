<template>
  <div class="group-mgr">
    <!-- ── Page Header ── -->
    <div class="mgr-header">
      <div class="header-left">
        <h2>{{ t('group.list.title') }}</h2>
        <p class="header-subtitle">{{ t('group.list.subtitle') }}</p>
      </div>
      <router-link to="/admin/model/group/form" class="btn btn-primary">
        <SvgIcon name="plus" :size="14" /> {{ t('group.list.add') }}
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
          :placeholder="t('group.list.searchPlaceholder')"
        />
      </div>
      <div class="filter-selects">
        <select v-model="statusFilter" class="filter-select">
          <option value="all">{{ t('group.list.allStatus') }}</option>
          <option value="enabled">{{ t('common.enabled') }}</option>
          <option value="disabled">{{ t('common.disabled') }}</option>
        </select>
        <select v-model="strategyFilter" class="filter-select">
          <option value="all">{{ t('group.list.allTypes') }}</option>
          <option value="random">{{ t('group.strategy.random') }}</option>
          <option value="round_robin">{{ t('group.strategy.roundRobin') }}</option>
          <option value="failover">{{ t('group.strategy.failover') }}</option>
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
      <LoadingSpinner :size="28" />
    </div>

    <!-- ── Card Grid ── -->
    <template v-else>
      <div v-if="filteredGroups.length === 0" class="mgr-empty">
        {{ t('group.list.empty') }}
      </div>

      <div v-else :class="['mgr-grid', viewMode === 'list' ? 'mgr-list' : '']">
        <div
          v-for="g in filteredGroups"
          :key="g.id"
          class="model-card color-block"
          :style="{ '--card-accent': accentColor(g.name) }"
          :class="{ 'card-disabled': (g.enabled ?? 1) !== 1 }"
        >
          <!-- Card Top: icon + name + toggle -->
          <div class="card-top">
            <div class="card-icon-wrap" :style="{ background: iconGradient(g.name) }">
              <span class="card-icon-letter">{{ g.name.charAt(0).toUpperCase() }}</span>
              <span class="icon-corner-tag">{{ t('group.list.cornerTag') }}</span>
            </div>
            <div class="card-name-area">
              <div class="card-name-row">
                <strong class="card-name">{{ g.name }}</strong>
              </div>
              <div class="card-tags">
                <span class="tag" :class="strategyTagClass(g.strategy || 'random')">
                  {{ strategyLabel(g.strategy || 'random') }}
                </span>
                <span v-if="g.sticky === 1" class="tag tag-success">{{ t('group.list.stickyOn') }}</span>
              </div>
            </div>
            <ToggleSwitch
              :model-value="(g.enabled ?? 1) === 1"
              size="sm"
              :show-label="false"
              :disabled="togglingId === g.id"
              @update:model-value="toggleEnabled(g)"
            />
          </div>

          <!-- Stats Row -->
          <div class="card-stats">
            <div class="stat-item">
              <span class="stat-label">{{ t('group.list.statMembers') }}</span>
              <span class="stat-value">{{ g.memberCount ?? 0 }}</span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-item">
              <span class="stat-label">{{ t('group.list.statRoutable') }}</span>
              <span class="stat-value">
                {{ g.availableCount ?? 0 }}
                <span
                  v-if="(g.brokenCount ?? 0) > 0"
                  class="stat-broken"
                  :title="t('group.list.statBroken') + ' ' + g.brokenCount"
                >{{ g.brokenCount }}</span>
              </span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-item">
              <span class="stat-label">{{ t('group.list.statAvgResponse') }}</span>
              <span class="stat-value">{{ g.ttftMs != null ? formatSeconds(g.ttftMs) : '-' }}</span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-item">
              <span class="stat-label">{{ t('group.list.statAvgOutputSpeed') }}</span>
              <span class="stat-value">{{ g.outputSpeed != null && g.outputSpeed > 0 ? g.outputSpeed.toFixed(1) + ' t/s' : '-' }}</span>
            </div>
          </div>

          <!-- Card Actions -->
          <div class="card-actions">
            <router-link
              :to="'/admin/model/group/' + g.id"
              class="action-btn"
              :title="t('group.list.manageMembers')"
            >
              <SvgIcon name="list" :size="14" />
            </router-link>
            <div class="action-spacer"></div>
            <div class="dropdown-wrapper" @click.stop>
              <button class="action-btn" @click="toggleDropdown(g.id!)" :title="t('group.list.actions')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="5" r="1.5" fill="currentColor" stroke="none"/>
                  <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/>
                  <circle cx="12" cy="19" r="1.5" fill="currentColor" stroke="none"/>
                </svg>
              </button>
              <div v-if="openDropdown === g.id" class="dropdown-menu" @click="closeDropdown">
                <router-link :to="'/admin/model/group/form/' + g.id" class="dropdown-item">
                  <SvgIcon name="edit" :size="14" /> {{ t('common.edit') }}
                </router-link>
                <button class="dropdown-item dropdown-danger" @click.stop="confirmDelete(g)">
                  <SvgIcon name="trash" :size="14" /> {{ t('common.delete') }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onActivated } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { useToast } from '@/composables/useToast'
import { groupApi, type ModelGroup } from '@/api/group'
import { formatSeconds } from '@/utils/format'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'

/* ══════════════════════════════════════
   State
   ══════════════════════════════════════ */
const { t } = useI18n()
const {
  visible: dialogVisible,
  title: dialogTitle,
  message: dialogMessage,
  type: dialogType,
  confirmClass: dialogConfirmClass,
  onConfirm: onDialogConfirm,
  open
} = useDialog()
const { showToast } = useToast()

const groups = ref<ModelGroup[]>([])
const loading = ref(true)
const togglingId = ref<number | null>(null)
const openDropdown = ref<number | null>(null)

// Filter state（与入口模型列表页一致：全部状态值用 'all'）
const searchQuery = ref('')
const statusFilter = ref('all')
const strategyFilter = ref('all')
const viewMode = ref<'grid' | 'list'>('grid')

/* ══════════════════════════════════════
   Computed
   ══════════════════════════════════════ */
const filteredGroups = computed(() => {
  return groups.value.filter(g => {
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase()
      if (!g.name.toLowerCase().includes(q)) return false
    }
    if (statusFilter.value === 'enabled' && (g.enabled ?? 1) !== 1) return false
    if (statusFilter.value === 'disabled' && (g.enabled ?? 1) === 1) return false
    if (strategyFilter.value !== 'all' && (g.strategy || 'random') !== strategyFilter.value) return false
    return true
  })
})

/* ══════════════════════════════════════
   Color blocks

   小组卡片按「颜色块」渲染：名称哈希命中一挡颜色，整张卡片（底色、描边、
   图标）都用同一挡色，不同小组在网格里靠颜色即可区分。入口模型卡片保持中性
   灰底，这是两个页面有意保留的差异。

   accent 是该挡的主色，gradient 用于图标块。
   ══════════════════════════════════════ */
interface BlockColor {
  gradient: string
  accent: string
}

const blockPalette: BlockColor[] = [
  { gradient: 'linear-gradient(135deg, #58a6ff, #1a5fb4)', accent: '#58a6ff' },
  { gradient: 'linear-gradient(135deg, #98c379, #3b6e22)', accent: '#98c379' },
  { gradient: 'linear-gradient(135deg, #e5c07b, #b8860b)', accent: '#e5c07b' },
  { gradient: 'linear-gradient(135deg, #c678dd, #7c3a9e)', accent: '#c678dd' },
  { gradient: 'linear-gradient(135deg, #56b6c2, #1a7a8a)', accent: '#56b6c2' },
  { gradient: 'linear-gradient(135deg, #e06c75, #b33b3b)', accent: '#e06c75' },
  { gradient: 'linear-gradient(135deg, #d4a0f0, #8b5cf6)', accent: '#d4a0f0' },
  { gradient: 'linear-gradient(135deg, #7ee787, #2d7d46)', accent: '#7ee787' },
]

function colorIndexOf(name: string): number {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = ((hash << 5) - hash) + name.charCodeAt(i)
    hash |= 0
  }
  return Math.abs(hash) % blockPalette.length
}

function iconGradient(name: string): string {
  return blockPalette[colorIndexOf(name)].gradient
}

function accentColor(name: string): string {
  return blockPalette[colorIndexOf(name)].accent
}

/* ══════════════════════════════════════
   Helpers
   ══════════════════════════════════════ */
function strategyLabel(s: string): string {
  const key = {
    failover: 'group.strategy.failover',
    random: 'group.strategy.random',
    round_robin: 'group.strategy.roundRobin'
  }[s]
  return key ? t(key) : s
}

function strategyTagClass(s: string): string {
  return s === 'random'
    ? 'tag-info'
    : s === 'round_robin'
      ? 'tag-success'
      : 'tag-warning'
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
function confirmDelete(g: ModelGroup) {
  closeDropdown()
  if (!g.id) return
  open({
    title: t('common.confirmDelete'),
    message: t('group.list.deleteConfirm').replace('{name}', g.name),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        const res = await groupApi.remove(g.id!)
        if (res.data.success) {
          showToast(t('group.list.deleted'), { type: 'success' })
          await loadData()
        } else {
          showToast(res.data.error || t('error.unknown'), { type: 'error', duration: 4000 })
        }
      } catch (e: any) {
        showToast(e.message || t('error.unknown'), { type: 'error' })
      }
    }
  })
}

// 启用/禁用走二次确认，与入口模型列表页一致；小组被入口模型关联时
// 启用状态影响实际路由，直接切换容易误操作。
function toggleEnabled(g: ModelGroup) {
  if (!g.id) return
  const newEnabled = (g.enabled ?? 1) === 1 ? 0 : 1
  const action = newEnabled === 1 ? t('group.list.enableConfirm') : t('group.list.disableConfirm')
  open({
    title: t('group.list.toggleTitle'),
    message: t('group.list.toggleMessage').replace('{name}', g.name).replace('{action}', action),
    type: 'confirm',
    confirmClass: newEnabled === 1 ? 'btn-success' : 'btn-warning',
    onConfirm: async () => {
      togglingId.value = g.id!
      try {
        const res = await groupApi.update(g.id!, { enabled: newEnabled })
        if (res.data.success) {
          g.enabled = newEnabled
          showToast(t('group.list.toggleSuccess'), { type: 'success' })
        } else {
          showToast(res.data.error || t('error.updateFailed'), { type: 'error' })
        }
      } catch (e: any) {
        showToast(e.message || t('error.updateFailed'), { type: 'error' })
      } finally {
        togglingId.value = null
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
    const res = await groupApi.list()
    groups.value = res.data
  } catch (e: any) {
    showToast(e.message || t('error.loadFailed'), { type: 'error' })
  } finally {
    loading.value = false
  }
}

/* ══════════════════════════════════════
   Lifecycle
   ══════════════════════════════════════ */
// 供 keep-alive 按组件名缓存（Layout.vue cachedViews）
defineOptions({ name: 'ModelGroupList' })

// onMounted 负责首次加载；onActivated 仅在 keep-alive 缓存恢复（菜单切回）时刷新，
// 首次跳过避免重复加载（与入口模型列表页一致）。
let activatedCount = 0
onMounted(() => {
  loadData()
  document.addEventListener('click', onDocumentClick)
})
onActivated(() => {
  if (activatedCount++ > 0) loadData()
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<style scoped>
/* 结构与样式对齐入口模型列表页（model/List.vue），类名与布局保持一致 */
.group-mgr {
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

/* ── Card ── */
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

/* ── Color Block（小组卡片专属：与入口模型页的中性灰底不同，整卡按哈希色着色）──
   --card-accent 由模板内联设置（accentColor(name)）。

   常态克制：底色只带一层很淡的哈希色渐变，描边轻染，阴影收敛为
   「微弱高光 + 贴近的软投影」，页面安静；立体效果（抬升、扩散阴影、
   主色氛围光）留到 hover 再释放。投影里的 accent 用 color-mix 自动
   降透明度（srgb 混合把 transparent 按 alpha 0 处理）。 */
.color-block {
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--card-accent) 8%, var(--bg-secondary)) 0%,
    color-mix(in srgb, var(--card-accent) 4%, var(--bg-secondary)) 100%
  );
  border-color: color-mix(in srgb, var(--card-accent) 24%, var(--border-color));
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, #ffffff 8%, transparent),
    0 1px 2px color-mix(in srgb, #000000 20%, transparent);
  transform: translateY(0);
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease, background 0.2s ease;
}

.color-block:hover {
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--card-accent) 14%, var(--bg-secondary)) 0%,
    color-mix(in srgb, var(--card-accent) 8%, var(--bg-secondary)) 100%
  );
  border-color: color-mix(in srgb, var(--card-accent) 45%, var(--border-color));
  transform: translateY(-2px);
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, #ffffff 18%, transparent),
    inset 0 -1px 0 color-mix(in srgb, #000000 18%, transparent),
    0 2px 4px color-mix(in srgb, #000000 30%, transparent),
    0 12px 28px -8px color-mix(in srgb, #000000 42%, transparent),
    0 8px 32px -10px color-mix(in srgb, var(--card-accent) 50%, transparent);
}

/* 图标块：渐变图标本身就是视觉重点，常态不加投影，hover 时才亮出主色氛围光 */
.color-block .card-icon-wrap {
  transition: box-shadow 0.2s ease;
}

.color-block:hover .card-icon-wrap {
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, #ffffff 26%, transparent),
    0 4px 10px -3px color-mix(in srgb, #000000 35%, transparent),
    0 3px 14px -4px color-mix(in srgb, var(--card-accent) 45%, transparent);
}

/* 统计行与顶部的分隔线也跟着主色走，避免灰线打断色块整体感 */
.color-block .card-stats {
  border-top-color: color-mix(in srgb, var(--card-accent) 18%, var(--border-color));
  border-bottom-color: color-mix(in srgb, var(--card-accent) 18%, var(--border-color));
}

.color-block .stat-divider {
  background: color-mix(in srgb, var(--card-accent) 24%, var(--border-color));
}

/* ── Card Top: icon + name + toggle ── */
.card-top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-icon-wrap {
  position: relative;
  overflow: hidden;
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

/* 图标右下角斜置「小组」角标：横条中心对准右下角斜切线中点 (30,30)，
   -45° 旋转后与底边中点→右边中点的斜线平行，两端由外层 overflow:hidden
   裁切成标准 corner ribbon。深色缎面 + 白字，在任意挡色渐变上都可读。 */
.icon-corner-tag {
  position: absolute;
  top: 30px;
  left: 30px;
  width: 60px;
  padding: 1px 0;
  text-align: center;
  font-size: 10px;
  line-height: 1.3;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  transform: translate(-50%, -50%) rotate(-45deg);
  pointer-events: none;
  white-space: nowrap;
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

/* 熔断成员数：跟在可路由数后面的小红标，悬停出提示 */
.stat-broken {
  margin-left: 2px;
  font-size: 11px;
  color: var(--accent-red);
}

.stat-divider {
  width: 1px;
  height: 24px;
  background: var(--border-color);
  flex-shrink: 0;
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
  grid-template-columns: auto 1fr auto;
  gap: 0 16px;
  padding: 12px 16px;
}

.mgr-list .card-top {
  grid-column: 1 / 2;
  grid-row: 1;
}

.mgr-list .card-stats {
  grid-column: 2 / 3;
  grid-row: 1;
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
  grid-row: 1;
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
    grid-template-rows: auto auto;
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
}

@media (min-width: 769px) and (max-width: 1024px) {
  .mgr-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
