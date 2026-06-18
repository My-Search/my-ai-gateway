<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? t('apikey.form.editTitle') : t('apikey.form.addTitle') }}</div>
      <router-link to="/admin/apikey/list" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-group">
        <label for="keyName">{{ t('apikey.form.keyName') }}</label>
        <input id="keyName" v-model="form.keyName" class="form-control" :placeholder="t('apikey.form.keyNamePlaceholder')" required />
      </div>
      <div class="form-group">
        <label for="keyValue">{{ t('apikey.form.keyValue') }}</label>
        <input id="keyValue" v-model="form.keyValue" class="form-control" :disabled="isEdit" :placeholder="isEdit ? t('apikey.form.keyValueDisabledHint') : t('apikey.form.keyValuePlaceholder')" />
        <div class="form-hint">{{ t('apikey.form.keyValueHint') }}, {{ isEdit ? t('apikey.form.editNoModify') : t('apikey.form.autoGenerate') }}</div>
      </div>
      <div class="form-group">
        <label for="enabled">{{ t('apikey.list.status') }}</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">{{ t('common.enabled') }}</option>
          <option :value="0">{{ t('common.disabled') }}</option>
        </select>
      </div>
      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving"><SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('apikey.form.save') }}</button>
        <router-link to="/admin/apikey/list" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> {{ t('apikey.form.cancel') }}</router-link>
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
import { apikeyApi, type ApiKey } from '@/api/apikey'
import Dialog from '@/components/common/Dialog.vue'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const form = ref<Partial<ApiKey>>({ keyName: '', keyValue: '', enabled: 1 })

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
      const res = await apikeyApi.get(Number(route.params.id))
      form.value = { ...res.data }
    } catch (e: any) {
      openDialog({ title: t('error.loadFailed'), message: e.message })
      router.push('/admin/apikey/list')
    }
  }
})

async function handleSave() {
  saving.value = true
  try {
    const payload = { ...form.value }
    // Key value cannot be modified in edit mode
    if (isEdit.value) {
      delete payload.keyValue
    }
    if (isEdit.value) {
      await apikeyApi.update(Number(route.params.id), payload)
      openDialog({ title: t('common.success'), message: t('apikey.form.updateSuccess'), onConfirm: () => router.push('/admin/apikey/list') })
    } else {
      await apikeyApi.create(payload)
      openDialog({ title: t('common.success'), message: t('apikey.form.createSuccess'), onConfirm: () => router.push('/admin/apikey/list') })
    }
  } catch (e: any) {
    openDialog({ title: t('apikey.form.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
</script>
