<template>
<section class="kb-card p-4">
  <div class="kb-section-head"><div><small>链路耗时分布</small><h3>阶段耗时</h3></div><b class="text-sm">{{ total }} ms</b></div>
  <div class="space-y-3">
    <div v-for="row in rows" :key="row.name" class="grid grid-cols-[100px_1fr_70px] items-center gap-3 text-xs">
      <span class="font-medium text-ink-600">{{ row.name }}</span>
      <div class="h-2 overflow-hidden rounded bg-ink-100"><div class="h-full rounded bg-[color:var(--info-bg)]" :style="{width:width(row.ms)}"></div></div>
      <span class="text-right font-mono text-ink-500">{{ row.ms }} ms</span>
    </div>
  </div>
  <div v-if="Object.keys(channelLatency).length" class="mt-4 border-t border-ink-100 pt-3">
    <div class="mb-2 text-[10px] font-semibold uppercase text-ink-500">召回通道耗时分解</div>
    <div class="flex flex-wrap gap-2"><span v-for="(ms,name) in channelLatency" :key="name" class="kb-badge">{{ name }} {{ ms }}ms</span></div>
  </div>
</section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
const props=defineProps<{ timings:Record<string,number>; channelLatency:Record<string,number> }>()
const rows=computed(()=>[
  {name:'Retrieval',ms:Number(props.timings.retrievalLatencyMs)||0},{name:'融合排序 / RRF',ms:Number(props.timings.fusionLatencyMs)||0},
  {name:'Rerank',ms:Number(props.timings.rerankLatencyMs)||0},{name:'LLM',ms:Number(props.timings.llmLatencyMs)||0},
])
const total=computed(()=>Number(props.timings.totalLatencyMs)||rows.value.reduce((a,b)=>a+b.ms,0))
const max=computed(()=>Math.max(...rows.value.map(x=>x.ms),1))
const width=(ms:number)=>`${Math.max(ms?2:0,ms/max.value*100)}%`
</script>
