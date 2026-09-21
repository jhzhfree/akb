<template>
<section class="kb-card p-4">
  <div class="kb-section-head">
    <div><small>评分拆解</small><h3>分数构成</h3></div>
    <span class="kb-badge">{{ rows.length }} candidates</span>
  </div>
  <!-- 没有明细数据时说明后端未产出 breakdown，保留表格其余列而不是渲染空面板。 -->
  <div v-if="!rows.length" class="p-6 text-center text-xs text-ink-400">当前结果未携带分数明细</div>
  <div v-else class="mt-3 space-y-3">
    <article v-for="row in rows" :key="row.key" class="rounded-lg border border-ink-100">
      <header class="flex flex-wrap items-start justify-between gap-2 border-b border-ink-100 bg-ink-50 px-3 py-2">
        <div class="min-w-0">
          <b class="block truncate text-xs text-ink-800">#{{ row.rank }} {{ row.title }}</b>
          <span class="text-[10px] text-ink-400">{{ row.locator }}</span>
        </div>
        <div class="flex flex-wrap items-center gap-1">
          <span v-for="hit in row.hits" :key="hit.channel" class="kb-badge" :title="hitText(hit)">
            {{ hit.label }} #{{ hit.rank ?? '—' }} · {{ fmt(hit.rawScore) }}
          </span>
          <span v-if="!row.hits.length" class="kb-badge">{{ row.channel || '—' }}</span>
        </div>
      </header>
      <div class="grid gap-3 p-3 sm:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <div>
          <div class="text-[10px] font-semibold uppercase text-ink-400">得分链路</div>
          <div class="mt-2 space-y-1.5 text-[11px]">
            <div v-for="step in row.chain" :key="step.label" class="flex items-center justify-between gap-3">
              <span class="text-ink-500">{{ step.label }}</span>
              <span class="font-mono text-ink-700">{{ step.value }}</span>
            </div>
          </div>
          <div v-if="row.qualityBoost != null" class="mt-2 text-[10px] text-ink-400">
            质量加权 ×{{ fmt(row.qualityBoost) }}<span v-if="row.qualitySignals" class="ml-1">{{ signalText(row.qualitySignals) }}</span>
          </div>
        </div>
        <div>
          <div class="text-[10px] font-semibold uppercase text-ink-400">命中原因</div>
          <ul class="mt-2 space-y-1 text-[11px] text-ink-600">
            <li v-for="(reason,i) in row.whyMatched" :key="`w-${i}`">· {{ reason }}</li>
            <li v-if="!row.whyMatched.length" class="text-ink-400">· 融合后进入候选（无通道明细）</li>
          </ul>
          <div v-if="row.filteredBy.length" class="mt-2">
            <div class="text-[10px] font-semibold uppercase text-rose-500">过滤原因</div>
            <div class="mt-1 flex flex-wrap gap-1">
              <span v-for="reason in row.filteredBy" :key="reason" class="kb-badge !text-rose-600">{{ filterReasonLabel(reason) }}</span>
            </div>
          </div>
        </div>
      </div>
    </article>
  </div>
</section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { ScoreBreakdown, ScoreChannelHit, TraceCandidate } from '../types'
import { filterReasonLabel } from '../types'

const props = defineProps<{ candidates:(TraceCandidate|Record<string,unknown>)[] }>()

const fmt = (v?:number|null) => (v == null || Number.isNaN(Number(v)) ? '—' : Number(v).toFixed(4))

/** 未携带 scoreBreakdown 的候选直接跳过，避免用融合分伪装成分数明细。 */
const rows = computed(() => props.candidates
  .map((c, i) => {
    const raw = c as Record<string, unknown>
    const breakdown = (raw.scoreBreakdown as ScoreBreakdown | undefined) || null
    if (!breakdown) return null
    const hits = Array.isArray(breakdown.channels) ? breakdown.channels : []
    const chain = [
      { label:'RRF 融合分', value:fmt(breakdown.rrfScore) },
      { label:'质量加权后', value:fmt(breakdown.preRerankScore) },
      { label:'重排分', value:fmt(breakdown.rerankScore) },
      { label:'最终分', value:fmt(breakdown.finalScore) },
    ]
    return {
      key:String(raw.id || i), rank:Number(raw.rerankRank ?? raw.fusionRank ?? i + 1),
      title:String(raw.title || raw.objectCode || raw.id || '—'),
      locator:[raw.sourceName, raw.pageNo ? `P${raw.pageNo}` : '', raw.objectType]
        .filter(Boolean).join(' · ') || '—',
      channel:String(raw.channel || raw.source || ''),
      hits, chain,
      qualityBoost:breakdown.qualityBoost, qualitySignals:breakdown.qualitySignals,
      whyMatched:breakdown.whyMatched || [],
      filteredBy:breakdown.filteredBy || [],
    }
  })
  .filter((x):x is NonNullable<typeof x> => x !== null))

const hitText = (hit:ScoreChannelHit) =>
  `原始分 ${fmt(hit.rawScore)} · 权重 ${hit.weight ?? 1} · RRF 贡献 ${fmt(hit.rrfContribution)}`

const signalText = (signals?:Record<string,number>) =>
  Object.entries(signals || {}).map(([k, v]) => `${k} ${Number(v).toFixed(2)}`).join(' / ')
</script>
