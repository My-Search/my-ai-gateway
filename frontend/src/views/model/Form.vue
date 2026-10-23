<template>
  <div class="page-header cfg-page-header">
    <div class="page-header-main">
      <div class="title-row">
        <router-link to="/admin/model/list" class="back-link" :title="t('common.back')">
          <SvgIcon name="arrow-left" :size="16" />
        </router-link>
        <h2>{{ isEdit ? t('model.form.editTitle') : t('model.form.addTitle') }}</h2>
        <span v-if="isEdit" class="badge badge-muted">#{{ route.params.id }}</span>
        <span class="badge" :class="form.enabled === 1 ? 'badge-success' : 'badge-muted'">
          {{ form.enabled === 1 ? t('common.enabled') : t('common.disabled') }}
        </span>
      </div>
      <p>{{ t('model.form.modelNameHint') }}</p>
    </div>
    <div class="header-actions">
      <router-link v-if="isEdit" :to="`/admin/model/rels/${route.params.id}`" class="btn btn-secondary btn-sm">
        <SvgIcon name="link" :size="13" /> {{ t('model.list.manageRels') }}
      </router-link>
    </div>
  </div>

  <ConfigShell :sections="sections" :railTitle="t('cfg.nav')">
    <!-- ══ 基础信息 ══ -->
    <ConfigSection id="mdl-basic" :title="t('cfg.basic')" :desc="t('model.form.modelNameHint')" icon="model">
      <ConfigField :label="t('model.form.modelName')" :hint="t('model.form.modelNameHint')" required>
        <input id="modelName" v-model="form.modelName" class="form-control mono-input"
               :placeholder="t('model.form.modelNamePlaceholder')" required />
      </ConfigField>

      <ConfigField :label="t('model.form.description')">
        <textarea id="description" v-model="form.description" class="form-control" rows="3"
                  :placeholder="t('model.form.descriptionPlaceholder')"></textarea>
      </ConfigField>

      <ConfigField :label="t('cfg.fieldStatus')">
        <div class="inline-control">
          <ToggleSwitch v-model="enabledBool" :show-label="false" />
          <span class="inline-hint">
            {{ form.enabled === 1 ? t('common.enabled') : t('common.disabled') }}
          </span>
        </div>
      </ConfigField>

      <ConfigField :label="t('model.form.hidden')" :hint="t('model.form.hiddenHint')">
        <div class="inline-control">
          <ToggleSwitch v-model="hiddenEnabled" :show-label="false" />
          <span class="inline-hint">{{ t('model.form.hidden') }}</span>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ 路由策略 ══ -->
    <ConfigSection id="mdl-strategy" :title="t('model.form.strategy')" :desc="t('model.form.strategyHint')" icon="rank">
      <template #actions>
        <button type="button" class="btn btn-sm btn-ghost" @click="showStrategyInfo = true">
          <SvgIcon name="question" :size="13" /> {{ t('model.form.strategy') }}
        </button>
      </template>

      <ConfigField :label="t('model.form.strategy')" :hint="t('model.form.strategyHint')" :stack="true" hide-label>
        <div class="strategy-grid">
          <button
            v-for="opt in strategyOptions"
            :key="opt.value"
            type="button"
            class="strategy-card"
            :class="{ active: (form.strategy || '') === opt.value }"
            @click="form.strategy = opt.value"
          >
            <span class="strategy-top">
              <SvgIcon :name="opt.icon" :size="15" />
              <strong>{{ opt.label }}</strong>
              <span v-if="(form.strategy || '') === opt.value" class="strategy-check">
                <SvgIcon name="check-bold" :size="10" />
              </span>
            </span>
            <span class="strategy-desc">{{ opt.desc }}</span>
          </button>
        </div>
      </ConfigField>

      <ConfigField v-if="isEdit" :label="t('model.advanced.forceOverrideReasoningEffort')" :hint="t('model.advanced.forceOverrideReasoningEffortHint')">
        <div class="inline-control">
          <ToggleSwitch v-model="forceOverrideEnabled" :show-label="false" />
          <span class="inline-hint">
            {{ forceOverrideEnabled ? t('common.on') : t('common.off') }}
          </span>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ 多模态失效阈值 ══ -->
    <ConfigSection v-if="isEdit" id="mdl-media" :title="t('model.advanced.mediaInvalidate')"
                   :desc="t('model.advanced.mediaInvalidateHint')" icon="image">
      <ConfigField
        v-for="media in mediaRows"
        :key="media.key"
        :label="t(media.labelKey)"
        :hint="t('model.advanced.invalidateCount')"
      >
        <div class="media-row">
          <ToggleSwitch
            :model-value="media.enabled.value"
            :active-label="t('common.on')"
            :inactive-label="t('common.off')"
            @update:model-value="media.onToggle"
          />
          <div class="media-counter">
            <input
              v-model.number="form[media.field]"
              type="number"
              class="form-control media-input"
              min="0"
              max="99"
              :disabled="!media.enabled.value"
            />
            <span class="media-unit">{{ t('model.advanced.invalidateCount') }}</span>
          </div>
        </div>
      </ConfigField>
    </ConfigSection>

    <!-- ══ Prompt 注入 ══ -->
    <ConfigSection v-if="isEdit" id="mdl-prompt" :title="t('model.advanced.promptInjection')"
                   :desc="t('model.advanced.promptInjectionHint')" icon="code">
      <ConfigField :label="t('model.advanced.promptInjection')" :hint="t('model.advanced.promptInjectionHint')">
        <router-link :to="`/admin/model/prompt-injections/${route.params.id}`" class="btn btn-secondary">
          <SvgIcon name="code" :size="14" /> {{ t('model.advanced.managePromptInjection') }}
        </router-link>
      </ConfigField>
    </ConfigSection>

    <template #footer>
      <span class="footer-hint">
        <SvgIcon name="info" :size="13" /> {{ t('cfg.stickyFooterHint') }}
      </span>
      <div class="footer-actions">
        <router-link to="/admin/model/list" class="btn btn-secondary">{{ t('model.form.cancel') }}</router-link>
        <button type="button" class="btn btn-primary" :disabled="saving" @click="handleSave">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('model.form.save') }}
        </button>
      </div>
    </template>
  </ConfigShell>

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
import ConfigShell from '@/components/common/ConfigShell.vue'
import ConfigSection from '@/components/common/ConfigSection.vue'
import ConfigField from '@/components/common/ConfigField.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const showStrategyInfo = ref(false)

const sections = computed(() => {
  const list = [
    { id: 'mdl-basic', label: t('cfg.basic'), icon: 'model' },
    { id: 'mdl-strategy', label: t('model.form.strategy'), icon: 'rank' },
  ]
  if (isEdit.value) {
    list.push(
      { id: 'mdl-media', label: t('model.advanced.mediaInvalidate'), icon: 'image' },
      { id: 'mdl-prompt', label: t('model.advanced.promptInjection'), icon: 'code' },
    )
  }
  return list
})

const strategyOptions = computed(() => [
  { value: '', label: t('model.form.strategyFailover'), desc: t('cfg.strategyFailoverDesc'), icon: 'rank' },
  { value: 'random', label: t('model.form.strategyRandom'), desc: t('cfg.strategyRandomDesc'), icon: 'zap' },
  { value: 'round_robin', label: t('model.form.strategyRoundRobin'), desc: t('cfg.strategyRoundRobinDesc'), icon: 'refresh' },
])

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

const enabledBool = computed({
  get: () => form.value.enabled === 1,
  set: (v: boolean) => { form.value.enabled = v ? 1 : 0 },
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

/** 多模态三行配置（图片/视频/音频） */
const mediaRows = computed(() => [
  {
    key: 'image',
    labelKey: 'model.advanced.image',
    field: 'imageInvalidateCount' as const,
    enabled: imageEnabled,
    onToggle: onImageToggle,
  },
  {
    key: 'video',
    labelKey: 'model.advanced.video',
    field: 'videoInvalidateCount' as const,
    enabled: videoEnabled,
    onToggle: onVideoToggle,
  },
  {
    key: 'audio',
    labelKey: 'model.advanced.audio',
    field: 'audioInvalidateCount' as const,
    enabled: audioEnabled,
    onToggle: onAudioToggle,
  },
])

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

/* ── Strategy cards ── */
.strategy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 10px;
}

.strategy-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 13px 14px;
  border-radius: var(--radius);
  border: 1px solid var(--border-color);
  background: var(--bg-sunken);
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: border-color var(--dur-1), background var(--dur-1), box-shadow var(--dur-1);
}

.strategy-card:hover {
  border-color: var(--border-strong);
  background: var(--bg-secondary);
}

.strategy-card.active {
  border-color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 6%, var(--bg-secondary));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 10%, transparent);
}

.strategy-top {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--text-secondary);
}

.strategy-card.active .strategy-top {
  color: var(--accent-blue);
}

.strategy-top strong {
  font-size: 13px;
  font-weight: 630;
  color: var(--text-primary);
  flex: 1;
  min-width: 0;
}

.strategy-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--accent-blue);
  color: #fff;
  flex-shrink: 0;
}

.strategy-desc {
  font-size: 11.5px;
  color: var(--text-muted);
  line-height: 1.5;
}

/* ── Media rows ── */
.media-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.media-counter {
  display: flex;
  align-items: center;
  gap: 8px;
}

.media-input {
  width: 78px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.media-unit {
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 768px) {
  .strategy-grid {
    grid-template-columns: 1fr;
  }
}
</style>
