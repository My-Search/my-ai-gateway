<template>
  <div class="chat-playground" :class="{ compact }">
    <!-- 移动端配置栏切换按钮 -->
    <button v-if="!compact" class="mobile-sidebar-toggle" @click="showSidebar = !showSidebar">
      <SvgIcon name="settings" :size="14" />
      {{ showSidebar ? '收起配置' : '展开配置' }}
    </button>

    <!-- 左侧配置栏 -->
    <div v-if="!compact" class="playground-sidebar" :class="{ 'mobile-hidden': !showSidebar }">
      <div class="card">
        <div class="card-title mb-3">测试配置</div>

        <div class="form-group">
          <label>选择模型</label>
          <select v-model="selectedModel" class="form-control">
            <option value="">-- 请选择模型 --</option>
            <option v-for="m in models" :key="m.modelName" :value="m.modelName">{{ m.modelName }}</option>
          </select>
        </div>

        <div v-if="!isShareMode" class="form-group">
          <label>API 密钥</label>
          <select v-model="selectedApiKey" class="form-control">
            <option :value="0">自动选择（第一个可用）</option>
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
          <input v-model.number="maxTokens" type="number" class="form-control" min="1" max="32000" />
        </div>

        <button class="btn btn-secondary" style="width:100%;" @click="clearChat">
          <SvgIcon name="trash" :size="14" /> 清空对话
        </button>
      </div>

      <div class="card mt-3">
        <div class="card-title mb-3">状态</div>
        <div class="status-item">
          <span class="status-dot" :class="streaming ? 'active' : 'inactive'"></span>
          <span>{{ streaming ? '生成中...' : '就绪' }}</span>
        </div>
        <div v-if="tokenUsage" class="text-muted mt-2" style="font-size:12px;">{{ tokenUsage }}</div>
      </div>
    </div>

    <!-- 右侧聊天区 -->
    <div class="playground-main">
      <div v-if="!compact" class="chat-header">
        <div class="chat-header-left">
          <span class="test-status-icon" :class="streaming ? 'testing' : 'idle'">
            <SvgIcon v-if="streaming" name="zap" :size="16" />
            <SvgIcon v-else name="check" :size="16" />
          </span>
          <span class="chat-header-title">模型可用性测试</span>
        </div>
        <div class="chat-header-right" v-if="selectedModel">
          当前模型: <code>{{ selectedModel }}</code>
        </div>
      </div>

      <!-- compact 模式的简单模型选择 -->
      <div v-if="compact" class="compact-controls">
        <select v-model="selectedModel" class="form-control">
          <option value="">-- 请选择模型 --</option>
          <option v-for="m in models" :key="m.modelName" :value="m.modelName">{{ m.modelName }}</option>
        </select>
      </div>

      <div class="chat-messages" ref="chatRef">
        <div v-if="!messages.length" class="chat-empty">
          <div class="empty-icon"><SvgIcon name="ai-chat" :size="48" color="var(--accent-purple)" /></div>
          <div style="font-size:16px;font-weight:600;margin-bottom:8px;">模型测试 Playground</div>
          <div style="color:var(--text-muted);">选择模型后输入消息开始测试</div>
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
              <span v-if="routingProgress.phase === 'trying'">正在尝试 {{ routingProgress.channel }} / {{ routingProgress.channel_model }}</span>
              <span v-else-if="routingProgress.phase === 'retrying'">重试中 {{ routingProgress.channel }} / {{ routingProgress.channel_model }}（{{ routingProgress.message }}）</span>
              <span v-else-if="routingProgress.phase === 'switching'">切换候选：{{ routingProgress.message }}，尝试下一渠道…</span>
            </div>
            <div class="chat-bubble" :class="{ 'cursor-blink': idx === messages.length - 1 && streaming }">
              {{ msg.content }}
            </div>
            <div v-if="msg.meta" class="chat-meta" v-html="msg.meta"></div>
          </div>
        </div>
      </div>

      <div v-if="!compact" class="chat-quick-actions">
        <button class="btn btn-secondary btn-quick" :disabled="streaming || !selectedModel" @click="quickSend('hello')">hello</button>
      </div>

      <div class="chat-input-area">
        <textarea v-model="userInput" class="form-control" placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
                  :rows="compact ? 2 : 3" @keydown="handleKeydown"></textarea>
        <div class="input-actions">
          <label class="stream-toggle">
            <input type="checkbox" v-model="streamEnabled" /> 流式输出
          </label>
          <button class="btn btn-primary" :disabled="!userInput.trim() || streaming" @click="sendMessage">
            <span>
              <SvgIcon name="send" :size="14" /> {{ streaming ? '生成中...' : '发送' }}
            </span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted, watch } from 'vue'
import { apikeyApi, type ApiKey } from '@/api/apikey'

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
  meta?: string
  channelInfo?: any
}

const isShareMode = ref(!!props.fixedShareCode)

const chatRef = ref<HTMLElement | null>(null)
const apiKeys = ref<ApiKey[]>([])
const selectedModel = ref('')
const selectedApiKey = ref(0)
const temperature = ref(0.7)
const maxTokens = ref(2048)
const streamEnabled = ref(true)
const userInput = ref('')
const messages = ref<ChatMessage[]>([])
const streaming = ref(false)
const tokenUsage = ref('')
const routingProgress = ref<{ phase: string; channel: string; channel_model: string; message?: string } | null>(null)
const showSidebar = ref(false)

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

/** 恢复上次选择的模型 */
function restoreSelectedModel() {
  const savedModel = localStorage.getItem('playground_selected_model')
  if (savedModel && props.models.some(m => m.modelName === savedModel)) {
    selectedModel.value = savedModel
  }
}

watch(selectedModel, (val) => {
  if (val) {
    localStorage.setItem('playground_selected_model', val)
  } else {
    localStorage.removeItem('playground_selected_model')
  }
})

onMounted(() => {
  loadApiKeys()
  if (!isShareMode.value) {
    restoreSelectedModel()
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

function addMessage(role: ChatMessage['role'], content: string, meta?: string, channelInfo?: any) {
  messages.value.push({ role, content, meta, channelInfo })
  scrollToBottom()
  return messages.value[messages.value.length - 1]
}

/** 构建请求体 */
function buildRequestBody() {
  const body: Record<string, any> = {
    model: selectedModel.value,
    messages: messages.value.filter(m => m.role !== 'system-msg' && m.content.trim() !== '').map(m => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.content
    })),
    temperature: temperature.value,
    max_tokens: maxTokens.value
  }
  // 管理端模式：在 body 中传 api_key_id
  if (!isShareMode.value) {
    body.api_key_id = selectedApiKey.value
  }
  return body
}

/** 获取聊天端点 URL */
function getStreamUrl(): string {
  if (isShareMode.value) {
    return `/api/share/chat/stream?shareCode=${props.fixedShareCode}`
  }
  return '/admin/api/chat/stream'
}

function getNonStreamUrl(): string {
  if (isShareMode.value) {
    return `/api/share/chat?shareCode=${props.fixedShareCode}`
  }
  return '/admin/api/chat'
}

async function sendMessage() {
  const content = userInput.value.trim()
  if (!content || streaming.value || !selectedModel.value) return

  if (!selectedModel.value) {
    addMessage('system-msg', '请先选择一个模型')
    return
  }

  addMessage('user', content)
  userInput.value = ''
  streaming.value = true

  const assistantMsg: ChatMessage = { role: 'assistant', content: '' }
  messages.value.push(assistantMsg)
  await scrollToBottom()

  try {
    if (streamEnabled.value) {
      await sendStreamRequest(assistantMsg)
    } else {
      await sendNonStreamRequest(assistantMsg)
    }
  } catch (e: any) {
    assistantMsg.content = '请求失败: ' + e.message
    assistantMsg.role = 'system-msg'
  } finally {
    streaming.value = false
    routingProgress.value = null
    await scrollToBottom()
  }
}

async function sendStreamRequest(targetMsg: ChatMessage) {
  const response = await fetch(getStreamUrl(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(buildRequestBody())
  })

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
            targetMsg.content = '错误: ' + (typeof json.error === 'object' ? json.error.message : json.error)
            continue
          }
          if (json.choices && json.choices[0]) {
            const delta = json.choices[0].delta
            if (delta?.content) {
              fullContent += delta.content
              tokenNum++
              targetMsg.content = fullContent
              await scrollToBottom()
            }
          }
        } catch { /* ignore parse errors */ }
      }
    }
  }

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1)
  targetMsg.content = fullContent
  tokenUsage.value = `本轮: ${tokenNum} tokens / ${elapsed}s`
}

async function sendNonStreamRequest(targetMsg: ChatMessage) {
  const response = await fetch(getNonStreamUrl(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(buildRequestBody())
  })

  const text = await response.text()
  try {
    const json = JSON.parse(text)
    if (json.error) {
      targetMsg.content = '错误: ' + (json.error.message || JSON.stringify(json.error))
      return
    }
    if (json.choices?.[0]?.message?.content) {
      targetMsg.content = json.choices[0].message.content
      const usage = json.usage
      if (usage) {
        tokenUsage.value = `本轮: ${usage.total_tokens} tokens (prompt: ${usage.prompt_tokens}, completion: ${usage.completion_tokens})`
      }
    }
  } catch {
    targetMsg.content = text
  }
}
</script>

<style scoped>
.chat-playground {
  display: flex;
  gap: 16px;
  height: calc(100vh - 130px);
}
.chat-playground.compact {
  flex-direction: column;
  height: auto;
  min-height: 480px;
}

.playground-sidebar {
  width: 280px;
  flex-shrink: 0;
  overflow-y: auto;
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
.chat-header-right { font-size: 12px; color: var(--text-muted); }
.chat-header-right code {
  background: var(--bg-tertiary); padding: 2px 6px; border-radius: 4px;
  font-size: 12px; color: var(--accent-purple);
}

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
.chat-quick-actions {
  display: flex; gap: 8px; padding: 8px 0;
  border-top: 1px solid var(--border-color);
}
.chat-input-area { margin-top: 4px; }
.chat-input-area textarea { resize: none; }

.input-actions {
  display: flex; gap: 8px; margin-top: 8px;
  justify-content: flex-end; align-items: center;
}
.stream-toggle {
  display: flex; align-items: center; gap: 4px;
  font-size: 13px; color: var(--text-secondary); cursor: pointer;
}
.btn-quick {
  font-size: 12px; padding: 4px 12px;
  font-weight: 600; letter-spacing: 0.5px;
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

@media (max-width: 768px) {
  .chat-playground {
    flex-direction: column;
    height: calc(100vh - 130px);
  }
  .chat-playground.compact {
    height: auto;
  }
  .mobile-sidebar-toggle {
    display: flex;
  }
  .playground-sidebar {
    width: 100%;
    max-height: 40vh;
    overflow-y: auto;
  }
  .playground-sidebar.mobile-hidden {
    display: none;
  }
  .playground-main {
    flex: 1;
    min-height: 0;
  }
}
</style>
