<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? t('group.form.editTitle').replace('{name}', form.name || '') : t('group.form.title') }}</div>
      <router-link to="/admin/model/group/list" class="btn btn-secondary">
        <SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}
      </router-link>
    </div>

    <form class="form-area" @submit.prevent="save">
      <div class="form-group">
        <label>{{ t('group.form.name') }}</label>
        <input v-model.trim="form.name" type="text" class="form-control" required maxlength="100"
               :placeholder="t('group.form.namePlaceholder')" />
      </div>

      <div class="form-group">
        <label>{{ t('group.form.strategy') }}</label>
        <select v-model="form.strategy" class="form-control" style="max-width:320px;">
          <option value="random">{{ t('group.strategy.random') }}</option>
          <option value="round_robin">{{ t('group.strategy.roundRobin') }}</option>
          <option value="failover">{{ t('group.strategy.failover') }}</option>
        </select>
        <div class="form-hint">{{ t('group.form.strategyHint') }}</div>
      </div>

      <div class="form-group">
        <div class="toggle-row">
          <ToggleSwitch v-model="stickyOn" :show-label="false" />
          <div>
            <div class="toggle-label">{{ t('group.form.sticky') }}</div>
            <div class="form-hint">{{ t('group.form.stickyHint') }}</div>
          </div>
        </div>
      </div>

      <div class="form-actions">
        <button type="submit" class="btn btn-primary" :disabled="saving || !form.name">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
        </button>
        <router-link to="/admin/model/group/list" class="btn btn-secondary">{{ t('common.cancel') }}</router-link>
      </div>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import { groupApi, type GroupStrategy } from '@/api/group'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { showToast } = useToast()

const isEdit = computed(() => !!route.params.id)
const saving = ref(false)

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
.form-area { padding: 16px; max-width: 560px; display: flex; flex-direction: column; gap: 14px; }
.form-actions { display: flex; gap: 10px; }
.toggle-row { display: flex; align-items: center; gap: 12px; }
.toggle-label { font-weight: 500; }
</style>
