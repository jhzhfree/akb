<template>
<el-drawer :model-value="visible" size="min(1180px,97vw)" :with-header="false" @close="$emit('close')">
  <div class="min-h-full bg-ink-50 p-5 md:p-7">
    <header class="mb-5 flex items-start justify-between gap-4">
      <div><div class="kb-eyebrow">QUERY OBSERVABILITY</div><h2 class="kb-title mt-1">检索链路 Trace</h2>
        <p class="kb-muted mt-1">{{ trace?.originalQuery || '正在加载查询链路…' }}</p></div>
      <span v-if="trace?.latencyMs" class="kb-badge">{{ trace.latencyMs }} ms</span>
    </header>
    <div v-if="loading" class="kb-card p-12 text-center text-sm text-ink-400">正在装配 Query Trace...</div>
    <template v-else-if="trace">
      <div class="mb-4 grid gap-3 md:grid-cols-4">
        <div class="kb-metric"><span>Query ID</span><b class="truncate text-sm">{{ trace.queryId }}</b></div>
        <div class="kb-metric"><span>Model</span><b class="text-sm">{{ trace.modelName || '—' }}</b></div>
        <div class="kb-metric"><span>Latency</span><b>{{ trace.latencyMs || 0 }} ms</b></div>
        <div class="kb-metric"><span>Status</span><b>{{ trace.errorCode || 'SUCCESS' }}</b></div>
      </div>
      <RetrievalPipeline class="mb-4" :candidates="candidates" :recall-counts="recallCounts"
        :selected-count="selectedCount" @open-candidate="$emit('open-candidate',$event)"/>
      <div class="mb-4 grid gap-4 xl:grid-cols-2">
        <LatencyWaterfall :timings="timings" :channel-latency="channelLatency"/>
        <RetrievalPolicyPanel :filters="filters"/>
      </div>
      <AnswerQualificationPanel class="mb-4" :value="qualification"/>
      <RecallChannelExplorer class="mb-4" :channels="recallChannels" @open-candidate="$emit('open-candidate',$event)"/>
      <section class="kb-card mb-4 p-4">
        <div class="kb-section-head"><div><small>CHANNEL RECALL</small><h3>多路召回分布</h3></div></div>
        <div class="grid gap-2 sm:grid-cols-5">
          <div v-for="(count,name) in recallCounts" :key="name" class="rounded-lg border border-ink-100 bg-white p-3">
            <div class="text-[10px] font-semibold uppercase text-ink-400">{{ name }}</div>
            <div class="mt-1 text-xl font-bold text-ink-800">{{ count }}</div>
          </div>
        </div>
      </section>
      <el-collapse>
        <el-collapse-item title="查看原始 Trace JSON">
          <div class="grid gap-4 lg:grid-cols-2">
            <TraceStage title="Query Rewrite" :value="trace.rewrittenQueriesJson"/>
            <TraceStage title="Router Decision" :value="trace.routerDecisionJson"/>
            <TraceStage title="Multi Recall" :value="trace.retrievalJson"/>
            <TraceStage title="Fusion / RRF" :value="trace.fusionJson"/>
            <TraceStage title="Reranker" :value="trace.rerankJson"/>
            <TraceStage title="Context Assembly" :value="trace.selectedContextJson"/>
            <TraceStage title="Citations" :value="trace.citationsJson"/>
            <TraceStage title="Token / Runtime" :value="trace.tokenUsageJson"/>
          </div>
        </el-collapse-item>
      </el-collapse>
      <section class="kb-card mt-4 p-5">
        <div class="kb-section-head"><div><small>FINAL ANSWER</small><h3>最终回答</h3></div></div>
        <div class="whitespace-pre-wrap text-sm leading-7 text-ink-700">{{ trace.answer || '—' }}</div>
      </section>
    </template>
  </div>
</el-drawer>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { QueryCitation, QueryTrace, TraceCandidate } from '../types'
import RetrievalPipeline from './RetrievalPipeline.vue'
import RecallChannelExplorer from './RecallChannelExplorer.vue'
import LatencyWaterfall from './LatencyWaterfall.vue'
import RetrievalPolicyPanel from './RetrievalPolicyPanel.vue'
import TraceStage from './TraceStage.vue'
import AnswerQualificationPanel from './AnswerQualificationPanel.vue'
const props=defineProps<{ visible:boolean; trace:QueryTrace|null; loading:boolean }>()
defineEmits<{ close:[]; 'open-candidate':[citation:QueryCitation] }>()
const parse=(v:unknown):any=>{if(typeof v!=='string')return v;try{return JSON.parse(v)}catch{return v}}
const full=computed<Record<string,any>>(()=>parse(props.trace?.traceJson)||{})
const selected=computed<any[]>(()=>parse(props.trace?.selectedContextJson)||full.value.selectedContext||[])
const candidates=computed<TraceCandidate[]>(()=>{
  const raw=Array.isArray(full.value.candidates)?full.value.candidates:selected.value
  return raw.map((c:any)=>({id:String(c.id||''),title:c.title,channel:c.channel,score:Number(c.score)||0,
    rerankScore:c.rerankScore==null?undefined:Number(c.rerankScore),objectType:c.objectType||c.metadata?.object_type,
    contentUnitId:c.contentUnitId||c.metadata?.content_unit_id,sourceName:c.sourceName||c.metadata?.source_name,
    pageNo:c.pageNo||c.metadata?.page_no,fusionRank:c.fusionRank,rerankRank:c.rerankRank,rankDelta:c.rankDelta,
    channelRanks:c.channelRanks||{}}))
})
const recallCounts=computed<Record<string,number>>(()=>{
  const trace=parse(full.value.trace)||{}
  if(trace.recallCounts)return trace.recallCounts
  const recall=parse(props.trace?.retrievalJson)||{}
  return Object.fromEntries(Object.entries(recall).map(([k,v])=>[k,Array.isArray(v)?v.length:0]))
})
const selectedCount=computed(()=>selected.value.length)
const normalize=(c:any,channel?:string):TraceCandidate=>({id:String(c.id||''),title:c.title,channel:channel||c.channel,score:Number(c.score)||0,
  objectType:c.objectType||c.metadata?.object_type,contentUnitId:c.contentUnitId||c.metadata?.content_unit_id,
  sourceName:c.sourceName||c.metadata?.source_name,pageNo:c.pageNo||c.metadata?.page_no})
const recallChannels=computed<Record<string,TraceCandidate[]>>(()=>{
  const recall=parse(props.trace?.retrievalJson)||full.value.recall||{}
  return Object.fromEntries(Object.entries(recall).map(([name,items])=>[name,Array.isArray(items)?items.map(c=>normalize(c,name)):[]]))
})
const traceRuntime=computed<Record<string,any>>(()=>parse(full.value.trace)||{})
const timings=computed<Record<string,number>>(()=>traceRuntime.value)
const channelLatency=computed<Record<string,number>>(()=>traceRuntime.value.channelLatencyMs||{})
const filters=computed<Record<string,unknown>>(()=>traceRuntime.value.filters||{})
const qualification=computed(()=>props.trace?.answerQualification||full.value.answerQualification||null)

</script>
