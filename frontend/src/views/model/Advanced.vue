<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('model.advanced.title').replace('{name}', model?.modelName || '') }}</div>
      <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-section">
        <div class="section-title">{{ t('model.advanced.mediaInvalidate') }}</div>
        <div class="section-desc">{{ t('model.advanced.mediaInvalidateHint') }}</div>

        <!-- Image -->
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
          <div class="form-group" style="width:140px;flex-shrink:0;">
            <label>{{ t('model.advanced.invalidateCount') }}</label>
            <input v-model.number="form.imageInvalidateCount" type="number" class="form-control" min="0" max="99"
                   :disabled="!imageEnabled" />
          </div>
        </div>

        <!-- Video -->
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
          <div class="form-group" style="width:140px;flex-shrink:0;">
            <label>{{ t('model.advanced.invalidateCount') }}</label>
            <input v-model.number="form.videoInvalidateCount" type="number" class="form-control" min="0" max="99"
                   :disabled="!videoEnabled" />
          </div>
        </div>

        <!-- Audio -->
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
          <div class="form-group" style="width:140px;flex-shrink:0;">
            <label>{{ t('model.advanced.invalidateCount') }}</label>
            <input v-model.number="form.audioInvalidateCount" type="number" class="form-control" min="0" max="99"
                   :disabled="!audioEnabled" />
          </div>
        </div>
      </div>

      <div class="form-section">
        <div class="section-title">{{ t('model.advanced.forceOverrideReasoningEffort') }}</div>
        <div class="section-desc">{{ t('model.advanced.forceOverrideReasoningEffortHint') }}</div>
        <div class="switch-group">
          <ToggleSwitch
            v-model="forceOverrideEnabled"
            :active-label="t('common.on')"
            :inactive-label="t('common.off')"
          />
        </div>
      </div>

      <div class="form-section">
        <div class="section-title">{{ t('model.advanced.promptInjection') }}</div>
        <div class="section-desc">{{ t('model.advanced.promptInjectionHint') }}</div>
        <router-link :to="`/admin/model/prompt-injections/${route.params.id}`" class="btn btn-secondary">
          <SvgIcon name="code" :size="14" /> {{ t('model.advanced.managePromptInjection') }}
        </router-link>
      </div>

      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving"><SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('model.advanced.save') }}</button>
        <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> {{ t('model.advanced.cancel') }}</router-link>
      </div>
    </form>
  </div>

  <!-- Common Dialog -->
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
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { modelApi, type CustomModel } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { visible: dialogVisible, title: dialogTitle, message: dialogMessage, type: dialogType, confirmClass: dialogConfirmClass, onConfirm: onDialogConfirm, open: openDialog } = useDialog()
const model = ref<CustomModel | null>(null)
const saving = ref(false)
const form = ref({
  imageInvalidateCount: 0,
  videoInvalidateCount: 0,
  audioInvalidateCount: 0
})
const imageEnabled = ref(false)
const videoEnabled = ref(false)
const audioEnabled = ref(false)
const forceOverrideEnabled = ref(false)

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

onMounted(async () => {
  const id = Number(route.params.id)
  try {
    const res = await modelApi.get(id)
    model.value = res.data
    form.value.imageInvalidateCount = res.data.imageInvalidateCount ?? 0
    form.value.videoInvalidateCount = res.data.videoInvalidateCount ?? 0
    form.value.audioInvalidateCount = res.data.audioInvalidateCount ?? 0
    imageEnabled.value = form.value.imageInvalidateCount > 0
    videoEnabled.value = form.value.videoInvalidateCount > 0
    audioEnabled.value = form.value.audioInvalidateCount > 0
    forceOverrideEnabled.value = (res.data.forceOverrideReasoningEffort ?? 0) === 1
  } catch (e: any) {
    openDialog({ title: t('error.loadFailed'), message: e.message })
    router.push('/admin/model/list')
  }
})

async function handleSave() {
  saving.value = true
  try {
    await modelApi.update(Number(route.params.id), {
      imageInvalidateCount: form.value.imageInvalidateCount,
      videoInvalidateCount: form.value.videoInvalidateCount,
      audioInvalidateCount: form.value.audioInvalidateCount,
      forceOverrideReasoningEffort: forceOverrideEnabled.value ? 1 : 0
    })
    openDialog({ title: t('common.success'), message: t('model.advanced.saveSuccess'), onConfirm: () => router.push('/admin/model/list') })
  } catch (e: any) {
    openDialog({ title: t('model.advanced.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-section {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 16px;
}

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

.form-row:last-child {
  margin-bottom: 0;
}

.flex-grow {
  flex: 1;
}

/* Toggle switch group */
.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}
</style>
