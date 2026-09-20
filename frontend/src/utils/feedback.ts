import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 全站统一的失败反馈：后端统一信封里的 message 优先，其次 HTTP 状态文案，
 * 最后才是缺省兜底。traceId 一并带出，报错可以立刻对上后端链路日志。
 */
export function errorMessage(error:unknown, fallback='操作失败'):string {
  const e = error as {
    response?:{ data?:{ message?:string; traceId?:string; code?:string }; status?:number; statusText?:string }
    message?:string
  }
  const body = e?.response?.data
  const msg = body?.message || e?.message || (e?.response?.statusText ?? '') || fallback
  const traceId = body?.traceId
  return traceId ? `${msg}（traceId: ${traceId}）` : String(msg)
}

/**
 * 失败提示：toast 之外还要把文本交回调用方，便于面板用 PanelState 长期展示。
 * 只弹一闪而过的 toast 时，用户来不及记下 traceId。
 */
export function notifyError(error:unknown, fallback='操作失败'):string {
  const msg = errorMessage(error, fallback)
  ElMessage.error(msg)
  return msg
}

export function notifySuccess(message:string):void { ElMessage.success(message) }
export function notifyInfo(message:string):void { ElMessage.info(message) }
export function notifyWarning(message:string):void { ElMessage.warning(message) }

export interface ConfirmOptions {
  title:string
  /** 说明后果的正文；破坏性操作必须写清会影响什么。 */
  message:string
  confirmText?:string
  cancelText?:string
  danger?:boolean
}

/**
 * 二次确认：不可逆或代价高的操作统一走这里，确认按钮在危险操作时转为红色，
 * 避免「确定」和「取消」长得一样被误点。用户取消时抛出 'cancel'，调用方直接
 * 用 try/catch 结束即可，不需要额外判断返回值。
 */
export async function confirmAction(options:ConfirmOptions):Promise<void> {
  try {
    await ElMessageBox.confirm(options.message, options.title, {
      confirmButtonText: options.confirmText || '确定',
      cancelButtonText: options.cancelText || '取消',
      type: options.danger === false ? 'info' : 'warning',
      confirmButtonClass: options.danger === false ? '' : 'el-button--danger',
      dangerouslyUseHTMLString: false,
    })
  } catch {
    throw new Error('cancel')
  }
}

/** 列表分页：前端切片 + 页码复位，避免数据刷新后停在一个已不存在的页码上。 */
export function paginate<T>(rows:T[], page:number, size:number):T[] {
  const start = (Math.max(1, page) - 1) * Math.max(1, size)
  return rows.slice(start, start + Math.max(1, size))
}

