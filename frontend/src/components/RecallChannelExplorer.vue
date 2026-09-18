<template>
<section class="kb-card overflow-hidden">
  <div class="kb-card-head"><div><b class="text-sm">召回通道 Top-N</b><div class="text-[10px] text-ink-400">CHANNEL LEVEL EXPLAINABILITY</div></div></div>
  <div class="grid gap-3 p-4 lg:grid-cols-2 xl:grid-cols-3">
    <article v-for="(items,channel) in channels" :key="channel" class="rounded-lg border border-ink-100 bg-white">
      <header class="flex items-center justify-between border-b border-ink-100 px-3 py-2">
        <b class="text-xs uppercase text-ink-700">{{ channel }}</b><span class="kb-badge">{{ items.length }}</span>
      </header>
      <button v-for="(item,i) in items.slice(0,8)" :key="`${channel}-${item.id}-${i}`"
        class="grid w-full grid-cols-[26px_1fr_auto] gap-2 border-b border-ink-50 px-3 py-2 text-left last:border-0 hover:bg-indigo-50/40"
        @click="open(item)">
        <span class="font-mono text-[10px] text-ink-400">#{{ i+1 }}</span>
        <span class="min-w-0"><b class="block truncate text-xs text-ink-700">{{ item.title || item.id }}</b>
          <small class="block truncate text-ink-400">{{ locator(item) }}</small></span>
        <span class="font-mono text-[10px] text-ink-400">{{ fmt(item.score) }}</span>
      </button>
    </article>
  </div>
</section>
</template>
<script setup lang="ts">
import type { QueryCitation, TraceCandidate } from '../types'
defineProps<{ channels:Record<string,TraceCandidate[]> }>()
const emit=defineEmits<{ 'open-candidate':[citation:QueryCitation] }>()
const fmt=(v?:number)=>v==null?'—':Number(v).toFixed(4)
const locator=(c:TraceCandidate)=>[c.sourceName,c.pageNo?`P${c.pageNo}`:''].filter(Boolean).join(' · ')||c.objectType||'知识对象'
const open=(c:TraceCandidate)=>emit('open-candidate',{objectId:c.id,objectCode:c.id,objectType:c.objectType,
  contentUnitId:c.contentUnitId,title:c.title,sourceName:c.sourceName,pageNo:c.pageNo,score:c.score})
</script>
