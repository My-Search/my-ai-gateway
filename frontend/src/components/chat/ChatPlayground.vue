<template>
  <div class="chat-playground" :class="{ compact }">
    <button v-if="!compact" class="mobile-sidebar-toggle" @click="showSidebar = !showSidebar">
      <SvgIcon name="settings" :size="14" />
      {{ showSidebar ? t('playground.toggleConfig') : t('playground.showConfig') }}
    </button>

    <!-- 侧栏 -->
    <div v-show="!compact" class="playground-sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="playground-sidebar-inner">
      <div class="card">
        <div class="card-title mb-3">{{ t('playground.testConfig') }}</div>

        <div class="form-group">
          <label>{{ t('playground.selectModel') }}</label>
          <select v-model="selectedModel" class="form-control">
            <option value="">{{ t('playground.selectModelPlaceholder') }}</option>
            <option v-for="m in models" :key="m.modelName" :value="m.modelName">{{ m.modelName }}</option>
          </select>
        </div>

        <div v-if="!isShareMode" class="form-group">
          <label>{{ t('playground.apiKey') }}</label>
          <select v-model="selectedApiKey" class="form-control">
            <option :value="0">{{ t('playground.autoSelect') }}</option>
            <option v-for="k in apiKeys" :key="k.id" :value="k.id">
              {{ k.keyName }} ({{ (k.keyValue || '').substring(0, 15) }}...)
            </option>
          </select>
        </div>

        <div class="form-group">
          <label>Temperature</label>
          <input v-model.number="temperature" type="number" class="form-control" min="0" max="2" step="0.1" />
        </div>

        <div class="form-group">
          <label>Max Tokens</label>
          <input v-model.number="maxTokens" type="number" class="form-control" min="1" max="256000" />
        </div>

        <button class="btn btn-secondary" style="width:100%;" @click="clearChat">
          <SvgIcon name="trash" :size="14" /> {{ t('playground.clearChat') }}
        </button>
      </div>

      <div class="card mt-3">
        <div class="card-title mb-3">{{ t('playground.status') }}</div>
        <div class="status-item">
          <span class="status-dot" :class="streaming ? 'active' : 'inactive'"></span>
          <span>{{ streaming ? t('playground.generating') : t('playground.ready') }}</span>
        </div>
        <div v-if="tokenUsage" class="text-muted mt-2" style="font-size:12px;">{{ tokenUsage }}</div>
      </div>
    </div>
    </div>

    <div class="playground-main">
      <div v-if="!compact" class="chat-header">
        <div class="chat-header-left">
          <span class="test-status-icon" :class="streaming ? 'testing' : 'idle'">
            <SvgIcon v-if="streaming" name="zap" :size="16" />
            <SvgIcon v-else name="check" :size="16" />
          </span>
          <span class="chat-header-title">{{ t('playground.title') }}</span>
        </div>
        <div class="chat-header-right">
          <div class="model-selector-bar">
            <span class="model-selector-label">{{ t('playground.currentModel') }}:</span>
            <select v-model="selectedModel" class="model-selector-select">
              <option value="">{{ t('playground.selectModelPlaceholder') }}</option>
              <option v-for="m in models" :key="m.modelName" :value="m.modelName">{{ m.modelName }}</option>
            </select>
          </div>
          <button type="button" class="btn-model-settings" @click="toggleSidebar()" :title="sidebarCollapsed ? t('playground.showConfig') : t('playground.toggleConfig')">
            <SvgIcon name="settings" :size="14" />
            {{ t('playground.modelSettings') }}
          </button>
        </div>
      </div>

      <div v-if="compact" class="compact-controls">
        <select v-model="selectedModel" class="form-control">
          <option value="">{{ t('playground.selectModelPlaceholder') }}</option>
          <option v-for="m in models" :key="m.modelName" :value="m.modelName">{{ m.modelName }}</option>
        </select>
      </div>

      <div class="chat-messages" ref="chatRef">
        <div v-if="!messages.length" class="chat-empty">
          <div class="empty-icon"><SvgIcon name="ai-chat" :size="48" color="var(--accent-purple)" /></div>
          <div style="font-size:16px;font-weight:600;margin-bottom:8px;">{{ t('playground.welcomeTitle') }}</div>
          <div style="color:var(--text-muted);">{{ t('playground.welcomeDesc') }}</div>
        </div>

        <div v-for="(msg, idx) in messages" :key="idx"
             class="chat-message" :class="msg.role === 'user' ? 'user' : msg.role === 'system-msg' ? 'system-msg' : 'assistant'">
          <div class="chat-avatar" :class="msg.role">
            <SvgIcon v-if="msg.role === 'user'" name="user" :size="18" />
            <SvgIcon v-else-if="msg.role === 'assistant'" name="bot" :size="18" />
            <SvgIcon v-else name="info" :size="18" />
          </div>
          <div style="min-width:0;flex:1;">
            <div v-if="msg.channelInfo" class="chat-channel-info">
              <span class="channel-badge" :class="msg.channelInfo.channel_type">{{ msg.channelInfo.channel_type }}</span>
              <span class="channel-name">{{ msg.channelInfo.channel }}</span>
              <span class="channel-arrow">→</span>
              <span class="channel-model-name">{{ msg.channelInfo.channel_model }}</span>
            </div>
            <div v-if="idx === messages.length - 1 && routingProgress" class="chat-routing-progress">
              <span class="routing-dot"></span>
              <span v-if="routingProgress.phase === 'trying'">{{ t('playground.trying').replace('{channel}', routingProgress.channel).replace('{channelModel}', routingProgress.channel_model) }}</span>
              <span v-else-if="routingProgress.phase === 'retrying'">{{ t('playground.retrying').replace('{channel}', routingProgress.channel).replace('{channelModel}', routingProgress.channel_model).replace('{msg}', routingProgress.message || '') }}</span>
              <span v-else-if="routingProgress.phase === 'switching'">{{ t('playground.switching').replace('{msg}', routingProgress.message || '') }}</span>
            </div>
            <!-- 思考/推理过程：思考时自动展开，完成时自动收起 -->
            <div v-if="msg.role === 'assistant' && msg.reasoningContent" class="chat-thinking">
              <div class="thinking-header" @click="toggleThinking(idx)">
                <template v-if="idx === messages.length - 1 && streaming">
                  <span class="thinking-dot streaming"></span>
                  <span class="thinking-label">{{ t('playground.thinking') }}</span>
                </template>
                <template v-else>
                  <span class="thinking-arrow" :class="{ open: expandedThinking[idx] }">▶</span>
                  <span class="thinking-label">{{ t('playground.thinkingCollapse') }}</span>
                </template>
              </div>
              <div v-if="expandedThinking[idx]" class="thinking-content markdown-body" v-html="renderReasoningMarkdown(msg.reasoningContent)"></div>
            </div>
            <div class="chat-bubble" :class="{ 'cursor-blink': idx === messages.length - 1 && streaming }">
              <div v-if="msg.images && msg.images.length" class="chat-images">
                <img v-for="(imgUrl, imgIdx) in msg.images" :key="imgIdx"
                     :src="imgUrl" class="chat-image" alt="image" @click="expandImage(imgUrl)" />
              </div>
              <div v-if="msg.role === 'assistant'" class="markdown-body" v-html="renderMarkdown(msg.content)"></div>
              <template v-else-if="msg.content">{{ msg.content }}</template>
            </div>
            <div v-if="msg.truncated" class="chat-truncated-hint">{{ t('playground.truncated') }}</div>
            <div v-if="msg.meta" class="chat-meta" v-html="msg.meta"></div>
          </div>
        </div>
      </div>

      <!-- Image Lightbox -->
      <Teleport to="body">
        <div v-if="expandedImageUrl" class="image-lightbox" @click.self="expandedImageUrl = ''">
          <button class="image-lightbox-close" @click="expandedImageUrl = ''">&times;</button>
          <img :src="expandedImageUrl" class="image-lightbox-img" alt="expanded" />
        </div>
      </Teleport>

      <div v-if="!compact" class="chat-quick-actions">
        <button class="btn-quick-hint" :disabled="streaming || !selectedModel" @click="quickSend('Hello, My AI Gateway!')">
          <SvgIcon name="send" :size="14" /> Hello, My AI Gateway!
        </button>
      </div>

      <div class="chat-input-area">
        <div class="chat-input-wrapper">
          <input ref="fileInputRef" type="file" accept="image/*" multiple class="hidden-file-input" @change="handleFileSelect" />
          <textarea v-model="userInput" class="form-control chat-textarea" :placeholder="t('playground.inputPlaceholder')"
                    :rows="compact ? 2 : 3" @keydown="handleKeydown" @paste="handlePaste"></textarea>
          <div v-if="pastedImages.length" class="pasted-images">
            <div v-for="img in pastedImages" :key="img.id" class="pasted-image-item" :class="{ uploading: img.uploading }">
              <img :src="img.dataUrl" class="pasted-image-preview" alt="pasted image" />
              <div v-if="img.uploading" class="pasted-image-overlay">
                <span class="upload-spinner"></span>
              </div>
              <div v-else-if="img.error" class="pasted-image-overlay error">
                <SvgIcon name="x" :size="14" />
              </div>
              <button v-if="!img.uploading" class="pasted-image-remove" @click="removePastedImage(img.id)" :title="t('common.delete')">
                <SvgIcon name="x" :size="12" />
              </button>
            </div>
          </div>
          <div class="chat-input-footer">
            <div class="chat-input-tools">
              <button class="btn-image-upload" :disabled="streaming || !selectedModel" @click="triggerFileSelect" :title="t('playground.uploadImage')">
                <SvgIcon name="image" :size="16" />
              </button>
              <button class="btn-code-mode" :class="{ active: codeMode }" @click="codeMode = !codeMode" :title="t('playground.codeMode')">
                <SvgIcon name="code" :size="16" />
              </button>
            </div>
            <button class="btn-send" :disabled="streaming || !selectedModel || (!userInput.trim() && pastedImages.length === 0)" @click="sendMessage">
              <SvgIcon name="send" :size="16" />
              {{ t('playground.send') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, watch, shallowRef, triggerRef } from 'vue'
import { apikeyApi, type ApiKey } from '@/api/apikey'
import { chatStream } from '@/api/chat'
import { uploadApi } from '@/api/upload'
import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'

import { useI18n } from '@/composables/useI18n'

/* 高亮模式下不再注入行号，如有 CSS 干扰则清除 */
marked.use(markedHighlight({
  langPrefix: 'hljs language-',
  highlight(code: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  }
}))

const { t } = useI18n()

/** 模型选项 */
interface ModelOption {
  modelName: string
  [key: string]: any
}

const props = withDefaults(defineProps<{
  /** 可选模型列表 */
  models: ModelOption[]
  /** 固定的分享码（分享模式），不传则为管理端模式 */
  fixedShareCode?: string
  /** 紧凑模式，用于嵌入卡片 */
  compact?: boolean
}>(), {
  compact: false
})

interface ChatMessage {
  role: 'user' | 'assistant' | 'system-msg'
  content: string
  /** 多模态图片 URL 列表（上传后或 data URI），用于用户消息 */
  images?: string[]
  /** 思考/推理过程内容（如 reasoning_content），展开后可查看 */
  reasoningContent?: string
  meta?: string
  channelInfo?: any
  /** 回答因 max_tokens 限制被截断 */
  truncated?: boolean
}

/** 粘贴的待发送图片 */
interface PastedImage {
  id: string
  dataUrl: string
  serverUrl: string
  uploading: boolean
  error?: string
}

const isShareMode = ref(!!props.fixedShareCode)

const chatRef = ref<HTMLElement | null>(null)
const apiKeys = ref<ApiKey[]>([])
const selectedModel = ref('')
const selectedApiKey = ref(0)
const temperature = ref(0.7)
const maxTokens = ref(65536)
const userInput = ref('')
// 使用 shallowRef 避免深层响应式追踪，配合 triggerRef 实现精确控制的流式渲染
const messages = shallowRef<ChatMessage[]>([])
const streaming = ref(false)
const tokenUsage = ref('')
const routingProgress = ref<{ phase: string; channel: string; channel_model: string; message?: string } | null>(null)
const showSidebar = ref(false)
/** 每条消息的推理内容展开状态 */
const expandedThinking = ref<Record<number, boolean>>({})
/** 是否启用代码模式（发送内容自动包裹为代码块） */
const codeMode = ref(false)

/* ========== 粘贴图片 ========== */
/** 待发送的粘贴图片列表 */
const pastedImages = ref<PastedImage[]>([])
/** 图片预览放大 */
const expandedImageUrl = ref('')

function expandImage(url: string) {
  expandedImageUrl.value = url
}

function removePastedImage(id: string) {
  pastedImages.value = pastedImages.value.filter(i => i.id !== id)
}

/**
 * 将 File 读取为 base64 data URI
 */
function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

/**
 * 处理粘贴事件：检测剪贴板中的图片并上传
 */
async function handlePaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return

  for (const item of items) {
    if (item.type.startsWith('image/')) {
      e.preventDefault()
      const file = item.getAsFile()
      if (!file) continue

      // 读为 data URI 用于本地预览
      const dataUrl = await fileToDataUrl(file)
      const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 6)

      const pasted: PastedImage = { id, dataUrl, serverUrl: '', uploading: true }
      pastedImages.value.push(pasted)

      // 上传到服务器
      try {
        const res = await uploadApi.upload(file)
        const found = pastedImages.value.find(i => i.id === id)
        if (found) {
          found.serverUrl = res.data.url
          found.uploading = false
        }
      } catch (err: any) {
        const found = pastedImages.value.find(i => i.id === id)
        if (found) {
          found.error = err.message || t('common.fail')
          found.uploading = false
        }
      }
      break
    }
  }
}

/**
 * 处理文件选择事件：上传选中图片
 */
const fileInputRef = ref<HTMLInputElement | null>(null)

function triggerFileSelect() {
  fileInputRef.value?.click()
}

async function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return

  for (const file of Array.from(files)) {
    if (!file.type.startsWith('image/')) continue

    const dataUrl = await fileToDataUrl(file)
    const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 6)

    const pasted: PastedImage = { id, dataUrl, serverUrl: '', uploading: true }
    pastedImages.value.push(pasted)

    try {
      const res = await uploadApi.upload(file)
      const found = pastedImages.value.find(i => i.id === id)
      if (found) {
        found.serverUrl = res.data.url
        found.uploading = false
      }
    } catch (err: any) {
      const found = pastedImages.value.find(i => i.id === id)
      if (found) {
        found.error = err.message || t('common.fail')
        found.uploading = false
      }
    }
  }

  // 重置 input 以便再次选择同一文件
  input.value = ''
}

/** 是否已选好模型（必要配置），用于自动收起侧栏
 *  selectedApiKey 默认 0 = 自动选择，也是有效配置 */
const isFullyConfigured = computed(() => {
  return !!selectedModel.value
})

/** 侧栏折叠状态（默认折叠，onMounted 中根据配置状态决定是否展开） */
const sidebarCollapsed = ref(true)

/** 展开/折叠侧栏 */
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

// 配置完整时自动折叠侧栏，配置不完整时自动展开
watch(isFullyConfigured, (configured) => {
  sidebarCollapsed.value = configured
})

/** 切换推理内容的展开/收起 */
function toggleThinking(idx: number) {
  expandedThinking.value = { ...expandedThinking.value, [idx]: !expandedThinking.value[idx] }
}

/** 加载 API 密钥列表（仅管理端模式） */
async function loadApiKeys() {
  if (isShareMode.value) return
  try {
    const res = await apikeyApi.list()
    apiKeys.value = res.data
  } catch {
    // ignore
  }
}

/** 保存所有测试配置到 localStorage */
function saveConfig() {
  // 未选择模型时不保存，避免恢复过程中因模型列表尚未加载导致 selectedModel 为空，
  // 进而覆盖已保存的有效配置
  if (!selectedModel.value) return
  const config = {
    selectedModel: selectedModel.value,
    selectedApiKey: selectedApiKey.value,
    temperature: temperature.value,
    maxTokens: maxTokens.value,
  }
  localStorage.setItem('playground_config', JSON.stringify(config))
}

/** 从 localStorage 恢复所有测试配置 */
function restoreConfig() {
  try {
    const saved = localStorage.getItem('playground_config')
    if (!saved) return
    const config = JSON.parse(saved)

    // 恢复模型（需在 models 加载后验证）
    if (config.selectedModel && props.models.some(m => m.modelName === config.selectedModel)) {
      selectedModel.value = config.selectedModel
    }
    // 恢复 API 密钥（需在 apiKeys 加载后验证，见 apiKeys watcher）
    if (config.selectedApiKey) {
      selectedApiKey.value = config.selectedApiKey
    }
    if (typeof config.temperature === 'number' && config.temperature >= 0 && config.temperature <= 2) {
      temperature.value = config.temperature
    }
    if (typeof config.maxTokens === 'number' && config.maxTokens >= 1) {
      maxTokens.value = config.maxTokens
    }
  } catch {
    // ignore parse errors
  }
}

/** 任一配置字段变化时自动保存 */
watch([selectedModel, selectedApiKey, temperature, maxTokens], () => {
  saveConfig()
}, { deep: false })

/** models 加载完成后重新尝试恢复保存的模型选择 */
watch(() => props.models.length, (len) => {
  if (len > 0) {
    const saved = localStorage.getItem('playground_config')
    if (!saved) return
    try {
      const config = JSON.parse(saved)
      if (config.selectedModel && props.models.some(m => m.modelName === config.selectedModel)) {
        selectedModel.value = config.selectedModel
      } else if (!isShareMode.value) {
        // 保存的模型已不存在 → 展开配置面板让用户重新选择
        sidebarCollapsed.value = false
      }
    } catch { /* ignore */ }
  }
})

/** apiKeys 加载完成后校验保存的 apiKey 是否仍有效 */
watch(() => apiKeys.value.length, (len) => {
  if (len > 0 && selectedApiKey.value) {
    const savedKeyId = selectedApiKey.value
    if (!apiKeys.value.some(k => k.id === savedKeyId)) {
      selectedApiKey.value = 0
    }
  }
})

onMounted(() => {
  loadApiKeys()
  if (!isShareMode.value) {
    restoreConfig()
    // 默认不显示配置项；没有已保存配置时展开配置面板让用户设置
    if (!localStorage.getItem('playground_config')) {
      sidebarCollapsed.value = false
    }
    // 有已保存配置但模型列表尚未加载时保持折叠，
    // 等模型加载完成后由 models watcher 决定是否展开
  } else {
    // 分享模式无本地存储配置，默认展开配置面板让用户选择模型
    sidebarCollapsed.value = false
  }
})

async function scrollToBottom() {
  await nextTick()
  if (chatRef.value) {
    chatRef.value.scrollTop = chatRef.value.scrollHeight
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function clearChat() {
  messages.value = []
  tokenUsage.value = ''
}

function quickSend(text: string) {
  userInput.value = text
  sendMessage()
}

function addMessage(role: ChatMessage['role'], content: string, meta?: string, channelInfo?: any, images?: string[]) {
  messages.value.push({ role, content, meta, channelInfo, images: images?.length ? images : undefined })
  triggerRef(messages)  // 手动触发 shallowRef 更新
  scrollToBottom()
  return messages.value[messages.value.length - 1]
}

/** 构建请求体 */
function buildRequestBody() {
  const body: Record<string, any> = {
    model: selectedModel.value,
    messages: messages.value.filter(m => m.role !== 'system-msg').filter(m => m.content.trim() !== '' || (m.images && m.images.length)).map(m => {
      // 用户消息含图片时构建多模态 content 数组
      if (m.role === 'user' && m.images && m.images.length > 0) {
        const content: any[] = []
        if (m.content.trim()) {
          content.push({ type: 'text', text: m.content })
        }
        for (const imgUrl of m.images) {
          content.push({ type: 'image_url', image_url: { url: imgUrl } })
        }
        return { role: 'user', content }
      }
      // DeepSeek thinking mode: assistant 的 reasoning_content 必须传回
      if (m.role === 'assistant') {
        const assistantMsg: Record<string, any> = { role: 'assistant', content: m.content }
        if (m.reasoningContent) {
          assistantMsg.reasoning_content = m.reasoningContent
        }
        return assistantMsg
      }
      return { role: 'user', content: m.content }
    }),
    temperature: temperature.value,
    max_tokens: maxTokens.value
  }
  // 管理端模式：在 body 中传 api_key_id
  if (!isShareMode.value) {
    body.api_key_id = selectedApiKey.value
  }
  return body
}

async function sendMessage() {
  let content = userInput.value.trim()
  const pendingImages = pastedImages.value.filter(i => !i.error && !i.uploading)
  if (!content && pendingImages.length === 0) return
  if (streaming.value) return

  if (!selectedModel.value) {
    addMessage('system-msg', t('playground.selectModelFirst'))
    return
  }

  // 代码模式下自动包裹为代码块
  if (codeMode.value && content) {
    content = '```\n' + content + '\n```'
  }

  // 收集已上传完成的图片 URL（优先服务器 URL，上传失败时回退 data URI）
  const msgImages = pendingImages.map(i => i.serverUrl || i.dataUrl)
  addMessage('user', content, undefined, undefined, msgImages.length ? msgImages : undefined)
  userInput.value = ''
  pastedImages.value = []
  streaming.value = true

  const assistantMsg: ChatMessage = { role: 'assistant', content: '' }
  messages.value.push(assistantMsg)
  await scrollToBottom()

  try {
    await sendStreamRequest(assistantMsg)
  } catch (e: any) {
    assistantMsg.content = t('playground.requestFailed') + ': ' + e.message
    assistantMsg.role = 'system-msg'
  } finally {
    streaming.value = false
    routingProgress.value = null
    // 流式生成结束 → 自动收起思考过程
    const lastIdx = messages.value.length - 1
    if (lastIdx >= 0 && expandedThinking.value[lastIdx]) {
      expandedThinking.value = { ...expandedThinking.value, [lastIdx]: false }
    }
    await scrollToBottom()
  }
}

async function sendStreamRequest(targetMsg: ChatMessage) {
  const response = await chatStream(
    buildRequestBody(),
    isShareMode.value,
    props.fixedShareCode
  )

  if (!response.ok) {
    throw new Error('HTTP ' + response.status)
  }

  let fullContent = ''
  let startTime = Date.now()
  let tokenNum = 0
  const reader = response.body!.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''

    // 本轮网络数据块中是否有新内容需要渲染
    let hasNewContent = false

    for (const event of events) {
      if (!event.trim()) continue
      const lines = event.split('\n')
      for (const line of lines) {
        let data: string | null = null
        if (line.startsWith('data: ')) data = line.substring(6)
        else if (line.startsWith('data:')) data = line.substring(5)
        if (!data || data === '[DONE]') continue

        try {
          const json = JSON.parse(data)
          if (json._gateway_meta) {
            targetMsg.channelInfo = json
            continue
          }
          if (json._routing_progress) {
            routingProgress.value = {
              phase: json.phase,
              channel: json.channel,
              channel_model: json.channel_model,
              message: json.message
            }
            continue
          }
          if (json.error) {
            targetMsg.content = t('playground.error') + ': ' + (typeof json.error === 'object' ? json.error.message : json.error)
            continue
          }
          if (json.choices && json.choices[0]) {
            const choice = json.choices[0]
            const delta = choice.delta
            if (delta?.content) {
              fullContent += delta.content
              tokenNum++
              targetMsg.content = fullContent
              hasNewContent = true
            }
            // 处理 reasoning_content（思考/推理过程）
            if (delta?.reasoning_content) {
              if (!targetMsg.reasoningContent) {
                targetMsg.reasoningContent = ''
                // 首次收到推理内容 → 自动展开
                const msgIdx = messages.value.indexOf(targetMsg)
                if (msgIdx >= 0) {
                  expandedThinking.value = { ...expandedThinking.value, [msgIdx]: true }
                }
              }
              targetMsg.reasoningContent += delta.reasoning_content
              // 即使没有 content 也要触发渲染，让 UI 显示思考内容
              hasNewContent = true
            }
            // 检测截断：finish_reason=length 表示上游 API 因 token 限制截断了回答
            if (choice.finish_reason === 'length') {
              targetMsg.truncated = true
              hasNewContent = true
            }
          }
        } catch { /* ignore parse errors */ }
      }
    }

    // 本轮 read 有新内容 → 等待 Vue 渲染后再处理下一批数据
    // 关键：让出主线程让浏览器有机会渲染这一帧，避免批量合并更新
    if (hasNewContent) {
      // 手动触发 shallowRef 更新
      triggerRef(messages)
      // 等待下一帧渲染
      await new Promise(resolve => requestAnimationFrame(resolve))
      await scrollToBottom()
    }
  }

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1)
  targetMsg.content = fullContent
  tokenUsage.value = t('playground.tokenUsage').replace('{tokens}', String(tokenNum)).replace('{elapsed}', elapsed)
}

/** 将 Markdown 渲染为安全的 HTML */
function renderMarkdown(text: string): string {
  if (!text) return ''
  try {
    // 压缩列表项之间的多余空行，避免 marked 生成 li>p 导致过大间距
    let processed = text
      .replace(/((?:^|\n)[ \t]*[-*+][ \t]+[^\n]+)\n\n(?=[ \t]*[-*+][ \t]+)/g, '$1\n')
      .replace(/((?:^|\n)[ \t]*\d+\.[ \t]+[^\n]+)\n\n(?=[ \t]*\d+\.[ \t]+)/g, '$1\n')
    return (marked.parse(processed, { async: false }) as string).trimEnd()
  } catch {
    return text
  }
}

/** 将 reasoning_content（思考/推理过程）渲染为 HTML，开启 breaks 保留换行 */
function renderReasoningMarkdown(text: string): string {
  if (!text) return ''
  try {
    // 压缩列表项之间的多余空行，避免 marked 生成 li>p 导致过大间距
    let processed = text
      .replace(/((?:^|\n)[ \t]*[-*+][ \t]+[^\n]+)\n\n(?=[ \t]*[-*+][ \t]+)/g, '$1\n')
      .replace(/((?:^|\n)[ \t]*\d+\.[ \t]+[^\n]+)\n\n(?=[ \t]*\d+\.[ \t]+)/g, '$1\n')
    return (marked.parse(processed, { async: false, breaks: true }) as string).trimEnd()
  } catch {
    return text
  }
}
</script>

<style scoped>
  .chat-playground {
    display: flex;
    gap: 0;
    height: calc(100vh - 130px);
  }
  .playground-sidebar {
    margin-right: 16px;
  }
  .playground-sidebar.collapsed {
    margin-right: 0;
  }
.chat-playground.compact {
  flex-direction: column;
  height: auto;
  min-height: 480px;
}

.playground-sidebar {
  width: 280px;
  flex-shrink: 0;
  overflow: hidden;
  transition: width 0.25s ease, margin 0.25s ease, padding 0.25s ease;
}
.playground-sidebar.collapsed {
  width: 0;
}
.playground-sidebar > .playground-sidebar-inner {
  min-width: 280px;
}
@media (max-width: 768px) {
  .chat-playground {
    flex-direction: column;
    height: calc(100vh - 130px);
  }
  .chat-playground.compact {
    height: auto;
  }
  .mobile-sidebar-toggle {
    display: none;
  }
  .playground-sidebar {
    width: 100%;
    overflow: hidden;
    margin-bottom: 12px;
    transition: max-height 0.25s ease, opacity 0.25s ease, margin-bottom 0.25s ease, padding 0.25s ease;
  }
  .playground-sidebar:not(.collapsed) {
    max-height: 500px;
    opacity: 1;
  }
  .playground-sidebar.collapsed {
    max-height: 0;
    opacity: 0;
    margin-bottom: 0;
    padding: 0;
  }
  .playground-sidebar > .playground-sidebar-inner {
    min-width: 0;
    overflow-y: auto;
    max-height: 40vh;
  }
  .playground-main {
    flex: 1;
    min-height: 0;
  }
}
.playground-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* compact 模式的简单控制栏 */
.compact-controls {
  padding: 8px 0;
}
.compact-controls .form-control {
  max-width: 300px;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px 8px 0 0;
  border-bottom: none;
}
.chat-header-left { display: flex; align-items: center; gap: 8px; }
.test-status-icon {
  width: 24px; height: 24px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 50%;
}
.test-status-icon.testing { animation: pulse 1.5s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }
.chat-header-title { font-size: 14px; font-weight: 600; color: var(--text-primary); }

.chat-messages {
  flex: 1;
  overflow-y: auto;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.chat-playground.compact .chat-messages {
  min-height: 280px;
  max-height: 400px;
}
.chat-empty {
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  height: 100%; text-align: center;
}

.chat-message { display: flex; gap: 12px; max-width: 90%; }
.chat-message.user { align-self: flex-end; flex-direction: row-reverse; }
.chat-avatar {
  width: 32px; height: 32px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.chat-avatar.user { background: var(--accent-blue); }
.chat-avatar.assistant { background: var(--accent-purple); }
.chat-avatar.system-msg { background: var(--accent-yellow); }

.chat-bubble {
  padding: 10px 14px; border-radius: 12px;
  font-size: 14px; line-height: 1.6;
  word-wrap: break-word; white-space: pre-wrap;
}
.chat-message.user .chat-bubble {
  background: rgba(88, 166, 255, 0.15);
  border: 1px solid rgba(88, 166, 255, 0.2);
}
.chat-message.assistant .chat-bubble {
  background: var(--bg-tertiary); border: 1px solid var(--border-color);
}
.chat-message.system-msg .chat-bubble {
  background: rgba(210, 153, 34, 0.1);
  border: 1px solid rgba(210, 153, 34, 0.2);
  color: var(--accent-yellow); font-size: 12px;
}

.chat-meta { font-size: 11px; color: var(--text-muted); margin-top: 4px; }
.chat-truncated-hint {
  font-size: 12px; color: var(--accent-yellow); margin-top: 4px;
  padding: 4px 10px; border-radius: 4px;
  background: rgba(210, 153, 34, 0.08);
  border: 1px solid rgba(210, 153, 34, 0.2);
}
.chat-quick-actions {
  display: flex; gap: 8px; padding: 8px 0;
}
.btn-quick-hint {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 20px;
  border: 1px solid var(--border-color);
  background: var(--bg-tertiary);
  color: var(--text-primary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-quick-hint:hover:not(:disabled) {
  border-color: var(--accent-purple);
  color: var(--accent-purple);
}
.btn-quick-hint:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.chat-input-area { margin-top: 4px; }
.chat-input-wrapper {
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 10px 12px;
}
.chat-input-wrapper:focus-within {
  border-color: var(--accent-blue);
}
.chat-textarea {
  width: 100%;
  box-sizing: border-box;
  border: none !important;
  background: transparent !important;
  resize: none;
  padding: 4px 2px;
  font-size: 14px;
  outline: none;
  min-height: 56px;
  box-shadow: none !important;
  margin-bottom: 0;
}
.chat-input-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-top: 8px;
  min-height: 0;
}
.chat-input-tools {
  display: flex;
  align-items: center;
  gap: 4px;
}
.btn-image-upload {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}
.btn-image-upload:hover:not(:disabled) {
  background: var(--bg-tertiary);
  color: var(--accent-blue);
}
.btn-image-upload:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.btn-code-mode {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
  font-family: monospace;
  font-weight: 600;
}
.btn-code-mode:hover:not(:disabled) {
  background: var(--bg-tertiary);
  color: var(--accent-blue);
}
.btn-code-mode.active {
  background: color-mix(in srgb, var(--accent-blue) 12%, transparent);
  color: var(--accent-blue);
}
.btn-send {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border: none;
  border-radius: 8px;
  background: var(--accent-purple);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}
.btn-send:hover:not(:disabled) {
  filter: brightness(1.15);
}
.btn-send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.hidden-file-input {
  display: none;
}

/* ===== 粘贴图片预览 ===== */
.pasted-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0 4px;
}
.pasted-image-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border-color);
  background: var(--bg-tertiary);
  flex-shrink: 0;
}
.pasted-image-item.uploading {
  opacity: 0.7;
}
.pasted-image-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.pasted-image-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,0.4);
  border-radius: 8px;
}
.pasted-image-overlay.error {
  background: rgba(248,81,73,0.3);
}
.upload-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.pasted-image-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  background: rgba(0,0,0,0.6);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.15s;
}
.pasted-image-item:hover .pasted-image-remove {
  opacity: 1;
}

/* ===== 聊天消息中的图片 ===== */
.chat-images {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  max-width: 100%;
}
.chat-image {
  max-width: 100%;
  height: auto;
  max-height: 400px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
  cursor: zoom-in;
  object-fit: contain;
  background: var(--bg-tertiary);
  transition: border-color 0.15s;
}
.chat-image:hover {
  border-color: var(--accent-blue);
}

/* ===== 图片放大灯箱 ===== */
.image-lightbox {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0,0,0,0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: zoom-out;
}
.image-lightbox-img {
  max-width: 90vw;
  max-height: 90vh;
  border-radius: 8px;
  box-shadow: 0 8px 40px rgba(0,0,0,0.5);
}
.image-lightbox-close {
  position: absolute;
  top: 16px;
  right: 24px;
  background: rgba(0,0,0,0.5);
  border: none;
  color: #fff;
  font-size: 28px;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}
.image-lightbox-close:hover {
  background: rgba(0,0,0,0.8);
}

.status-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }

.chat-channel-info {
  display: flex; align-items: center; gap: 6px;
  margin-bottom: 6px; font-size: 12px;
}
.channel-badge { padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.channel-badge.openai { background: rgba(16, 163, 127, 0.15); color: #10a37f; border: 1px solid rgba(16, 163, 127, 0.3); }
.channel-badge.anthropic { background: rgba(204, 146, 80, 0.15); color: #cc9250; border: 1px solid rgba(204, 146, 80, 0.3); }
.channel-name { color: var(--text-muted); font-weight: 500; }
.channel-arrow { color: var(--text-muted); opacity: 0.5; }
.channel-model-name { color: var(--accent-purple); font-weight: 500; }

.chat-routing-progress {
  display: flex; align-items: center; gap: 6px;
  margin-bottom: 6px; font-size: 12px;
  color: var(--text-muted);
}
.routing-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--accent-purple);
  animation: pulse 1.2s infinite;
}
@keyframes pulse { 0%, 100% { opacity: 0.4; } 50% { opacity: 1; } }

.cursor-blink::after { content: '▋'; animation: blink 1s infinite; color: var(--accent-purple); }
@keyframes blink { 0%, 50% { opacity: 1; } 51%, 100% { opacity: 0; } }

/* 思考/推理过程区块 */
.chat-thinking {
  margin-bottom: 8px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  /* 使用 isolation 保持圆角裁剪，但不裁剪内容，让列表标记能显示在 padding 区域 */
  isolation: isolate;
  background: var(--bg-tertiary);
}
.thinking-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-muted);
  user-select: none;
  transition: background 0.15s;
}
.thinking-header:hover {
  background: color-mix(in srgb, var(--accent-purple) 8%, transparent);
}
.thinking-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--accent-purple);
}
.thinking-dot.streaming {
  animation: pulse 1.2s infinite;
}
.thinking-label {
  font-weight: 500;
  color: var(--accent-purple);
}
.thinking-arrow {
  font-size: 9px;
  transition: transform 0.2s;
  margin-left: 4px;
}
.thinking-arrow.open {
  transform: rotate(90deg);
}
.thinking-content {
  padding: 8px 20px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--bg-secondary) 50%, transparent);
}
/* 思考/推理内容的 white-space 在全局块中设置（见下文），此处不再重复 */

/* ===== 右上角模型选择 + 设置 ===== */
.chat-header-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  position: relative;
  z-index: 10;
}
.model-selector-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 4px 10px;
  font-size: 13px;
}
.model-selector-label {
  color: var(--text-muted);
  white-space: nowrap;
}
.model-selector-select {
  background: transparent;
  border: none;
  color: var(--accent-purple);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  outline: none;
  padding-right: 4px;
  max-width: 120px;
}
.model-selector-select option {
  background: var(--bg-secondary);
  color: var(--text-primary);
}
.btn-model-settings {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}
.btn-model-settings:hover {
  background: var(--bg-tertiary);
  color: var(--accent-purple);
  border-color: var(--accent-purple);
}

/* 移动端配置栏切换按钮 - 默认隐藏 */
.mobile-sidebar-toggle {
  display: none;
  width: 100%;
  padding: 10px 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.mobile-sidebar-toggle:active {
  background: var(--bg-tertiary);
}



<style>
/* github-markdown-css 提供结构排版（字号、间距、边框圆角等），
 * 但所有颜色属性都是硬编码 hex，与 App 的 CSS 变量体系不兼容。
 * 此覆盖块将所有颜色属性映射到 App 主题变量，使 markdown 渲染融入当前主题。 */

/* ── 基础 ── */
/* 关键：markdown-body 内的所有元素都需要重置 line-height，
 * 避免被 chat-bubble 的 1.6 撑出过多空白（14px × 1.6 = 22.4px 单行）
 * 同时 line-height 1.3 让单行 box 高度接近 font-size（18.2px ≈ 14px 文字），
 * 消除视觉上的"半行距空行"错觉 */
.chat-playground .markdown-body {
  background-color: transparent !important;
  color: var(--text-primary);
  white-space: normal !important;
  font-size: 14px !important;
  line-height: 1.3 !important;
}
.chat-playground .markdown-body *,
.chat-playground .markdown-body {
  line-height: 1.3 !important;
  white-space: normal !important;
}
.chat-playground .markdown-body pre,
.chat-playground .markdown-body pre * {
  white-space: pre !important;
}
.chat-playground .thinking-content.markdown-body {
  white-space: normal !important;
}

/* ── 垂直间距压缩 ── */
/* 关键：聊天场景下完全抛弃 markdown 库默认的 margin 体系，
 * 改用外层 chat-bubble 的 padding + flex gap 控制视觉距离。
 * markdown-body 内部元素之间几乎不依赖 margin。 */
.chat-playground .markdown-body p {
  margin: 0 !important;
  line-height: 1.3 !important;
}
.chat-playground .markdown-body li {
  line-height: 1.3 !important;
}
/* 非段落块级元素保留极小间距，仅用于结构分隔 */
.chat-playground .markdown-body blockquote,
.chat-playground .markdown-body pre,
.chat-playground .markdown-body hr,
.chat-playground .markdown-body table,
.chat-playground .markdown-body ul,
.chat-playground .markdown-body ol,
.chat-playground .markdown-body dl,
.chat-playground .markdown-body figure {
  margin-top: 4px !important;
  margin-bottom: 4px !important;
}
/* 列表内段落：消除库默认 li>p { margin-top: 1rem } 的 16px 顶部间距 */
.chat-playground .markdown-body li > p {
  margin: 0 !important;
}
/* 列表项之间：消除 0.25em 间距 */
.chat-playground .markdown-body li + li {
  margin-top: 0 !important;
}
/* 收尾：首元素无上间距，末元素无下间距 */
.chat-playground .markdown-body > *:first-child {
  margin-top: 0 !important;
}
.chat-playground .markdown-body > *:last-child {
  margin-bottom: 0 !important;
}

/* ── 标题 ── */
/* 聊天场景下标题字号应与正文比例协调（参考 h2=1.5em 缩到 ~1.1~1.4em），
 * 不再沿用 github 文档 2em 的巨大字号 */
.chat-playground .markdown-body h1,
.chat-playground .markdown-body h2,
.chat-playground .markdown-body h3,
.chat-playground .markdown-body h4,
.chat-playground .markdown-body h5,
.chat-playground .markdown-body h6 {
  margin: 4px 0 0 0 !important;
  padding: 0 !important;
  font-weight: 600 !important;
  line-height: 1.2 !important;
}
.chat-playground .markdown-body h1 {
  font-size: 1.4em !important;
  border-bottom: 1px solid var(--border-color);
}
.chat-playground .markdown-body h2 {
  font-size: 1.25em !important;
  border-bottom: 1px solid var(--border-color);
}
.chat-playground .markdown-body h3 {
  font-size: 1.1em !important;
}
.chat-playground .markdown-body h4 {
  font-size: 1em !important;
}
.chat-playground .markdown-body h5,
.chat-playground .markdown-body h6 {
  font-size: .9em !important;
}
/* 标题作为首元素时无需顶部 margin（chat-bubble padding 已给） */
.chat-playground .markdown-body > h1:first-child,
.chat-playground .markdown-body > h2:first-child,
.chat-playground .markdown-body > h3:first-child,
.chat-playground .markdown-body > h4:first-child,
.chat-playground .markdown-body > h5:first-child,
.chat-playground .markdown-body > h6:first-child {
  margin-top: 0 !important;
}

/* ── 代码 ── */
.chat-playground .markdown-body pre {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}
.chat-playground .markdown-body code {
  background: transparent;
}
.chat-playground .markdown-body p > code,
.chat-playground .markdown-body li > code {
  background: color-mix(in srgb, var(--accent-blue) 12%, transparent);
  color: var(--accent-blue);
  padding: 2px 6px;
  border-radius: 4px;
}
.chat-playground .markdown-body pre code {
  background: none;
  color: inherit;
}

/* ── 引用 ── */
.chat-playground .markdown-body blockquote {
  border-left-color: var(--accent-purple);
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--accent-purple) 6%, transparent);
  border-radius: 0 4px 4px 0;
  padding: 4px 12px;
}

/* ── 表格 ── */
.chat-playground .markdown-body table th,
.chat-playground .markdown-body table td {
  border-color: var(--border-color);
}
.chat-playground .markdown-body table th {
  background: color-mix(in srgb, var(--accent-blue) 8%, transparent);
  font-weight: 600;
}

/* ── 分割线 ── */
.chat-playground .markdown-body hr {
  background: transparent;
  border-bottom-color: var(--border-color);
}

/* ── 高亮标记 ── */
.chat-playground .markdown-body mark {
  background: color-mix(in srgb, var(--accent-yellow) 20%, transparent);
  color: var(--text-primary);
}

/* ── 图片 ── */
.chat-playground .markdown-body img {
  max-width: 100%;
  border-radius: 8px;
}
</style>
