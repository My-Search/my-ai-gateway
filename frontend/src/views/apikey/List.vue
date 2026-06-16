<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">API 密钥列表</div>
      <router-link to="/admin/apikey/form" class="btn btn-primary">+ 添加密钥</router-link>
    </div>
    <div class="alert alert-info">
      API 密钥用于调用网关的 API。用户请求时需要在 Header 中携带密钥。
    </div>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>密钥名称</th>
            <th>密钥值</th>
            <th>状态</th>
            <th>最后使用</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="key in apiKeys" :key="key.id">
            <td style="color:var(--text-muted);">{{ key.id }}</td>
            <td><strong>{{ key.keyName }}</strong></td>
            <td>
              <code class="model-tag" style="user-select:all;cursor:pointer;" @click="copyKey(key.keyValue)">
                {{ maskKey(key.keyValue) }}
              </code>
              <button class="copy-btn" @click="copyKey(key.keyValue)" title="复制密钥">
                <SvgIcon name="copy" :size="14" />
              </button>
            </td>
            <td>
              <span v-if="key.enabled === 1" class="badge badge-success">
                <span class="status-dot active"></span>启用
              </span>
              <span v-else class="badge badge-danger">
                <span class="status-dot inactive"></span>禁用
              </span>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ key.lastUsedAt || '从未使用' }}</td>
            <td style="font-size:12px;color:var(--text-muted);">{{ key.createdAt }}</td>
            <td>
              <div style="display:flex;gap:6px;">
                <router-link :to="`/admin/apikey/form/${key.id}`" class="btn btn-sm btn-secondary">编辑</router-link>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(key)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!apiKeys.length">
            <td colspan="7" style="text-align:center;color:var(--text-muted);padding:40px;">
              暂无 API 密钥，点击右上角「添加密钥」创建
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { apikeyApi, type ApiKey } from '@/api/apikey'

const apiKeys = ref<ApiKey[]>([])

function maskKey(key: string) {
  if (!key) return ''
  if (key.length > 30) return key.substring(0, 15) + '...'
  return key
}

async function copyKey(val: string) {
  try {
    await navigator.clipboard.writeText(val)
    const toast = document.createElement('div')
    toast.textContent = '复制成功'
    Object.assign(toast.style, {
      position: 'fixed', bottom: '24px', left: '50%', transform: 'translateX(-50%)',
      background: '#10b981', color: '#fff', padding: '8px 20px', borderRadius: '8px',
      fontSize: '14px', zIndex: '9999', transition: 'opacity .3s'
    })
    document.body.appendChild(toast)
    setTimeout(() => { toast.style.opacity = '0'; setTimeout(() => toast.remove(), 300) }, 1500)
  } catch {
    alert('复制失败，请手动复制')
  }
}

function confirmDelete(key: ApiKey) {
  if (!confirm(`确认删除密钥「${key.keyName}」？`)) return
  apikeyApi.delete(key.id!).then(() => loadKeys()).catch(e => alert('删除失败: ' + e.message))
}

async function loadKeys() {
  try {
    const res = await apikeyApi.list()
    apiKeys.value = res.data
  } catch (e: any) {
    alert('加载失败: ' + e.message)
  }
}

onMounted(loadKeys)
</script>

<style scoped>
.copy-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; cursor: pointer; color: var(--text-muted);
  background: var(--bg-tertiary); border: 1px solid var(--border-color);
  border-radius: 4px; padding: 0; margin-left: 8px; vertical-align: middle;
}
.copy-btn:hover { color: var(--accent-blue); border-color: var(--accent-blue); }
</style>
