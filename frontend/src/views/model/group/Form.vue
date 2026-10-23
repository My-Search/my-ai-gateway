<template>
  <div class="page-header cfg-page-header">
    <div class="page-header-main">
      <div class="title-row">
        <router-link to="/admin/model/group/list" class="back-link" :title="t('common.back')">
          <SvgIcon name="arrow-left" :size="16" />
        </router-link>
        <h2>{{ isEdit ? t('group.form.editTitle').replace('{name}', form.name || '') : t('group.form.title') }}</h2>
        <span v-if="isEdit" class="badge badge-muted">#{{ route.params.id }}</span>
      </div>
      <p>{{ t('group.form.strategyHint') }}</p>
    </div>
  </div>

  <ConfigShell :sections="sections" :railTitle="t('cfg.nav')">
    <!-- ══ 基础信息 ══ -->
    <ConfigSection id="grp-basic" :title="t('cfg.basic')" :desc="t('group.form.namePlaceholder')" icon="grid">
      <ConfigField :label="t('group.form.name')" required>
        <input v-model.trim="form.name" type="text" class="form-control" required maxlength="100"
               :placeholder="t('group.form.namePlaceholder')" />
      </ConfigField>
    </ConfigSection>

    <!-- ══ 路由方式 ══ -->
    <ConfigSection id="grp-strategy" :title="t('group.form.strategy')" :desc="t('group.form.strategyHint')" icon="rank">
      <ConfigField :label="t('group.form.strategy')" :hint="t('group.form.strategyHint')" :stack="true" hide-label>
        <div class="strategy-grid">
          <button
            v-for="opt in strategyOptions"
            :key="opt.value"
            type="button"
            class="strategy-card"
            :class="{ active: form.strategy === opt.value }"
            @click="form.strategy = opt.value"
          >
            <span class="strategy-top">
              <SvgIcon :name="opt.icon" :size="15" />
              <strong>{{ opt.label }}</strong>
              <span v-if="form.strategy === opt.value" class="strategy-check">
                <SvgIcon name="check-bold" :size="10" />
              </span>
            </span>
            <span class="strategy-desc">{{ opt.desc }}</span>
          </button>
        </div>
      </ConfigField>

      <ConfigField :label="t('group.form.sticky')" :hint="t('group.form.stickyHint')">
        <div class="inline-control">
          <ToggleSwitch v-model="stickyOn" :show-label="false" />
          <span class="inline-hint">{{ stickyOn ? t('common.on') : t('common.off') }}</span>
        </div>
      </ConfigField>
    </ConfigSection>

    <template #footer>
      <span class="footer-hint">
        <SvgIcon name="info" :size="13" /> {{ t('cfg.stickyFooterHint') }}
      </span>
      <div class="footer-actions">
        <router-link to="/admin/model/group/list" class="btn btn-secondary">{{ t('common.cancel') }}</router-link>
        <button type="button" class="btn btn-primary" :disabled="saving || !form.name" @click="save">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </template>
  </ConfigShell>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import { groupApi, type GroupStrategy } from '@/api/group'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import ConfigShell from '@/components/common/ConfigShell.vue'
import ConfigSection from '@/components/common/ConfigSection.vue'
import ConfigField from '@/components/common/ConfigField.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { showToast } = useToast()

const isEdit = computed(() => !!route.params.id)
const saving = ref(false)

const sections = computed(() => [
  { id: 'grp-basic', label: t('cfg.basic'), icon: 'grid' },
  { id: 'grp-strategy', label: t('group.form.strategy'), icon: 'rank' },
])

const strategyOptions = computed(() => [
  { value: 'random' as GroupStrategy, label: t('group.strategy.random'), desc: t('cfg.strategyRandomDesc'), icon: 'zap' },
  { value: 'round_robin' as GroupStrategy, label: t('group.strategy.roundRobin'), desc: t('cfg.strategyRoundRobinDesc'), icon: 'refresh' },
  { value: 'failover' as GroupStrategy, label: t('group.strategy.failover'), desc: t('cfg.strategyFailoverDesc'), icon: 'rank' },
])

const form = ref({
  name: '',
  strategy: 'random' as GroupStrategy,
  // 粘性默认开启：Prompt 缓存亲和是小组的主要收益，编辑已有小组时回填其真实值
  sticky: 1
})
const stickyOn = computed({
  get: () => form.value.sticky === 1,
  set: (v: boolean) => { form.value.sticky = v ? 1 : 0 }
})

onMounted(async () => {
  if (!isEdit.value) return
  try {
    const res = await groupApi.get(Number(route.params.id))
    const g = res.data.group
    form.value = {
      name: g.name,
      strategy: g.strategy || 'random',
      sticky: g.sticky ?? 0
    }
  } catch (e: any) {
    showToast(e.message || t('error.loadFailed'), { type: 'error' })
    router.push('/admin/model/group/list')
  }
})

async function save() {
  if (saving.value || !form.value.name) return
  saving.value = true
  try {
    if (isEdit.value) {
      const res = await groupApi.update(Number(route.params.id), form.value)
      if (res.data.success) {
        showToast(t('group.form.saved'), { type: 'success' })
        router.push('/admin/model/group/' + route.params.id)
      } else {
        showToast(res.data.error || t('error.unknown'), { type: 'error' })
      }
      return
    }
    const res = await groupApi.create(form.value)
    if (res.data.success) {
      showToast(t('group.form.saved'), { type: 'success' })
      router.push(res.data.id ? '/admin/model/group/' + res.data.id : '/admin/model/group/list')
    } else {
      showToast(res.data.error || t('error.unknown'), { type: 'error' })
    }
  } catch (e: any) {
    showToast(e.message || t('error.unknown'), { type: 'error' })
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

@media (max-width: 768px) {
  .strategy-grid {
    grid-template-columns: 1fr;
  }
}
</style>
