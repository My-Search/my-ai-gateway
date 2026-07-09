<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? t('channel.form.editTitle') : t('channel.form.addTitle') }}</div>
      <router-link to="/admin/channel/list" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>

    <form @submit.prevent="handleSave" style="max-width:600px;">
      <input type="hidden" v-model="form.id" />

      <div class="form-group">
        <label for="name">{{ t('channel.form.nameLabel') }}</label>
        <input id="name" v-model="form.name" type="text" class="form-control" :placeholder="t('channel.form.namePlaceholder')" required />
        <div class="form-hint">{{ t('channel.form.nameHint') }}</div>
      </div>

      <div class="form-group">
        <label for="channelType">{{ t('channel.form.typeLabel') }}</label>
        <select id="channelType" v-model="form.channelType" class="form-control" required>
          <option value="">{{ t('channel.form.typePlaceholder') }}</option>
          <option value="openai">{{ t('channel.form.typeOpenAI') }}</option>
          <option value="anthropic">Anthropic</option>
        </select>
        <div class="form-hint">{{ t('channel.form.typeHint') }}</div>
      </div>

      <div class="form-group">
        <label for="baseUrl">{{ t('channel.form.endpointLabel') }}</label>
        <input id="baseUrl" v-model="form.baseUrl" type="url" class="form-control" :placeholder="t('channel.form.endpointPlaceholder')" />
        <div class="form-hint">{{ t('channel.form.endpointHint') }}</div>
      </div>

      <div class="form-group">
        <label>{{ t('channel.form.apiKeys') }}</label>
        <div class="form-hint">{{ t('channel.form.apiKeysHint') }}</div>
        <div class="api-keys-list">
          <div v-for="(ak, idx) in apiKeys" :key="idx" class="api-key-item">
            <span class="api-key-left"><strong>{{ ak.keyName }}</strong><code class="api-key-masked">{{ maskKey(ak.apiKey) }}</code></span>
            <span class="api-key-actions">
              <CopyButton :text="ak.apiKey" :title="t('common.copy')" />
              <button type="button" class="btn btn-sm btn-danger" @click="removeApiKey(idx)"><SvgIcon name="trash" :size="14" /> {{ t('common.delete') }}</button>
            </span>
          </div>
          <div v-if="!apiKeys.length" style="color:var(--text-muted);font-size:13px;padding:8px 0;">
            {{ t('channel.form.noKeys') }}
          </div>
        </div>
        <button type="button" class="btn btn-sm btn-primary" style="margin-top:8px;" @click="openApiKeyDialog">
          <SvgIcon name="plus" :size="14" /> {{ t('channel.form.addKey') }}
        </button>
      </div>

      <div class="form-group">
        <label for="enabled">{{ t('channel.form.statusLabel') }}</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">{{ t('common.enabled') }}</option>
          <option :value="0">{{ t('common.disabled') }}</option>
        </select>
      </div>

      <!-- Channel Models -->
      <div class="form-group">
        <label>{{ t('channel.form.models') }}</label>
        <div class="model-toolbar">
          <button type="button" class="btn btn-success btn-sm" :disabled="fetchLoading" @click="doFetchModels">
            <SvgIcon name="refresh" :size="14" /> {{ fetchLoading ? t('channel.form.fetching') : t('channel.form.fetchModels') }}
          </button>
          <button type="button" class="btn btn-primary btn-sm" @click="showAddModel = true">
            <SvgIcon name="plus" :size="14" /> {{ t('channel.form.manualAdd') }}
          </button>
          <div class="toolbar-right">
            <span class="model-stats">{{ t('channel.form.modelCount', { count: models.length }) }}</span>
            <button v-if="models.length" type="button" class="btn btn-danger btn-sm" @click="clearAllModels"><SvgIcon name="trash" :size="14" /> {{ t('channel.form.clearAll') }}</button>
          </div>
        </div>
        <div class="model-tags-container">
          <template v-if="models.length">
            <span v-for="(m, idx) in models" :key="idx"
                  class="model-tag" :class="{ '_deleted': m._deleted }"
                  @click="toggleModel(idx)" :title="m._deleted ? t('channel.form.clickRestore') : t('channel.form.clickRemove')">
              {{ m.displayName || m.modelName }}
              <span class="tag-remove" @click.stop="removeModel(idx)">
                <SvgIcon name="x" :size="12" />
              </span>
            </span>
          </template>
          <span v-else class="empty-hint">{{ t('channel.list.noModels') }}</span>
        </div>
        <div class="form-hint">{{ t('channel.form.modelHint') }}</div>
      </div>

      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
        </button>
        <router-link to="/admin/channel/list" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> {{ t('common.cancel') }}</router-link>
      </div>
    </form>

    <!-- Add Model Dialog -->
    <Dialog
      v-model="showAddModel"
      :title="t('channel.form.addModelTitle')"
      type="confirm"
      confirm-text="确认添加"
      width="400px"
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
import { channelApi, type Channel, type ChannelApiKey, type ChannelModel } from '@/api/channel'
import Dialog from '@/components/common/Dialog.vue'
import CopyButton from '@/components/common/CopyButton.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()
const isEdit = computed(() => !!route.params.id)

const saving = ref(false)
const fetchLoading = ref(false)
const showAddModel = ref(false)
const newModelName = ref('')
const newDisplayName = ref('')

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
  if (!apiKey) {
    open({ title: t('common.prompt'), message: t('channel.form.inputKeyValue') })
    return
  }
  if (apiKeys.value.some(k => k.keyName === keyName)) {
    open({ title: t('common.prompt'), message: t('channel.form.keyNameExists') })
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
  enabled: 1
})
const apiKeys = ref<ChannelApiKey[]>([])
const models = ref<ModelItem[]>([])

onMounted(async () => {
  if (isEdit.value) {
    const id = Number(route.params.id)
    try {
      const res = await channelApi.get(id)
      const data = res.data
      form.value = { ...data.channel }
      apiKeys.value = (data.apiKeys || []).map(k => ({ ...k }))
      models.value = (data.channelModels || []).map(m => ({
        id: m.id,
        modelName: m.modelName,
        displayName: m.displayName || m.modelName
      }))
    } catch (e: any) {
      open({ title: t('error.loadFailed'), message: e.message })
      router.push('/admin/channel/list')
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
      })))
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
.api-keys-list {
  display: flex; flex-direction: column; gap: 8px; margin-top: 12px;
}
.api-key-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border: 1px solid var(--border-color); border-radius: 6px;
}
.api-key-left {
  flex: 1; display: inline-flex; align-items: center; gap: 8px;
  font-size: 13px;
}
.api-key-actions {
  display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0;
}
.api-key-masked {
  font-size: 13px; font-family: var(--font-mono, monospace);
  user-select: all; cursor: pointer;
  padding: 2px 8px;
  border-radius: 4px; letter-spacing: 0.5px;
}
.copy-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; cursor: pointer; color: var(--text-muted);
  border: 1px solid var(--border-color);
  border-radius: 4px; padding: 0; vertical-align: middle;
}
.copy-btn:hover { color: var(--accent-blue); border-color: var(--accent-blue); }
.model-toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px;
}
.toolbar-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.model-stats { font-size: 12px; color: var(--text-muted); }
.model-tags-container {
  display: flex; flex-wrap: wrap; gap: 4px; margin-top: 10px; padding: 6px 8px;
  border: 1px solid var(--border-color); border-radius: 8px; min-height: 28px;
  max-height: 140px; overflow-y: auto;
}
.model-tags-container .empty-hint {
  color: var(--text-muted); font-size: 13px;
}
.model-tag {
  display: inline-flex; align-items: center; gap: 0;
  padding: 2px 8px;
  border: 1px solid var(--border-color); border-radius: 12px;
  font-size: 11px; color: var(--text-primary); line-height: 1.5; cursor: pointer;
  transition: all 0.15s;
}
.model-tag:hover { background: var(--bg-hover); }
.model-tag._deleted { opacity: 0.5; text-decoration: line-through; background: rgba(248,81,73,0.1); }
.model-tag .tag-remove { display: none; cursor: pointer; opacity: 0.6; margin-left: 4px; }
.model-tag:hover .tag-remove { display: inline-flex; align-items: center; }
.model-tag .tag-remove:hover { opacity: 1; color: var(--accent-red); }
</style>
