<template>
  <!-- ── Page header ── -->
  <div class="page-header cfg-page-header">
    <div class="page-header-main">
      <div class="title-row">
        <router-link to="/admin/channel/list" class="back-link" :title="t('common.back')">
          <SvgIcon name="arrow-left" :size="16" />
        </router-link>
        <h2>{{ isEdit ? t('channel.form.editTitle') : t('channel.form.addTitle') }}</h2>
        <span v-if="isEdit" class="badge badge-muted">#{{ route.params.id }}</span>
        <span class="badge" :class="form.enabled === 1 ? 'badge-success' : 'badge-muted'">
          {{ form.enabled === 1 ? t('common.enabled') : t('common.disabled') }}
        </span>
      </div>
      <p>{{ t('channel.form.nameHint') }}</p>
    </div>
    <div class="header-actions">
      <span class="summary-chip" :title="t('cfg.summary')">
        <SvgIcon name="key" :size="12" /> {{ apiKeys.length }}
        <span class="chip-sep"></span>
        <SvgIcon name="model" :size="12" /> {{ activeModelCount }}
      </span>
    </div>
  </div>

  <!-- 编辑态先显示行内加载态，避免空表单闪现 -->
  <div v-if="loading" class="page-loading">
    <LoadingSpinner :size="18" :text="t('common.loading')" />
  </div>

  <ConfigShell v-else :sections="sections" :railTitle="t('cfg.nav')">
    <!-- ══ 基础信息 ══ -->
    <ConfigSection id="cfg-basic" :title="t('cfg.basic')" :desc="t('cfg.sectionBasicDesc')" icon="channel">
      <ConfigField :label="t('cfg.fieldName')" :hint="t('channel.form.nameHint')" required>
        <input id="name" v-model="form.name" type="text" class="form-control"
               :placeholder="t('channel.form.namePlaceholder')" required />
      </ConfigField>

      <ConfigField :label="t('cfg.fieldProtocol')" :hint="t('channel.form.typeHint')" required>
        <div class="protocol-grid">
          <button
            v-for="opt in protocolOptions"
            :key="opt.value"
            type="button"
            class="protocol-card"
            :class="{ active: form.channelType === opt.value }"
            @click="form.channelType = opt.value"
          >
            <span class="protocol-icon"><SvgIcon :name="opt.icon" :size="16" /></span>
            <span class="protocol-text">
              <strong>{{ opt.label }}</strong>
              <small>{{ opt.desc }}</small>
            </span>
            <span class="protocol-check" v-if="form.channelType === opt.value">
              <SvgIcon name="check-bold" :size="11" />
            </span>
          </button>
        </div>
      </ConfigField>

      <ConfigField :label="t('cfg.fieldEndpoint')" :hint="t('channel.form.endpointHint')" wide>
        <input id="baseUrl" v-model="form.baseUrl" type="url" class="form-control mono-input"
               :placeholder="t('channel.form.endpointPlaceholder')" />
      </ConfigField>

      <ConfigField :label="t('cfg.fieldStatus')" :hint="t('channel.form.statusLabel')">
        <div class="inline-control">
          <ToggleSwitch v-model="enabledBool" :show-label="false" />
          <span class="inline-hint">
            {{ form.enabled === 1 ? t('common.enabled') : t('common.disabled') }}
          </span>
        </div>
      </ConfigField>

      <ConfigField :label="t('channel.form.modelRefresh')" :hint="t('channel.form.modelRefreshHint')">
        <div class="inline-control">
          <ToggleSwitch v-model="modelRefreshBool" :show-label="false" />
          <span class="inline-hint">
            {{ form.modelRefreshEnabled === 1 ? t('common.enabled') : t('common.disabled') }}
          </span>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ API Keys ══ -->
    <ConfigSection id="cfg-keys" :title="t('cfg.keys')" :desc="t('cfg.sectionKeysDesc')" icon="key">
      <template #actions>
        <span class="count-pill">{{ apiKeys.length }}</span>
        <button type="button" class="btn btn-sm btn-secondary" @click="openApiKeyDialog">
          <SvgIcon name="plus" :size="13" /> {{ t('channel.form.addKey') }}
        </button>
      </template>

      <p class="block-hint">{{ t('channel.form.apiKeysHint') }}</p>

      <div ref="apiKeysListRef" class="key-list">
        <div v-for="(ak, idx) in apiKeys" :key="ak.keyName" class="key-row" :class="{ 'is-off': ak.enabled !== 1 }">
          <span class="drag-handle" :title="t('channel.form.dragSort')">
            <SvgIcon name="list" :size="14" />
          </span>
          <span class="key-identity">
            <code class="key-name">{{ ak.keyName }}</code>
            <code class="key-value">{{ maskKey(ak.apiKey) }}</code>
          </span>
          <span class="key-actions">
            <ToggleSwitch
              :model-value="ak.enabled === 1"
              @update:modelValue="(v: boolean) => ak.enabled = v ? 1 : 0"
              :active-label="t('common.enabled')"
              :inactive-label="t('common.disabled')"
              size="sm"
            />
            <CopyButton :text="ak.apiKey" :title="t('common.copy')" />
            <button type="button" class="icon-btn danger" :title="t('common.delete')" @click="removeApiKey(idx)">
              <SvgIcon name="trash" :size="14" />
            </button>
          </span>
        </div>

        <button v-if="!apiKeys.length" type="button" class="empty-drop" @click="openApiKeyDialog">
          <SvgIcon name="plus" :size="15" />
          <span>{{ t('channel.form.noKeys') }}</span>
        </button>
      </div>
    </ConfigSection>

    <!-- ══ 渠道模型 ══ -->
    <ConfigSection id="cfg-models" :title="t('cfg.models')" :desc="t('cfg.sectionModelsDesc')" icon="model">
      <template #actions>
        <span class="count-pill">{{ activeModelCount }}</span>
        <button type="button" class="btn btn-sm btn-success" :disabled="fetchLoading" @click="doFetchModels">
          <SvgIcon name="refresh" :size="13" />
          {{ fetchLoading ? t('channel.form.fetching') : t('channel.form.fetchModels') }}
        </button>
        <button type="button" class="btn btn-sm btn-secondary" @click="showAddModel = true">
          <SvgIcon name="plus" :size="13" /> {{ t('channel.form.manualAdd') }}
        </button>
        <button v-if="activeModelCount" type="button" class="btn btn-sm btn-danger" @click="clearAllModels">
          <SvgIcon name="trash" :size="13" /> {{ t('channel.form.clearAll') }}
        </button>
      </template>

      <div class="model-cloud">
        <template v-if="models.length">
          <span
            v-for="(m, idx) in models"
            :key="idx"
            class="chip-model"
            :class="{ 'is-deleted': m._deleted }"
            @click="toggleModel(idx)"
            :title="m._deleted ? t('channel.form.clickRestore') : t('channel.form.clickRemove')"
          >
            <span class="chip-text">{{ m.displayName || m.modelName }}</span>
            <span class="chip-x" @click.stop="removeModel(idx)">
              <SvgIcon name="x" :size="10" />
            </span>
          </span>
        </template>
        <div v-else class="model-empty">
          <SvgIcon name="model" :size="18" />
          <span>{{ t('channel.list.noModels') }}</span>
        </div>
      </div>
      <p class="block-hint">{{ t('channel.form.modelHint') }}</p>
    </ConfigSection>

    <!-- ══ 高级选项 ══ -->
    <ConfigSection id="cfg-advanced" :title="t('cfg.advanced')" :desc="t('cfg.sectionAdvancedDesc')" icon="settings">
      <template #actions>
        <button type="button" class="btn btn-sm btn-secondary" @click="addHeader">
          <SvgIcon name="plus" :size="13" /> {{ t('channel.form.addHeader') }}
        </button>
      </template>

      <div class="cfg-block">
        <div class="cfg-block-head">
          <span class="cfg-block-title">{{ t('channel.form.customHeaders') }}</span>
        </div>
        <p class="block-hint">{{ t('channel.form.customHeadersHint') }}</p>

        <div v-if="customHeaders.length" class="header-rows">
          <div v-for="(h, idx) in customHeaders" :key="idx" class="header-row">
            <input v-model="h.key" class="form-control" :placeholder="t('channel.form.headerNamePlaceholder')" />
            <span class="header-eq">=</span>
            <input v-model="h.value" class="form-control" :placeholder="t('channel.form.headerValuePlaceholder')" />
            <button type="button" class="icon-btn danger" :title="t('common.delete')" @click="removeHeader(idx)">
              <SvgIcon name="trash" :size="14" />
            </button>
          </div>
        </div>
        <div v-else class="model-empty header-empty">{{ t('channel.form.noHeaders') }}</div>
      </div>
    </ConfigSection>

    <!-- ══ Sticky footer ══ -->
    <template #footer>
      <span class="footer-hint">
        <SvgIcon name="info" :size="13" /> {{ t('cfg.stickyFooterHint') }}
      </span>
      <div class="footer-actions">
        <router-link to="/admin/channel/list" class="btn btn-secondary">
          {{ t('common.cancel') }}
        </router-link>
        <button type="button" class="btn btn-primary" :disabled="saving" @click="handleSave">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </template>
  </ConfigShell>

  <!-- Add Model Dialog -->
  <Dialog
    v-model="showAddModel"
    :title="t('channel.form.addModelTitle')"
    type="confirm"
    :confirm-text="t('channel.form.confirmAddModel')"
    width="440px"
    @confirm="confirmAddModel"
  >
    <div class="form-group">
      <label>{{ t('channel.form.modelName') }}</label>
      <input v-model="newModelName" class="form-control" :placeholder="t('channel.form.modelNamePlaceholder')"
             @keydown.enter.prevent="confirmAddModel" />
    </div>
    <div class="form-group" style="margin-bottom:0;">
      <label>{{ t('channel.form.displayName') }}</label>
      <input v-model="newDisplayName" class="form-control" :placeholder="t('channel.form.displayNamePlaceholder')"
             @keydown.enter.prevent="confirmAddModel" />
    </div>
  </Dialog>

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

  <!-- API Key Input Dialog -->
  <Dialog
    v-model="apiKeyDialogVisible"
    :title="t('channel.form.addKey')"
    type="confirm"
    :confirm-text="t('dialog.add')"
    width="480px"
    @confirm="confirmAddApiKey"
    @cancel="closeApiKeyDialog"
  >
    <div class="form-group">
      <label>{{ t('channel.form.keyNameLabel') }}</label>
      <input v-model="newApiKeyName" class="form-control" :placeholder="t('channel.form.keyNamePlaceholder')" @keydown.enter.prevent="confirmAddApiKey" />
    </div>
    <div class="form-group" style="margin-bottom:0;">
      <label>{{ t('channel.form.keyValueLabel') }}</label>
      <input ref="apiKeyInputRef" v-model="newApiKeyValue" class="form-control" :placeholder="t('channel.form.keyValuePlaceholder')" @keydown.enter.prevent="confirmAddApiKey" />
    </div>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { channelApi, type Channel, type ChannelApiKey } from '@/api/channel'
import Dialog from '@/components/common/Dialog.vue'
import CopyButton from '@/components/common/CopyButton.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import ConfigShell from '@/components/common/ConfigShell.vue'
import ConfigSection from '@/components/common/ConfigSection.vue'
import ConfigField from '@/components/common/ConfigField.vue'
import Sortable from 'sortablejs'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()
const isEdit = computed(() => !!route.params.id)

const saving = ref(false)
// 编辑态初始即加载中（数据到达前显示骨架），新建态直接渲染表单
const loading = ref(isEdit.value)
const fetchLoading = ref(false)
const showAddModel = ref(false)
const newModelName = ref('')
const newDisplayName = ref('')
const apiKeysListRef = ref<HTMLElement>()
let sortableInstance: Sortable | null = null

/* ---------- 分区锚点 ---------- */
const sections = computed(() => [
  { id: 'cfg-basic', label: t('cfg.basic'), icon: 'channel' },
  { id: 'cfg-keys', label: t('cfg.keys'), icon: 'key' },
  { id: 'cfg-models', label: t('cfg.models'), icon: 'model' },
  { id: 'cfg-advanced', label: t('cfg.advanced'), icon: 'settings' },
])

/* ---------- 协议卡片 ---------- */
const protocolOptions = computed(() => [
  { value: 'openai', label: t('channel.form.typeOpenAI'), desc: 'chat/completions', icon: 'openai' },
  { value: 'anthropic', label: t('channel.form.typeAnthropic'), desc: 'v1/messages', icon: 'anthropic' },
  { value: 'responses', label: t('channel.form.typeResponses'), desc: 'v1/responses', icon: 'zap' },
])

// 开关型字段的布尔代理
const enabledBool = computed({
  get: () => form.value.enabled === 1,
  set: (v: boolean) => { form.value.enabled = v ? 1 : 0 },
})
const modelRefreshBool = computed({
  get: () => form.value.modelRefreshEnabled === 1,
  set: (v: boolean) => { form.value.modelRefreshEnabled = v ? 1 : 0 },
})

/* ---------- API Key Dialog state ---------- */
const apiKeyDialogVisible = ref(false)
const newApiKeyName = ref('')
const newApiKeyValue = ref('')
const apiKeyInputRef = ref<HTMLInputElement>()

watch(apiKeyDialogVisible, (visible) => {
  if (visible) {
    nextTick(() => apiKeyInputRef.value?.focus())
  }
})

function openApiKeyDialog() {
  const count = apiKeys.value.length
  newApiKeyName.value = count === 0 ? 'master' : `slave${count}`
  newApiKeyValue.value = ''
  apiKeyDialogVisible.value = true
}

function closeApiKeyDialog() {
  apiKeyDialogVisible.value = false
}

function confirmAddApiKey() {
  const keyName = newApiKeyName.value.trim()
  const apiKey = newApiKeyValue.value.trim()
  if (!keyName) {
    open({ title: t('common.prompt'), message: t('channel.form.inputKeyName') })
    return
  }
  if (apiKeys.value.some(k => k.keyName === keyName)) {
    open({ title: t('common.prompt'), message: t('channel.form.keyNameExists') })
    return
  }
  if (!apiKey) {
    // 空 API Key 用于免费模型，每个渠道仅允许一个
    if (apiKeys.value.some(k => !k.apiKey || !k.apiKey.trim())) {
      open({ title: t('common.prompt'), message: t('channel.form.emptyKeyExists') })
      return
    }
  } else if (apiKeys.value.some(k => k.apiKey && k.apiKey.trim() === apiKey)) {
    open({ title: t('common.prompt'), message: t('channel.form.keyValueExists') })
    return
  }
  apiKeys.value.push({
    keyName: keyName,
    apiKey: apiKey,
    enabled: 1,
    sortOrder: apiKeys.value.length
  })
  apiKeyDialogVisible.value = false
}
/* ------------------------------ */

function initSortable() {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
  const el = apiKeysListRef.value
  if (!el) return
  sortableInstance = new Sortable(el, {
    handle: '.drag-handle',
    animation: 100,
    easing: 'cubic-bezier(0.25, 0.46, 0.45, 0.94)',
    ghostClass: 'sortable-ghost',
    dragClass: 'sortable-drag',
    forceFallback: true,
    fallbackClass: 'sortable-fallback',
    fallbackOnBody: true,
    fallbackTolerance: 3,
    delay: 0,
    delayOnTouchOnly: true,
    onEnd: (evt: { oldIndex?: number; newIndex?: number }) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined || evt.oldIndex === evt.newIndex) return
      const newKeys = [...apiKeys.value]
      const [moved] = newKeys.splice(evt.oldIndex, 1)
      newKeys.splice(evt.newIndex, 0, moved)
      newKeys.forEach((k, i) => k.sortOrder = i)
      _skipSortableReinit = true
      apiKeys.value = newKeys
    }
  })
}

interface ModelItem {
  id?: number
  modelName: string
  displayName?: string
  _deleted?: boolean
}

const form = ref<Partial<Channel>>({
  name: '',
  channelType: 'openai',
  baseUrl: '',
  enabled: 1,
  modelRefreshEnabled: 1
})
const apiKeys = ref<ChannelApiKey[]>([])
const models = ref<ModelItem[]>([])
const customHeaders = ref<{ key: string; value: string }[]>([])

const activeModelCount = computed(() => models.value.filter(m => !m._deleted).length)

// 需要 deep 监听 apiKeys：新增/删除走 push/splice（引用不变），非 deep 无法触发重建。
// 拖拽 onEnd 触发的数组更新用标记位跳过重建（onEnd 本身已是本地 DOM 操作）。
let _skipSortableReinit = false
watch(apiKeys, () => {
  if (_skipSortableReinit) { _skipSortableReinit = false; return }
  nextTick(() => initSortable())
}, { deep: true })

onMounted(async () => {
  if (isEdit.value) {
    const id = Number(route.params.id)
    try {
      const res = await channelApi.get(id)
      const data = res.data
      form.value = { ...data.channel }
      // 旧渠道无刷新模式字段，回显默认自动刷新
      if (form.value.modelRefreshEnabled == null) form.value.modelRefreshEnabled = 1
      apiKeys.value = (data.apiKeys || []).map(k => ({ ...k }))
      models.value = (data.channelModels || []).map(m => ({
        id: m.id,
        modelName: m.modelName,
        displayName: m.displayName || m.modelName
      }))
      // 解析自定义请求头 JSON
      if (data.channel.customHeaders) {
        try {
          const parsed = JSON.parse(data.channel.customHeaders)
          customHeaders.value = Object.entries(parsed).map(([key, value]) => ({ key, value: String(value) }))
        } catch { console.warn('Failed to parse channel customHeaders JSON') }
      }
    } catch (e: any) {
      open({ title: t('error.loadFailed'), message: e.message })
      router.push('/admin/channel/list')
    } finally {
      loading.value = false
    }
  }
})

function maskKey(key: string) {
  if (!key) return ''
  if (key.length <= 10) return '•'.repeat(key.length)
  return key.substring(0, 6) + '••••' + key.substring(key.length - 4)
}

function removeApiKey(index: number) {
  open({
    title: t('common.confirmDelete'),
    message: t('apikey.list.deleteConfirm').replace('{name}', apiKeys.value[index]?.keyName || ''),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      apiKeys.value.splice(index, 1)
    }
  })
}

function toggleModel(index: number) {
  const m = models.value[index]
  if (!m) return
  if (m._deleted) {
    delete m._deleted
  } else if (isEdit.value && m.id) {
    m._deleted = true
  } else {
    models.value.splice(index, 1)
  }
}

function removeModel(index: number) {
  const m = models.value[index]
  if (!m) return
  if (isEdit.value && m.id) {
    m._deleted = true
  } else {
    models.value.splice(index, 1)
  }
}

function confirmAddModel() {
  const name = newModelName.value.trim()
  if (!name) { open({ title: t('common.prompt'), message: t('channel.form.inputModelName') }); return }
  if (models.value.some(m => m.modelName === name)) { open({ title: t('common.prompt'), message: t('channel.form.modelExists') }); return }
  models.value.push({
    modelName: name,
    displayName: newDisplayName.value.trim() || name
  })
  newModelName.value = ''
  newDisplayName.value = ''
  showAddModel.value = false
}

async function doFetchModels() {
  if (isEdit.value) {
    // Refresh models from API
    const id = Number(route.params.id)
    fetchLoading.value = true
    try {
      const res = await channelApi.reloadModels(id)
      if (res.data.success) {
        const existing = new Set(models.value.map(m => m.modelName))
        let added = 0
        for (const m of res.data.data || []) {
          if (!existing.has(m.modelName)) {
            models.value.push({ id: m.id, modelName: m.modelName, displayName: m.displayName || m.modelName })
            added++
          }
        }
        open({ message: t('channel.form.fetchedModels').replace('{count}', String(added)) })
      } else {
        open({ title: t('channel.form.refreshFailed'), message: res.data.error || t('channel.form.refreshFailed') })
      }
    } catch (e: any) {
      open({ title: t('channel.form.refreshFailed'), message: e.message })
    } finally {
      fetchLoading.value = false
    }
  } else {
    // Preview fetch
    if (!form.value.channelType) { open({ title: t('common.prompt'), message: t('channel.form.selectTypeFirst') }); return }
    if (!apiKeys.value.length) { open({ title: t('common.prompt'), message: t('channel.form.addAtLeastOneKey') }); return }
    fetchLoading.value = true
    try {
      const res = await channelApi.fetchModels(
        form.value.baseUrl || '',
        apiKeys.value[0].apiKey,
        form.value.channelType
      )
      if (res.data.success) {
        const existing = new Set(models.value.map(m => m.modelName))
        let added = 0
        for (const m of res.data.data || []) {
          if (!existing.has(m.modelName)) {
            models.value.push({ modelName: m.modelName, displayName: m.displayName || m.modelName })
            added++
          }
        }
        open({ message: t('channel.form.addedModels').replace('{count}', res.data.count).replace('{added}', String(added)) })
      } else {
        open({ title: t('channel.form.refreshFailed'), message: res.data.error || t('channel.form.refreshFailed') })
      }
    } catch (e: any) {
      open({ title: t('common.fail'), message: e.message })
    } finally {
      fetchLoading.value = false
    }
  }
}

function clearAllModels() {
  if (!models.value.length) return
  open({
    title: t('common.confirmDelete'),
    message: t('channel.form.clearModelsConfirm').replace('{count}', String(models.value.length)),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      models.value = []
    }
  })
}

function addHeader() {
  customHeaders.value.push({ key: '', value: '' })
}

function removeHeader(index: number) {
  customHeaders.value.splice(index, 1)
}

async function handleSave() {
  saving.value = true
  try {
    // Filter out deleted models for submission
    const activeModels = models.value.filter(m => !m._deleted)
    const payload: any = {
      ...form.value,
      baseUrl: (form.value.baseUrl || '').trim(),
      manualModels: JSON.stringify(activeModels.map(m => ({
        id: m.id,
        modelName: m.modelName,
        displayName: m.displayName
      }))),
      apiKeysJson: JSON.stringify(apiKeys.value.map(k => ({
        id: k.id,
        keyName: k.keyName,
        apiKey: k.apiKey,
        enabled: k.enabled,
        sortOrder: k.sortOrder
      }))),
      customHeaders: JSON.stringify(Object.fromEntries(
        customHeaders.value.filter(h => h.key.trim()).map(h => [h.key.trim(), h.value])
      ))
    }

    if (isEdit.value) {
      const id = Number(route.params.id)
      await channelApi.update(id, payload)
      open({ title: t('common.success'), message: t('channel.form.saveSuccess'), onConfirm: () => router.push('/admin/channel/list') })
    } else {
      await channelApi.create(payload)
      open({ title: t('common.success'), message: t('channel.form.createSuccess'), onConfirm: () => router.push('/admin/channel/list') })
    }
  } catch (e: any) {
    open({ title: t('channel.form.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* ── Page header ── */
.cfg-page-header {
  align-items: flex-start;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.back-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  transition: all var(--dur-1);
  flex-shrink: 0;
}

.back-link:hover {
  color: var(--text-primary);
  border-color: var(--border-strong);
  background: var(--bg-hover);
}

.summary-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 11px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.chip-sep {
  width: 1px;
  height: 12px;
  background: var(--border-color);
}

.mono-input {
  font-family: var(--font-mono);
  font-size: 12.5px;
}

/* ── Protocol cards ── */
.protocol-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 10px;
  max-width: 720px;
}

.protocol-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 13px;
  border-radius: var(--radius);
  border: 1px solid var(--border-color);
  background: var(--bg-sunken);
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition: border-color var(--dur-1), background var(--dur-1), box-shadow var(--dur-1);
}

.protocol-card:hover {
  border-color: var(--border-strong);
  background: var(--bg-secondary);
}

.protocol-card.active {
  border-color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 6%, var(--bg-secondary));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 10%, transparent);
}

.protocol-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  flex-shrink: 0;
}

.protocol-card.active .protocol-icon {
  background: color-mix(in srgb, var(--accent-blue) 14%, transparent);
  color: var(--accent-blue);
}

.protocol-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.protocol-text strong {
  font-size: 13px;
  font-weight: 620;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.protocol-text small {
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}

.protocol-check {
  position: absolute;
  top: 8px;
  right: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: var(--accent-blue);
  color: #fff;
}

/* ── Inline control ── */
.inline-control {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
}

.inline-hint {
  font-size: 12.5px;
  color: var(--text-muted);
}

/* ── Section-level bits ── */
.count-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 20px;
  padding: 0 7px;
  border-radius: var(--radius-full);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  font-size: 11.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  margin-right: 2px;
}

.block-hint {
  font-size: 12.5px;
  color: var(--text-muted);
  line-height: 1.55;
  margin-bottom: 12px;
}

/* ── API key list ── */
.key-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius);
  background: var(--bg-sunken);
  transition: border-color var(--dur-1), background var(--dur-1);
}

.key-row:hover {
  border-color: var(--border-strong);
  background: var(--bg-secondary);
}

.key-row.is-off {
  opacity: 0.62;
}

.drag-handle {
  cursor: grab;
  color: var(--text-muted);
  user-select: none;
  display: inline-flex;
  align-items: center;
  touch-action: none;
  flex-shrink: 0;
}

.drag-handle:active {
  cursor: grabbing;
}

.key-identity {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  flex-wrap: wrap;
}

.key-name {
  font-size: 12.5px;
  font-weight: 620;
  color: var(--text-primary);
  font-family: inherit;
  white-space: nowrap;
}

.key-value {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-muted);
  padding: 1px 7px;
  border-radius: var(--radius-xs);
  background: var(--bg-tertiary);
  cursor: pointer;
  user-select: all;
  letter-spacing: 0.02em;
}

.key-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: background var(--dur-1), color var(--dur-1);
}

.icon-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.icon-btn.danger:hover {
  background: color-mix(in srgb, var(--accent-red) 12%, transparent);
  color: var(--accent-red);
}

.empty-drop {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius);
  background: transparent;
  color: var(--text-muted);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all var(--dur-1);
}

.empty-drop:hover {
  border-color: var(--accent-blue);
  color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 4%, transparent);
}

/* ── Model cloud ── */
.model-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 13px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius);
  background: var(--bg-sunken);
  min-height: 52px;
  max-height: 188px;
  overflow-y: auto;
}

.model-empty {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 2px;
  color: var(--text-muted);
  font-size: 12.5px;
}

.chip-model {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 5px 3px 10px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  font-size: 11.5px;
  font-family: var(--font-mono);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--dur-1);
  max-width: 100%;
}

.chip-model:hover {
  border-color: var(--border-strong);
  color: var(--text-primary);
  background: var(--bg-hover);
}

.chip-model.is-deleted {
  opacity: 0.5;
  text-decoration: line-through;
  border-color: color-mix(in srgb, var(--accent-red) 35%, var(--border-color));
}

.chip-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chip-x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  color: var(--text-muted);
  flex-shrink: 0;
}

.chip-x:hover {
  background: color-mix(in srgb, var(--accent-red) 16%, transparent);
  color: var(--accent-red);
}

/* ── Custom headers ── */
.header-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.header-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1.4fr) auto;
  align-items: center;
  gap: 8px;
}

.header-row .form-control {
  font-family: var(--font-mono);
  font-size: 12px;
}

.header-eq {
  color: var(--text-muted);
  font-size: 12px;
  font-family: var(--font-mono);
}

.header-empty {
  padding: 8px 2px;
}

/* ── Sortable ── */
.sortable-ghost {
  opacity: 0.4;
  background: color-mix(in srgb, var(--accent-blue) 10%, transparent) !important;
  border-style: dashed !important;
}

.sortable-drag {
  opacity: 0.9;
  box-shadow: var(--shadow-lg);
}

@media (max-width: 768px) {
  .key-value {
    display: none;
  }
  .key-identity {
    min-width: 0;
    overflow: hidden;
  }
  .key-name {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .protocol-grid {
    grid-template-columns: 1fr;
  }
  .header-row {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'key key'
      'value value'
      'eq-del eq-del';
  }
  .header-row .form-control:first-child { grid-area: key; }
  .header-row .form-control:nth-child(3) { grid-area: value; }
  .header-eq { display: none; }
}

</style>
