<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">自定义模型列表</div>
      <router-link to="/admin/model/form" class="btn btn-primary">+ 添加模型</router-link>
    </div>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>模型名称</th>
            <th>描述</th>
            <th>选择策略</th>
            <th>关联模型</th>
            <th>熔断配置</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in models" :key="m.id">
            <td style="color:var(--text-muted);">{{ m.id }}</td>
            <td><strong>{{ m.modelName }}</strong></td>
            <td style="font-size:12px;color:var(--text-secondary);max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
              {{ m.description }}
            </td>
            <td>
              <span class="badge" :class="strategyBadge(m.strategy)">
                {{ strategyLabel(m.strategy) }}
              </span>
            </td>
            <td>
              <router-link :to="`/admin/model/rels/${m.id}`" class="btn btn-sm btn-secondary">管理关联</router-link>
            </td>
            <td>
              <router-link :to="`/admin/model/circuit-breaker/${m.id}`" class="btn btn-sm btn-secondary">配置</router-link>
            </td>
            <td>
              <span v-if="m.enabled === 1" class="badge badge-success">启用</span>
              <span v-else class="badge badge-danger">禁用</span>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ m.createdAt }}</td>
            <td>
              <div style="display:flex;gap:6px;">
                <router-link :to="`/admin/model/form/${m.id}`" class="btn btn-sm btn-secondary">编辑</router-link>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(m)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!models.length">
            <td colspan="9" style="text-align:center;color:var(--text-muted);padding:40px;">
              暂无自定义模型，点击右上角「添加模型」创建
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Mobile card list (visible ≤ 768px) -->
    <div class="mobile-card-list">
      <div v-if="!models.length" class="empty-state">
        暂无自定义模型，点击右上角「添加模型」创建
      </div>
      <div v-for="m in models" :key="m.id" class="mobile-card">
        <div class="mobile-card-header">
          <strong class="mobile-card-title">{{ m.modelName }}</strong>
          <span v-if="m.enabled === 1" class="badge badge-success">启用</span>
          <span v-else class="badge badge-danger">禁用</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">描述:</span>
          <span class="mobile-card-value">{{ m.description || '-' }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">策略:</span>
          <span class="badge" :class="strategyBadge(m.strategy)">{{ strategyLabel(m.strategy) }}</span>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-actions">
          <router-link :to="`/admin/model/rels/${m.id}`" class="btn btn-sm btn-secondary">管理关联</router-link>
          <router-link :to="`/admin/model/circuit-breaker/${m.id}`" class="btn btn-sm btn-secondary">配置</router-link>
          <router-link :to="`/admin/model/form/${m.id}`" class="btn btn-sm btn-secondary">编辑</router-link>
          <button class="btn btn-sm btn-danger" @click="confirmDelete(m)">删除</button>
        </div>
      </div>
    </div>
  </div>

  <!-- 通用弹框 -->
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
import { ref, onMounted } from 'vue'
import { modelApi, type CustomModel } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'

const models = ref<CustomModel[]>([])

/* ---------- 弹框状态 ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref('提示')
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
  dialogTitle.value = opts.title ?? '提示'
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
/* ------------------------------ */

function strategyLabel(s?: string) {
  return s === 'random' ? '随机' : s === 'round_robin' ? '轮询' : '故障转移'
}

function strategyBadge(s?: string) {
  return s === 'random' ? 'badge-info' : s === 'round_robin' ? 'badge-success' : 'badge-warning'
}

function confirmDelete(m: CustomModel) {
  openDialog({
    title: '确认删除',
    message: `确认删除模型「${m.modelName}」？`,
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      modelApi.delete(m.id!).then(() => loadModels()).catch(e =>
        openDialog({ title: '删除失败', message: e.message })
      )
    }
  })
}

async function loadModels() {
  try {
    const res = await modelApi.list()
    models.value = res.data
  } catch (e: any) {
    openDialog({ title: '加载失败', message: e.message })
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
