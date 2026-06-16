import { createRouter, createWebHistory } from 'vue-router'
import { checkAuth } from '@/composables/useAuth'

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
 * 注意：使用 next('/login') 进行 Vue Router 客户端导航，不要用 window.location.href
 * 因为全页跳转会销毁 SPA 实例，重建后可能因 cookie 时序导致死循环。
 */
router.beforeEach(async (to, _from, next) => {
  // 登录页始终放行
  if (to.path === '/login') {
    next()
    return
  }

  // 需要认证的路由 → 向后端确认 session 有效
  if (to.matched.some(r => r.meta.requiresAuth !== false)) {
    try {
      const auth = await checkAuth()
      if (!auth.authenticated) {
        // 客户端导航到登录页，避免全页刷新
        next({ path: '/login', replace: true })
        return
      }
    } catch {
      next({ path: '/login', replace: true })
      return
    }
  }

  next()
})

/**
 * 供 axios 401 拦截器使用的登录跳转函数（避免 full page reload 导致循环）
 * 仅在非登录页面触发
 */
export function navigateToLogin() {
  if (window.location.pathname !== '/login') {
    router.replace('/login')
  }
}

export default router
