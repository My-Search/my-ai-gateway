<template>
  <aside class="sidebar" :class="{ 'sidebar-open': isOpen }">
    <div class="sidebar-header">
      <div class="sidebar-logo">
        <div class="logo-icon">
          <img src="/favicon.svg" alt="logo" />
        </div>
        <div class="logo-text">
          <h1>My AI Gateway</h1>
          <div class="subtitle">{{ t('nav.subtitle') }}</div>
        </div>
      </div>
    </div>
    <nav class="sidebar-nav">
      <div class="nav-section">{{ t('nav.overview') }}</div>
      <router-link to="/admin/dashboard" @click="$emit('close')" class="nav-link">
        <SvgIcon name="home" :size="16" class="nav-icon" />
        <span>{{ t('nav.dashboard') }}</span>
      </router-link>

      <div class="nav-section">{{ t('nav.resources') }}</div>
      <router-link to="/admin/channel/list" @click="$emit('close')" class="nav-link">
        <SvgIcon name="channel" :size="16" class="nav-icon" />
        <span>{{ t('nav.channels') }}</span>
      </router-link>
      <router-link to="/admin/model/list" @click="$emit('close')" class="nav-link">
        <SvgIcon name="model" :size="16" class="nav-icon" />
        <span>{{ t('nav.models') }}</span>
      </router-link>
      <router-link to="/admin/apikey/list" @click="$emit('close')" class="nav-link">
        <SvgIcon name="key" :size="16" class="nav-icon" />
        <span>{{ t('nav.apiKeys') }}</span>
      </router-link>

      <div class="nav-section">{{ t('nav.monitoring') }}</div>
      <router-link to="/admin/log/list" @click="$emit('close')" class="nav-link">
        <SvgIcon name="log" :size="16" class="nav-icon" />
        <span>{{ t('nav.logs') }}</span>
      </router-link>

      <div class="nav-section">{{ t('nav.tools') }}</div>
      <router-link to="/admin/playground" @click="$emit('close')" class="nav-link">
        <SvgIcon name="chat" :size="16" class="nav-icon" />
        <span>{{ t('nav.playground') }}</span>
      </router-link>
      <router-link to="/admin/setting/system" @click="$emit('close')" class="nav-link">
        <SvgIcon name="settings" :size="16" class="nav-icon" />
        <span>{{ t('nav.systemConfig') }}</span>
      </router-link>
    </nav>

    <div class="sidebar-footer">
      <div class="sidebar-footer-info">
        <span class="status-dot active"></span>
        <span class="footer-text">v1.0</span>
      </div>
    </div>
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
  display: flex;
  flex-direction: column;
}

/* ── Sidebar Header (Modern logo area) ── */
.sidebar-header {
  padding: 20px;
  border-bottom: 1px solid var(--border-color);
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md, 12px);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}

.logo-icon img {
  width: 36px;
  height: 36px;
}

.logo-text {
  min-width: 0;
}

.sidebar-header h1 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.3;
  letter-spacing: -0.02em;
}

.sidebar-header .subtitle {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 1px;
  font-weight: 500;
}

/* ── Navigation ── */
.sidebar-nav {
  flex: 1;
  padding: 8px 0;
  overflow-y: auto;
}

.sidebar-nav .nav-section {
  padding: 14px 20px 6px;
  font-size: 10px;
  text-transform: uppercase;
  color: var(--text-muted);
  font-weight: 700;
  letter-spacing: 0.08em;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  margin: 1px 8px;
  color: var(--text-secondary);
  font-size: 14px;
  transition: all 0.15s ease;
  border-radius: var(--radius);
  text-decoration: none;
}

.nav-link:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.nav-link.router-link-exact-active,
.nav-link.router-link-active {
  background: color-mix(in srgb, var(--accent-blue) 10%, transparent);
  color: var(--accent-blue);
  font-weight: 500;
}

.nav-icon {
  opacity: 0.8;
  flex-shrink: 0;
  line-height: 1;
}

.nav-link.router-link-exact-active .nav-icon,
.nav-link.router-link-active .nav-icon {
  opacity: 1;
}

/* ── Sidebar Footer ── */
.sidebar-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--border-color);
}

.sidebar-footer-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.footer-text {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 500;
}

/* ── Mobile responsive ── */
@media (max-width: 768px) {
  .sidebar {
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: none;
  }
  .sidebar.sidebar-open {
    transform: translateX(0);
    box-shadow: 4px 0 24px rgba(0,0,0,0.3);
  }
}
</style>
