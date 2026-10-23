<template>
  <div class="page-header cfg-page-header">
    <div class="page-header-main">
      <div class="title-row">
        <h2>{{ t('systemConfig.title') }}</h2>
        <span class="badge" :class="hasChanges ? 'badge-warning' : 'badge-success'">
          {{ hasChanges ? t('cfg.unsaved') : t('cfg.saved') }}
        </span>
      </div>
      <p>{{ t('systemConfig.logManagementDesc') }}</p>
    </div>
  </div>

  <!-- Error alert -->
  <div v-if="error" class="alert alert-error">
    <SvgIcon name="alert" :size="16" /> {{ error }}
  </div>

  <!-- Loading state -->
  <div v-if="loading" class="page-loading">
    <LoadingSpinner :size="18" :text="t('common.loading')" />
  </div>

  <ConfigShell v-else :sections="sections" :railTitle="t('cfg.nav')">
    <!-- ══ 日志管理 ══ -->
    <ConfigSection id="sc-logs" :title="t('systemConfig.logManagement')"
                   :desc="t('systemConfig.logManagementDesc')" icon="log">
      <ConfigField :label="t('systemConfig.cleanupEnabled')" :hint="t('systemConfig.cleanupEnabledHint')">
        <div class="inline-control">
          <ToggleSwitch :model-value="form.log_cleanup_enabled === '1'" :show-label="false" @update:model-value="toggleCleanup" />
          <span class="inline-hint">
            {{ form.log_cleanup_enabled === '1' ? t('common.enabled') : t('common.disabled') }}
          </span>
        </div>
      </ConfigField>

      <ConfigField :label="t('systemConfig.retentionDays')" :hint="t('systemConfig.retentionDaysHint')">
        <div class="unit-input">
          <input type="number" class="form-control" v-model.number="form.log_retention_days"
                 :min="1" :max="365" :disabled="form.log_cleanup_enabled !== '1'" />
          <span class="unit">{{ t('cfg.unitDays') }}</span>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ 原始请求数据 ══ -->
    <ConfigSection id="sc-request" :title="t('systemConfig.requestDataManagement')"
                   :desc="t('systemConfig.requestDataManagementDesc')" icon="detail">
      <ConfigField :label="t('systemConfig.requestBodyTtl')" :hint="t('systemConfig.retryFailTtlHint')">
        <div class="ttl-grid">
          <label class="ttl-item">
            <span class="ttl-label">{{ t('systemConfig.ttlNormalLabel') }}</span>
            <input type="number" class="form-control ttl-input" v-model.number="form.request_body_ttl_hours"
                   :min="0" :max="8760" />
          </label>
          <label class="ttl-item">
            <span class="ttl-label">{{ t('systemConfig.ttlRetryFailLabel') }}</span>
            <input type="number" class="form-control ttl-input" v-model.number="form.retry_fail_ttl_hours"
                   :placeholder="t('systemConfig.retryFailTtlPlaceholder')" :min="0" :max="8760" />
          </label>
          <span class="unit">{{ t('cfg.unitHours') }}</span>
        </div>
        <p class="cfg-field-hint">{{ t('systemConfig.requestBodyTtlHint') }}</p>
      </ConfigField>

      <ConfigField :label="t('systemConfig.requestDataSaveLevel')" :hint="t('systemConfig.requestDataSaveLevelHint')">
        <select class="form-control" style="max-width:180px;" v-model="form.request_data_save_level">
          <option value="none">{{ t('systemConfig.saveLevelNone') }}</option>
          <option value="info">{{ t('systemConfig.saveLevelInfo') }}</option>
          <option value="warn">{{ t('systemConfig.saveLevelWarn') }}</option>
          <option value="error">{{ t('systemConfig.saveLevelError') }}</option>
        </select>
      </ConfigField>
    </ConfigSection>

    <!-- ══ 超时配置 ══ -->
    <ConfigSection id="sc-timeout" :title="t('systemConfig.timeoutManagement')"
                   :desc="t('systemConfig.timeoutManagementDesc')" icon="clock">
      <ConfigField :label="t('systemConfig.timeoutMin')" :hint="t('systemConfig.timeoutMinHint')">
        <div class="unit-input">
          <input type="number" class="form-control" v-model.number="form.timeout_min_seconds" :min="1" :max="600" />
          <span class="unit">{{ t('cfg.unitSeconds') }}</span>
        </div>
      </ConfigField>

      <ConfigField :label="t('systemConfig.timeoutMax')" :hint="t('systemConfig.timeoutMaxHint')">
        <div class="unit-input">
          <input type="number" class="form-control" v-model.number="form.timeout_max_seconds" :min="1" :max="600" />
          <span class="unit">{{ t('cfg.unitSeconds') }}</span>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ 熔断恢复探测 ══ -->
    <ConfigSection id="sc-probe" :title="t('systemConfig.probeManagement')"
                   :desc="t('systemConfig.probeManagementDesc')" icon="monitor">
      <ConfigField :label="t('systemConfig.probeInterval')" :hint="t('systemConfig.probeIntervalHint')">
        <div class="unit-input">
          <input type="number" class="form-control" v-model.number="form.circuit_breaker_probe_interval_minutes" :min="1" :max="1440" />
          <span class="unit">{{ t('cfg.unitMinutes') }}</span>
        </div>
      </ConfigField>

      <ConfigField :label="t('systemConfig.probeThrottle')" :hint="t('systemConfig.probeThrottleHint')">
        <div class="unit-input">
          <input type="number" class="form-control" v-model.number="form.circuit_breaker_probe_throttle_seconds" :min="0" :max="30" :step="0.1" />
          <span class="unit">{{ t('cfg.unitSeconds') }}</span>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ 渠道模型自动刷新 ══ -->
    <ConfigSection id="sc-refresh" :title="t('systemConfig.modelRefreshManagement')"
                   :desc="t('systemConfig.modelRefreshManagementDesc')" icon="refresh">
      <ConfigField :label="t('systemConfig.modelRefreshInterval')" :hint="t('systemConfig.modelRefreshIntervalHint')">
        <div class="unit-input">
          <input type="number" class="form-control" v-model.number="form.channel_model_refresh_interval_minutes" :min="1" :max="1440" />
          <span class="unit">{{ t('cfg.unitMinutes') }}</span>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ models.dev 同步 ══ -->
    <ConfigSection id="sc-models-dev" :title="t('systemConfig.modelsDevManagement')"
                   :desc="t('systemConfig.modelsDevManagementDesc')" icon="download">
      <ConfigField :label="t('systemConfig.modelsDevEnabled')" :hint="t('systemConfig.modelsDevEnabledHint')">
        <div class="inline-control">
          <ToggleSwitch :model-value="form.models_dev_enabled === '1'" :show-label="false" @update:model-value="toggleModelsDev" />
          <span class="inline-hint">
            {{ form.models_dev_enabled === '1' ? t('common.enabled') : t('common.disabled') }}
          </span>
        </div>
      </ConfigField>

      <ConfigField :label="t('systemConfig.modelsDevFile')" :hint="t('systemConfig.modelsDevFileHint')" wide>
        <input type="text" class="form-control mono-input" v-model="form.models_dev_file"
               :disabled="form.models_dev_enabled !== '1'" />
      </ConfigField>

      <ConfigField :label="t('systemConfig.modelsDevSourceUrl')" :hint="t('systemConfig.modelsDevSourceUrlHint')" wide>
        <input type="text" class="form-control mono-input" v-model="form.models_dev_source_url"
               :disabled="form.models_dev_enabled !== '1'" />
      </ConfigField>

      <ConfigField :label="t('systemConfig.modelsDevInterval')" :hint="t('systemConfig.modelsDevIntervalHint')">
        <div class="unit-input">
          <input type="number" class="form-control" v-model.number="form.models_dev_refresh_interval_minutes"
                 :min="1" :max="1440" :disabled="form.models_dev_enabled !== '1'" />
          <span class="unit">{{ t('cfg.unitMinutes') }}</span>
        </div>
      </ConfigField>
    </ConfigSection>

    <template #footer>
      <span class="footer-hint">
        <SvgIcon :name="hasChanges ? 'alert' : 'check'" :size="13" />
        {{ hasChanges ? t('cfg.unsaved') : t('cfg.saved') }}
      </span>
      <div class="footer-actions">
        <button class="btn btn-primary" @click="handleSave" :disabled="saving || !hasChanges">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </template>
  </ConfigShell>

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
import { ref, reactive, onMounted, computed, onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { systemApi } from '@/api/system'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import ConfigShell from '@/components/common/ConfigShell.vue'
import ConfigSection from '@/components/common/ConfigSection.vue'
import ConfigField from '@/components/common/ConfigField.vue'

const { t } = useI18n()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()

// 初始为 true：首次渲染即显示加载态，避免表单默认值闪现
const loading = ref(true)
const saving = ref(false)
const error = ref('')
/** 用于检测表单是否变更的初始快照（JSON 字符串） */
const originalForm = ref('')

const sections = computed(() => [
  { id: 'sc-logs', label: t('systemConfig.logManagement'), icon: 'log' },
  { id: 'sc-request', label: t('systemConfig.requestDataManagement'), icon: 'detail' },
  { id: 'sc-timeout', label: t('systemConfig.timeoutManagement'), icon: 'clock' },
  { id: 'sc-probe', label: t('systemConfig.probeManagement'), icon: 'monitor' },
  { id: 'sc-refresh', label: t('systemConfig.modelRefreshManagement'), icon: 'refresh' },
  { id: 'sc-models-dev', label: t('systemConfig.modelsDevManagement'), icon: 'download' },
])

const form = reactive({
  log_retention_days: 30,
  log_cleanup_enabled: '1',
  request_body_ttl_hours: 4,
  // 空 = 跟随普通记录保留时长，0 = 跟随日志保留天数
  retry_fail_ttl_hours: '' as number | '',
  request_data_save_level: 'info',
  timeout_min_seconds: 20,
  timeout_max_seconds: 60,
  circuit_breaker_probe_interval_minutes: 30,
  circuit_breaker_probe_throttle_seconds: 30,
  channel_model_refresh_interval_minutes: 30,
  models_dev_enabled: '1',
  models_dev_file: 'data/models.json',
  models_dev_source_url: 'https://models.dev/models.json',
  models_dev_refresh_interval_minutes: 30
})

/** 表单是否已被修改但未保存 */
const hasChanges = computed(() => {
  return JSON.stringify({ ...form }) !== originalForm.value
})

/** 将 form 当前值保存为初始快照，供变更检测使用 */
function snapshotForm() {
  originalForm.value = JSON.stringify({ ...form })
}

/** 浏览器关闭/刷新前的确认 */
function onBeforeUnload(e: BeforeUnloadEvent) {
  if (hasChanges.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

async function loadConfig() {
  loading.value = true
  error.value = ''
  try {
    const res = await systemApi.getConfig()
    if (res.data.success && res.data.data) {
      form.log_retention_days = parseInt(res.data.data.log_retention_days) || 30
      form.log_cleanup_enabled = res.data.data.log_cleanup_enabled === '1' ? '1' : '0'
      form.request_body_ttl_hours = parseInt(res.data.data.request_body_ttl_hours) || 0
      // 注意：空字符串表示跟随普通记录保留时长，不能用 || 兜底（会把空值吞成 0）
      const retryFailTtl = res.data.data.retry_fail_ttl_hours
      form.retry_fail_ttl_hours = retryFailTtl == null || String(retryFailTtl).trim() === ''
        ? ''
        : (parseInt(String(retryFailTtl)) || 0)
      form.request_data_save_level = res.data.data.request_data_save_level || 'info'
      form.timeout_min_seconds = parseInt(res.data.data.timeout_min_seconds) || 20
      form.timeout_max_seconds = parseInt(res.data.data.timeout_max_seconds) || 60
      // 注意：节流配置合法值为 0（不节流），不能用 || 兜底（0 || 6 会把 0 吞掉）
      const probeInterval = parseInt(res.data.data.circuit_breaker_probe_interval_minutes)
      form.circuit_breaker_probe_interval_minutes = Number.isNaN(probeInterval) ? 30 : probeInterval
      const probeThrottle = parseFloat(res.data.data.circuit_breaker_probe_throttle_seconds)
      form.circuit_breaker_probe_throttle_seconds = Number.isNaN(probeThrottle) ? 30 : probeThrottle
      const modelRefreshInterval = parseInt(res.data.data.channel_model_refresh_interval_minutes)
      form.channel_model_refresh_interval_minutes = Number.isNaN(modelRefreshInterval) ? 30 : modelRefreshInterval
      form.models_dev_enabled = res.data.data.models_dev_enabled === '1' ? '1' : '0'
      form.models_dev_file = res.data.data.models_dev_file || 'data/models.json'
      form.models_dev_source_url = res.data.data.models_dev_source_url || 'https://models.dev/models.json'
      const modelsDevInterval = parseInt(res.data.data.models_dev_refresh_interval_minutes)
      form.models_dev_refresh_interval_minutes = Number.isNaN(modelsDevInterval) ? 30 : modelsDevInterval
      // 加载完成后记录初始快照
      snapshotForm()
    }
  } catch (e: any) {
    error.value = e.message || t('error.loadFailed')
  } finally {
    loading.value = false
  }
}

function toggleCleanup(v: boolean) {
  form.log_cleanup_enabled = v ? '1' : '0'
}

function toggleModelsDev(v: boolean) {
  form.models_dev_enabled = v ? '1' : '0'
}

async function handleSave() {
  if (form.log_cleanup_enabled === '1') {
    const days = form.log_retention_days
    if (!days || days < 1 || days > 365) {
      error.value = t('systemConfig.retentionDaysInvalid')
      return
    }
  }

  if (form.timeout_min_seconds > form.timeout_max_seconds) {
    error.value = t('systemConfig.timeoutInvalid')
    return
  }

  if (!form.circuit_breaker_probe_interval_minutes || form.circuit_breaker_probe_interval_minutes < 1) {
    error.value = t('systemConfig.probeIntervalInvalid')
    return
  }

  if (form.circuit_breaker_probe_throttle_seconds < 0 || form.circuit_breaker_probe_throttle_seconds > 30) {
    error.value = t('systemConfig.probeThrottleInvalid')
    return
  }

  if (!form.channel_model_refresh_interval_minutes || form.channel_model_refresh_interval_minutes < 1) {
    error.value = t('systemConfig.modelRefreshIntervalInvalid')
    return
  }

  if (form.models_dev_enabled === '1' && !String(form.models_dev_file || '').trim()) {
    error.value = t('systemConfig.modelsDevFileInvalid')
    return
  }

  if (form.models_dev_enabled === '1' && !String(form.models_dev_source_url || '').trim()) {
    error.value = t('systemConfig.modelsDevSourceUrlInvalid')
    return
  }

  if (form.models_dev_enabled === '1' &&
      (!form.models_dev_refresh_interval_minutes || form.models_dev_refresh_interval_minutes < 1)) {
    error.value = t('systemConfig.modelsDevIntervalInvalid')
    return
  }

  saving.value = true
  error.value = ''
  try {
    const res = await systemApi.updateConfig({
      log_retention_days: String(form.log_retention_days),
      log_cleanup_enabled: form.log_cleanup_enabled,
      request_body_ttl_hours: String(form.request_body_ttl_hours),
      // 空 = 跟随普通记录保留时长，原样保存空字符串
      retry_fail_ttl_hours: form.retry_fail_ttl_hours === '' || form.retry_fail_ttl_hours == null
        ? ''
        : String(form.retry_fail_ttl_hours),
      request_data_save_level: form.request_data_save_level,
      timeout_min_seconds: String(form.timeout_min_seconds),
      timeout_max_seconds: String(form.timeout_max_seconds),
      circuit_breaker_probe_interval_minutes: String(form.circuit_breaker_probe_interval_minutes),
      circuit_breaker_probe_throttle_seconds: String(form.circuit_breaker_probe_throttle_seconds),
      channel_model_refresh_interval_minutes: String(form.channel_model_refresh_interval_minutes),
      models_dev_enabled: form.models_dev_enabled,
      models_dev_file: String(form.models_dev_file || '').trim(),
      models_dev_source_url: String(form.models_dev_source_url || '').trim(),
      models_dev_refresh_interval_minutes: String(form.models_dev_refresh_interval_minutes)
    })
    if (res.data.success) {
      open({ title: t('common.success'), message: t('systemConfig.saveSuccess') })
      snapshotForm()
    } else {
      error.value = res.data.error || t('error.saveFailed')
    }
  } catch (e: any) {
    error.value = e.message || t('error.saveFailed')
  } finally {
    saving.value = false
  }
}

/**
 * 路由离开守卫：当表单未保存时弹出确认
 * 使用同步的 window.confirm 避免异步竞态
 */
onBeforeRouteLeave((_to, _from) => {
  if (hasChanges.value) {
    return window.confirm(t('systemConfig.unsavedConfirm'))
  }
})

onMounted(() => {
  loadConfig()
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<style scoped>
.page-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 13px;
}

.cfg-page-header {
  align-items: flex-start;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
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

.ttl-grid {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.ttl-item {
  display: flex;
  align-items: center;
  gap: 7px;
}

.ttl-label {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}

.ttl-input {
  width: 92px;
  max-width: 92px !important;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.mono-input {
  font-family: var(--font-mono);
  font-size: 12.5px;
}

@media (max-width: 768px) {
  .ttl-grid {
    gap: 10px;
  }
  .ttl-item {
    flex: 1 1 140px;
  }
  .ttl-input {
    flex: 1;
    min-width: 0;
  }
}
</style>
