<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title"><SvgIcon name="model" :size="18" /> {{ t('model.list.title') }}</div>
      <router-link to="/admin/model/form" class="btn btn-primary"><SvgIcon name="plus" :size="14" /> {{ t('model.list.add') }}</router-link>
    </div>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>{{ t('model.list.modelName') }}</th>
            <th>{{ t('model.list.description') }}</th>
            <th>{{ t('model.list.strategy') }}</th>
            <th>{{ t('model.list.relMode') }}</th>
            <th>{{ t('model.list.status') }}</th>
            <th>{{ t('model.list.createdAt') }}</th>
            <th>{{ t('model.list.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in models" :key="m.id">
            <td><strong>{{ m.modelName }}</strong></td>
            <td style="font-size:12px;color:var(--text-secondary);max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
              {{ m.description || '--' }}
            </td>
            <td>
              <span class="badge" :class="strategyBadge(m.strategy)">
                {{ strategyLabel(m.strategy) }}
              </span>
            </td>
            <td>
              <span class="mode-badge" :class="`mode-${m.relMode || 'self_add'}`">
                {{ (m.relMode || 'self_add') === 'inherit' ? t('model.rels.modeInherit') : t('model.rels.modeSelfAdd') }}
              </span>
            </td>
            <td>
              <span v-if="m.enabled === 1" class="badge badge-success">{{ t('common.enabled') }}</span>
              <span v-else class="badge badge-danger">{{ t('common.disabled') }}</span>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ formatLocalDateTimeFull(m.createdAt) }}</td>
            <td>
              <div style="display:flex;gap:6px;">
                <router-link :to="`/admin/model/rels/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="link" :size="14" /> {{ t('model.list.manageRels') }}</router-link>
                <router-link :to="`/admin/model/circuit-breaker/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="zap" :size="14" /> {{ t('model.list.config') }}</router-link>
                <router-link :to="`/admin/model/advanced/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="settings" :size="14" /> {{ t('model.list.advanced') }}</router-link>
                <router-link :to="`/admin/model/form/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="edit" :size="14" /> {{ t('model.list.edit') }}</router-link>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(m)"><SvgIcon name="trash" :size="14" /> {{ t('model.list.delete') }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="!models.length">
            <td colspan="7" style="color:var(--text-muted);padding:40px;">
              {{ t('model.list.empty') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Mobile card list (visible ≤ 768px) -->
    <div class="mobile-card-list">
      <div v-if="!models.length" class="empty-state">
        {{ t('model.list.empty') }}
      </div>
      <div v-for="m in models" :key="m.id" class="mobile-card">
        <div class="mobile-card-header">
          <strong class="mobile-card-title">{{ m.modelName }}</strong>
          <span v-if="m.enabled === 1" class="badge badge-success">{{ t('common.enabled') }}</span>
          <span v-else class="badge badge-danger">{{ t('common.disabled') }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('model.list.description') }}:</span>
          <span class="mobile-card-value">{{ m.description || '-' }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('model.list.strategy') }}:</span>
          <span class="badge" :class="strategyBadge(m.strategy)">{{ strategyLabel(m.strategy) }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('model.list.relMode') }}:</span>
          <span class="mode-badge" :class="`mode-${m.relMode || 'self_add'}`">
            {{ (m.relMode || 'self_add') === 'inherit' ? t('model.rels.modeInherit') : t('model.rels.modeSelfAdd') }}
          </span>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-actions">
          <router-link :to="`/admin/model/rels/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="link" :size="14" /> {{ t('model.list.manageRels') }}</router-link>
          <router-link :to="`/admin/model/circuit-breaker/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="zap" :size="14" /> {{ t('model.list.config') }}</router-link>
          <router-link :to="`/admin/model/advanced/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="settings" :size="14" /> {{ t('model.list.advanced') }}</router-link>
          <router-link :to="`/admin/model/form/${m.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="edit" :size="14" /> {{ t('model.list.edit') }}</router-link>
          <button class="btn btn-sm btn-danger" @click="confirmDelete(m)"><SvgIcon name="trash" :size="14" /> {{ t('model.list.delete') }}</button>
        </div>
      </div>
    </div>
  </div>

  <!-- Common Dialog -->
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
import { ref, onMounted } from 'vue'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { modelApi, type CustomModel } from '@/api/model'
import { formatLocalDateTimeFull } from '@/utils/date'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()

const models = ref<CustomModel[]>([])

function strategyLabel(s?: string) {
  return s === 'random' ? t('model.list.strategyRandom') : s === 'round_robin' ? t('model.list.strategyRoundRobin') : t('model.list.strategyFailover')
}

function strategyBadge(s?: string) {
  return s === 'random' ? 'badge-info' : s === 'round_robin' ? 'badge-success' : 'badge-warning'
}

function confirmDelete(m: CustomModel) {
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

async function loadModels() {
  try {
    const res = await modelApi.list()
    models.value = res.data
  } catch (e: any) {
    open({ title: t('error.loadFailed'), message: e.message })
  }
}

onMounted(loadModels)
</script>

<style scoped>
/* Mobile card list — hidden on desktop, shown ≤ 768px */
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

.mobile-card-value {
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* Mode badge */
.mode-badge {
  font-size: 11px;
  padding: 3px 8px;
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

/* Responsive toggle */
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
