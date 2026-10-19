<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">接口服务</div>
        <h1 class="kb-title mt-1">事件总线</h1>
        <p class="kb-muted mt-1">
          平台关键事件的持久流水：知识库、摄入、抽取、检索、治理与权限的每一次动作都会落一条记录，
          SSE 订阅推送的就是同一份数据。断线后从这里按事件号接着看，不必重放整条流。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-outline" :disabled="loading" @click="load">刷新</button>
        <button :class="live ? 'kb-btn-danger' : 'kb-btn-primary'" @click="toggleLive">
          {{ live ? '停止实时' : '实时订阅' }}
        </button>
      </div>
    </header>

    <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
      <article class="kb-metric">
        <span class="kb-metric-label">活跃订阅</span>
        <strong class="kb-metric-value">{{ subscriptions?.active ?? 0 }}</strong>
        <small class="kb-metric-hint">每凭据上限 {{ subscriptions?.maxPerPrincipal ?? 5 }} 路</small>
      </article>
      <article class="kb-metric">
        <span class="kb-metric-label">已加载</span>
        <strong class="kb-metric-value">{{ items.length }}</strong>
        <small class="kb-metric-hint">{{ pending.length ? `${pending.length} 条待并入` : (hasMore ? '可继续翻页' : '已到最早一条') }}</small>
      </article>
      <article class="kb-metric">
        <span class="kb-metric-label">最早事件号</span>
        <strong class="kb-metric-value">{{ oldestId ?? '—' }}</strong>
        <small class="kb-metric-hint">{{ live ? `实时已到 #${lastId || '—'}` : '保留窗口内' }}</small>
      </article>
      <article class="kb-metric">
        <span class="kb-metric-label">保留策略</span>
        <strong class="kb-metric-value">{{ retentionText }}</strong>
        <small class="kb-metric-hint">累计清理 {{ retention?.purgedTotal ?? 0 }} 条</small>
      </article>
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-4">
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">筛选</b>
          <button class="text-xs font-medium text-accent hover:text-accent" @click="resetFilters">重置</button>
        </div>
        <div class="kb-card-body">
          <span class="kb-field-label">知识库范围</span>
          <select v-model="kb" class="kb-input mt-1 w-full" @change="load">
            <option :value="null">全部知识库</option>
            <option v-for="b in bases" :key="b.id" :value="b.id">{{ b.name }}</option>
          </select>

          <span class="kb-field-label mt-3 block">关键字</span>
          <input
            v-model="keyword" class="kb-input mt-1 w-full" placeholder="摘要 / 对象编码 / 操作人"
            @keyup.enter="load" />
          <div class="mt-1 text-[11px] text-ink-500">回车或点刷新生效；也支持事件号与 traceId 精确匹配。</div>

          <span class="kb-field-label mt-3 block">事件类型（不勾选＝全部）</span>
          <div class="mt-1 max-h-72 overflow-auto">
            <label v-for="t in types" :key="t" class="flex items-center gap-2 py-0.5">
              <input v-model="selectedTypes" type="checkbox" :value="t" @change="load" />
              <span class="text-[11px] text-ink-700">{{ t }}</span>
            </label>
          </div>
        </div>
      </article>

      <article class="kb-card xl:col-span-3">
        <div class="kb-card-head">
          <b class="text-sm">事件流</b>
          <div class="flex items-center gap-2">
            <button v-if="pending.length" class="kb-btn-primary text-[11px]" @click="mergePending">
              并入 {{ pending.length }} 条新事件
            </button>
            <span class="kb-muted">{{ live ? `实时订阅中 · ${liveStatus}` : '未订阅实时' }}</span>
          </div>
        </div>
        <div class="kb-card-body max-h-[620px] overflow-auto">
          <PanelState
            :loading="loading" :error="error" :empty="!items.length && !pending.length"
            empty-text="没有匹配的事件"
            empty-hint="换一个知识库或关键字，或点「实时订阅」后去执行一次问答 / 上传"
            retryable @retry="load">
            <div v-for="e in pending" :key="`p-${e.id}`"
              class="border-b border-[color:var(--line-d)] bg-accent-50/40 py-1.5">
              <div class="flex flex-wrap items-center gap-2">
                <span class="kb-badge kb-state-ok">NEW</span>
                <span class="text-[11px] font-semibold text-ink-700">{{ e.type }}</span>
                <span class="text-[11px] text-ink-500">#{{ e.id }} · {{ formatTime(e.time) }}</span>
                <span class="min-w-0 flex-1 truncate text-[11px] text-ink-700">{{ e.summary }}</span>
              </div>
            </div>
            <div v-for="e in items" :key="e.id" class="border-b border-[color:var(--line-d)] py-1.5 last:border-0">
              <div class="flex cursor-pointer flex-wrap items-center gap-2" @click="toggle(e.id)">
                <span class="kb-badge" :class="typeClass(e.type)">{{ e.type }}</span>
                <span class="text-[11px] text-ink-500">#{{ e.id }} · {{ formatTime(e.time) }}</span>
                <span class="text-[11px] text-ink-500">{{ e.source }} · {{ e.actor?.name || 'system' }}</span>
                <span class="min-w-0 flex-1 truncate text-[11px] text-ink-700">{{ e.summary || '—' }}</span>
              </div>
              <div v-if="open[e.id]" class="mt-1 space-y-1 text-[11px] text-ink-600">
                <div class="flex flex-wrap gap-3">
                  <span>对象：{{ e.targetType || '—' }} / {{ e.targetCode || '—' }}</span>
                  <span>知识库：{{ baseName(e.knowledgeBaseId) }}</span>
                  <span v-if="e.traceId">traceId：{{ e.traceId }}</span>
                </div>
                <div class="flex gap-2">
                  <button class="kb-btn-ghost text-[11px]" @click="copy(JSON.stringify(e.detail || {}, null, 2))">
                    复制 detail
                  </button>
                  <button v-if="e.traceId" class="kb-btn-ghost text-[11px]" @click="copy(String(e.traceId))">
                    复制 traceId
                  </button>
                </div>
                <pre class="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-ink-50 p-2 text-[11px] leading-5 text-ink-700">
                  {{ JSON.stringify(e.detail || {}, null, 2) }}</pre>
              </div>
            </div>
          </PanelState>
          <div v-if="hasMore" class="mt-2 text-center">
            <button class="kb-btn-outline text-[11px]" :disabled="loadingMore" @click="loadMore">
              {{ loadingMore ? '加载中…' : '加载更早' }}
            </button>
          </div>
        </div>
      </article>
    </div>

    <article v-if="subscriptions?.items?.length" class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">订阅观测</b>
        <span class="kb-muted">当前挂在总线上的凭据</span>
      </div>
      <div class="kb-card-body flex flex-wrap gap-2">
        <span v-for="s in subscriptions.items" :key="s.principal" class="kb-badge kb-state-info">
          {{ s.principal }} · {{ s.streams }} 路
        </span>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import type { KnowledgeBase } from '../types'
import { unwrap } from '../api/knowledge'
import {
  fetchPlatformEventHistory, fetchPlatformEventTypes, fetchPlatformSubscriptions,
  type PlatformEventItem, type PlatformEventRetention, type PlatformSubscriptionStats,
} from '../api/external'
import { getToken } from '../api/auth'
import PanelState from './PanelState.vue'
import { errorMessage as message } from '../utils/feedback'

const props = defineProps<{ bases: KnowledgeBase[]; knowledgeBaseId?: number | null }>()

const PAGE_SIZE = 100

const types = ref<string[]>([])
const selectedTypes = ref<string[]>([])
const kb = ref<number | null>(props.knowledgeBaseId ?? null)
const keyword = ref('')
const items = ref<PlatformEventItem[]>([])
/** 实时事件先落在这里：直接插进列表会让正在阅读的历史往下跳。 */
const pending = ref<PlatformEventItem[]>([])
const open = reactive<Record<number, boolean>>({})
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const hasMore = ref(false)
const nextUntilId = ref<number | null>(null)
const oldestId = ref<number | null>(null)
const subscriptions = ref<PlatformSubscriptionStats | null>(null)
const retention = ref<PlatformEventRetention | null>(null)

const live = ref(false)
const liveStatus = ref('未连接')
const lastId = computed(() => items.value[0]?.id ?? 0)
let controller: AbortController | null = null

const retentionText = computed(() => {
  const policy = retention.value
  if (!policy) return '—'
  if (!policy.enabled) return policy.retentionDays <= 0 ? '不清理' : '已停用'
  return `${policy.retentionDays} 天`
})

function request(untilId: number | null = null) {
  return {
    knowledgeBaseId: kb.value,
    types: selectedTypes.value,
    keyword: keyword.value.trim(),
    untilId,
    limit: PAGE_SIZE,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(await fetchPlatformEventHistory(request()), {} as never)
    items.value = data.items || []
    pending.value = []
    hasMore.value = !!data.hasMore
    nextUntilId.value = data.nextUntilId ?? null
    oldestId.value = data.oldestId ?? null
    subscriptions.value = data.subscriptions ?? null
    retention.value = data.retention ?? null
  } catch (e) {
    error.value = message(e)
  } finally {
    loading.value = false
  }
}

/** 翻页用 nextUntilId 而不是 offset：期间新到达的事件不会让下一页重复或漏行。 */
async function loadMore() {
  if (!hasMore.value || nextUntilId.value == null || loadingMore.value) return
  loadingMore.value = true
  try {
    const data = unwrap(await fetchPlatformEventHistory(request(nextUntilId.value)), {} as never)
    items.value = items.value.concat(data.items || [])
    hasMore.value = !!data.hasMore
    nextUntilId.value = data.nextUntilId ?? null
  } catch (e) {
    error.value = message(e)
  } finally {
    loadingMore.value = false
  }
}

function resetFilters() {
  kb.value = props.knowledgeBaseId ?? null
  keyword.value = ''
  selectedTypes.value = []
  load()
}

async function loadTypes() {
  try {
    types.value = unwrap(await fetchPlatformEventTypes(), [])
  } catch (e) {
    error.value = message(e)
  }
}

/** 订阅观测单独取：连接开关之间它会变，历史接口只在翻页时才顺带回来。 */
async function loadSubscriptions() {
  try {
    subscriptions.value = unwrap<PlatformSubscriptionStats | null>(await fetchPlatformSubscriptions(), null)
  } catch (e) {
    void e
  }
}

function toggleLive() {
  if (live.value) stopLive()
  else startLive()
}

async function startLive() {
  if (live.value) return
  const ctrl = new AbortController()
  controller = ctrl
  live.value = true
  liveStatus.value = '连接中…'
  const query = new URLSearchParams()
  if (kb.value != null) query.set('knowledgeBaseId', String(kb.value))
  if (selectedTypes.value.length) query.set('types', selectedTypes.value.join(','))
  const token = getToken()
  try {
    const response = await fetch(`/api/internal/platform/events/stream${query.toString() ? `?${query}` : ''}`, {
      headers: { Accept:'text/event-stream', ...(token ? { Authorization:`Bearer ${token}` } : {}) },
      signal: ctrl.signal,
    })
    if (!response.ok || !response.body) {
      liveStatus.value = `HTTP ${response.status}`
      live.value = false
      return
    }
    liveStatus.value = `已连接 HTTP ${response.status}`
    loadSubscriptions()
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += value
      const frames = buffer.split('\n\n')
      buffer = frames.pop() ?? ''
      frames.forEach(handleFrame)
    }
    liveStatus.value = '连接已结束'
  } catch (e) {
    liveStatus.value = (e as { name?: string })?.name === 'AbortError' ? '已停止' : `订阅中断：${message(e)}`
  } finally {
    if (controller === ctrl) {
      controller = null
      live.value = false
    }
    // 断开后路数会变，观测要跟上，否则页面上会留下一条已经不存在的订阅。
    loadSubscriptions()
  }
}

function stopLive() {
  controller?.abort()
  controller = null
  live.value = false
  liveStatus.value = '已停止'
}

/**
 * Heartbeat frames are comments and the handshake carries no event; everything else is one event
 * in the same envelope shape the history endpoint returns.
 */
function handleFrame(frame: string) {
  let name = ''
  const chunks: string[] = []
  frame.split('\n').forEach(line => {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith(':')) return
    if (trimmed.startsWith('event:')) name = trimmed.slice(6).trim()
    else if (trimmed.startsWith('data:')) chunks.push(trimmed.slice(5).trim())
  })
  if (!chunks.length || name === 'open') return
  let row: PlatformEventItem
  try {
    row = JSON.parse(chunks.join('\n')) as PlatformEventItem
  } catch {
    return
  }
  if (!row?.id) return
  if (items.value.some(item => item.id === row.id) || pending.value.some(item => item.id === row.id)) return
  pending.value.push(row)
}

function mergePending() {
  const known = new Set(items.value.map(item => item.id))
  const rows = pending.value.filter(item => !known.has(item.id)).reverse()
  items.value = rows.concat(items.value)
  pending.value = []
}

function toggle(id: number) {
  open[id] = !open[id]
}

function baseName(id: number | null): string {
  if (id == null) return '平台级'
  return props.bases.find(base => base.id === id)?.name || `#${id}`
}

function formatTime(value: string): string {
  return (value || '').replace('T', ' ').slice(0, 19)
}

/** 类型着色只按领域前缀分，不逐类型维护：新增事件类型无需改前端。 */
function typeClass(type: string): string {
  if (type.startsWith('INGESTION')) return 'kb-state-warn'
  if (type.startsWith('RETRIEVAL')) return 'kb-state-info'
  if (type.startsWith('KNOWLEDGE') || type.startsWith('EXTRACTION')) return 'kb-state-ok'
  if (type.startsWith('ACL') || type.startsWith('GOVERNANCE')) return 'kb-state-danger'
  return 'kb-state-info'
}

function copy(text: string) {
  navigator.clipboard?.writeText(text)
}

onMounted(() => {
  loadTypes()
  loadSubscriptions()
  load()
})
onUnmounted(stopLive)
</script>
