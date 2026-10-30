/**
 * 生成随机 UUID。
 *
 * crypto.randomUUID 只在安全上下文（HTTPS 或 localhost）存在。内网部署用明文 HTTP 打开
 * 控制台时它是 undefined，点「新建主体」会在生成编码那一步直接炸掉——而内网明文正是我们
 * 放开的部署方式，所以这里必须能退。
 *
 * 退路顺序：randomUUID → getRandomValues（非安全上下文也可用）拼 v4 → Math.random
 * （只为不崩，不保证随机强度）。
 */
export function uuid(): string {
  const webCrypto = globalThis.crypto
  if (typeof webCrypto?.randomUUID === 'function') return webCrypto.randomUUID()
  const bytes = new Uint8Array(16)
  if (typeof webCrypto?.getRandomValues === 'function') {
    webCrypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256)
  }
  // v4：版本号 0100、变体位 10。不用位运算，免得撞 lint 规则
  bytes[6] = (bytes[6] % 16) + 64
  bytes[8] = (bytes[8] % 64) + 128
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
