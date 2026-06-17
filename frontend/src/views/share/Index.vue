<template>
  <div class="share-container">
    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner"></div>
      <p>加载中...</p>
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="error-state">
      <div class="error-icon">⚠️</div>
      <h2>加载失败</h2>
      <p>{{ error }}</p>
    </div>

    <!-- 主要内容 -->
    <div v-else class="share-content">
      <!-- 头部信息 -->
      <div class="share-header">
        <h1>API 密钥使用说明</h1>
        <p class="subtitle">使用此密钥调用 AI 模型 API</p>
      </div>

      <!-- 接口格式选择 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <SvgIcon name="rocket" :size="18" />
            选择接口格式
          </div>
        </div>
        <div class="card-body">
          <div class="format-selector">
            <button
              class="format-btn"
              :class="{ active: selectedFormat === 'openai' }"
              @click="selectedFormat = 'openai'"
            >
              <SvgIcon name="openai" :size="24" class="format-icon" />
              <span class="format-name">OpenAI</span>
              <span class="format-desc">兼容 OpenAI API 格式</span>
            </button>
            <button
              class="format-btn"
              :class="{ active: selectedFormat === 'anthropic' }"
              @click="selectedFormat = 'anthropic'"
            >
              <SvgIcon name="anthropic" :size="24" class="format-icon" />
              <span class="format-name">Anthropic</span>
              <span class="format-desc">兼容 Anthropic API 格式</span>
            </button>
          </div>
        </div>
      </div>

      <!-- 接口信息 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <SvgIcon name="info" :size="18" />
            接口信息
          </div>
        </div>
        <div class="card-body">
          <!-- Base URL -->
          <div class="info-row">
            <span class="info-label">Base URL:</span>
            <div class="info-value-with-copy">
              <code class="info-code">{{ currentBaseUrl }}</code>
              <button class="btn btn-sm btn-secondary" @click="copyText(currentBaseUrl, 'Base URL')">
                <SvgIcon name="copy" :size="14" />
              </button>
            </div>
          </div>

          <!-- API Key -->
          <div class="info-row">
            <span class="info-label">API Key:</span>
            <div class="info-value-with-copy">
              <code class="info-code">{{ shareData.keyValue }}</code>
              <button class="btn btn-sm btn-secondary" @click="copyText(shareData.keyValue, 'API Key')">
                <SvgIcon name="copy" :size="14" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 可用模型 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <SvgIcon name="model" :size="18" />
            可用模型
          </div>
          <span class="model-count">{{ filteredModels.length }} 个模型</span>
        </div>
        <div class="card-body">
          <div v-if="filteredModels.length === 0" class="empty-state">
            暂无该接口格式的可用模型
          </div>
          <div v-else class="model-tags">
            <span
              v-for="model in filteredModels"
              :key="model.id"
              class="model-tag"
            >
              {{ model.modelName }}
            </span>
          </div>
        </div>
      </div>

      <!-- 在线测试 -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <SvgIcon name="ai-chat" :size="18" />
            在线测试
          </div>
        </div>
        <div class="card-body">
          <ChatPlayground
            :models="filteredModels"
            :fixed-share-code="shareData.shareCode"
            compact
          />
        </div>
      </div>
    </div>

    <!-- Toast 提示 -->
    <div v-if="toast.show" class="toast" :class="toast.type">
      {{ toast.message }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { shareApi, type ShareData } from '@/api/share'
import ChatPlayground from '@/components/chat/ChatPlayground.vue'

const route = useRoute()

// 状态
const loading = ref(true)
const error = ref('')
const selectedFormat = ref<'openai' | 'anthropic'>('openai')
const shareData = ref<ShareData>({
  id: 0,
  shareCode: '',
  keyName: '',
  keyValue: '',
  keyValueMasked: '',
  baseUrl: '',
  models: []
})
const models = computed(() => shareData.value.models)
const baseUrl = computed(() => shareData.value.baseUrl)

// Toast
const toast = ref({ show: false, message: '', type: 'success' })

// 所有模型两种接口格式都可用（网关支持协议互转）
const filteredModels = computed(() => models.value)

// 当前接口格式的 Base URL
const currentBaseUrl = computed(() => {
  const base = baseUrl.value || window.location.origin
  return `${base}/v1`
})

// 显示的 API Key（优先使用遮罩值，fallback 到原始值）
const displayKeyValue = computed(() => {
  return shareData.value.keyValueMasked || shareData.value.keyValue
})

// 当前页面源（用于生成分享链接）
const windowLocationOrigin = window.location.origin

// 生命周期
onMounted(async () => {
  const shareCode = route.params.code as string

  if (!shareCode) {
    error.value = '无效的分享链接'
    loading.value = false
    return
  }

  try {
    // 通过分享码获取密钥信息（分享码在 URL 中不暴露密钥值）
    const res = await shareApi.getShareInfo(shareCode)
    shareData.value = res.data
    loading.value = false
  } catch (e: any) {
    error.value = e.message || '加载失败'
    loading.value = false
  }
})

// 方法
function showToast(message: string, type: 'success' | 'error' = 'success') {
  toast.value = { show: true, message, type }
  setTimeout(() => {
    toast.value.show = false
  }, 2000)
}

async function copyText(text: string, label: string = '') {
  try {
    await navigator.clipboard.writeText(text)
    showToast(`${label || '内容'}已复制`)
  } catch {
    showToast('复制失败', 'error')
  }
}
</script>

<style scoped>
.share-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
}

.loading-state,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  text-align: center;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-color);
  border-top-color: var(--accent-blue);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.share-header {
  margin-bottom: 24px;
}

.share-header h1 {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 8px;
}

.subtitle {
  color: var(--text-muted);
  font-size: 14px;
}

.card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}

.model-count {
  font-size: 12px;
  color: var(--text-muted);
  background: var(--bg-tertiary);
  padding: 4px 8px;
  border-radius: 4px;
}

.card-body {
  padding: 20px;
}

/* 接口格式选择器 */
.format-selector {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.format-btn {
  flex: 1;
  min-width: 200px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 16px 20px;
  background: var(--bg-primary);
  border: 2px solid var(--border-color);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s ease;
  text-align: left;
}

.format-btn:hover {
  border-color: var(--accent-blue);
  background: var(--bg-tertiary);
}

.format-btn.active {
  border-color: var(--accent-blue);
  background: rgba(88, 166, 255, 0.08);
  box-shadow: 0 0 0 3px rgba(88, 166, 255, 0.15);
}

.format-icon {
  color: var(--text-primary);
}

.format-btn.active .format-icon {
  color: var(--accent-blue);
}

.format-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.format-desc {
  font-size: 12px;
  color: var(--text-muted);
}

.info-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.info-row:last-child {
  margin-bottom: 0;
}

.info-label {
  font-weight: 500;
  color: var(--text-secondary);
  min-width: 90px;
  flex-shrink: 0;
}

.info-value-with-copy {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.info-code {
  flex: 1;
  padding: 8px 12px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  color: var(--accent-purple);
  word-break: break-all;
  min-width: 0;
}

.empty-state {
  text-align: center;
  color: var(--text-muted);
  padding: 24px;
}

.model-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.model-tag {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}

/* Toast */
.toast {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 14px;
  z-index: 9999;
  animation: slideUp 0.3s ease;
}

.toast.success {
  background: #10b981;
  color: white;
}

.toast.error {
  background: #ef4444;
  color: white;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateX(-50%) translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateX(-50%) translateY(0);
  }
}

/* 响应式 */
@media (max-width: 768px) {
  .share-container {
    padding: 16px;
  }

  .info-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
