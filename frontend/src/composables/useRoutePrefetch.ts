import { onMounted, onUnmounted } from 'vue'
import type { RouteRecordNormalized } from 'vue-router'
import router from '@/router'

/**
 * 路由 chunk 空闲预取
 *
 * 背景：切菜单要"立即显示页面"，唯一可变的部分就是懒加载 chunk 的下载/编译
 * （守卫已经是零网络放行了）。这里在进入后台后利用浏览器空闲时间，把侧边栏
 * 各菜单页的 chunk 提前拉下来，等用户真的点过去时 `router-view` 挂载即渲染。
 *
 * 实现要点：
 * - `router.getRoutes()` 返回的是扁平化后的规范化记录，每条记录的
 *   `components.default` 就是原始的 `() => import(...)` 懒加载函数，
 *   直接调用即可触发下载，不导航、不渲染。
 * - vue-router 在导航成功后会把这个字段**替换成已解析的组件对象**，
 *   所以调用前必须判 `typeof === 'function'`；这同时也让预取天然幂等——
 *   已加载过的路由会自动跳过，无需额外维护缓存。
 * - 失败必须吞掉：某个 chunk 拉取失败不能变成未处理的 Promise rejection。
 * - 用 `requestIdleCallback`（降级 setTimeout）避开首屏关键路径。
 */

/** 已发起过预取请求的加载函数（按引用去重，同一 chunk 不会重复下载） */
const prefetchedLoaders = new Set<unknown>()

/** 卸载标记：避免组件销毁后空闲回调仍在跑 */
let disposed = false

/** 空闲回调句柄，卸载时用于取消 */
let idleHandle: number | null = null
let fallbackTimer: ReturnType<typeof setTimeout> | null = null

/** 错开发起预取用的定时器，卸载时统一取消 */
const staggerTimers = new Set<ReturnType<typeof setTimeout>>()

/** 取出该路由自身的页面组件加载函数；redirect-only 路由没有 components */
function getLoader(route: RouteRecordNormalized) {
  const component = route.components?.default
  return typeof component === 'function' ? (component as () => Promise<unknown>) : undefined
}

/**
 * 侧边栏菜单的路径顺序。预取按这个顺序排队，让最常点的页面先落地；
 * 不在列表里的 /admin 子页（各表单、详情页）排在后面。
 * 只预取 /admin 下的路由：登录页与分享页属于公开入口，从控制台里点不到，
 * 提前下载只会浪费带宽。
 */
const MENU_ORDER = [
  '/admin/dashboard',
  '/admin/channel/list',
  '/admin/model/group/list',
  '/admin/model/list',
  '/admin/apikey/list',
  '/admin/log/list',
  '/admin/playground',
  '/admin/setting/system',
]

/** 收集待预取的路由，按菜单顺序优先、其余按注册顺序垫后 */
function collectTargets() {
  const targets: Array<() => Promise<unknown>> = []
  const seen = new Set<unknown>()

  const push = (route: RouteRecordNormalized) => {
    const loader = getLoader(route)
    if (!loader || seen.has(loader) || prefetchedLoaders.has(loader)) return
    seen.add(loader)
    targets.push(loader)
  }

  const routes = router.getRoutes()

  for (const path of MENU_ORDER) {
    const route = routes.find(r => r.path === path)
    if (route) push(route)
  }
  for (const route of routes) {
    if (route.path.startsWith('/admin/')) push(route)
  }

  return targets
}

/** 预取单个路由的 chunk；失败静默，不影响正常导航 */
function prefetchOne(loader: () => Promise<unknown>) {
  if (disposed) return
  prefetchedLoaders.add(loader)
  try {
    const result = loader()
    // 兼容同步返回或非 thenable 的异常加载函数
    if (result && typeof (result as Promise<unknown>).catch === 'function') {
      ;(result as Promise<unknown>).catch(() => {})
    }
  } catch {
    // 同步抛错也忽略：预取是纯优化，绝不能影响主流程
  }
}

/** 在浏览器空闲时预取所有懒加载路由 */
export function prefetchRouteChunks() {
  if (disposed || idleHandle !== null || fallbackTimer !== null) return

  const run = () => {
    idleHandle = null
    fallbackTimer = null
    if (disposed) return

    const targets = collectTargets()
    // 逐个错开发起：一次性并发几十个 chunk 会挤占用户随后真正点击时的带宽，
    // 反而让"立即显示"变慢。每个 chunk 间隔 ~60ms 分散到多个任务里。
    targets.forEach((loader, i) => {
      if (i === 0) {
        prefetchOne(loader)
        return
      }
      const timer = setTimeout(() => {
        staggerTimers.delete(timer)
        prefetchOne(loader)
      }, i * 60)
      staggerTimers.add(timer)
    })
  }

  const w = window as Window & {
    requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number
  }

  if (typeof w.requestIdleCallback === 'function') {
    idleHandle = w.requestIdleCallback(run, { timeout: 2000 })
  } else {
    fallbackTimer = setTimeout(run, 1200)
  }
}

/**
 * 在组件挂载后预取路由 chunk。
 *
 * 设计成组合式函数而不是 main.ts 里的裸调用，是为了让预取的生命周期
 * 跟着 Layout 走：退出后台（卸载）时立刻停手，不占用带宽。
 */
export function useRoutePrefetch() {
  onMounted(() => {
    prefetchRouteChunks()
  })

  onUnmounted(() => {
    disposed = true
    if (idleHandle !== null) {
      ;(window as Window & { cancelIdleCallback?: (h: number) => void }).cancelIdleCallback?.(idleHandle)
      idleHandle = null
    }
    if (fallbackTimer !== null) {
      clearTimeout(fallbackTimer)
      fallbackTimer = null
    }
    for (const t of staggerTimers) clearTimeout(t)
    staggerTimers.clear()
  })
}

// dev 模式下 HMR 热替换本模块时，重置去重集合与卸载标记，
// 否则旧模块的 disposed 状态会让新模块永远不再预取
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    disposed = false
    prefetchedLoaders.clear()
    idleHandle = null
    fallbackTimer = null
    for (const t of staggerTimers) clearTimeout(t)
    staggerTimers.clear()
  })
}
