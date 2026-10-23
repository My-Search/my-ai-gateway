<template>
  <div class="layout">
    <!-- Sidebar -->
    <Sidebar :isOpen="sidebarOpen" @close="sidebarOpen = false" />

    <!-- Mobile overlay -->
    <Transition name="overlay">
      <div v-if="sidebarOpen" class="sidebar-overlay" @click="sidebarOpen = false"></div>
    </Transition>

    <!-- Main content -->
    <div class="main-content">
      <!-- Mobile header -->
      <div class="mobile-header">
        <div class="mobile-header-left">
          <button class="mobile-menu-btn" @click="sidebarOpen = !sidebarOpen" :aria-label="t('layout.console')">
            <SvgIcon name="menu" :size="20" />
          </button>
          <span class="mobile-title">{{ currentTitle }}</span>
        </div>
        <div class="mobile-header-right">
          <button class="btn-icon-mobile" @click="toggleTheme" :title="isDark ? t('layout.switchLight') : t('layout.switchDark')">
            <SvgIcon :name="isDark ? 'sun' : 'moon'" :size="18" />
          </button>
          <button class="btn-icon-mobile lang-btn" @click="toggleLang" :title="t('layout.switchLang')">
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
          <span class="crumb-root">{{ t('layout.console') }}</span>
          <SvgIcon name="chevron-down" :size="12" class="crumb-sep" />
          <span class="crumb-current">{{ currentTitle }}</span>
        </div>
        <div class="header-right">
          <button class="btn-icon" @click="toggleTheme" :title="isDark ? t('layout.switchLight') : t('layout.switchDark')" :aria-label="isDark ? t('layout.switchLight') : t('layout.switchDark')">
            <SvgIcon :name="isDark ? 'sun' : 'moon'" :size="16" />
          </button>
          <button class="lang-chip" @click="toggleLang" :title="t('layout.switchLang')">
            {{ localeStore.locale === 'zh-CN' ? '中' : 'EN' }}
          </button>
          <span class="header-divider"></span>
          <button class="btn-logout" @click="handleLogout">
            <SvgIcon name="logout" :size="15" />
            <span>{{ t('layout.logout') }}</span>
          </button>
        </div>
      </header>

      <!-- Content area -->
      <div class="content-area">
        <!-- 仅缓存主菜单页面（切换流畅、保留状态）；参数页靠 :key 按路径强制重挂载，切换 id 时重新加载 -->
        <keep-alive :include="cachedViews">
          <router-view :key="$route.path" />
        </keep-alive>
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
import { useRoutePrefetch } from '@/composables/useRoutePrefetch'
import Sidebar from './Sidebar.vue'

/**
 * 需要 keep-alive 缓存的主菜单页面组件名（与各视图 defineOptions name 对应）。
 * 仅缓存轮询轻量、状态值得保留的列表/仪表盘页；带 SSE 或长连接的重型页面（日志）不缓存。
 *
 * ModelGroupList 与其它列表页同构（onMounted 首载 + onActivated 回访刷新），
 * 一并缓存后，从菜单切回小组列表能立即显示上次内容，再由 onActivated 静默刷新。
 */
const cachedViews = ['Dashboard', 'ChannelList', 'ModelList', 'ModelGroupList', 'ApikeyList']

// 进入后台后空闲预取各菜单页 chunk，让"点菜单立即显示页面"成立
useRoutePrefetch()

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
  min-width: 0;
}

/* Sidebar overlay (mobile) */
.sidebar-overlay {
  position: fixed;
  inset: 0;
  background: rgba(6, 8, 12, 0.55);
  backdrop-filter: blur(2px);
  z-index: 100;
}

.overlay-enter-active,
.overlay-leave-active {
  transition: opacity 0.22s ease;
}

.overlay-enter-from,
.overlay-leave-to {
  opacity: 0;
}

/* ── Top Header ── */
.top-header {
  height: var(--header-height);
  background: color-mix(in srgb, var(--bg-primary) 84%, transparent);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  position: sticky;
  top: 0;
  z-index: 50;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.crumb-root {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-muted);
  white-space: nowrap;
}

.crumb-sep {
  color: var(--text-muted);
  opacity: 0.5;
  transform: rotate(-90deg);
  flex-shrink: 0;
}

.crumb-current {
  font-size: 13px;
  font-weight: 620;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius);
  color: var(--text-secondary);
  background: transparent;
  transition: background var(--dur-1), color var(--dur-1);
  border: none;
  cursor: pointer;
}

.btn-icon:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.lang-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  height: 26px;
  padding: 0 8px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border-strong);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: 11.5px;
  font-weight: 650;
  font-family: inherit;
  cursor: pointer;
  transition: background var(--dur-1), color var(--dur-1), border-color var(--dur-1);
}

.lang-chip:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
  border-color: color-mix(in srgb, var(--text-muted) 45%, var(--border-strong));
}

.header-divider {
  width: 1px;
  height: 18px;
  background: var(--border-color);
  margin: 0 4px;
}

.btn-logout {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 11px;
  border-radius: var(--radius);
  font-size: 12.5px;
  font-weight: 550;
  color: var(--text-secondary);
  background: transparent;
  transition: background var(--dur-1), color var(--dur-1);
  border: 1px solid transparent;
  cursor: pointer;
  font-family: inherit;
}

.btn-logout:hover {
  background: color-mix(in srgb, var(--accent-red) 10%, transparent);
  color: var(--accent-red);
}

/* ── Mobile header ── */
.mobile-header {
  display: none;
}

.mobile-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.mobile-title {
  font-size: 14px;
  font-weight: 650;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mobile-header-right {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.btn-icon-mobile {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius);
  color: var(--text-secondary);
  background: transparent;
  transition: background var(--dur-1), color var(--dur-1);
  border: none;
  cursor: pointer;
  font-family: inherit;
}

.btn-icon-mobile:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.lang-btn {
  font-size: 12px;
  font-weight: 700;
}

.mobile-menu-btn {
  background: none;
  border: none;
  color: var(--text-primary);
  cursor: pointer;
  padding: 4px 6px;
  display: flex;
  align-items: center;
  border-radius: var(--radius-sm);
}

.mobile-menu-btn:hover {
  background: var(--bg-hover);
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
    padding: 9px 12px;
    background: color-mix(in srgb, var(--bg-primary) 88%, transparent);
    backdrop-filter: saturate(180%) blur(16px);
    -webkit-backdrop-filter: saturate(180%) blur(16px);
    border-bottom: 1px solid var(--border-color);
    position: sticky;
    top: 0;
    z-index: 50;
  }
}
</style>
