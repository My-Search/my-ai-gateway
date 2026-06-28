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
              <label class="switch">
                <input type="checkbox" v-model="imageEnabled" @change="onImageToggle" />
                <span class="switch-slider"></span>
              </label>
              <span class="switch-label">{{ imageEnabled ? t('common.on') : t('common.off') }}</span>
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
              <label class="switch">
                <input type="checkbox" v-model="videoEnabled" @change="onVideoToggle" />
                <span class="switch-slider"></span>
              </label>
              <span class="switch-label">{{ videoEnabled ? t('common.on') : t('common.off') }}</span>
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
              <label class="switch">
                <input type="checkbox" v-model="audioEnabled" @change="onAudioToggle" />
                <span class="switch-slider"></span>
              </label>
              <span class="switch-label">{{ audioEnabled ? t('common.on') : t('common.off') }}</span>
            </div>
          </div>
          <div class="form-group" style="width:140px;flex-shrink:0;">
            <label>{{ t('model.advanced.invalidateCount') }}</label>
            <input v-model.number="form.audioInvalidateCount" type="number" class="form-control" min="0" max="99"
                   :disabled="!audioEnabled" />
          </div>
        </div>
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
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { modelApi, type CustomModel } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
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

/* ---------- Dialog state ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref(t('common.prompt'))
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? t('common.prompt')
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

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
      audioInvalidateCount: form.value.audioInvalidateCount
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
  background: var(--bg-secondary);
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

/* Toggle switch */
.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}

.switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
}

.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--border-color, #444);
  transition: 0.3s;
  border-radius: 22px;
}

.switch-slider::before {
  position: absolute;
  content: "";
  height: 16px;
  width: 16px;
  left: 3px;
  bottom: 3px;
  background-color: var(--bg-primary, #fff);
  transition: 0.3s;
  border-radius: 50%;
}

.switch input:checked + .switch-slider {
  background-color: var(--accent-blue, #58a6ff);
}

.switch input:checked + .switch-slider::before {
  transform: translateX(18px);
}

.switch-label {
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
