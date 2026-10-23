<template>
  <aside class="sidebar" :class="{ 'sidebar-open': isOpen }">
    <!-- Brand -->
    <div class="sidebar-header">
      <router-link to="/admin/dashboard" class="brand" @click="$emit('close')">
        <span class="brand-mark">
          <img src="/favicon.svg" alt="" />
        </span>
        <span class="brand-text">
          <strong>My AI Gateway</strong>
          <small>{{ t('nav.subtitle') }}</small>
        </span>
      </router-link>
    </div>

    <!-- Navigation -->
    <nav class="sidebar-nav">
      <div v-for="group in navGroups" :key="group.label" class="nav-group">
        <div class="nav-section">{{ t(group.label) }}</div>
        <router-link
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          @click="$emit('close')"
        >
          <span class="nav-icon-wrap">
            <SvgIcon :name="item.icon" :size="16" class="nav-icon" />
          </span>
          <span class="nav-label">{{ t(item.label) }}</span>
        </router-link>
      </div>
    </nav>

    <!-- Footer -->
    <div class="sidebar-footer">
      <span class="status-dot active"></span>
      <span class="footer-text">v1.0</span>
      <span class="footer-sep">·</span>
      <span class="footer-text">{{ t('login.serviceOnline') }}</span>
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

/**
 * 导航分组。结构与原有菜单保持一致，只按使用频率重新归组：
 * 总览 / 接入（渠道、模型、小组、密钥）/ 可观测（日志）/ 工具（测试、配置）
 */
const navGroups = [
  {
    label: 'nav.overview',
    items: [{ to: '/admin/dashboard', label: 'nav.dashboard', icon: 'home' }],
  },
  {
    label: 'nav.resources',
    items: [
      { to: '/admin/channel/list', label: 'nav.channels', icon: 'channel' },
      { to: '/admin/model/group/list', label: 'nav.modelGroups', icon: 'grid' },
      { to: '/admin/model/list', label: 'nav.models', icon: 'model' },
      { to: '/admin/apikey/list', label: 'nav.apiKeys', icon: 'key' },
    ],
  },
  {
    label: 'nav.monitoring',
    items: [{ to: '/admin/log/list', label: 'nav.logs', icon: 'log' }],
  },
  {
    label: 'nav.tools',
    items: [
      { to: '/admin/playground', label: 'nav.playground', icon: 'chat' },
      { to: '/admin/setting/system', label: 'nav.systemConfig', icon: 'settings' },
    ],
  },
]
</script>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border-color);
  position: fixed;
  top: 0;
  left: 0;
  height: 100vh;
  z-index: 100;
  display: flex;
  flex-direction: column;
}

/* ── Brand ── */
.sidebar-header {
  padding: 16px 14px 14px;
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 6px 8px;
  border-radius: var(--radius);
  transition: background var(--dur-1);
}

.brand:hover {
  background: var(--bg-hover);
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  box-shadow: var(--shadow-sm);
}

.brand-mark img {
  width: 22px;
  height: 22px;
}

.brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}

.brand-text strong {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.brand-text small {
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 500;
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── Navigation ── */
.sidebar-nav {
  flex: 1;
  padding: 4px 10px 12px;
  overflow-y: auto;
  overflow-x: hidden;
}

.nav-group + .nav-group {
  margin-top: 4px;
}

.sidebar-nav .nav-section {
  padding: 14px 10px 6px;
  font-size: 10px;
  text-transform: uppercase;
  color: var(--text-muted);
  font-weight: 700;
  letter-spacing: 0.085em;
  opacity: 0.9;
}

.nav-link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  margin-bottom: 1px;
  color: var(--text-secondary);
  font-size: 13.5px;
  font-weight: 500;
  transition: background var(--dur-1) var(--ease-out), color var(--dur-1) var(--ease-out);
  border-radius: var(--radius);
  text-decoration: none;
}

.nav-link:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.nav-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 7px;
  flex-shrink: 0;
  transition: background var(--dur-1), color var(--dur-1);
}

.nav-icon {
  opacity: 0.72;
}

.nav-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-badge {
  margin-left: auto;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: var(--radius-full);
  background: var(--bg-tertiary);
  color: var(--text-muted);
}

.nav-link.router-link-exact-active,
.nav-link.router-link-active {
  background: var(--sidebar-active-bg);
  color: var(--accent-blue);
  font-weight: 620;
}

.nav-link.router-link-exact-active .nav-icon,
.nav-link.router-link-active .nav-icon {
  opacity: 1;
}

.nav-link.router-link-exact-active .nav-icon-wrap,
.nav-link.router-link-active .nav-icon-wrap {
  background: color-mix(in srgb, var(--accent-blue) 14%, transparent);
}

.nav-link.router-link-exact-active::before,
.nav-link.router-link-active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: var(--accent-blue);
}

/* ── Footer ── */
.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 20px 14px;
  border-top: 1px solid var(--border-color);
  flex-shrink: 0;
}

.footer-text {
  font-size: 11.5px;
  color: var(--text-muted);
  font-weight: 500;
}

.footer-sep {
  font-size: 11px;
  color: var(--text-muted);
  opacity: 0.6;
}

/* ── Mobile ── */
@media (max-width: 768px) {
  .sidebar {
    transform: translateX(-100%);
    transition: transform 0.26s var(--ease-out);
    box-shadow: none;
    z-index: 101;
  }
  .sidebar.sidebar-open {
    transform: translateX(0);
    box-shadow: 24px 0 60px -20px rgba(0, 0, 0, 0.45);
  }
}
</style>
