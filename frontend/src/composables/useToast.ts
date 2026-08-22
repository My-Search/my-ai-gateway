/**
 * 通用 Toast 通知 composable
 *
 * 统一管理页面内轻量级 toast 提示，
 * 避免各组件重复书写 document.createElement('div') 逻辑。
 *
 * 使用方式：
 * ```ts
 * const { showToast } = useToast()
 * showToast('保存成功')
 * showToast('复制失败', { isError: true })
 * ```
 */
import { ref } from 'vue'

export interface ToastOptions {
  isError?: boolean
  duration?: number
  onClose?: () => void
}

export interface ToastItem {
  id: number
  message: string
  isError: boolean
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
    const { isError = false, duration = 1500, onClose } = opts ?? {}
    const id = nextToastId++
    const timer = setTimeout(() => closeToast(id), duration)
    toasts.value.push({ id, message: msg, isError, duration, timer, onClose })
    return id
  }

  return { showToast, closeToast }
}
