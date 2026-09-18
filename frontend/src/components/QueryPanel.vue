<template>
<section class="kb-page max-w-[1180px]">
  <header class="kb-section-head"><div><div class="kb-eyebrow">HYBRID / GRAPH RAG</div>
    <h2 class="kb-title mt-1">智能问答</h2><p class="kb-muted mt-1">查询理解 → 多路召回 → RRF → Rerank → Evidence Context → 可追溯回答。</p></div></header>
  <div class="grid gap-4 lg:grid-cols-[1fr_280px]">
    <div>
      <article class="kb-card overflow-hidden">
        <textarea :value="query" rows="5" class="w-full resize-none border-0 bg-white px-5 py-4 text-[15px] leading-7 text-ink-900 outline-none"
          placeholder="向企业知识库提问…" @input="$emit('update:query',($event.target as HTMLTextAreaElement).value)" />
        <footer class="flex items-center justify-between border-t border-ink-100 bg-ink-50/70 px-4 py-2.5">
          <span class="text-[11px] text-ink-400">回答必须可追溯到 Evidence / Source</span>
          <button class="kb-btn-primary" :disabled="asking" @click="$emit('ask')">{{ asking ? '检索中…' : '发送问题' }}</button>
        </footer>
      </article>

      <div v-if="asking" class="kb-card mt-4 flex items-center gap-2 p-4 text-sm text-ink-500">
        <span class="inline-block h-3 w-3 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent"/>
        正在检索知识库并生成回答…
      </div>

      <template v-else-if="result">
        <!-- 用户问题气泡（llmwiki 对话式样式） -->
        <div class="mt-4 flex justify-end">
          <div class="max-w-[80%] rounded-2xl rounded-br-sm bg-indigo-600 px-4 py-2.5 text-sm leading-6 text-white shadow-sm">
            {{ result.question || query }}
          </div>
        </div>

        <!-- 助手回答卡片 -->
        <article class="kb-card mt-4 p-4">
          <div class="md-answer" v-html="renderedAnswer"></div>

          <p class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-ink-400">
            <span v-if="result.questionTypeLabel" class="kb-badge bg-sky-50 text-sky-700">{{ result.questionTypeLabel }}</span>
            <span v-if="rewritten" title="改写后查询：{{ result.rewrittenQuery }}">已改写 ↻</span>
            <span :title="result.routerNote || ''">召回通道：{{ (result.channels || result.strategies || []).join(' + ') || '（无）' }}</span>
            <span v-if="result.reranked" class="kb-badge bg-emerald-50 text-emerald-700">已重排序</span>
            <span v-if="gateStatus" class="kb-badge" :class="gateClass">门控 {{ gateStatus }}</span>
            <span v-if="typeof result.confidence === 'number' && result.confidence > 0"
              title="检索强度 + 断言支撑的综合估计">置信度 {{ Math.round(result.confidence * 100) }}%</span>
            <span v-if="typeof result.contextChars === 'number'">上下文 {{ result.contextChars.toLocaleString() }} 字符</span>
            <span v-if="result.durationMs">耗时 {{ result.durationMs }}ms</span>
            <span v-if="result.model">模型 {{ result.model }}</span>
          </p>

          <div v-if="(result.claims || []).length" class="mt-2 border-t border-ink-100 pt-2">
            <p class="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-400">支撑断言</p>
            <div class="flex flex-wrap gap-1.5">
              <span v-for="claim in result.claims" :key="claim.claimId"
                class="kb-badge cursor-default"
                :class="claim.status === 'EFFECTIVE' ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700'"
                :title="claimTitle(claim)">
                {{ claim.subject }} --{{ claim.predicate }}--&gt; {{ claim.object }}
              </span>
            </div>
          </div>

          <div v-if="(result.citations || []).length" class="mt-3 border-t border-ink-100 pt-3">
            <p class="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-400">引用来源</p>
            <div class="flex flex-wrap gap-1.5">
              <button v-for="citation in result.citations" :key="citation.index ?? citation.objectId"
                class="rounded px-1.5 py-0.5 text-xs text-indigo-600 hover:bg-indigo-50 hover:underline"
                :title="citationTitle(citation)"
                @click="$emit('open-citation',citation)">
                [{{ citation.index ?? 0 }}] {{ citation.title || citation.objectId || 'Evidence' }}
              </button>
            </div>
          </div>

          <div class="mt-3 flex justify-end border-t border-ink-100 pt-3">
            <button v-if="result.queryId" class="kb-btn kb-btn-outline text-xs" @click="$emit('open-trace',result.queryId)">查看完整 Trace</button>
          </div>
        </article>
      </template>
      <div v-else class="mt-16 text-center text-sm text-ink-400">输入问题开始检索</div>
    </div>
    <aside class="kb-card h-fit overflow-hidden">
      <div class="kb-card-head"><div><b class="text-sm">问答历史</b><div class="text-[10px] text-ink-400">QUERY HISTORY</div></div>
        <button class="text-xs text-indigo-600" @click="$emit('refresh-history')">刷新</button></div>
      <div class="max-h-[620px] divide-y divide-ink-100 overflow-auto">
        <button v-for="item in history" :key="item.queryId" class="block w-full p-3 text-left hover:bg-ink-50" @click="$emit('open-trace',item.queryId)">
          <b class="line-clamp-2 text-xs leading-5 text-ink-700">{{ item.originalQuery }}</b>
          <div class="mt-2 flex justify-between text-[10px] text-ink-400"><span>{{ time(item.createdTime) }}</span><span>{{ item.latencyMs || 0 }}ms</span></div>
        </button>
        <div v-if="!history.length" class="p-8 text-center text-xs text-ink-400">暂无问答历史</div>
      </div>
    </aside>
  </div>
</section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { QueryCitation, QueryHistoryItem, QueryResult, QuerySupportClaim } from '../types'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps<{ query:string; result:QueryResult|null; asking:boolean; history:QueryHistoryItem[] }>()
defineEmits<{ ask:[]; 'update:query':[value:string]; 'open-trace':[qid:string]; 'refresh-history':[]; 'open-citation':[citation:QueryCitation] }>()
const time=(v?:string)=>v?new Date(v).toLocaleString():'—'

/** Markdown 答案 + [证据N] 上标引用（llmwiki 式排版）。 */
const renderedAnswer = computed(() =>
  renderMarkdown(props.result?.answer || '', props.result?.citations || []))
const rewritten = computed(() => {
  const rewrittenQuery = props.result?.rewrittenQuery
  return !!rewrittenQuery && rewrittenQuery !== (props.result?.question || props.query)
})
const gateStatus = computed(() => {
  const status = props.result?.answerQualification?.gateStatus
  return status && status !== 'BYPASSED' ? String(status) : ''
})
const gateClass = computed(() => ({
  'bg-emerald-50 text-emerald-700': gateStatus.value === 'PASS',
  'bg-amber-50 text-amber-700': gateStatus.value === 'PARTIAL',
  'bg-rose-50 text-rose-700': gateStatus.value === 'BLOCKED',
}))
const claimTitle = (claim:QuerySupportClaim) => {
  const confidence = typeof claim.confidence === 'number' ? `置信度 ${Math.round(claim.confidence * 100)}%` : ''
  const conditions = claim.conditions?.length ? ` · 条件：${claim.conditions.join('；')}` : ''
  return `${confidence}${conditions}`
}
const citationTitle = (citation:QueryCitation) => {
  const parts = [citation.sourceName || '—']
  if (citation.pageNo) parts.push(`P${citation.pageNo}`)
  if (citation.channel) parts.push(citation.channel)
  if (typeof citation.score === 'number') parts.push(citation.score.toFixed(3))
  return parts.join(' · ')
}
</script>
<style scoped>
.md-answer{padding:0.25rem 1rem 0.25rem 0;font-size:14px;line-height:1.8;color:#1f2937}
.md-answer :deep(p){margin:0.5rem 0}
.md-answer :deep(.md-heading){margin:0.9rem 0 0.4rem;font-weight:600;color:#111827}
.md-answer :deep(.md-heading:first-child){margin-top:0.2rem}
.md-answer :deep(ul),.md-answer :deep(ol){margin:0.4rem 0;padding-left:1.4rem}
.md-answer :deep(li){margin:0.2rem 0}
.md-answer :deep(blockquote){margin:0.5rem 0;border-left:3px solid #c7d2fe;padding:0.1rem 0 0.1rem 0.75rem;color:#4b5563}
.md-answer :deep(.md-code){border-radius:4px;background:#f3f4f6;padding:0.1rem 0.3rem;font-family:ui-monospace,monospace;font-size:12px}
.md-answer :deep(.md-pre){margin:0.5rem 0;border-radius:8px;background:#0f172a;color:#e2e8f0;padding:0.75rem 1rem;overflow:auto;font-size:12px;line-height:1.6}
.md-answer :deep(.md-cite){margin-left:2px;color:#4f46e5;font-weight:600;cursor:help}
</style>
