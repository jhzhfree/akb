<template>
  <el-drawer :model-value="visible" size="min(1120px, 96vw)" :with-header="false" @close="$emit('close')">
    <div class="min-h-full bg-ink-50 p-5 md:p-7">
      <div v-if="loading" class="kb-card p-10 text-center text-sm text-ink-400">正在装配知识证据链...</div>
      <template v-else-if="detail">
        <header class="mb-5 flex flex-wrap items-start justify-between gap-4">
          <div>
            <div class="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-indigo-600">
              <span>{{ mode }}</span><span>·</span><span>{{ code }}</span>
            </div>
            <h2 class="text-2xl font-bold text-ink-900">{{ title }}</h2>
            <p class="mt-2 max-w-3xl text-sm leading-6 text-ink-500">{{ summary }}</p>
          </div>
          <div class="flex items-center gap-2">
            <span class="kb-badge">{{ status }}</span>
            <span class="kb-badge">可信度 {{ confidence }}</span>
            <button class="kb-btn kb-btn-outline" @click="$emit('open-graph',code)">打开图谱</button>
          </div>
        </header>

        <div class="mb-5 grid gap-3 sm:grid-cols-4">
          <div class="kb-metric"><span>Evidence</span><b>{{ detail.evidence.length }}</b></div>
          <div class="kb-metric"><span>Conflict</span><b>{{ detail.conflicts.length }}</b></div>
          <div class="kb-metric"><span>Review</span><b>{{ detail.reviews.length }}</b></div>
          <div class="kb-metric"><span>Assessment</span><b>{{ detail.assessments.length }}</b></div>
        </div>

        <div class="grid gap-5 lg:grid-cols-[1.35fr_.65fr]">
          <div class="space-y-5">
            <section class="kb-card p-5">
              <div class="kb-section-head"><div><small>KNOWLEDGE</small><h3>知识正文</h3></div></div>
              <div class="whitespace-pre-wrap text-sm leading-7 text-ink-700">{{ content }}</div>
            </section>
            <VersionDiffPanel :versions="detail.versions || []" />
            <section class="kb-card p-5">
              <div class="kb-section-head"><div><small>EVIDENCE CHAIN</small><h3>证据与来源</h3></div></div>
              <div class="space-y-3">
                <article v-for="e in detail.evidence" :key="e.evidenceCode"
                  class="cursor-pointer rounded-lg border border-ink-100 bg-white p-4 transition hover:border-indigo-200 hover:shadow-sm"
                  @click="$emit('open-evidence',e.evidenceCode)">
                  <div
    class="mb-2 flex items-center justify-between gap-3"><b
    class="text-sm text-ink-800">{{ e.sourceName }}</b><span
    class="text-xs text-ink-400">{{ e.evidenceCode }}</span></div>
                  <p class="text-sm leading-6 text-ink-600">{{ e.quoteText }}</p>
                  <div v-if="e.documentTitle || e.contentUnitCode" class="mt-3 rounded-md bg-indigo-50 px-3 py-2 text-[11px] text-indigo-700">
                    📍 {{ e.documentTitle || e.documentCode }}<span v-if="e.pageNo"> · P{{ e.pageNo }}</span>
                    <span v-if="e.headingPath"> · {{ e.headingPath }}</span><span v-if="e.contentUnitCode"> · {{ e.contentUnitCode }}</span>
                  </div>
                  <div class="mt-3 flex items-center justify-between gap-3 text-xs text-ink-400">
                    <div class="flex gap-3"><span>权威 {{ score(e.authorityScore) }}</span><span>新鲜度 {{ score(e.freshnessScore) }}</span></div>
                    <span class="font-medium text-indigo-600">查看原文 →</span>
                  </div>
                </article>
                <div v-if="!detail.evidence.length" class="py-8 text-center text-sm text-ink-400">暂无证据关联</div>
              </div>
            </section>
          </div>
          <aside class="space-y-5">
            <section class="kb-card p-5"><div class="kb-section-head"><div><small>GOVERNANCE</small><h3>裁决状态</h3></div></div>
              <div
    v-for="a in detail.assessments"
    :key="String(a.id)"
    class="mb-3 rounded-lg bg-ink-50 p-3 text-xs"><b
    class="text-ink-800">{{ a.decision }}</b><p
    class="mt-1 text-ink-500">{{ a.reasonText }}</p></div>
              <div v-if="!detail.assessments.length" class="text-sm text-ink-400">暂无智能裁决记录</div>
            </section>
            <section class="kb-card p-5"><div class="kb-section-head"><div><small>REVIEW HISTORY</small><h3>人工复核历史</h3></div></div>
              <div
    v-for="r in detail.reviews"
    :key="r.id"
    class="review-line"><b>{{ r.decision }}</b><p
    class="mt-1 text-ink-500">{{ r.comment || '—' }}</p><small
    class="text-ink-400">{{ r.reviewer }} · {{ time(r.createdTime) }}</small></div>
              <div v-if="!detail.reviews.length" class="text-sm text-ink-400">暂无人工复核</div>
            </section>
            <section
    v-if="detail.conflicts.length"
    class="kb-card border-amber-200 p-5"><div
    class="kb-section-head"><div><small>CONFLICT</small><h3>知识冲突</h3></div></div><div
    v-for="(c,i) in detail.conflicts"
    :key="i"
    class="mb-2 rounded-lg bg-amber-50 p-3 text-xs text-amber-800">{{ c.conflictType || 'CONFLICT' }} · {{ c.objectACode }} ↔ {{ c.objectBCode }}</div></section>
          </aside>
        </div>
      </template>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeObjectDetail } from '../types'
import { statusLabel } from '../types'
import VersionDiffPanel from './VersionDiffPanel.vue'
const props=defineProps<{visible:boolean;mode:'CARD'|'CLAIM';code:string;detail:KnowledgeObjectDetail|null;loading:boolean}>()
defineEmits<{close:[];'open-graph':[code:string];'open-evidence':[evidenceCode:string]}>()
const obj=computed(()=>props.detail?.object||{})
const title=computed(()=>String(obj.value.title||obj.value.subject||props.code))
const summary=computed(()=>String(obj.value.summary||obj.value.predicate||''))
const content=computed(()=>String(obj.value.content||obj.value.objectValue||obj.value.summary||''))
const status=computed(()=>statusLabel(obj.value.status))
const confidence=computed(()=>score(Number(obj.value.confidence||0)))
const score=(v?:number)=>Number(v||0).toFixed(2)
const time=(v?:string)=>v?new Date(v).toLocaleString():'—'
</script>
<style scoped>
.review-line{position:relative;border-left:1px solid #e2e8f0;padding:0 0 1rem 1rem;font-size:.75rem}
.review-line:before{content:"";position:absolute;left:-4px;top:4px;width:8px;height:8px;border-radius:999px;background:#6366f1}
</style>
