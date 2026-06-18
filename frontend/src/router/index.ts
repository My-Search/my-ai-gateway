import { createRouter, createWebHistory } from 'vue-router'
import { checkAuth } from '@/composables/useAuth'
import { useLoadingStore } from '@/stores/loading'

// 延迟显示加载模态，避免快速导航时闪烁
const LOADING_SHOW_DELAY = 300 // 延迟显示（毫秒）
// 延迟隐藏加载模态，避免页面刚渲染就消失导致的闪烁
const LOADING_MIN_DURATION = 200 // 最小显示时长（毫秒）
let loadingStartTime = 0
let showLoadingTimer: ReturnType<typeof setTimeout> | null = null

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/admin/dashboard'
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { title: '登录', requiresAuth: false }
    },
    {
      path: '/share/apikey/:code',
      name: 'apikey-share-legacy',
      component: () => import('@/views/share/Index.vue'),
      meta: { title: 'API 密钥分享', requiresAuth: false }
    },
    {
      path: '/share/:code',
      name: 'apikey-share',
      component: () => import('@/views/share/Index.vue'),
      meta: { title: 'API 密钥分享', requiresAuth: false }
    },
    {
      path: '/admin',
      component: () => import('@/components/layout/Layout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/Dashboard.vue'),
          meta: { title: '仪表盘' }
        },
        {
          path: 'channel/list',
          name: 'channel-list',
          component: () => import('@/views/channel/List.vue'),
          meta: { title: '渠道管理' }
        },
        {
          path: 'channel/form',
          name: 'channel-form',
          component: () => import('@/views/channel/Form.vue'),
          meta: { title: '渠道配置' }
        },
        {
          path: 'channel/form/:id',
          name: 'channel-edit',
          component: () => import('@/views/channel/Form.vue'),
          meta: { title: '编辑渠道' }
        },
        {
          path: 'channel/models/:id',
          name: 'channel-models',
          component: () => import('@/views/channel/Models.vue'),
          meta: { title: '渠道模型' }
        },
        {
          path: 'channel/reload/:id',
          name: 'channel-reload',
          component: () => import('@/views/channel/List.vue'),
          meta: { title: '渠道管理' }
        },
        {
          path: 'model/list',
          name: 'model-list',
          component: () => import('@/views/model/List.vue'),
          meta: { title: '模型管理' }
        },
        {
          path: 'model/form',
          name: 'model-form',
          component: () => import('@/views/model/Form.vue'),
          meta: { title: '模型配置' }
        },
        {
          path: 'model/form/:id',
          name: 'model-edit',
          component: () => import('@/views/model/Form.vue'),
          meta: { title: '编辑模型' }
        },
        {
          path: 'model/rels/:id',
          name: 'model-rels',
          component: () => import('@/views/model/Rels.vue'),
          meta: { title: '模型关系' }
        },
        {
          path: 'model/circuit-breaker/:id',
          name: 'circuit-breaker',
          component: () => import('@/views/model/CircuitBreaker.vue'),
          meta: { title: '熔断状态' }
        },
        {
          path: 'apikey/list',
          name: 'apikey-list',
          component: () => import('@/views/apikey/List.vue'),
          meta: { title: 'API密钥' }
        },
        {
          path: 'apikey/form',
          name: 'apikey-form',
          component: () => import('@/views/apikey/Form.vue'),
          meta: { title: '密钥配置' }
        },
        {
          path: 'apikey/form/:id',
          name: 'apikey-edit',
          component: () => import('@/views/apikey/Form.vue'),
          meta: { title: '编辑密钥' }
        },
        {
          path: 'log/list',
          name: 'log-list',
          component: () => import('@/views/log/List.vue'),
          meta: { title: '请求日志' }
        },
        {
          path: 'playground',
          name: 'playground',
          component: () => import('@/views/playground/Index.vue'),
          meta: { title: '模型测试' }
        }
      ]
    }
  ]
})

/**
 * 全局前置守卫：需要登录的页面先验证 session
 * 
 * 使用 Vue Router 4 推荐的 return value 模式，替代已弃用的 next() 回调。
 * 避免 async 守卫中 next() 可能引发的竞态条件（导航在 promise 解析前提前完成）。
 */
router.beforeEach(async (to, _from) => {
  // 清除之前的延迟显示定时器
  if (showLoadingTimer) {
    clearTimeout(showLoadingTimer)
    showLoadingTimer = null
  }

  // 延迟显示加载模态，避免快速切换时的闪烁
  showLoadingTimer = setTimeout(() => {
    const loadingStore = useLoadingStore()
    loadingStore.show('页面加载中...')
    loadingStartTime = Date.now()
  }, LOADING_SHOW_DELAY)

  // 公开页面白名单：这些页面不需要任何认证，始终放行
  // 注意：使用 to.path 而非 route meta，避免 Vue Router 的 matched 链在某些
  // 边缘情况下（如嵌套路由、redirect 路由）不能正确反映 requiresAuth 状态
  const publicPaths = ['/login', '/share/']
  const isPublicPath = publicPaths.some(prefix => to.path.startsWith(prefix))

  if (isPublicPath) {
    // 取消延迟显示
    if (showLoadingTimer) {
      clearTimeout(showLoadingTimer)
      showLoadingTimer = null
    }
    return // return undefined → 允许导航
  }

  // 其他页面需要认证 → 向后端确认 session 有效
  try {
    const auth = await checkAuth()
    if (!auth.authenticated) {
      // 取消延迟显示
      if (showLoadingTimer) {
        clearTimeout(showLoadingTimer)
        showLoadingTimer = null
      }
      return { path: '/login', replace: true } // 重定向到登录页
    }
  } catch {
    // 取消延迟显示
    if (showLoadingTimer) {
      clearTimeout(showLoadingTimer)
      showLoadingTimer = null
    }
    return { path: '/login', replace: true }
  }

  // return undefined → 允许导航
})

/**
 * 全局后置守卫：导航完成后隐藏加载模态
 */
router.afterEach(() => {
  // 清除延迟显示定时器，防止加载模态在延迟期间被触发
  if (showLoadingTimer) {
    clearTimeout(showLoadingTimer)
    showLoadingTimer = null
  }

  // 延迟隐藏，避免页面刚渲染就消失导致的闪烁
  const elapsed = Date.now() - loadingStartTime
  const delay = Math.max(0, LOADING_MIN_DURATION - elapsed)

  setTimeout(() => {
    const loadingStore = useLoadingStore()
    loadingStore.hide()
  }, delay)
})

/**
 * 供 axios 401 拦截器使用的登录跳转函数（避免 full page reload 导致循环）
 * 仅在非登录页面触发
 */
export function navigateToLogin() {
  if (window.location.pathname !== '/login') {
    // 清除 token，确保回到登录状态
    localStorage.removeItem('admin_token')
    router.replace('/login')
  }
}

export default router
