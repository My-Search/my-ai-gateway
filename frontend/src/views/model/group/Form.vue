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

  <!-- 编辑态先显示行内加载态，避免空表单闪现 -->
  <div v-if="loading" class="page-loading">
    <LoadingSpinner :size="18" :text="t('common.loading')" />
  </div>

  <ConfigShell v-else :sections="sections" :railTitle="t('cfg.nav')">
    <!-- ══ 基础信息 ══ -->
    <ConfigSection id="grp-basic" :title="t('cfg.basic')" :desc="t('group.form.namePlaceholder')" icon="grid">
      <ConfigField :label="t('group.form.name')" required>
        <input v-model.trim="form.name" type="text" class="form-control" required maxlength="100"
               :placeholder="t('group.form.namePlaceholder')" />
      </ConfigField>
    </ConfigSection>

    <!-- ══ 小组类型 ══ -->
    <ConfigSection id="grp-type" :title="t('group.form.type')" :desc="t('group.form.typeHint')" icon="grid">
      <ConfigField :label="t('group.form.type')" :stack="true" hide-label>
        <div class="strategy-grid">
          <button
            v-for="opt in typeOptions"
            :key="opt.value"
            type="button"
            class="strategy-card"
            :class="{ active: form.type === opt.value }"
            @click="form.type = opt.value"
          >
            <span class="strategy-top">
              <SvgIcon :name="opt.icon" :size="15" />
              <strong>{{ opt.label }}</strong>
              <span v-if="form.type === opt.value" class="strategy-check">
                <SvgIcon name="check-bold" :size="10" />
              </span>
            </span>
            <span class="strategy-desc">{{ opt.desc }}</span>
          </button>
        </div>
        <!-- 编辑态切换类型时提示互转后果（新建态 typeHint 已说明，无需重复） -->
        <p v-if="typeChangeTip" class="type-change-tip">{{ typeChangeTip }}</p>
      </ConfigField>
      <!-- 动态小组：匹配规则（含写法提示） -->
      <ConfigField v-if="form.type === 'rule'" :label="t('model.rels.rulePattern')" :stack="true">
        <div class="pattern-row" :class="{ 'pattern-flash': flashPattern }">
          <input ref="patternInputRef" v-model.trim="form.pattern" type="text" class="form-control" required
                 :placeholder="t('model.rels.rulePatternPlaceholder')" :title="t('model.rels.ruleHint')" />
          <RuleSyntaxHint />
        </div>
        <p class="pattern-hint">{{ t('group.form.patternHint') }}</p>
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
    </ConfigSection>

    <template #footer>
      <span class="footer-hint">
        <SvgIcon name="info" :size="13" /> {{ t('cfg.stickyFooterHint') }}
      </span>
      <div class="footer-actions">
        <router-link to="/admin/model/group/list" class="btn btn-secondary">{{ t('common.cancel') }}</router-link>
        <button type="button" class="btn btn-primary" :disabled="saving || !canSave" @click="save">
          <SvgIcon name="check" :size="14" /> {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </template>
  </ConfigShell>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useToast } from '@/composables/useToast'
import { groupApi, type GroupStrategy } from '@/api/group'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ConfigShell from '@/components/common/ConfigShell.vue'
import ConfigSection from '@/components/common/ConfigSection.vue'
import ConfigField from '@/components/common/ConfigField.vue'
import RuleSyntaxHint from '@/components/common/RuleSyntaxHint.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { showToast } = useToast()

const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
// 编辑态初始即加载中（数据到达前显示骨架），新建态直接渲染表单
const loading = ref(isEdit.value)

const sections = computed(() => [
  { id: 'grp-basic', label: t('cfg.basic'), icon: 'grid' },
  { id: 'grp-type', label: t('group.form.type'), icon: 'grid' },
  { id: 'grp-strategy', label: t('group.form.strategy'), icon: 'rank' },
])

const typeOptions = computed(() => [
  { value: 'manual' as const, label: t('group.type.manual'), desc: t('group.type.manualDesc'), icon: 'list' },
  { value: 'rule' as const, label: t('group.type.rule'), desc: t('group.type.ruleDesc'), icon: 'link' },
])

const strategyOptions = computed(() => [
  { value: 'sticky' as GroupStrategy, label: t('group.strategy.sticky'), desc: t('cfg.strategyStickyDesc'), icon: 'zap' },
  { value: 'failover' as GroupStrategy, label: t('group.strategy.failover'), desc: t('cfg.strategyFailoverDesc'), icon: 'rank' },
])

const form = ref({
  name: '',
  // 类型：manual 手动添加成员 / rule 动态匹配渠道模型（成员跟随渠道配置更新）
  type: 'manual' as 'manual' | 'rule',
  pattern: '',
  // 粘性默认：按成员权重一致性哈希把同一会话固定到同一成员，命中上游 prompt cache
  strategy: 'sticky' as GroupStrategy
})

/** 保存按钮可用性：动态小组还需要有效表达式 */
const canSave = computed(() => !!form.value.name && (form.value.type !== 'rule' || !!form.value.pattern.trim()))

// 加载时的原始类型（新建态为空）：编辑态切换类型时用于显示互转后果提示
const originalType = ref<'' | 'manual' | 'rule'>('')

// ?focus=rule 跳转定位：滚动到类型区并闪烁高亮匹配规则输入框
const patternInputRef = ref<HTMLInputElement | null>(null)
const flashPattern = ref(false)

/** 编辑态类型互转提示：转动态 → 成员将按规则重建；转手动 → 成员保留、规则休眠。 */
const typeChangeTip = computed(() => {
  if (!isEdit.value || !originalType.value || form.value.type === originalType.value) return ''
  return form.value.type === 'rule'
    ? t('group.form.switchToRuleTip')
    : t('group.form.switchToManualTip')
})

onMounted(async () => {
  if (!isEdit.value) return
  try {
    const res = await groupApi.get(Number(route.params.id))
    const g = res.data.group
    form.value = {
      name: g.name,
      type: g.type === 'rule' ? 'rule' : 'manual',
      // 手动小组的 pattern 是休眠值：保留加载，切回动态小组时自动恢复
      pattern: g.pattern || '',
      // 历史策略值（random / round_robin）已由数据库迁移统一为粘性。
      strategy: (g.strategy === 'failover' ? 'failover' : 'sticky') as GroupStrategy
    }
    originalType.value = form.value.type
  } catch (e: any) {
    showToast(e.message || t('error.loadFailed'), { type: 'error' })
    router.push('/admin/model/group/list')
    return
  } finally {
    loading.value = false
  }
  // 详情页点击匹配规则跳转过来（?focus=rule）：加载完成、类型区渲染后，
  // 定位到类型区并聚焦规则输入框，闪烁提示目标位置。
  if (route.query.focus === 'rule' && form.value.type === 'rule') {
    await nextTick()
    document.getElementById('grp-type')?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
    flashPattern.value = true
    patternInputRef.value?.focus()
    window.setTimeout(() => { flashPattern.value = false }, 2400)
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
/* 动态小组的匹配规则输入行：输入框 + 写法提示图标 */
.pattern-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pattern-row .form-control {
  flex: 1;
}

.pattern-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

/* 编辑态类型互转的后果提示：与匹配规则的 pattern-hint 同级弱化样式 */
.type-change-tip {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
}

/* ?focus=rule 跳转定位的闪烁高亮：两轮柔和的描边脉冲，2.4s 后由脚本移除 */
.pattern-flash {
  animation: pattern-flash 1.2s ease-in-out 2;
}

@keyframes pattern-flash {
  0%, 100% { box-shadow: none; }
  50% { box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 25%, transparent); }
}

.pattern-flash .form-control {
  border-color: var(--accent-blue);
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
