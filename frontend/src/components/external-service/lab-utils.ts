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

export function prettyBody(body:string):string {
  if (!body) return '（无响应体）'
  const value = safeJson(body)
  return value === null ? body : JSON.stringify(value, null, 2)
}

export function bodySize(body:string):string {
  const bytes = new Blob([body]).size
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`
}
