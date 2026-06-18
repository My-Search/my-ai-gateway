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
        <label for="strategy">{{ t('model.form.strategy') }}</label>
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
      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving"><SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('model.form.save') }}</button>
        <router-link to="/admin/model/list" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> {{ t('model.form.cancel') }}</router-link>
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
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { modelApi, type CustomModel } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const form = ref<Partial<CustomModel>>({ modelName: '', description: '', strategy: '', enabled: 1 })

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
  if (isEdit.value) {
    try {
      const res = await modelApi.get(Number(route.params.id))
      form.value = { ...res.data }
    } catch (e: any) {
      openDialog({ title: t('model.form.loadFailed'), message: e.message })
      router.push('/admin/model/list')
    }
  }
})

async function handleSave() {
  saving.value = true
  try {
    if (isEdit.value) {
      await modelApi.update(Number(route.params.id), form.value)
      openDialog({ title: t('common.success'), message: t('model.form.updateSuccess'), onConfirm: () => router.push('/admin/model/list') })
    } else {
      await modelApi.create(form.value)
      openDialog({ title: t('common.success'), message: t('model.form.createSuccess'), onConfirm: () => router.push('/admin/model/list') })
    }
  } catch (e: any) {
    openDialog({ title: t('model.form.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
</script>
