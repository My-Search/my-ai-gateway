<template>
  <aside class="sidebar" :class="{ 'sidebar-open': isOpen }">
    <div class="sidebar-header">
      <h1>My AI Gateway</h1>
      <div class="subtitle">{{ t('nav.subtitle') }}</div>
    </div>
    <nav class="sidebar-nav">
      <div class="nav-section">{{ t('nav.overview') }}</div>
      <router-link to="/admin/dashboard" @click="$emit('close')">
        <SvgIcon name="home" :size="16" class="nav-icon" /> {{ t('nav.dashboard') }}
      </router-link>

      <div class="nav-section">{{ t('nav.resources') }}</div>
      <router-link to="/admin/channel/list" @click="$emit('close')">
        <SvgIcon name="channel" :size="16" class="nav-icon" /> {{ t('nav.channels') }}
      </router-link>
      <router-link to="/admin/model/list" @click="$emit('close')">
        <SvgIcon name="model" :size="16" class="nav-icon" /> {{ t('nav.models') }}
      </router-link>
      <router-link to="/admin/apikey/list" @click="$emit('close')">
        <SvgIcon name="key" :size="16" class="nav-icon" /> {{ t('nav.apiKeys') }}
      </router-link>

      <div class="nav-section">{{ t('nav.monitoring') }}</div>
      <router-link to="/admin/log/list" @click="$emit('close')">
        <SvgIcon name="log" :size="16" class="nav-icon" /> {{ t('nav.logs') }}
      </router-link>

      <div class="nav-section">{{ t('nav.tools') }}</div>
      <router-link to="/admin/playground" @click="$emit('close')">
        <SvgIcon name="chat" :size="16" class="nav-icon" /> {{ t('nav.playground') }}
      </router-link>
    </nav>

  </aside>
</template>

<script setup lang="ts">
import { useI18n } from '@/composables/useI18n'

defineProps<{
  isOpen: boolean
}>()

defineEmits<{
  close: []
}>()

const { t } = useI18n()
</script>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  background: var(--bg-secondary);
  border-right: 1px solid var(--border-color);
  position: fixed;
  top: 0;
  left: 0;
  height: 100vh;
  overflow-y: auto;
  z-index: 100;
}

.sidebar-header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
}

.sidebar-header h1 {
  font-size: 18px;
  font-weight: 600;
  color: var(--accent-blue);
}

.sidebar-header .subtitle {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.sidebar-nav {
  padding: 8px 0;
}

.sidebar-nav .nav-section {
  padding: 12px 20px 6px;
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-muted);
  font-weight: 600;
  letter-spacing: 0.05em;
  border-top: 1px solid var(--border-color);
  margin-top: 6px;
}

.sidebar-nav .nav-section:first-child {
  border-top: none;
  margin-top: 0;
}

.sidebar-nav a {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 20px;
  color: var(--text-secondary);
  font-size: 14px;
  transition: all 0.15s;
  border-right: 3px solid transparent;
}

.sidebar-nav a:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.sidebar-nav a.router-link-exact-active,
.sidebar-nav a.router-link-active {
  background: color-mix(in srgb, var(--accent-blue) 8%, transparent);
  color: var(--accent-blue);
  border-right-color: var(--accent-blue);
  font-weight: 500;
}

.nav-icon {
  opacity: 0.85;
  flex-shrink: 0;
  line-height: 1;
}

@media (max-width: 768px) {
  .sidebar {
    transform: translateX(-100%);
    transition: transform 0.25s ease;
  }
  .sidebar.sidebar-open {
    transform: translateX(0);
  }
}
</style>
