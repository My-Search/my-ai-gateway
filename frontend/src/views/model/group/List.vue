<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title"><SvgIcon name="model" :size="18" /> {{ t('group.list.title') }}</div>
      <router-link to="/admin/model/group/form" class="btn btn-primary btn-sm">
        <SvgIcon name="plus" :size="14" /> {{ t('group.list.add') }}
      </router-link>
    </div>

    <div class="filter-bar">
      <input
        v-model="keyword"
        type="text"
        class="form-control"
        :placeholder="t('group.list.searchPlaceholder')"
        style="max-width:260px;"
      />
      <select v-model="statusFilter" class="form-control" style="max-width:130px;">
        <option value="">{{ t('group.list.allStatus') }}</option>
        <option value="enabled">{{ t('common.enabled') }}</option>
        <option value="disabled">{{ t('common.disabled') }}</option>
      </select>
    </div>

    <div v-if="loading" class="page-loading">
      <LoadingSpinner :size="18" :text="t('common.loading')" />
    </div>

    <div v-else class="table-container">
      <table>
        <thead>
          <tr>
            <th>{{ t('group.list.name') }}</th>
            <th>{{ t('group.list.strategy') }}</th>
            <th>{{ t('group.list.sticky') }}</th>
            <th>{{ t('group.list.members') }}</th>
            <th>{{ t('group.list.enabled') }}</th>
            <th>{{ t('group.list.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in filteredGroups" :key="g.id">
            <td>
              <div class="group-name">{{ g.name }}</div>
            </td>
            <td><span class="badge badge-strategy">{{ strategyLabel(g.strategy || 'random') }}</span></td>
            <td>
              <span v-if="g.sticky === 1" class="badge badge-sticky">{{ t('group.list.stickyOn') }}</span>
              <span v-else class="text-muted">-</span>
            </td>
            <td>{{ g.memberCount ?? 0 }}</td>
            <td>
              <ToggleSwitch
                :model-value="(g.enabled ?? 1) === 1"
                size="sm"
                :show-label="false"
                :disabled="togglingId === g.id"
                @update:model-value="v => toggleEnabled(g, v)"
              />
            </td>
            <td>
              <div class="action-group">
                <router-link :to="'/admin/model/group/' + g.id" class="btn btn-sm btn-secondary">
                  <SvgIcon name="list" :size="13" /> {{ t('group.list.manageMembers') }}
                </router-link>
                <router-link :to="'/admin/model/group/form/' + g.id" class="btn btn-sm btn-secondary">
                  <SvgIcon name="edit" :size="13" /> {{ t('common.edit') }}
                </router-link>
                <button class="btn btn-sm btn-danger" @click="removeGroup(g)">
                  <SvgIcon name="trash" :size="13" /> {{ t('common.delete') }}
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!filteredGroups.length">
            <td colspan="6" class="empty-cell">{{ t('group.list.empty') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { useToast } from '@/composables/useToast'
import { groupApi, type ModelGroup } from '@/api/group'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'

defineOptions({ name: 'ModelGroupList' })

const { t } = useI18n()
const { open } = useDialog()
const { showToast } = useToast()

const groups = ref<ModelGroup[]>([])
const loading = ref(true)
const keyword = ref('')
const statusFilter = ref('')
const togglingId = ref<number | null>(null)

const filteredGroups = computed(() => {
  let out = groups.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    out = out.filter(g =>
      g.name.toLowerCase().includes(kw))
  }
  if (statusFilter.value === 'enabled') out = out.filter(g => (g.enabled ?? 1) === 1)
  if (statusFilter.value === 'disabled') out = out.filter(g => (g.enabled ?? 0) === 0)
  return out
})

function strategyLabel(s: string): string {
  const key = {
    failover: 'group.strategy.failover',
    random: 'group.strategy.random',
    round_robin: 'group.strategy.roundRobin'
  }[s]
  return key ? t(key) : s
}

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

async function toggleEnabled(g: ModelGroup, v: boolean) {
  if (!g.id) return
  togglingId.value = g.id
  try {
    const res = await groupApi.update(g.id, { enabled: v ? 1 : 0 })
    if (res.data.success) {
      g.enabled = v ? 1 : 0
    } else {
      showToast(res.data.error || t('error.updateFailed'), { type: 'error' })
    }
  } catch (e: any) {
    showToast(e.message || t('error.updateFailed'), { type: 'error' })
  } finally {
    togglingId.value = null
  }
}

function removeGroup(g: ModelGroup) {
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

onMounted(loadData)
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 10px;
  padding: 12px 16px 0;
  flex-wrap: wrap;
}
.group-name { font-weight: 600; }
.badge-strategy {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(88, 166, 255, 0.12);
  color: var(--accent-blue, #58a6ff);
}
.badge-sticky {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(63, 185, 80, 0.12);
  color: #3fb950;
}
.action-group { display: flex; gap: 6px; flex-wrap: wrap; }
.empty-cell { text-align: center; color: var(--text-muted); padding: 40px; }
.text-muted { color: var(--text-muted); font-size: 12px; }
</style>
