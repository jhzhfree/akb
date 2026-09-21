<template>
<el-drawer :model-value="visible" size="min(920px,96vw)" :with-header="false" append-to-body @close="$emit('close')">
  <div class="min-h-full bg-ink-50 p-5 md:p-7">
    <header class="mb-5 flex items-start justify-between gap-4">
      <div><div class="kb-eyebrow">证据原文</div><h2 class="kb-title mt-1">证据原文</h2>
        <p class="kb-muted mt-1">证据 → 来源 → 文档 → 内容单元</p></div>
      <span v-if="detail?.evidenceStatus" class="kb-badge">{{ detail.evidenceStatus }}</span>
    </header>
    <div v-if="loading" class="kb-card p-12 text-center text-sm text-ink-400">正在定位原始证据...</div>
    <template v-else-if="detail">
      <section class="kb-card mb-4 p-5">
        <div class="grid gap-3 sm:grid-cols-4">
          <div><small class="text-ink-400">来源</small><div class="mt-1 text-sm font-semibold">{{ detail.sourceName || '—' }}</div></div>
          <div><small class="text-ink-400">文档</small><div class="mt-1 text-sm font-semibold">{{ detail.documentTitle || '—' }}</div></div>
          <div><small class="text-ink-400">页码</small><div class="mt-1 text-sm font-semibold">{{ detail.pageNo ? `P${detail.pageNo}` : '—' }}</div></div>
          <div><small class="text-ink-400">内容单元</small><div class="mt-1 truncate font-mono text-xs">{{ detail.contentUnitCode || '—' }}</div></div>
        </div>
        <div v-if="detail.headingPath" class="mt-4 rounded-md bg-indigo-50 px-3 py-2 text-xs text-indigo-700">章节：{{ detail.headingPath }}</div>
      </section>
      <section class="kb-card mb-4 overflow-hidden">
        <div class="kb-card-head"><div><b class="text-sm">内容单元原文</b><div class="text-[10px] text-ink-400">权威来源上下文</div></div></div>
        <div ref="contentBox" class="max-h-[58vh] overflow-auto p-5 text-sm leading-7 text-ink-700" v-html="highlighted"></div>
      </section>
      <section class="kb-card p-5">
        <div class="kb-section-head"><div><small>原文引用</small><h3>引用片段</h3></div></div>
        <blockquote class="border-l-2 border-indigo-400 pl-4 text-sm leading-7 text-ink-700">{{ detail.quoteText || '—' }}</blockquote>
        <div class="mt-4 flex flex-wrap gap-2 text-[11px] text-ink-400">
          <span class="kb-badge">Authority {{ score(detail.authorityScore) }}</span>
          <span class="kb-badge">Freshness {{ score(detail.freshnessScore) }}</span>
          <span v-if="detail.contentHash" class="kb-badge font-mono">HASH {{ detail.contentHash.slice(0,12) }}</span>
        </div>
      </section>
    </template>
    <div v-else class="kb-card p-10 text-center text-sm text-ink-400">未找到证据</div>
  </div>
</el-drawer>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { EvidenceDetail } from '../types'
const props=defineProps<{ visible:boolean; detail:EvidenceDetail|null; loading:boolean }>()
defineEmits<{ close:[] }>()
const escapeHtml=(s:string)=>s.replace(/[&<>"']/g,c=>({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c] || c))
const highlighted=computed(()=>{
  const content=escapeHtml(props.detail?.content || '')
  const quote=escapeHtml(props.detail?.quoteText || '').trim()
  if(!quote) return content.replace(/\n/g,'<br>')
  const i=content.indexOf(quote)
  if(i<0) return content.replace(/\n/g,'<br>')
  return (content.slice(0,i)+'<mark class="rounded bg-amber-100 px-0.5">'+quote+'</mark>'+content.slice(i+quote.length)).replace(/\n/g,'<br>')
})
const score=(v?:number)=>v==null?'—':Number(v).toFixed(2)
</script>
