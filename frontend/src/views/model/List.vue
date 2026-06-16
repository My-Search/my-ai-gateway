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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { modelApi, type CustomModel } from '@/api/model'

const models = ref<CustomModel[]>([])

function strategyLabel(s?: string) {
  return s === 'random' ? '随机' : s === 'round_robin' ? '轮询' : '故障转移'
}

function strategyBadge(s?: string) {
  return s === 'random' ? 'badge-info' : s === 'round_robin' ? 'badge-success' : 'badge-warning'
}

function confirmDelete(m: CustomModel) {
  if (!confirm(`确认删除模型「${m.modelName}」？`)) return
  modelApi.delete(m.id!).then(() => loadModels()).catch(e => alert('删除失败: ' + e.message))
}

async function loadModels() {
  try {
    const res = await modelApi.list()
    models.value = res.data
  } catch (e: any) {
    alert('加载失败: ' + e.message)
  }
}

onMounted(loadModels)
</script>
