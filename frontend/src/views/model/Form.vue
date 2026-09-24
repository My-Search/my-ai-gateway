<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? t('model.form.editTitle') : t('model.form.addTitle') }}</div>
      <router-link to="/admin/model/list" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-group">
        <label for="modelName">{{ t('model.form.modelName') }}</label>
        <input id="modelName" v-model="form.modelName" class="form-control" :placeholder="t('model.form.modelNamePlaceholder')" required />
        <div class="form-hint">{{ t('model.form.modelNameHint') }}</div>
      </div>
      <div class="form-group">
        <label for="description">{{ t('model.form.description') }}</label>
        <textarea id="description" v-model="form.description" class="form-control" :placeholder="t('model.form.descriptionPlaceholder')"></textarea>
      </div>
      <div class="form-group">
        <label for="strategy">{{ t('model.form.strategy') }} <span class="question-icon" @click="showStrategyInfo = true">?</span></label>
        <select id="strategy" v-model="form.strategy" class="form-control">
          <option value="">{{ t('model.form.strategyFailover') }}</option>
          <option value="random">{{ t('model.form.strategyRandom') }}</option>
          <option value="round_robin">{{ t('model.form.strategyRoundRobin') }}</option>
        </select>
        <div class="form-hint">{{ t('model.form.strategyHint') }}</div>
      </div>
      <div class="form-group">
        <label for="enabled">{{ t('model.form.status') }}</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">{{ t('common.enabled') }}</option>
          <option :value="0">{{ t('common.disabled') }}</option>
        </select>
      </div>
      <div class="form-group">
        <div class="toggle-label-row">
          <ToggleSwitch
            v-model="hiddenEnabled"
            :show-label="false"
            :title="t('model.form.hidden')"
          />
          <label>{{ t('model.form.hidden') }}</label>
        </div>
        <div class="form-hint">{{ t('model.form.hiddenHint') }}</div>
      </div>

      <div v-if="isEdit" class="form-group">
        <div class="toggle-label-row">
          <ToggleSwitch
            v-model="forceOverrideEnabled"
            :show-label="false"
            :title="t('model.advanced.forceOverrideReasoningEffort')"
          />
          <label>{{ t('model.advanced.forceOverrideReasoningEffort') }}</label>
        </div>
        <div class="form-hint">{{ t('model.advanced.forceOverrideReasoningEffortHint') }}</div>
      </div>

      <div v-if="isEdit" class="advanced-section">
        <button type="button" class="advanced-toggle" @click="showAdvanced = !showAdvanced">
          <SvgIcon name="settings" :size="14" />
          <span>{{ t('model.form.advancedSettings') }}</span>
          <SvgIcon :name="showAdvanced ? 'chevron-up' : 'chevron-down'" :size="14" class="toggle-icon" :class="{ '_open': showAdvanced }" />
        </button>
        <div v-show="showAdvanced" class="advanced-body">
          <div class="form-section">
            <div class="section-title">{{ t('model.advanced.mediaInvalidate') }}</div>
            <div class="section-desc">{{ t('model.advanced.mediaInvalidateHint') }}</div>

            <div class="form-row">
              <div class="form-group flex-grow">
                <label>{{ t('model.advanced.image') }}</label>
                <div class="switch-group">
                  <ToggleSwitch
                    v-model="imageEnabled"
                    :active-label="t('common.on')"
                    :inactive-label="t('common.off')"
                    @update:model-value="onImageToggle"
                  />
                </div>
              </div>
              <div class="form-group invalidate-count">
                <label>{{ t('model.advanced.invalidateCount') }}</label>
                <input v-model.number="form.imageInvalidateCount" type="number" class="form-control" min="0" max="99"
                       :disabled="!imageEnabled" />
              </div>
            </div>

            <div class="form-row">
              <div class="form-group flex-grow">
                <label>{{ t('model.advanced.video') }}</label>
                <div class="switch-group">
                  <ToggleSwitch
                    v-model="videoEnabled"
                    :active-label="t('common.on')"
                    :inactive-label="t('common.off')"
                    @update:model-value="onVideoToggle"
                  />
                </div>
              </div>
              <div class="form-group invalidate-count">
                <label>{{ t('model.advanced.invalidateCount') }}</label>
                <input v-model.number="form.videoInvalidateCount" type="number" class="form-control" min="0" max="99"
                       :disabled="!videoEnabled" />
              </div>
            </div>

            <div class="form-row">
              <div class="form-group flex-grow">
                <label>{{ t('model.advanced.audio') }}</label>
                <div class="switch-group">
                  <ToggleSwitch
                    v-model="audioEnabled"
                    :active-label="t('common.on')"
                    :inactive-label="t('common.off')"
                    @update:model-value="onAudioToggle"
                  />
                </div>
              </div>
              <div class="form-group invalidate-count">
                <label>{{ t('model.advanced.invalidateCount') }}</label>
                <input v-model.number="form.audioInvalidateCount" type="number" class="form-control" min="0" max="99"
                       :disabled="!audioEnabled" />
              </div>
            </div>
          </div>

          <div v-if="isEdit" class="form-section">
            <div class="section-title">{{ t('model.advanced.promptInjection') }}</div>
            <div class="section-desc">{{ t('model.advanced.promptInjectionHint') }}</div>
            <router-link :to="`/admin/model/prompt-injections/${route.params.id}`" class="btn btn-secondary">
              <SvgIcon name="code" :size="14" /> {{ t('model.advanced.managePromptInjection') }}
            </router-link>
          </div>
        </div>
      </div>

      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving"><SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('model.form.save') }}</button>
        <router-link to="/admin/model/list" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> {{ t('model.form.cancel') }}</router-link>
      </div>
    </form>
  </div>

  <!-- Strategy Info Dialog -->
  <Dialog
    v-model="showStrategyInfo"
    :title="t('model.form.strategy')"
    type="alert"
  >
    <div style="white-space:pre-line;line-height:1.7;">{{ t('model.form.strategyExplain') }}</div>
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
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { modelApi, type CustomModel } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const showAdvanced = ref(false)
const showStrategyInfo = ref(false)
const form = ref<Partial<CustomModel>>({
  modelName: '',
  description: '',
  strategy: '',
  enabled: 1,
  hidden: 0,
  imageInvalidateCount: 0,
  videoInvalidateCount: 0,
  audioInvalidateCount: 0,
  forceOverrideReasoningEffort: 0
})
const imageEnabled = computed({
  get: () => (form.value.imageInvalidateCount ?? 0) > 0,
  set: (value: boolean) => { form.value.imageInvalidateCount = value ? 1 : 0 }
})
const videoEnabled = computed({
  get: () => (form.value.videoInvalidateCount ?? 0) > 0,
  set: (value: boolean) => { form.value.videoInvalidateCount = value ? 1 : 0 }
})
const audioEnabled = computed({
  get: () => (form.value.audioInvalidateCount ?? 0) > 0,
  set: (value: boolean) => { form.value.audioInvalidateCount = value ? 1 : 0 }
})
const hiddenEnabled = computed({
  get: () => form.value.hidden === 1,
  set: (value: boolean) => { form.value.hidden = value ? 1 : 0 }
})
const forceOverrideEnabled = computed({
  get: () => (form.value.forceOverrideReasoningEffort ?? 0) === 1,
  set: (value: boolean) => { form.value.forceOverrideReasoningEffort = value ? 1 : 0 }
})

onMounted(async () => {
  if (isEdit.value) {
    try {
      const res = await modelApi.get(Number(route.params.id))
      form.value = {
        ...res.data,
        imageInvalidateCount: res.data.imageInvalidateCount ?? 0,
        videoInvalidateCount: res.data.videoInvalidateCount ?? 0,
        audioInvalidateCount: res.data.audioInvalidateCount ?? 0,
        forceOverrideReasoningEffort: res.data.forceOverrideReasoningEffort ?? 0
      }
    } catch (e: any) {
      open({ title: t('model.form.loadFailed'), message: e.message })
      router.push('/admin/model/list')
    }
  }
})

function onImageToggle() {
  if (!imageEnabled.value) {
    form.value.imageInvalidateCount = 0
  } else if (form.value.imageInvalidateCount === 0) {
    form.value.imageInvalidateCount = 1
  }
}

function onVideoToggle() {
  if (!videoEnabled.value) {
    form.value.videoInvalidateCount = 0
  } else if (form.value.videoInvalidateCount === 0) {
    form.value.videoInvalidateCount = 1
  }
}

function onAudioToggle() {
  if (!audioEnabled.value) {
    form.value.audioInvalidateCount = 0
  } else if (form.value.audioInvalidateCount === 0) {
    form.value.audioInvalidateCount = 1
  }
}

async function handleSave() {
  saving.value = true
  try {
    if (isEdit.value) {
      await modelApi.update(Number(route.params.id), form.value)
      open({ title: t('common.success'), message: t('model.form.updateSuccess'), onConfirm: () => router.push('/admin/model/list') })
    } else {
      await modelApi.create(form.value)
      open({ title: t('common.success'), message: t('model.form.createSuccess'), onConfirm: () => router.push('/admin/model/list') })
    }
  } catch (e: any) {
    open({ title: t('model.form.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.toggle-label-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.toggle-label-row label {
  cursor: pointer;
}
.question-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 1px solid var(--text-muted);
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  cursor: help;
  line-height: 1;
  vertical-align: middle;
  margin-left: 4px;
  transition: all 0.15s;
}
.question-icon:hover {
  border-color: var(--accent-blue);
  color: var(--accent-blue);
}

.form-section {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}

.advanced-section {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}

.advanced-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 10px 14px;
  background: var(--bg-secondary);
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  font-family: inherit;
}
.advanced-toggle:hover { background: var(--bg-hover); }
.toggle-icon { margin-left: auto; transition: transform 0.2s; }
.advanced-body { padding: 14px; }
.advanced-body .form-section { margin-bottom: 12px; }
.advanced-body .form-section:last-child { margin-bottom: 0; }

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.section-desc {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 20px;
  line-height: 1.6;
}

.form-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-bottom: 16px;
}
.form-row:last-child { margin-bottom: 0; }
.flex-grow { flex: 1; }
.invalidate-count { width: 140px; flex-shrink: 0; }
.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}
</style>
