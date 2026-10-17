export function safeJson(text:string):unknown {
  try { return JSON.parse(text) } catch { return null }
}

export function asRecord(value:unknown):Record<string, unknown> {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

export function asArray(value:unknown):unknown[] {
  return Array.isArray(value) ? value : []
}

export function isJsonBody(body:string):boolean {
  return safeJson(body) !== null
}

/**
 * 格式化后的完整响应体：能解析就缩进展开，解析不了（错误页、纯文本）就原样返回。
 * 复制用这个——保证拿到的是完整内容。
 */
export function prettyBody(body:string):string {
  if (!body) return '（无响应体）'
  const value = safeJson(body)
  return value === null ? body : JSON.stringify(value, null, 2)
}

/**
 * 界面展示用的上限。响应体可能很大（topK=50 的候选加证据原文轻易过万字符），整段塞进
 * <pre> 会把页面拖住，所以这里才截断——但必须明写出来，不能给一段看不出被截过的 JSON。
 */
const MAX_DISPLAY_CHARS = 200_000

/** 供 <pre> 渲染：格式化 + 超长时截断并标注。复制请走 {@link prettyBody}。 */
export function displayBody(body:string):string {
  const text = prettyBody(body)
  if (text.length <= MAX_DISPLAY_CHARS) return text
  return text.slice(0, MAX_DISPLAY_CHARS)
    + `\n…（响应过长，界面仅展示前 ${MAX_DISPLAY_CHARS} 字符；点「复制响应」取完整内容）`
}

export function bodySize(body:string):string {
  const bytes = new Blob([body]).size
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`
}
