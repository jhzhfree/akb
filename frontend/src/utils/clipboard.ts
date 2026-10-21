/**
 * 复制到剪贴板：可用则用 Clipboard API，不可用则退回 execCommand。
 *
 * <p>`navigator.clipboard` 只在安全上下文（HTTPS / localhost）下存在。部署在内网 IP 上
 * 通过 HTTP 访问时它是 undefined，`navigator.clipboard.writeText(...)` 会在取值处同步抛
 * TypeError——调用方的 `.catch` 接不住这个错，表现就是「点了按钮毫无反应」，连个提示都
 * 没有。所以这里先判断可用性，再决定走哪条路。</p>
 *
 * <p>兜底用的是 textarea + execCommand('copy')：虽然已废弃，但在非安全上下文里仍然有效，
 * 是这类内网部署唯一可用的路径。</p>
 *
 * @returns 是否复制成功；调用方据此给成功或「请手动复制」的提示
 */
export async function copyText(text:string):Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒或文档未聚焦：继续走兜底，不要在这里就放弃
    }
  }
  return legacyCopy(text)
}

function legacyCopy(text:string):boolean {
  const area = document.createElement('textarea')
  area.value = text
  // 不能 display:none —— 隐藏元素选不中，复制不到内容；移到视口外即可。
  area.style.position = 'fixed'
  area.style.top = '-9999px'
  area.setAttribute('readonly', '')
  document.body.appendChild(area)

  // 复制完把选区还给用户：否则页面上的选中文字会被这次操作清掉。
  const selection = document.getSelection()
  const previous = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null

  area.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(area)
  if (previous && selection) {
    selection.removeAllRanges()
    selection.addRange(previous)
  }
  return ok
}
