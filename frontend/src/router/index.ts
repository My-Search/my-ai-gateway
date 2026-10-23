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
          meta: { title: '入口模型' }
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
          path: 'model/group/list',
          name: 'model-group-list',
          component: () => import('@/views/model/group/List.vue'),
          meta: { title: '模型小组' }
        },
        {
          path: 'model/group/form',
          name: 'model-group-form',
          component: () => import('@/views/model/group/Form.vue'),
          meta: { title: '新建小组' }
        },
        {
          path: 'model/group/form/:id',
          name: 'model-group-edit',
          component: () => import('@/views/model/group/Form.vue'),
          meta: { title: '编辑小组' }
        },
        {
          path: 'model/group/:id',
          name: 'model-group-detail',
          component: () => import('@/views/model/group/Detail.vue'),
          meta: { title: '小组成员' }
        },
        {
          path: 'model/circuit-breaker/:id',
          name: 'circuit-breaker',
          component: () => import('@/views/model/CircuitBreaker.vue'),
          meta: { title: '熔断状态' }
        },
        {
          path: 'model/advanced/:id',
          name: 'model-advanced',
          redirect: to => `/admin/model/form/${to.params.id}`
        },
        {
          path: 'model/prompt-injections/:id',
          name: 'model-prompt-injections',
          component: () => import('@/views/model/PromptInjections.vue'),
          meta: { title: 'Prompt 注入' }
        },
        {
          path: 'apikey/list',
          name: 'apikey-list',
          component: () => import('@/views/apikey/List.vue'),
          meta: { title: 'API密钥' }
        },
        {
          path: 'apikey/usage/:id',
          name: 'apikey-usage',
          component: () => import('@/views/apikey/Models.vue'),
          meta: { title: '密钥用量' }
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
        },
        {
          path: 'setting/system',
          name: 'system-config',
          component: () => import('@/views/setting/SystemConfig.vue'),
          meta: { title: '系统配置' }
        }
      ]
    },
    // 兜底路由：放在最后，匹配所有未定义路径。
    // 没有它时输错 URL 会落到 vue-router 的无匹配状态，渲染出一片空白。
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFound.vue'),
      meta: { title: '页面不存在', requiresAuth: false }
    }
  ]
})

/**
 * 全局前置守卫：需要登录的页面先验证 session
 *
 * 切页必须立即显示目标页面，因此守卫不能阻塞导航（不做网络往返）：
 * - 本地已有 JWT → 直接放行；token/会话过期由 axios 401 拦截器统一跳登录
 * - 本地无 token（首次访问或手动清过存储）→ 才调 checkAuth 兜底确认会话
 *
 * 使用 Vue Router 4 推荐的 return value 模式，替代已弃用的 next() 回调。
 * 避免 async 守卫中 next() 可能引发的竞态条件（导航在 promise 解析前提前完成）。
 */
router.beforeEach(async (to, _from) => {
  // 公开页面白名单：这些页面不需要任何认证，始终放行
  // 注意：使用 to.path 而非 route meta，避免 Vue Router 的 matched 链在某些
  // 边缘情况下（如嵌套路由、redirect 路由）不能正确反映 requiresAuth 状态
  const publicPaths = ['/login', '/share/']
  const isPublicPath = publicPaths.some(prefix => to.path.startsWith(prefix))

  if (isPublicPath) {
    return // return undefined → 允许导航
  }

  // 本地已有 token → 零网络往返，立即放行（会话失效时由 401 拦截器兜底）
  if (localStorage.getItem('admin_token')) {
    return
  }

  // 无 token（如首次访问、Session-only 登录）→ 向后端确认 session 有效
  try {
    const auth = await checkAuth()
    if (!auth.authenticated) {
      return { path: '/login', replace: true } // 重定向到登录页
    }
  } catch {
    return { path: '/login', replace: true }
  }

  // return undefined → 允许导航
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
