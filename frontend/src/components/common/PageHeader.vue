<template>
  <div class="page-header">
    <div class="page-header-main">
      <h2>{{ title }}</h2>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div class="header-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 页面头部：标题 + 副标题 + 右侧操作区。
 * 用于列表页 / 仪表盘等非配置页面的统一页头。
 */
withDefaults(defineProps<{
  title: string
  subtitle?: string
}>(), {
  subtitle: ''
})
</script>

<style scoped>
.page-header-main {
  min-width: 0;
}
</style>
