<template>
  <section :id="id" class="cfg-section">
    <header class="cfg-section-head">
      <span v-if="icon" class="cfg-section-icon">
        <SvgIcon :name="icon" :size="16" />
      </span>
      <div class="cfg-section-heading">
        <h3 class="cfg-section-title">
          <slot name="title">{{ title }}</slot>
          <slot name="badge" />
        </h3>
        <p v-if="desc || $slots.desc" class="cfg-section-desc">
          <slot name="desc">{{ desc }}</slot>
        </p>
      </div>
      <div v-if="$slots.actions" class="cfg-section-actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="cfg-section-body">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 配置分区卡片：标题 + 说明 + 操作区 + 内容区。
 * 与 ConfigShell 的锚点 id 一一对应（传入相同的 id）。
 */
import SvgIcon from '@/components/common/SvgIcon.vue'

withDefaults(defineProps<{
  id: string
  title?: string
  desc?: string
  icon?: string
}>(), {
  title: '',
  desc: '',
  icon: ''
})
</script>

<style scoped>
.cfg-section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

@media (max-width: 768px) {
  .cfg-section-actions {
    width: 100%;
    margin-left: 0;
    flex-wrap: wrap;
  }
}
</style>
