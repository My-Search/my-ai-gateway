<template>
  <div class="key-form">
    <div class="page-header cfg-page-header">
      <div class="page-header-main">
        <div class="title-row">
          <router-link to="/admin/apikey/list" class="back-link" :aria-label="t('common.back')">
            <SvgIcon name="arrow-left" :size="16" />
          </router-link>
          <h2>{{ isEdit ? t('apikey.form.editTitle') : t('apikey.form.addTitle') }}</h2>
          <span v-if="isEdit" class="badge badge-muted">#{{ route.params.id }}</span>
          <span class="badge" :class="form.enabled === 1 ? 'badge-success' : 'badge-muted'">
            {{ form.enabled === 1 ? t('common.enabled') : t('common.disabled') }}
          </span>
        </div>
        <p>{{ t('apikey.form.subtitle') }}</p>
      </div>
    </div>

    <!-- 编辑态先显示行内加载态，避免空表单闪现 -->
    <div v-if="loading" class="page-loading">
      <LoadingSpinner :size="18" :text="t('common.loading')" />
    </div>

    <form v-else @submit.prevent="handleSave">
      <ConfigShell :sections="sections" :railTitle="t('cfg.nav')">
        <!-- ══ 密钥信息 ══ -->
        <ConfigSection id="key-basic" :title="t('apikey.form.sectionBasic')"
                       :desc="t('apikey.form.sectionBasicDesc')" icon="key">
          <ConfigField :label="t('apikey.form.keyName')" :hint="t('apikey.form.keyNameHint')" required>
            <input id="keyName" v-model="form.keyName" class="form-control"
                   :placeholder="t('apikey.form.keyNamePlaceholder')" required />
          </ConfigField>

          <ConfigField :label="t('apikey.form.keyValue')" :hint="keyValueHint" wide>
            <input id="keyValue" v-model="form.keyValue" class="form-control mono-input"
                   :disabled="isEdit"
                   :placeholder="isEdit ? t('apikey.form.keyValueDisabledHint') : t('apikey.form.keyValuePlaceholder')" />
          </ConfigField>

          <ConfigField :label="t('apikey.list.status')" :hint="t('apikey.form.statusHint')">
            <div class="inline-control">
              <ToggleSwitch v-model="enabledBool" :show-label="false" />
              <span class="inline-hint">
                {{ form.enabled === 1 ? t('common.enabled') : t('common.disabled') }}
              </span>
            </div>
          </ConfigField>
        </ConfigSection>

        <template #footer>
          <span class="footer-hint">
            <SvgIcon name="info" :size="13" /> {{ t('apikey.form.footerHint') }}
          </span>
          <div class="footer-actions">
            <router-link to="/admin/apikey/list" class="btn btn-secondary">
              <SvgIcon name="x" :size="14" /> {{ t('apikey.form.cancel') }}
            </router-link>
            <button type="submit" class="btn btn-primary" :disabled="saving">
              <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('apikey.form.save') }}
            </button>
          </div>
        </template>
      </ConfigShell>
    </form>
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
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { apikeyApi, type ApiKey } from '@/api/apikey'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import ConfigShell from '@/components/common/ConfigShell.vue'
import ConfigSection from '@/components/common/ConfigSection.vue'
import ConfigField from '@/components/common/ConfigField.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
// 编辑态初始即加载中（数据到达前显示骨架），新建态直接渲染表单
const loading = ref(isEdit.value)
const form = ref<Partial<ApiKey>>({ keyName: '', keyValue: '', enabled: 1 })

/** 启用状态：表单里是 1/0，开关组件用布尔值 */
const enabledBool = computed({
  get: () => form.value.enabled === 1,
  set: (v: boolean) => { form.value.enabled = v ? 1 : 0 }
})

const sections = computed(() => [
  { id: 'key-basic', label: t('apikey.form.sectionBasic'), icon: 'key' },
])

/** 密钥输入说明：编辑态强调不可修改，新增态说明留空即自动生成 */
const keyValueHint = computed(() =>
  isEdit.value
    ? `${t('apikey.form.keyValueHint')}, ${t('apikey.form.editNoModify')}`
    : `${t('apikey.form.keyValueHint')}, ${t('apikey.form.autoGenerate')}`)

onMounted(async () => {
  if (isEdit.value) {
    try {
      const res = await apikeyApi.get(Number(route.params.id))
      form.value = { ...res.data }
    } catch (e: any) {
      open({ title: t('error.loadFailed'), message: e.message })
      router.push('/admin/apikey/list')
    } finally {
      loading.value = false
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
      open({ title: t('common.success'), message: t('apikey.form.updateSuccess'), onConfirm: () => router.push('/admin/apikey/list') })
    } else {
      await apikeyApi.create(payload)
      open({ title: t('common.success'), message: t('apikey.form.createSuccess'), onConfirm: () => router.push('/admin/apikey/list') })
    }
  } catch (e: any) {
    open({ title: t('apikey.form.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
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

.mono-input {
  font-family: var(--font-mono);
  font-size: 12.5px;
}

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

</style>
