<template>
<section class="kb-card p-4">
  <div class="kb-section-head">
    <div><small>PIPELINE TIMELINE</small><h3>流水线时间轴</h3></div>
    <span class="kb-badge">{{ stages.length }} stages · {{ measured }} ms</span>
  </div>
  <div v-if="!stages.length" class="p-6 text-center text-xs text-ink-400">当前 Trace 未记录分层耗时</div>
  <ol v-else class="mt-3 space-y-2">
    <li v-for="(stage,i) in stages" :key="`${stage.key}-${i}`">
      <div class="rounded-lg border border-ink-100 px-3 py-2" :class="errorCount(stage) ? 'border-rose-200 bg-rose-50/40' : ''">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex min-w-0 items-center gap-2">
            <span class="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-indigo-50 text-[10px] font-bold text-indigo-700">{{ i + 1 }}</span>
            <b class="text-xs text-ink-800">{{ stage.label || stage.key }}</b>
            <span class="font-mono text-[10px] uppercase text-ink-400">{{ stage.key }}</span>
          </div>
          <div class="flex items-center gap-2 text-[11px]">
            <span class="text-ink-500">{{ countText(stage) }}</span>
            <span class="font-mono text-ink-700">{{ msText(stage.latencyMs) }}</span>
          </div>
        </div>
        <div class="mt-1.5 h-1.5 overflow-hidden rounded bg-ink-100">
          <div class="h-full rounded" :class="errorCount(stage) ? 'bg-rose-400' : 'bg-indigo-500'" :style="{width:width(stage.latencyMs)}"></div>
        </div>
        <div v-if="detailEntries(stage).length" class="mt-1.5 flex flex-wrap gap-1">
          <span v-for="entry in detailEntries(stage)" :key="entry[0]" class="kb-badge">{{ entry[0] }}: {{ entry[1] }}</span>
        </div>
        <div v-if="errorList(stage).length" class="mt-1.5 space-y-0.5">
          <p v-for="(msg,idx) in errorList(stage)" :key="idx" class="text-[11px] text-rose-600">⚠ {{ msg }}</p>
        </div>
      </div>
    </li>
  </ol>
</section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { PipelineStage } from '../types'

const props = defineProps<{ stages:PipelineStage[] }>()

/** 未埋点的阶段耗时为 null，不能按 0 参与占比计算。 */
const measured = computed(() => props.stages.reduce((sum, s) => sum + (Number(s.latencyMs) || 0), 0))
const max = computed(() => Math.max(...props.stages.map(s => Number(s.latencyMs) || 0), 1))
const width = (ms?:number|null) => `${Math.max(Number(ms) ? 2 : 0, (Number(ms) || 0) / max.value * 100)}%`

const msText = (ms?:number|null) => (ms == null ? '—' : `${Math.round(Number(ms))} ms`)
const countText = (stage:PipelineStage) => {
  const inCount = stage.inputCount, out = stage.outputCount
  if (inCount == null && out == null) return '—'
  return `${inCount == null ? '—' : inCount} → ${out == null ? '—' : out}`
}

/** 只展示标量明细：模型名、策略开关、索引版本等；数组/对象留给原始 JSON 面板。 */
function detailEntries(stage:PipelineStage):[string,string][] {
  const detail = stage.detail || {}
  return Object.entries(detail)
    .filter(([, v]) => v != null && typeof v !== 'object')
    .slice(0, 6)
    .map(([k, v]) => [k, typeof v === 'boolean' ? (v ? 'on' : 'off') : String(v)] as [string,string])
}

function errorList(stage:PipelineStage):string[] {
  const errors = stage.errors
  if (!errors) return []
  if (Array.isArray(errors)) return errors.map(String)
  if (typeof errors === 'object') {
    return Object.entries(errors as Record<string, unknown>)
      .flatMap(([channel, list]) => (Array.isArray(list) ? list : [list]).map(x => `${channel}: ${String(x)}`))
  }
  return [String(errors)]
}
const errorCount = (stage:PipelineStage) => errorList(stage).length
</script>
