/**
 * 通用 Dialog 状态管理 composable
 * 
 * 封装了 Dialog 组件的状态管理和操作方法，
 * 避免在各个列表页中重复编写相同的 dialog 逻辑。
 * 
 * 使用方式：
 * ```ts
 * const dialog = useDialog()
 * dialog.open({
 *   title: '提示',
 *   message: '确认删除？',
 *   type: 'confirm',
 *   confirmClass: 'btn-danger',
 *   onConfirm: () => { ... }
 * })
 * ```
 */
import { ref } from 'vue'
import { useI18n } from '@/composables/useI18n'

export function useDialog() {
  const { t } = useI18n()

  const visible = ref(false)
  const title = ref(t('common.prompt'))
  const message = ref('')
  const type = ref<'alert' | 'confirm'>('alert')
  const confirmClass = ref('btn-primary')
  const confirmText = ref(t('dialog.confirm'))
  const cancelText = ref(t('dialog.cancel'))
  let onConfirmCallback: (() => void) | null = null

  function open(opts: {
    title?: string
    message: string
    type?: 'alert' | 'confirm'
    confirmClass?: string
    confirmText?: string
    cancelText?: string
    onConfirm?: () => void
  }) {
    title.value = opts.title ?? t('common.prompt')
    message.value = opts.message
    type.value = opts.type ?? 'alert'
    confirmClass.value = opts.confirmClass ?? 'btn-primary'
    confirmText.value = opts.confirmText ?? t('dialog.confirm')
    cancelText.value = opts.cancelText ?? t('dialog.cancel')
    onConfirmCallback = opts.onConfirm ?? null
    visible.value = true
  }

  function onConfirm() {
    onConfirmCallback?.()
    onConfirmCallback = null
  }

  function onCancel() {
    onConfirmCallback = null
  }

  return {
    visible,
    title,
    message,
    type,
    confirmClass,
    confirmText,
    cancelText,
    open,
    onConfirm,
    onCancel
  }
}
