<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('cb.title').replace('{name}', model?.modelName || '') }}</div>
      <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-group">
        <label>{{ t('cb.enabled') }}</label>
        <select v-model.number="config.enabled" class="form-control">
          <option :value="1">{{ t('cb.on') }}</option>
          <option :value="0">{{ t('cb.off') }}</option>
        </select>
      </div>
      <div class="form-group">
        <label>{{ t('cb.scope') }}</label>
        <select v-model="config.circuitBreakScope" class="form-control">
          <option value="channel">{{ t('cb.scopeChannel') }}</option>
          <option value="model">{{ t('cb.scopeModel') }}</option>
        </select>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label>{{ t('cb.retryCount') }}</label>
          <input v-model.number="config.retryCount" type="number" class="form-control" min="0" />
        </div>
        <div class="form-group">
          <label>{{ t('cb.duration') }}</label>
          <input v-model.number="config.circuitBreakDuration" type="number" class="form-control" min="1" />
        </div>
      </div>
      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving"><SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('cb.save') }}</button>
        <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> {{ t('cb.cancel') }}</router-link>
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
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { modelApi, type CustomModel, type CircuitBreakerConfig } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const model = ref<CustomModel | null>(null)
const saving = ref(false)
const config = ref<CircuitBreakerConfig>({
  modelId: 0, enabled: 0, retryCount: 3,
  circuitBreakDuration: 60, circuitBreakScope: 'model'
})

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

onMounted(async () => {
  const id = Number(route.params.id)
  try {
    const res = await modelApi.getCircuitBreaker(id)
    model.value = res.data.model
    if (res.data.config) {
      config.value = { ...config.value, ...res.data.config, modelId: id }
    } else {
      config.value.modelId = id
    }
  } catch (e: any) {
    openDialog({ title: t('error.loadFailed'), message: e.message })
    router.push('/admin/model/list')
  }
})

async function handleSave() {
  saving.value = true
  try {
    await modelApi.saveCircuitBreaker(Number(route.params.id), config.value)
    openDialog({ title: t('common.success'), message: t('cb.saveSuccess'), onConfirm: () => router.push('/admin/model/list') })
  } catch (e: any) {
    openDialog({ title: t('cb.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
</script>
