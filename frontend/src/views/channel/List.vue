<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">渠道列表</div>
      <router-link to="/admin/channel/form" class="btn btn-primary">+ 添加渠道</router-link>
    </div>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>渠道名称</th>
            <th>类型</th>
            <th>接口地址</th>
            <th>状态</th>
            <th>模型数</th>
            <th>请求次数</th>
            <th>Token 用量</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ch in channels" :key="ch.id">
            <td style="color:var(--text-muted);">{{ ch.id }}</td>
            <td><strong>{{ ch.name }}</strong></td>
            <td><span class="badge badge-info">{{ ch.channelType }}</span></td>
            <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;font-size:12px;color:var(--text-muted);">
              {{ ch.baseUrl }}
            </td>
            <td>
              <span v-if="ch.enabled === 1" class="badge badge-success">
                <span class="status-dot active"></span>启用
              </span>
              <span v-else class="badge badge-danger">
                <span class="status-dot inactive"></span>禁用
              </span>
            </td>
            <td>
              <router-link :to="`/admin/channel/models/${ch.id}`" class="btn btn-sm btn-secondary">查看</router-link>
            </td>
            <td style="text-align:right;font-variant-numeric:tabular-nums;">
              <span style="font-weight:600;">{{ formatNumber(ch.requestCount) }}</span>
            </td>
            <td style="font-size:12px;font-variant-numeric:tabular-nums;">
              <template v-if="ch.totalTokens && ch.totalTokens > 0">
                <div style="display:flex;flex-direction:column;gap:2px;">
                  <span :title="`输入: ${formatNumber(ch.promptTokens)} | 输出: ${formatNumber(ch.completionTokens)}`">
                    {{ formatTokens(ch.totalTokens) }}
                  </span>
                  <span style="color:var(--text-muted);font-size:11px;">
                    入 {{ formatTokens(ch.promptTokens) }} / 出 {{ formatTokens(ch.completionTokens) }}
                  </span>
                </div>
              </template>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ ch.createdAt }}</td>
            <td>
              <div style="display:flex;gap:6px;flex-wrap:nowrap;">
                <router-link :to="`/admin/channel/form/${ch.id}`" class="btn btn-sm btn-secondary">编辑</router-link>
                <button class="btn btn-sm btn-success" @click="quickTest(ch)">测试</button>
                <router-link :to="`/admin/channel/reload/${ch.id}`" class="btn btn-sm btn-secondary"
                  @click.prevent="reloadModels(ch.id!)">刷新模型</router-link>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(ch)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!channels.length">
            <td colspan="10" style="text-align:center;color:var(--text-muted);padding:40px;">
              暂无渠道数据，点击右上角「添加渠道」开始
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Quick Test Modal -->
    <div v-if="showTestModal" class="modal-overlay" @click.self="closeTestModal">
      <div class="modal-box">
        <div class="modal-header">
          <SvgIcon name="zap" :size="18" /> 渠道快速测试
          <button class="modal-close" @click="closeTestModal">&times;</button>
        </div>
        <div style="font-size:13px;color:var(--text-muted);margin-bottom:12px;">
          渠道: {{ testChannel?.name }}
        </div>
        <div class="form-group">
          <label>测试消息</label>
          <textarea v-model="testMessage" class="form-control" rows="3"></textarea>
        </div>
        <div v-if="testResult" class="test-result" :class="testResult.success ? 'success' : 'error'">
          <template v-if="testResult.success">
            <SvgIcon name="check-bold" :size="16" /> 测试成功 ({{ testResult.responseTime }}ms)
            <pre>{{ testResult.response }}</pre>
          </template>
          <template v-else>
            <SvgIcon name="x" :size="16" /> 测试失败
            <pre>{{ testResult.error }}</pre>
          </template>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="closeTestModal">关闭</button>
          <button class="btn btn-primary" :disabled="testLoading" @click="sendTestRequest">
            <SvgIcon name="send" :size="14" /> {{ testLoading ? '测试中...' : '发送测试' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { channelApi, type Channel } from '@/api/channel'

const router = useRouter()
const channels = ref<Channel[]>([])
const showTestModal = ref(false)
const testChannel = ref<Channel | null>(null)
const testMessage = ref('Hello, this is a test message.')
const testResult = ref<{ success: boolean; response?: string; responseTime?: number; error?: string } | null>(null)
const testLoading = ref(false)

async function loadChannels() {
  try {
    const res = await channelApi.list()
    channels.value = res.data
  } catch (e: any) {
    alert('加载渠道列表失败: ' + e.message)
  }
}

function quickTest(ch: Channel) {
  testChannel.value = ch
  testMessage.value = 'Hello, this is a test message.'
  testResult.value = null
  showTestModal.value = true
}

function closeTestModal() {
  showTestModal.value = false
  testChannel.value = null
}

async function sendTestRequest() {
  if (!testChannel.value?.id) return
  testLoading.value = true
  try {
    const res = await channelApi.quickTest(testChannel.value.id, testMessage.value)
    testResult.value = res.data
  } catch (e: any) {
    testResult.value = { success: false, error: e.message }
  } finally {
    testLoading.value = false
  }
}

async function reloadModels(id: number) {
  if (!confirm('确认重新加载模型？将清除当前所有模型。')) return
  try {
    const res = await channelApi.reloadModels(id)
    alert(res.data.success ? '模型重新加载成功' : '加载失败: ' + res.data.error)
  } catch (e: any) {
    alert('请求失败: ' + e.message)
  }
}

function confirmDelete(ch: Channel) {
  if (!confirm(`确认删除渠道「${ch.name}」？关联数据将被清除。`)) return
  channelApi.delete(ch.id!).then(() => loadChannels()).catch(e => alert('删除失败: ' + e.message))
}

/** 格式化数字，千分位 */
function formatNumber(n: number | undefined): string {
  if (n == null) return '0'
  return n.toLocaleString()
}

/** 格式化 token 数量，大数字用 K/M 缩写 */
function formatTokens(n: number | undefined): string {
  if (n == null || n === 0) return '0'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return n.toString()
}

onMounted(loadChannels)
</script>

<style scoped>
.modal-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.6); z-index: 1000;
  display: flex; align-items: center; justify-content: center;
}
.modal-box {
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 12px; padding: 24px; width: 480px; max-width: 90vw;
}
.modal-header {
  display: flex; align-items: center; gap: 8px;
  font-size: 16px; font-weight: 600; margin-bottom: 16px;
  justify-content: space-between;
}
.modal-close {
  background: none; border: none; color: var(--text-muted);
  cursor: pointer; font-size: 18px;
}
.modal-actions {
  display: flex; gap: 8px; margin-top: 16px; justify-content: flex-end;
}
.test-result { margin-top: 12px; padding: 12px; border-radius: 8px; font-size: 13px; max-height: 200px; overflow-y: auto; }
.test-result.success { background: rgba(63,185,80,0.1); color: var(--accent-green); }
.test-result.error { background: rgba(248,81,73,0.1); color: var(--accent-red); }
.test-result pre { margin-top: 8px; white-space: pre-wrap; word-break: break-all; font-size: 12px; }
</style>
