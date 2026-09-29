<template>
<section class="kb-card overflow-hidden">
  <div class="kb-card-head">
    <div><b class="text-sm">检索候选流水线</b><div class="text-[10px] text-ink-500">召回 → 融合排序 → 重排 → 上下文组装</div></div>
    <span class="kb-badge">{{ candidates.length }} candidates</span>
  </div>
  <div class="border-b border-ink-100 px-4 py-3">
    <div class="flex flex-wrap items-center gap-2">
      <template v-for="(stage,i) in stages" :key="stage.name">
        <div class="rounded-lg border border-ink-100 bg-ink-50 px-3 py-2">
          <div class="text-[10px] font-semibold uppercase text-ink-500">{{ stage.name }}</div>
          <div class="mt-1 text-sm font-bold text-ink-800">{{ stage.value }}</div>
        </div>
        <span v-if="i<stages.length-1" class="text-ink-300">→</span>
      </template>
    </div>
  </div>
  <div class="overflow-x-auto">
    <table class="w-full min-w-[760px] text-left text-xs">
      <thead class="bg-ink-50 text-[10px] uppercase tracking-wide text-ink-500">
        <tr><th class="px-4 py-2">最终</th><th>候选知识</th><th>通道</th><th>融合排序</th><th>重排</th><th>排名变化</th><th class="pr-4">得分轨迹</th></tr>
      </thead>
      <tbody class="divide-y divide-ink-100">
        <tr v-for="(c,i) in candidates" :key="`${c.id}-${i}`" class="cursor-pointer hover:bg-[color:var(--info-bg)]/40" @click="open(c)">
          <td class="px-4 py-3 font-mono text-ink-500">{{ i+1 }}</td>
          <td class="max-w-[280px] py-3"><b class="block truncate text-ink-800">{{ c.title || c.id }}</b>
            <span class="mt-1 block truncate text-[10px] text-ink-500">{{ locator(c) }}</span></td>
          <td><span class="kb-badge">{{ c.channel || '—' }}</span></td>
          <td class="font-mono text-ink-500">{{ fmt(c.score) }}</td>
          <td class="font-mono font-semibold text-accent">{{ fmt(c.rerankScore) }}</td>
          <td><span :class="rankClass(c.rankDelta)">{{ rankText(c) }}</span></td>
          <td class="pr-4"><div class="w-32 space-y-1">
            <div class="h-1.5 rounded bg-ink-100"><div class="h-1.5 rounded bg-ink-400" :style="{width:bar(c.score,maxScore)}"></div></div>
            <div class="h-1.5 rounded bg-ink-100"><div class="h-1.5 rounded bg-[color:var(--info-bg)]" :style="{width:bar(c.rerankScore,maxRerank)}"></div></div>
          </div></td>
        </tr>
      </tbody>
    </table>
  </div>
  <div v-if="!candidates.length" class="p-8 text-center text-xs text-ink-500">链路中暂无结构化候选数据</div>
</section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { QueryCitation, TraceCandidate } from '../types'
const props=defineProps<{ candidates:TraceCandidate[]; recallCounts:Record<string,number>; selectedCount:number }>()
const emit=defineEmits<{ 'open-candidate':[citation:QueryCitation] }>()
const maxScore=computed(()=>Math.max(...props.candidates.map(x=>Number(x.score)||0),.000001))
const maxRerank=computed(()=>Math.max(...props.candidates.map(x=>Number(x.rerankScore)||0),.000001))
const stages=computed(()=>[
  {name:'Recall',value:Object.values(props.recallCounts).reduce((a,b)=>a+b,0)},
  {name:'RRF',value:props.candidates.length},{name:'Rerank',value:props.candidates.length},
  {name:'Context',value:props.selectedCount},
])
const fmt=(v?:number)=>v==null?'—':Number(v).toFixed(4)
const rankText=(c:TraceCandidate)=>{const d=Number(c.rankDelta)||0;return `#${c.fusionRank||'—'} → #${c.rerankRank||'—'} ${d>0?'↑'+d:d<0?'↓'+Math.abs(d):'='}`}
const rankClass=(d?:number)=>Number(d)>0?'text-[color:var(--ok)]':Number(d)<0?'text-[color:var(--danger)]':'text-ink-500'
const bar=(v:number|undefined,max:number)=>`${Math.max(2,Math.min(100,(Number(v)||0)/max*100))}%`
const locator=(c:TraceCandidate)=>[c.sourceName,c.pageNo?`P${c.pageNo}`:'',c.contentUnitId?`CU:${c.contentUnitId}`:''].filter(Boolean).join(' · ')||'点击查看关联知识/证据'
const open=(c:TraceCandidate)=>emit('open-candidate',{
  objectId:c.id,objectCode:c.id,objectType:c.objectType,contentUnitId:c.contentUnitId,
  title:c.title,sourceName:c.sourceName,pageNo:c.pageNo,score:c.rerankScore ?? c.score,
})
</script>
