/** 卡片副信息里的相对时间：与原型「更新于 2 小时前 / 昨天 / 3 天前」一致。 */
export function fromNow(value?: string | number | null): string {
  if (value === null || value === undefined || value === '') return ''
  const time = typeof value === 'number' ? value : Date.parse(String(value).replace(' ', 'T'))
  if (!Number.isFinite(time)) return String(value)
  const diff = Date.now() - time
  if (diff < 0) return '刚刚'
  const minute = 60_000, hour = 60 * minute, day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 2 * day) return '昨天'
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  const d = new Date(time)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 欢迎区问候语：按小时段取词，与原型「你好」保持一致。 */
export function greeting(date = new Date()): string {
  const h = date.getHours()
  if (h < 6) return '凌晨好'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}
