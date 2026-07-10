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
export function useToast() {
  /**
   * 显示一条 toast 通知
   * @param msg 要显示的消息文本
   * @param opts 选项（isError: 错误模式红色背景；duration: 显示时长 ms，默认 1500）
   */
  function showToast(msg: string, opts?: { isError?: boolean; duration?: number }) {
    const { isError, duration } = { isError: false, duration: 1500, ...opts }
    const toast = document.createElement('div')
    toast.textContent = msg
    Object.assign(toast.style, {
      position: 'fixed',
      bottom: '24px',
      left: '50%',
      transform: 'translateX(-50%)',
      background: isError ? '#f87171' : '#10b981',
      color: '#fff',
      padding: '8px 20px',
      borderRadius: '8px',
      fontSize: '14px',
      zIndex: '9999',
      transition: 'opacity .3s',
    } as CSSStyleDeclaration)
    document.body.appendChild(toast)
    setTimeout(() => {
      toast.style.opacity = '0'
      setTimeout(() => toast.remove(), 300)
    }, duration)
  }

  return { showToast }
}
