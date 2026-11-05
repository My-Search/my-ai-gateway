<template>
  <span class="rule-syntax-hint" @click.stop>
    <button
      type="button"
      class="rule-syntax-trigger"
      :title="t('model.rels.ruleSyntaxTitle')"
      :aria-label="t('model.rels.ruleSyntaxTitle')"
      :aria-expanded="visible"
      @click="visible = !visible"
    >
      <SvgIcon name="question" :size="13" />
    </button>
    <div v-if="visible" class="rule-syntax-pop" @click.stop>
      <div class="rule-syntax-title">{{ t('model.rels.ruleSyntaxTitle') }}</div>
      <div v-for="ex in examples" :key="ex.expr" class="rule-syntax-item">
        <code class="rule-syntax-expr">{{ ex.expr }}</code>
        <div class="rule-syntax-desc">{{ ex.desc }}</div>
        <div class="rule-syntax-hit">{{ ex.hit }}</div>
      </div>
      <div class="rule-syntax-note">{{ t('model.rels.ruleSyntaxNote') }}</div>
    </div>
  </span>
</template>

<script setup lang="ts">
/**
 * 匹配规则写法提示：疑问图标 + 点击展开的示例气泡。
 * 示例按实际渠道配置里的「模型名 (渠道名)」来写，避免用户照抄后匹配不到任何模型。
 * 用在入口模型关联页的「规则匹配」添加栏与规则编辑弹框。
 */
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from '@/composables/useI18n'
import SvgIcon from './SvgIcon.vue'

const { t } = useI18n()
const visible = ref(false)

const examples = computed(() => [
  {
    expr: 'deepseek flash',
    desc: t('model.rels.ruleSyntaxPlain'),
    hit: t('model.rels.ruleSyntaxPlainHit')
  },
  {
    expr: '/gpt-4o|claude/',
    desc: t('model.rels.ruleSyntaxRegex'),
    hit: t('model.rels.ruleSyntaxRegexHit')
  },
  {
    expr: '/workbuddy|trae/ deepseek',
    desc: t('model.rels.ruleSyntaxMixed'),
    hit: t('model.rels.ruleSyntaxMixedHit')
  }
])

function onDocumentClick() {
  visible.value = false
}

onMounted(() => document.addEventListener('click', onDocumentClick))
onUnmounted(() => document.removeEventListener('click', onDocumentClick))
</script>

<style scoped>
.rule-syntax-hint {
  position: relative;
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
}

.rule-syntax-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  border-radius: 50%;
  cursor: pointer;
  line-height: 1;
  transition: color 0.15s ease, background-color 0.15s ease;
}

.rule-syntax-trigger:hover,
.rule-syntax-trigger[aria-expanded='true'] {
  color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 14%, transparent);
}

.rule-syntax-trigger:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-blue) 50%, transparent);
}

.rule-syntax-pop {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 10010;
  width: 380px;
  max-width: calc(100vw - 32px);
  padding: 12px 14px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-strong, var(--border-color));
  border-radius: var(--radius);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.4);
  text-align: left;
  white-space: normal;
}

.rule-syntax-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 10px;
}

/* 每条示例纵向排布：表达式一行、说明与命中样例各一行，长表达式不会被挤压 */
.rule-syntax-item {
  margin-bottom: 10px;
}

.rule-syntax-expr {
  display: inline-block;
  margin-bottom: 4px;
  padding: 3px 8px;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--accent-blue);
  background: var(--bg-tertiary);
  border-radius: var(--radius-sm);
  word-break: break-all;
}

.rule-syntax-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}

.rule-syntax-hit {
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-muted);
  word-break: break-all;
}

.rule-syntax-note {
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px solid var(--border-color);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-muted);
}
</style>
