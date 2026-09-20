<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">KNOWLEDGE INTEGRATION</div>
        <h1 class="kb-title mt-1">集成概览</h1>
        <p class="kb-muted mt-1">
          接知识：把平台的可信知识能力通过 REST API / MCP / SSE 与凭证接入业务系统、AI 应用与 Agent。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <select v-model.number="hours" class="kb-input w-32" @change="load">
          <option :value="24">最近 24 小时</option>
          <option :value="72">最近 3 天</option>
          <option :value="168">最近 7 天</option>
        </select>
        <button class="kb-btn-outline" @click="load">刷新</button>
      </div>
    </header>

    <!-- §6.2 服务 KPI -->
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-5">
      <article class="kb-metric">
        <span class="kb-metric-label">集成服务</span>
        <strong class="kb-metric-value">{{ overview?.serviceCount ?? 0 }}</strong>
        <small class="kb-metric-hint">{{ overview?.credentialCount ?? 0 }} 个凭证</small>
      </article>
      <article class="kb-metric">
        <span class="kb-metric-label">调用量</span>
        <strong class="kb-metric-value">{{ overview?.calls ?? 0 }}</strong>
        <small class="kb-metric-hint">窗口 {{ hours }} 小时</small>
      </article>
      <article class="kb-metric">
        <span class="kb-metric-label">成功率</span>
        <strong class="kb-metric-value">{{ overview?.calls ? pct(overview?.successRate) : '—' }}</strong>
        <small class="kb-metric-hint">失败 {{ overview?.errorCount ?? 0 }} 次</small>
      </article>
      <article class="kb-metric">
        <span class="kb-metric-label">P95</span>
        <strong class="kb-metric-value">{{ overview?.p95Ms ?? 0 }} ms</strong>
        <small class="kb-metric-hint">均值 {{ overview?.avgDurationMs ?? 0 }} ms</small>
      </article>
      <article class="kb-metric">
        <span class="kb-metric-label">健康状态</span>
        <strong class="kb-metric-value" :class="healthClass">{{ healthSummary }}</strong>
        <small class="kb-metric-hint">REST / MCP / SSE</small>
      </article>
    </div>

    <!-- §6 集成模块的其余入口：MCP / SSE / 凭证等左侧不再展开二级菜单 -->
    <nav class="kos-modnav mt-4" aria-label="知识集成">
      <button
        v-for="item in entries"
        :key="item.key"
        class="kos-modnav-chip"
        @click="$emit('navigate',item.key)">
        <span class="kos-nav-ic">{{ item.icon }}</span>
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
      <!-- §6.3 集成服务 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">集成服务</b>
          <button class="text-xs font-medium text-indigo-600 hover:text-indigo-700" @click="$emit('navigate','integration.services')">
            服务与 API →
          </button>
        </div>
        <div class="divide-y divide-ink-100 px-4">
          <div v-for="service in overview?.services || []" :key="service.code" class="py-3">
            <div class="flex items-center justify-between">
              <b class="text-[13px] text-ink-800">{{ service.name }}</b>
              <el-tag size="small" effect="plain" :type="service.status==='PUBLISHED' ? 'success' : 'info'">
                {{ service.status }}
              </el-tag>
            </div>
            <div class="mt-1 text-[11px] text-ink-500">{{ service.purpose }}</div>
            <code class="mt-1 block truncate text-[11px] text-indigo-600">{{ service.endpoint }}</code>
          </div>
          <div v-if="!overview?.services?.length" class="py-6 text-center text-[12px] text-ink-400">暂无集成服务</div>
        </div>
      </article>

      <!-- 协议分布与通道健康 -->
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">协议分布</b><span class="kb-muted">REST / MCP / SSE</span></div>
        <div class="space-y-3 p-4">
          <div v-for="row in overview?.protocolDistribution || []" :key="String(row.protocol)" class="text-[12px]">
            <div class="flex items-center justify-between">
              <span class="text-ink-600">{{ row.protocol }}</span>
              <b>{{ row.calls }} 次</b>
            </div>
            <div class="mt-1 flex h-1.5 overflow-hidden rounded bg-ink-100">
              <span
                class="bg-emerald-400"
                :style="{ width:ratio(row.successCount,row.calls) }"></span>
              <span class="bg-rose-400" :style="{ width:ratio(row.errorCount,row.calls) }"></span>
            </div>
          </div>
          <div v-if="!overview?.protocolDistribution?.length" class="text-[12px] text-ink-400">
            窗口内暂无调用记录
          </div>
        </div>
        <div class="border-t border-ink-100 px-4 py-3">
          <div class="mb-1 text-[12px] text-ink-500">通道健康</div>
          <div class="flex flex-wrap gap-2">
            <span
              v-for="row in overview?.health || []"
              :key="row.name"
              class="rounded-lg border border-ink-200 px-2 py-1 text-[11px]"
              :class="row.status==='正常' ? 'text-emerald-700' : 'text-amber-700'">
              {{ row.name }}：{{ row.status }}
            </span>
          </div>
        </div>
      </article>

      <!-- §6.8 最近调用 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">最近调用</b>
          <button class="text-xs font-medium text-indigo-600 hover:text-indigo-700" @click="$emit('navigate','integration.logs')">
            调用日志 →
          </button>
        </div>
        <div class="divide-y divide-ink-100">
          <div v-for="(row,i) in overview?.recentCalls || []" :key="i" class="px-4 py-2.5 text-[12px]">
            <div class="flex items-center justify-between">
              <span class="truncate text-ink-700">{{ row.client || '匿名调用' }}</span>
              <span :class="row.status && row.status >= 400 ? 'text-rose-600' : 'text-emerald-600'">
                {{ row.status ?? '—' }}
              </span>
            </div>
            <div class="mt-0.5 flex items-center gap-2 text-[11px] text-ink-400">
              <span>{{ row.protocol }}</span>
              <span>{{ row.method }}</span>
              <span>{{ row.latencyMs ?? 0 }} ms</span>
              <button
                v-if="row.traceId"
                class="text-indigo-600 hover:text-indigo-700"
                @click="$emit('open-trace',String(row.traceId))">
                traceId →
              </button>
            </div>
          </div>
          <div v-if="!overview?.recentCalls?.length" class="px-4 py-6 text-center text-[12px] text-ink-400">
            暂无调用记录
          </div>
        </div>
      </article>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getIntegrationOverview, unwrap } from '../api/knowledge'
import type { IntegrationOverview } from '../types'

const props = defineProps<{ knowledgeBaseId:number }>()
defineEmits<{ navigate:[key:string]; 'open-trace':[traceId:string] }>()

const overview = ref<IntegrationOverview|null>(null)
const hours = ref(24)
const loading = ref(false)

/** 集成模块的二级入口：协议适配与凭证管理。 */
const entries = [
  { key:'integration.services', icon:'🔌', label:'服务与 API' },
  { key:'integration.mcp', icon:'🧩', label:'MCP' },
  { key:'integration.sse', icon:'⇉', label:'SSE' },
  { key:'integration.credentials', icon:'🔑', label:'凭证' },
  { key:'integration.logs', icon:'📜', label:'调用日志' },
]

const healthSummary = computed(() => {
  const rows = overview.value?.health || []
  if (!rows.length) return '—'
  const abnormal = rows.filter(row => row.status !== '正常').length
  if (!abnormal) return '正常'
  return `${abnormal} 项异常`
})
const healthClass = computed(() => healthSummary.value === '正常' ? 'text-emerald-600' : 'text-amber-600')

function pct(value:number|null|undefined):string {
  if (value == null) return '—'
  return `${Number(value).toFixed(2)}%`
}
function ratio(count:number|undefined, total:number|undefined):string {
  if (!total) return '0%'
  return `${Math.round((count || 0) * 100 / total)}%`
}

async function load() {
  loading.value = true
  try {
    overview.value = await getIntegrationOverview(hours.value).then(r => unwrap(r, null as IntegrationOverview|null))
  } catch {
    overview.value = null
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.knowledgeBaseId, load)
</script>
