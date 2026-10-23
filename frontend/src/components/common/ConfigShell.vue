<template>
  <div class="cfg-layout">
    <!-- ── Anchor rail ── -->
    <aside class="cfg-rail" :aria-label="railLabel">
      <div v-if="railTitle" class="cfg-rail-title">{{ railTitle }}</div>
      <button
        v-for="s in sections"
        :key="s.id"
        type="button"
        class="cfg-rail-link"
        :class="{ active: activeId === s.id }"
        :aria-current="activeId === s.id ? 'true' : undefined"
        @click="scrollTo(s.id)"
      >
        <SvgIcon v-if="s.icon" :name="s.icon" :size="14" class="rail-icon" />
        <span class="rail-text">{{ s.label }}</span>
      </button>
      <div v-if="$slots.railFooter" class="cfg-rail-footer">
        <slot name="railFooter" />
      </div>
    </aside>

    <!-- ── Content column ── -->
    <div class="cfg-main">
      <slot />
      <div v-if="$slots.footer" class="cfg-footer">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 方案配置外壳：左侧分区锚点导航 + 右侧分区卡片内容。
 *
 * 用法：
 *   <ConfigShell :sections="[{ id: 'basic', label: '基础信息', icon: 'channel' }]">
 *     <ConfigSection id="basic" title="基础信息" icon="channel">…</ConfigSection>
 *     <template #footer>…保存按钮…</template>
 *   </ConfigShell>
 *
 * 锚点滚动基于 window 滚动容器（Layout 的 .content-area 不独立滚动），
 * 定位偏移由 .cfg-section 的 scroll-margin-top 统一控制。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SvgIcon from '@/components/common/SvgIcon.vue'

export interface ConfigSectionMeta {
  id: string
  label: string
  icon?: string
}

const props = withDefaults(defineProps<{
  sections: ConfigSectionMeta[]
  railTitle?: string
  railLabel?: string
}>(), {
  railTitle: '',
  railLabel: '配置分区导航'
})

const activeId = ref<string>(props.sections[0]?.id ?? '')

/**
 * 判定线：视口高度的 40%（不低于 132px）。
 * 取“分区顶部是否越过判定线”，而不是“是否贴住视口顶部”——
 * 否则短页面里靠后的分区永远滚不到顶部，锚点永远亮不起来。
 */
const READING_LINE_RATIO = 0.4
const MIN_READING_LINE = 132
/** 页面可滚动量小于该值视为无需滚动，不做比例压缩 */
const MIN_SCROLLABLE = 8

function scrollTo(id: string) {
  const el = document.getElementById(id)
  if (!el) return
  activeId.value = id
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

let ticking = false

function updateActive() {
  ticking = false
  const items = props.sections
    .map(s => ({ id: s.id, el: document.getElementById(s.id) }))
    .filter((x): x is { id: string; el: HTMLElement } => x.el !== null)
  if (!items.length) return

  const lineY = Math.max(window.innerHeight * READING_LINE_RATIO, MIN_READING_LINE)
  const maxScroll = document.documentElement.scrollHeight - window.innerHeight
  // 整页放得下、压根无法滚动时，视为停留在页首 → 高亮第一个分区
  if (maxScroll <= MIN_SCROLLABLE) {
    activeId.value = items[0].id
    return
  }

  // 每个分区“应当被点亮”的滚动位置（分区文档偏移 - 判定线）
  const triggers = items.map(({ el }) =>
    Math.max(0, el.getBoundingClientRect().top + window.scrollY - lineY))

  // 末尾分区所需的滚动量常常超过页面的最大滚动量（页面不够长），
  // 此时把全部激活点按比例压缩到可滚动区间，保证每个分区都能轮到高亮。
  const overflow = triggers[triggers.length - 1]
  const scale = overflow > maxScroll ? maxScroll / overflow : 1

  let current = items[0].id
  triggers.forEach((trigger, i) => {
    if (window.scrollY >= trigger * scale - 1) current = items[i].id
  })
  activeId.value = current
}

function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(updateActive)
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll, { passive: true })
  updateActive()
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
})

const railTitle = computed(() => props.railTitle)
defineExpose({ activeId, scrollTo })
</script>

<style scoped>
/* .cfg-* 静态样式在 assets/styles/index.css 中统一定义 */
.rail-text {
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
