<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">MCP 服务</div>
        <h1 class="kb-title mt-1">MCP 服务</h1>
        <p class="kb-muted mt-1">
          把知识能力以 Tools / Resources 的形式交给外部智能体：一个 URL 加一个 API Key 即可接入。
          工具清单直接来自协议注册表，页面不会另抄一份，免得对外宣传的工具和实际能调的对不上。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <span class="kb-badge" :class="manifest?.enabled ? 'kb-state-ok' : 'bg-ink-50 text-ink-500'">
          {{ manifest?.enabled ? '已启用' : '已关闭' }}
        </span>
        <button class="kb-btn-outline" :disabled="loading" @click="loadAll">刷新</button>
      </div>
    </header>

    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="协议版本" :value="manifest?.protocolVersion || '—'" hint="MCP 协商版本" />
      <MetricCard label="Tools" :value="manifest?.tools?.length ?? 0" hint="可被调用的工具" />
      <MetricCard label="最近调用" :value="calls.length" hint="MCP 通道审计条数" />
      <MetricCard label="平均耗时" :value="avgDuration" hint="最近调用均值（ms）" />
    </div>

    <div v-if="error" class="mt-3 rounded-lg border border-[color:var(--warn-bd)] bg-[color:var(--warn-bg)] px-4 py-2.5 text-xs text-[color:var(--warn)]">
      {{ error }}
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
      <article class="kb-card">
        <div class="kb-card-head">
          <b>工具</b>
          <span class="kb-muted">点开查看入参与示例请求</span>
        </div>
        <PanelState :loading="loading" :error="error" :empty="!tools.length" empty-text="没有可用工具" retryable @retry="loadAll">
          <ul class="divide-y divide-ink-100">
            <li v-for="tool in tools" :key="tool.name">
              <button class="flex w-full items-start justify-between gap-3 px-4 py-3 text-left transition hover:bg-ink-50"
                @click="toggle(tool.name)">
                <span class="min-w-0">
                  <b class="text-sm text-ink-800">{{ tool.name }}</b>
                  <span class="mt-0.5 block text-xs text-ink-500">{{ tool.description }}</span>
                </span>
                <span class="kb-badge shrink-0">{{ requiredCount(tool) ? `${requiredCount(tool)} 必填` : '无必填' }}</span>
              </button>
              <div v-if="opened===tool.name" class="border-t border-ink-100 bg-ink-50 px-4 py-3">
                <b class="text-[11px] text-ink-600">入参</b>
                <ul class="mt-1 space-y-1">
                  <li v-for="(prop, name) in tool.inputSchema?.properties || {}" :key="String(name)" class="text-xs text-ink-700">
                    <code class="rounded bg-white px-1" style="background:var(--bg-sunken);color:var(--ink-2)">{{ name }}</code>
                    <span class="text-ink-500">· {{ prop.type }}</span>
                    <span v-if="(tool.inputSchema?.required || []).includes(String(name))" class="text-[color:var(--danger)]">· 必填</span>
                    <span class="text-ink-500">— {{ prop.description }}</span>
                  </li>
                  <li v-if="!Object.keys(tool.inputSchema?.properties || {}).length" class="text-xs text-ink-500">无入参</li>
                </ul>
                <b class="mt-3 block text-[11px] text-ink-600">tools/call 调用示例</b>
                <pre class="mt-1 overflow-x-auto rounded-lg bg-white p-2 text-[11px] leading-5" style="background:var(--bg-sunken);color:var(--ink-2)">{{ callSample(tool) }}</pre>
              </div>
            </li>
          </ul>
        </PanelState>
      </article>

      <div class="space-y-4">
        <article class="kb-card">
          <div class="kb-card-head"><b>接入信息</b></div>
          <dl class="space-y-2 px-4 py-3 text-xs">
            <div class="flex items-center justify-between gap-3">
              <dt class="text-ink-500">接入地址</dt><dd class="font-mono">{{ manifest?.endpoint || '—' }}</dd>
            </div>
            <div class="flex items-center justify-between gap-3">
              <dt class="text-ink-500">服务端</dt><dd>{{ manifest?.serverName || '—' }} {{ manifest?.serverVersion || '' }}</dd>
            </div>
            <div class="flex items-center justify-between gap-3">
              <dt class="text-ink-500">鉴权</dt><dd>{{ manifest?.auth || '—' }}</dd>
            </div>
          </dl>
          <div class="border-t border-ink-100 px-4 py-3">
            <b class="text-[11px] text-ink-600">initialize 握手响应</b>
            <pre class="mt-1 max-h-48 overflow-auto rounded-lg bg-ink-50 p-2 text-[11px] leading-5 text-ink-700">{{ initSample }}</pre>
          </div>
        </article>

        <article class="kb-card">
          <div class="kb-card-head">
            <b>最近调用</b>
            <button class="text-xs font-medium text-accent hover:text-accent" @click="$emit('view-calls','MCP')">
              调用日志 →
            </button>
          </div>
          <PanelState :loading="loading" :error="error" :empty="!calls.length" empty-text="暂无 MCP 调用"
            empty-hint="用 API Key 调一次 tools/list 或 tools/call 后这里会出现记录" @retry="loadAll">
            <ul class="divide-y divide-ink-100">
              <li v-for="call in calls" :key="call.id" class="px-4 py-2.5">
                <div class="flex items-center justify-between gap-2">
                  <code class="text-xs text-ink-800">{{ call.method }}</code>
                  <span class="kb-badge" :class="call.statusCode >= 400 ? 'kb-state-danger' : 'kb-state-ok'">
                    {{ call.statusCode }}
                  </span>
                </div>
                <div class="mt-0.5 flex items-center justify-between text-[11px] text-ink-500">
                  <span>{{ call.keyCode || '—' }} · {{ call.durationMs }} ms</span>
                  <span>{{ time(call.createdTime) }}</span>
                </div>
                <div v-if="call.message" class="mt-1 text-[11px] text-[color:var(--danger)]">{{ call.message }}</div>
                <div v-if="call.traceId" class="mt-0.5 truncate text-[11px] text-ink-500">trace {{ call.traceId }}</div>
                <button class="mt-1 text-[11px] text-accent" @click="openCall = openCall === call.id ? null : call.id">
                  {{ openCall === call.id ? '收起报文' : '查看入参与返回' }}
                </button>
                <div v-if="openCall === call.id" class="mt-1 space-y-2">
                  <div>
                    <b class="text-[11px] font-normal text-ink-500">传入参数（工具名与 arguments）</b>
                    <pre class="mt-0.5 max-h-56 overflow-auto whitespace-pre-wrap break-all rounded border border-black/10 bg-black/[0.03] p-2 text-[11px] leading-5">{{ pretty(call.requestParams, '本次调用没有入参') }}</pre>
                  </div>
                  <div>
                    <b class="text-[11px] font-normal text-ink-500">返回数据（工具的业务结果）</b>
                    <pre class="mt-0.5 max-h-56 overflow-auto whitespace-pre-wrap break-all rounded border border-black/10 bg-black/[0.03] p-2 text-[11px] leading-5">{{ pretty(call.responseData, '本次调用没有返回数据') }}</pre>
                  </div>
                </div>
              </li>
            </ul>
          </PanelState>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import MetricCard from './MetricCard.vue'
import PanelState from './PanelState.vue'
import { getMcpManifest, listMcpCalls } from '../api/external'
import type { McpCallAudit, McpManifest, McpTool } from '../types'

defineProps<{ knowledgeBaseId:number }>()
/** 从协议视角跳到全量调用日志：带上协议，省掉一次手工筛选。 */
defineEmits<{ 'view-calls':[protocol:string] }>()

const manifest = ref<McpManifest | null>(null)
const calls = ref<McpCallAudit[]>([])
const loading = ref(false)
const error = ref('')
const opened = ref('')
/** 展开的那一条调用的报文；同一时刻只看一条，列表才不至于被大段返回撑变形。 */
const openCall = ref<number | null>(null)

const tools = computed<McpTool[]>(() => manifest.value?.tools || [])
const avgDuration = computed(() => {
  if (!calls.value.length) return '—'
  const avg = calls.value.reduce((sum, c) => sum + (c.durationMs || 0), 0) / calls.value.length
  return Math.round(avg) + ' ms'
})
const initSample = computed(() => manifest.value?.initialize ? JSON.stringify(manifest.value.initialize, null, 2) : '—')
const time = (value?:string) => value ? new Date(value).toLocaleString() : '—'
const requiredCount = (tool:McpTool) => (tool.inputSchema?.required || []).length

/** 服务端以 JSON 文本存报文，这里还原成缩进格式；截断或空值原样展示。 */
const pretty = (raw?:string|null, empty = '—') => {
  if (!raw) return empty
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch { return raw }
}
const toggle = (name:string) => { opened.value = opened.value === name ? '' : name }

/** 示例请求按 schema 生成必填占位：直接复制就能跑，比手填字段名少一半出错机会。 */
function callSample(tool:McpTool) {
  const args:Record<string, unknown> = {}
  for (const name of tool.inputSchema?.required || []) args[name] = `<${name}>`
  return JSON.stringify({ jsonrpc:'2.0', id:1, method:'tools/call',
    params:{ name:tool.name, arguments:args } }, null, 2)
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [m, c] = await Promise.allSettled([getMcpManifest(), listMcpCalls(50)])
    manifest.value = m.status === 'fulfilled' ? m.value.data.data || null : null
    calls.value = c.status === 'fulfilled' ? c.value.data.data || [] : []
    // 工具清单拿不到时仍保留调用记录：先看得到"有没有人在用"，再谈工具细节。
    if (m.status === 'rejected') error.value = (m.reason as Error)?.message || 'MCP 工具清单加载失败'
    else if (c.status === 'rejected') error.value = (c.reason as Error)?.message || '调用审计加载失败'
  } finally { loading.value = false }
}

loadAll()
</script>
