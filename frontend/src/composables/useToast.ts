/**
 * 通用 Toast 通知 composable
 *
 * 统一管理页面内轻量级 toast 提示，
 * 避免各组件重复书写 document.createElement('div') 逻辑。
 *
 * 使用方式：
 * ```ts
 * const { showToast } = useToast()
 * showToast('保存成功')                          // 默认 success
 * showToast('配置即将过期', { type: 'warning' })
 * showToast('复制失败', { type: 'error' })
 * showToast('复制失败', { isError: true })       // 旧写法，等价于 type: 'error'
 * ```
 */
import { ref } from 'vue'

/** toast 语义类型，决定图标与进度条配色 */
export type ToastType = 'success' | 'warning' | 'error'

export interface ToastOptions {
  /** 语义类型，默认为 success */
  type?: ToastType
  /** @deprecated 请改用 type: 'error'，仅为向后兼容保留 */
  isError?: boolean
  duration?: number
  onClose?: () => void
}

export interface ToastItem {
  id: number
  message: string
  type: ToastType
  duration: number
  timer: ReturnType<typeof setTimeout>
  onClose?: () => void
}

export const toasts = ref<ToastItem[]>([])

let nextToastId = 1

export function closeToast(id: number) {
  const index = toasts.value.findIndex(toast => toast.id === id)
  if (index === -1) return
  const [toast] = toasts.value.splice(index, 1)
  clearTimeout(toast.timer)
  toast.onClose?.()
}

export function useToast() {
  function showToast(msg: string, opts?: ToastOptions) {
    const {
      isError = false,
      type = isError ? 'error' : 'success',
      duration = 1500,
      onClose
    } = opts ?? {}
    const id = nextToastId++
    const timer = setTimeout(() => closeToast(id), duration)
    toasts.value.push({ id, message: msg, type, duration, timer, onClose })
    return id
  }

  return { showToast, closeToast }
}
