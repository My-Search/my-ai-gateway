<template>
  <div>
    <div class="card">
      <div class="card-header">
        <div class="card-title"><SvgIcon name="settings" :size="18" /> {{ t('systemConfig.title') }}</div>
      </div>

      <!-- Error alert -->
      <div v-if="error" class="alert alert-error">
        <SvgIcon name="alert" :size="16" /> {{ error }}
      </div>

      <!-- Success alert -->
      <div v-if="successMsg" class="alert alert-success">
        <SvgIcon name="check" :size="16" /> {{ successMsg }}
      </div>

      <!-- Log Management -->
      <div class="section">
        <div class="section-header">
          <SvgIcon name="log" :size="18" />
          <span>{{ t('systemConfig.logManagement') }}</span>
        </div>
        <div class="section-desc">{{ t('systemConfig.logManagementDesc') }}</div>

        <div class="config-row">
          <div class="config-row-label">
            <div class="config-label">{{ t('systemConfig.cleanupEnabled') }}</div>
            <div class="config-hint">{{ t('systemConfig.cleanupEnabledHint') }}</div>
          </div>
          <div class="config-row-control">
            <button class="toggle-btn" :class="{ active: form.log_cleanup_enabled === '1' }"
                    @click="toggleCleanup">
              <span class="toggle-track">
                <span class="toggle-thumb"></span>
              </span>
              <span class="toggle-label">{{ form.log_cleanup_enabled === '1' ? t('common.enabled') : t('common.disabled') }}</span>
            </button>
          </div>
        </div>

        <div class="config-row">
          <div class="config-row-label">
            <div class="config-label">{{ t('systemConfig.retentionDays') }}</div>
            <div class="config-hint">{{ t('systemConfig.retentionDaysHint') }}</div>
          </div>
          <div class="config-row-control">
            <input type="number" class="form-control" style="width:120px;"
                   v-model.number="form.log_retention_days"
                   :min="1" :max="365"
                   :disabled="form.log_cleanup_enabled !== '1'" />
          </div>
        </div>

      </div>
      <!-- End Log Management -->

      <!-- Request Data Management -->
      <div class="section">
        <div class="section-header">
          <SvgIcon name="log" :size="18" />
          <span>{{ t('systemConfig.requestDataManagement') }}</span>
        </div>
        <div class="section-desc">{{ t('systemConfig.requestDataManagementDesc') }}</div>

        <div class="config-row">
          <div class="config-row-label">
            <div class="config-label">{{ t('systemConfig.requestBodyTtl') }}</div>
            <div class="config-hint">{{ t('systemConfig.requestBodyTtlHint') }}</div>
          </div>
          <div class="config-row-control">
            <input type="number" class="form-control" style="width:120px;"
                   v-model.number="form.request_body_ttl_hours"
                   :min="0" :max="8760" />
          </div>
        </div>
      </div>

      <!-- Save -->
      <div class="section-footer">
        <button class="btn btn-primary" @click="handleSave" :disabled="saving">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { systemApi } from '@/api/system'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const successMsg = ref('')

const form = reactive({
  log_retention_days: 30,
  log_cleanup_enabled: '1',
  request_body_ttl_hours: 4
})

async function loadConfig() {
  loading.value = true
  error.value = ''
  successMsg.value = ''
  try {
    const res = await systemApi.getConfig()
    if (res.data.success && res.data.data) {
      form.log_retention_days = parseInt(res.data.data.log_retention_days) || 30
      form.log_cleanup_enabled = res.data.data.log_cleanup_enabled === '1' ? '1' : '0'
      form.request_body_ttl_hours = parseInt(res.data.data.request_body_ttl_hours) || 0
    }
  } catch (e: any) {
    error.value = e.message || t('error.loadFailed')
  } finally {
    loading.value = false
  }
}

function toggleCleanup() {
  form.log_cleanup_enabled = form.log_cleanup_enabled === '1' ? '0' : '1'
}

async function handleSave() {
  if (form.log_cleanup_enabled === '1') {
    const days = form.log_retention_days
    if (!days || days < 1 || days > 365) {
      error.value = t('systemConfig.retentionDaysInvalid')
      return
    }
  }

  saving.value = true
  error.value = ''
  successMsg.value = ''
  try {
    const res = await systemApi.updateConfig({
      log_retention_days: String(form.log_retention_days),
      log_cleanup_enabled: form.log_cleanup_enabled,
      request_body_ttl_hours: String(form.request_body_ttl_hours)
    })
    if (res.data.success) {
      successMsg.value = t('systemConfig.saveSuccess')
    } else {
      error.value = res.data.error || t('error.saveFailed')
    }
  } catch (e: any) {
    error.value = e.message || t('error.saveFailed')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.section {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 20px;
  margin-top: 16px;
}

.section:first-of-type {
  margin-top: 0;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.section-desc {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 20px;
}

.section-footer {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
}

.config-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid var(--border-color);
  gap: 16px;
}

.config-row:last-of-type {
  border-bottom: none;
}

.config-row-label {
  flex: 1;
  min-width: 0;
}

.config-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.config-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.config-row-control {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

/* Toggle switch */
.toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  font-family: inherit;
}

.toggle-track {
  display: inline-block;
  width: 40px;
  height: 22px;
  background: var(--bg-hover);
  border-radius: 11px;
  position: relative;
  transition: background 0.2s;
}

.toggle-btn.active .toggle-track {
  background: var(--accent-green);
}

.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.2s;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}

.toggle-btn.active .toggle-thumb {
  transform: translateX(18px);
}

.toggle-label {
  font-size: 13px;
  color: var(--text-muted);
  min-width: 32px;
}
</style>
