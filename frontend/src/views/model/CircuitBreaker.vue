<template>
  <div class="page-header cfg-page-header">
    <div class="page-header-main">
      <div class="title-row">
        <router-link to="/admin/model/list" class="back-link" :title="t('common.back')">
          <SvgIcon name="arrow-left" :size="16" />
        </router-link>
        <h2>{{ t('cb.title').replace('{name}', model?.modelName || '') }}</h2>
        <span class="badge" :class="config.enabled === 1 ? 'badge-success' : 'badge-muted'">
          {{ config.enabled === 1 ? t('cb.on') : t('cb.off') }}
        </span>
      </div>
      <p>{{ t('cb.subtitle') }}</p>
    </div>
  </div>

  <!-- Loading state -->
  <div v-if="loading" class="page-loading">
    <LoadingSpinner :size="18" :text="t('common.loading')" />
  </div>

  <form v-else @submit.prevent="handleSave">
    <ConfigShell :sections="sections" :railTitle="t('cfg.nav')">
      <!-- ══ 熔断开关 ══ -->
      <ConfigSection id="cb-basic" :title="t('cb.sectionBasic')" :desc="t('cb.sectionBasicDesc')" icon="zap">
        <ConfigField :label="t('cb.enabled')" :hint="t('cb.enabledHint')">
          <div class="inline-control">
            <ToggleSwitch v-model="enabledBool" :show-label="false" />
            <span class="inline-hint">
              {{ config.enabled === 1 ? t('cb.on') : t('cb.off') }}
            </span>
          </div>
        </ConfigField>

        <ConfigField :label="t('cb.scope')" :hint="t('cb.scopeHint')">
          <select v-model="config.circuitBreakScope" class="form-control">
            <option value="channel">{{ t('cb.scopeChannel') }}</option>
            <option value="model">{{ t('cb.scopeModel') }}</option>
          </select>
        </ConfigField>
      </ConfigSection>

      <!-- ══ 触发策略 ══ -->
      <ConfigSection id="cb-policy" :title="t('cb.sectionPolicy')" :desc="t('cb.sectionPolicyDesc')" icon="refresh">
        <ConfigField :label="t('cb.retryCount')" :hint="t('cb.retryCountHint')">
          <div class="unit-input">
            <input v-model.number="config.retryCount" type="number" class="form-control" min="0" />
            <span class="unit">{{ t('cfg.unitTimes') }}</span>
          </div>
        </ConfigField>

        <ConfigField :label="t('cb.duration')" :hint="t('cb.durationHint')">
          <div class="unit-input">
            <input v-model.number="config.circuitBreakDuration" type="number" class="form-control" min="1" />
            <span class="unit">{{ t('cfg.unitSeconds') }}</span>
          </div>
        </ConfigField>
      </ConfigSection>

      <template #footer>
        <span class="footer-hint">
          <SvgIcon name="info" :size="13" /> {{ t('cfg.stickyFooterHint') }}
        </span>
        <div class="footer-actions">
          <router-link :to="'/admin/model/list'" class="btn btn-secondary">
            <SvgIcon name="x" :size="14" /> {{ t('cb.cancel') }}
          </router-link>
          <button type="submit" class="btn btn-primary" :disabled="saving">
            <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('cb.save') }}
          </button>
        </div>
      </template>
    </ConfigShell>
  </form>

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
import { modelApi, type CustomModel, type CircuitBreakerConfig } from '@/api/model'
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
const model = ref<CustomModel | null>(null)
const loading = ref(true)
const saving = ref(false)
const config = ref<CircuitBreakerConfig>({
  modelId: 0, enabled: 0, retryCount: 3,
  circuitBreakDuration: 60, circuitBreakScope: 'model'
})

/** 熔断开关：后端存 1/0，开关组件用布尔值 */
const enabledBool = computed({
  get: () => config.value.enabled === 1,
  set: (v: boolean) => { config.value.enabled = v ? 1 : 0 }
})

const sections = computed(() => [
  { id: 'cb-basic', label: t('cb.sectionBasic'), icon: 'zap' },
  { id: 'cb-policy', label: t('cb.sectionPolicy'), icon: 'refresh' },
])

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
    open({ title: t('error.loadFailed'), message: e.message })
    router.push('/admin/model/list')
  } finally {
    loading.value = false
  }
})

async function handleSave() {
  saving.value = true
  try {
    await modelApi.saveCircuitBreaker(Number(route.params.id), config.value)
    open({ title: t('common.success'), message: t('cb.saveSuccess'), onConfirm: () => router.push('/admin/model/list') })
  } catch (e: any) {
    open({ title: t('cb.saveFailed'), message: e.message })
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

.unit-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.unit-input .form-control {
  width: 118px;
  max-width: 118px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.unit {
  font-size: 12.5px;
  color: var(--text-muted);
  white-space: nowrap;
}

</style>
