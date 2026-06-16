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
        <button class="btn-logout-mobile" @click="handleLogout" title="退出登录">
          <SvgIcon name="logout" :size="18" />
        </button>
      </div>

      <!-- Top header -->
      <header class="top-header">
        <div class="header-left">
          <div class="page-title">{{ currentTitle }}</div>
          <span class="version-tag">v1.0</span>
        </div>
        <div class="header-right">
          <button class="btn-logout" @click="handleLogout">
            <SvgIcon name="logout" :size="16" />
            <span>退出</span>
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
import Sidebar from './Sidebar.vue'

const route = useRoute()
const router = useRouter()
const sidebarOpen = ref(false)

const currentTitle = computed(() => (route.meta?.title as string) || '控制台')

async function handleLogout() {
  try {
    await authApi.logout()
  } catch {
    // ignore
  }
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
}

/* Sidebar overlay (mobile) */
.sidebar-overlay {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.5);
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
  background: rgba(248, 81, 73, 0.15);
  color: var(--accent-red);
}

.content-area {
  padding: 24px;
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
  background: rgba(248, 81, 73, 0.15);
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
