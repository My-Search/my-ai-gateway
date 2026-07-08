<template>
  <div class="layout">
    <!-- Sidebar -->
    <Sidebar :isOpen="sidebarOpen" @close="sidebarOpen = false" />

    <!-- Mobile overlay -->
    <div v-if="sidebarOpen" class="sidebar-overlay" @click="sidebarOpen = false"></div>

    <!-- Main content -->
    <div class="main-content">
      <!-- Mobile header -->
      <div class="mobile-header">
        <div class="mobile-header-left">
          <button class="mobile-menu-btn" @click="sidebarOpen = !sidebarOpen">
            <SvgIcon name="menu" :size="20" />
          </button>
          <span class="page-title">{{ currentTitle }}</span>
          <span class="version-badge">v1.0</span>
        </div>
        <div class="mobile-header-right">
          <button class="btn-icon-mobile" @click="toggleTheme" :title="isDark ? t('layout.switchLight') : t('layout.switchDark')">
            <SvgIcon :name="isDark ? 'sun' : 'moon'" :size="18" />
          </button>
          <button class="btn-icon-mobile" @click="toggleLang" :title="t('layout.switchLang')">
            {{ localeStore.locale === 'zh-CN' ? '中' : 'EN' }}
          </button>
          <button class="btn-icon-mobile" @click="handleLogout" :title="t('layout.logout')">
            <SvgIcon name="logout" :size="18" />
          </button>
        </div>
      </div>

      <!-- Top header -->
      <header class="top-header">
        <div class="header-left">
          <div class="page-title">{{ currentTitle }}</div>
          <span class="version-tag">v1.0</span>
        </div>
        <div class="header-right">
          <button class="btn-icon" @click="toggleTheme" :title="isDark ? t('layout.switchLight') : t('layout.switchDark')">
            <SvgIcon :name="isDark ? 'sun' : 'moon'" :size="16" />
          </button>
          <button class="btn-icon" @click="toggleLang" :title="t('layout.switchLang')">
            {{ localeStore.locale === 'zh-CN' ? '中' : 'EN' }}
          </button>
          <button class="btn-logout" @click="handleLogout">
            <SvgIcon name="logout" :size="16" />
            <span>{{ t('layout.logout') }}</span>
          </button>
        </div>
      </header>

      <!-- Content area -->
      <div class="content-area">
        <router-view />
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { useThemeStore } from '@/stores/theme'
import { useI18n } from '@/composables/useI18n'
import { useLocaleStore } from '@/stores/locale'
import Sidebar from './Sidebar.vue'

const route = useRoute()
const router = useRouter()
const sidebarOpen = ref(false)
const themeStore = useThemeStore()
const isDark = themeStore.isDark
const { t } = useI18n()
const localeStore = useLocaleStore()

const currentTitle = computed(() => (route.meta?.title as string) || t('layout.console'))

function toggleTheme() {
  themeStore.toggle()
}

function toggleLang() {
  localeStore.toggle()
}

async function handleLogout() {
  try {
    await authApi.logout()
  } catch {
    // ignore
  }
  // 清除 JWT Token，确保下次 checkAuth 不会因旧 token 再跳回来
  localStorage.removeItem('admin_token')
  router.push('/login')
}
</script>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}

.main-content {
  margin-left: var(--sidebar-width);
  flex: 1;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* Sidebar overlay (mobile) */
.sidebar-overlay {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.6);
  z-index: 99;
}

@media (max-width: 768px) {
  .sidebar-overlay {
    display: block;
  }
}

/* Top header */
.top-header {
  height: var(--header-height);
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 50;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.version-tag {
  font-size: 12px;
  color: var(--text-muted);
  background: var(--bg-tertiary);
  padding: 2px 10px;
  border-radius: 10px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Icon-only button in header */
.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 6px;
  color: var(--text-secondary);
  background: transparent;
  text-decoration: none;
  transition: all 0.15s;
  border: none;
  cursor: pointer;
}

.btn-icon:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.btn-logout {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  background: var(--bg-tertiary);
  text-decoration: none;
  transition: all 0.15s;
  border: none;
  cursor: pointer;
  font-family: inherit;
}

.btn-logout:hover {
  background: color-mix(in srgb, var(--accent-red) 15%, transparent);
  color: var(--accent-red);
}

.content-area {
  flex: 1;
  padding: 24px;
  display: flex;
  flex-direction: column;
}

/* Mobile header */
.mobile-header {
  display: none;
}

.mobile-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mobile-header-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.btn-icon-mobile {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 6px;
  color: var(--text-secondary);
  background: transparent;
  text-decoration: none;
  transition: all 0.15s;
  border: none;
  cursor: pointer;
}

.btn-icon-mobile:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.mobile-header .page-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.mobile-header .version-badge {
  font-size: 11px;
  color: var(--text-muted);
  background: var(--bg-tertiary);
  padding: 1px 8px;
  border-radius: 8px;
  font-weight: 500;
}

.mobile-menu-btn {
  background: none;
  border: none;
  color: var(--text-primary);
  cursor: pointer;
  padding: 4px 8px;
  display: flex;
  align-items: center;
}

.btn-logout-mobile {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 6px;
  color: var(--text-secondary);
  background: var(--bg-tertiary);
  text-decoration: none;
  transition: all 0.15s;
  border: none;
  cursor: pointer;
}

.btn-logout-mobile:hover {
  background: color-mix(in srgb, var(--accent-red) 15%, transparent);
  color: var(--accent-red);
}

@media (max-width: 768px) {
  .main-content {
    margin-left: 0;
  }
  .top-header {
    display: none;
  }
  .mobile-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 16px;
    background: var(--bg-secondary);
    border-bottom: 1px solid var(--border-color);
    position: sticky;
    top: 0;
    z-index: 50;
  }
  .content-area {
    padding: 16px;
  }
}
</style>
