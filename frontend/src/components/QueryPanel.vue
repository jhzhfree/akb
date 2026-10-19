<template>
<section class="kb-page max-w-[1180px]">
  <header class="kb-section-head"><div><div class="kb-eyebrow">混合检索与图谱增强</div>
    <h2 class="kb-title mt-1">智能问答</h2><p class="kb-muted mt-1">查询理解 → 多路召回 → 融合排序 → 重排 → 证据上下文 → 可追溯回答。</p></div></header>
  <!-- 检索模块各入口统一由页面顶部的模块 Tab 承载，这里不再重复一排同样的菜单 -->
  <!-- 历史列比原来宽一些：单行要放下「问题 + 时间 + 耗时」，280px 会把问题截得只剩两三个字。 -->
  <div class="grid gap-4 lg:grid-cols-[1fr_340px]">
    <div>
      <article class="kb-card overflow-hidden">
        <textarea :value="query" rows="5" class="w-full resize-none border-0 px-5 py-4 text-[15px] leading-7 text-ink-900 outline-none"
          :placeholder="defaultQuery || '向企业知识库提问…'" @input="$emit('update:query',($event.target as HTMLTextAreaElement).value)" />
        <footer class="flex items-center justify-between border-t border-ink-100 bg-ink-50/70 px-4 py-2.5">
          <span class="text-[11px] text-ink-500">回答必须可追溯到 证据 / 来源</span>
          <div class="flex items-center gap-2">
            <label class="flex items-center gap-1 text-[11px] text-ink-500">
              返回格式
              <select
                class="rounded border border-ink-200 bg-white px-1.5 py-1 text-[11px] text-ink-700 outline-none"
                :value="answerFormat"
                @change="$emit('update:answerFormat',($event.target as HTMLSelectElement).value as AnswerFormat)">
                <option v-for="option in ANSWER_FORMATS" :key="option.value" :value="option.value" :title="option.hint">
                  {{ option.label }}
                </option>
              </select>
            </label>
            <button class="kb-btn-primary" :disabled="asking" @click="$emit('ask')">{{ asking ? '检索中…' : '发送问题' }}</button>
          </div>
        </footer>
        <div class="px-4 pb-3">
          <SampleQuestionChips :items="chips" @pick="pickQuestion" />
        </div>
      </article>

      <div v-if="asking" class="kb-card mt-4 flex items-center gap-2 p-4 text-sm text-ink-500">
        <span class="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[color:var(--info)] border-t-transparent"/>
        正在检索知识库并生成回答…
      </div>

      <template v-else-if="result">
        <!-- 用户问题气泡（llmwiki 对话式样式） -->
        <div class="mt-4 flex justify-end">
          <div class="max-w-[80%] rounded-2xl rounded-br-sm bg-brand-600 px-4 py-2.5 text-sm leading-6 text-[color:var(--on-brand)] shadow-sm">
            {{ result.question || query }}
          </div>
        </div>

        <!-- 助手回答卡片 -->
        <article class="kb-card mt-4 p-4">
          <!-- 安全：两种 HTML 来源都先转义再生成标签——服务端的 html 格式如此，
               utils/markdown.ts 的 renderMarkdown 同样先 escapeHtml。 -->
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div v-if="isHtml" class="md-answer" v-html="props.result?.answer || ''"></div>
          <pre v-else-if="isText" class="md-text">{{ props.result?.answer || '' }}</pre>
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div v-else class="md-answer" v-html="renderedAnswer"></div>

          <!-- 结论来源：这句话是查到的、走图谱走出来的，还是规则推出来的 -->
          <p v-if="conclusionLabel" class="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px]">
            <span class="kb-badge" :class="conclusionClass" :title="conclusionHint">结论来源 · {{ conclusionLabel }}</span>
            <span class="text-ink-500">{{ conclusionHint }}</span>
          </p>

          <p class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-ink-500">
            <span v-if="result.questionTypeLabel" class="kb-badge kb-state-info">{{ result.questionTypeLabel }}</span>
            <span v-if="rewritten" title="改写后查询：{{ result.rewrittenQuery }}">已改写 ↻</span>
            <span :title="result.routerNote || ''">召回通道：{{ (result.channels || result.strategies || []).join(' + ') || '（无）' }}</span>
            <span v-if="result.reranked" class="kb-badge kb-state-ok">已重排序</span>
            <span v-if="gateStatus" class="kb-badge" :class="gateClass">门控 {{ gateStatus }}</span>
            <span v-if="typeof result.confidence === 'number' && result.confidence > 0"
              title="检索强度 + 断言支撑的综合估计">置信度 {{ Math.round(result.confidence * 100) }}%</span>
            <span v-if="typeof result.contextChars === 'number'">上下文 {{ result.contextChars.toLocaleString() }} 字符</span>
            <span v-if="result.durationMs">耗时 {{ result.durationMs }}ms</span>
            <span v-if="result.model">模型 {{ result.model }}</span>
          </p>

          <div v-if="(result.claims || []).length" class="mt-2 border-t border-ink-100 pt-2">
            <p class="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-500">支撑断言</p>
            <div class="flex flex-wrap gap-1.5">
              <span v-for="claim in result.claims" :key="claim.claimId"
                class="kb-badge cursor-default"
                :class="claim.status === 'EFFECTIVE' ? 'kb-state-ok' : 'kb-state-warn'"
                :title="claimTitle(claim)">
                {{ claim.subject }} --{{ claim.predicate }}--&gt; {{ claim.object }}
              </span>
            </div>
          </div>

          <div v-if="graphPaths.length" class="mt-3 border-t border-ink-100 pt-3">
            <p class="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-500">推导路径 · {{ graphPaths.length }} 条</p>
            <ol class="space-y-1">
              <li v-for="(path,pathIndex) in graphPaths.slice(0,5)" :key="pathIndex"
                class="flex flex-wrap items-center gap-1 text-xs text-ink-700">
                <template v-for="(hop,hopIndex) in path" :key="hopIndex">
                  <span v-if="hopIndex === 0">{{ hop.sourceName || '—' }}</span>
                  <span class="kb-badge kb-state-info">--{{ hop.relationType }}--&gt;</span>
                  <span>{{ hop.targetName || '—' }}</span>
                </template>
              </li>
            </ol>
          </div>

          <div v-if="(result.citations || []).length" class="mt-3 border-t border-ink-100 pt-3">
            <p class="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-500">引用来源</p>
            <div class="flex flex-wrap gap-1.5">
              <button v-for="citation in result.citations" :key="citation.index ?? citation.objectId"
                class="rounded px-1.5 py-0.5 text-xs text-accent hover:bg-[color:var(--info-bg)] hover:underline"
                :title="citationTitle(citation)"
                @click="$emit('open-citation',citation)">
                [{{ citation.index ?? 0 }}] {{ citation.title || citation.objectId || 'Evidence' }}
              </button>
            </div>
          </div>

          <div class="mt-3 flex justify-end border-t border-ink-100 pt-3">
            <button v-if="result.queryId" class="kb-btn kb-btn-outline text-xs" @click="$emit('open-trace',result.queryId)">查看完整链路</button>
          </div>
        </article>
      </template>
      <div v-else class="mt-16 text-center text-sm text-ink-500">输入问题开始检索</div>
    </div>
    <aside class="kb-card h-fit overflow-hidden">
      <div class="kb-card-head"><div><b class="text-sm">问答历史</b><div class="text-[10px] text-ink-500">查询历史</div></div>
        <button class="text-xs text-accent" @click="$emit('refresh-history')">刷新</button></div>
      <!-- 列表形态与「知识概览 · 知识治理」一致：卡片内的次级表面块（--bg-sub）+
           语义圆点 + 名称 + 数字胶囊 + hover 才出现的箭头，静态时也有明确层次。 -->
      <div class="p-2">
        <div class="max-h-[560px] min-h-[132px] space-y-1 overflow-y-auto rounded-lg bg-[color:var(--bg-sub)] p-1.5">
          <button v-for="item in history" :key="item.queryId"
            class="group flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left transition-colors hover:bg-brand-500/10"
            :title="`${item.originalQuery}${item.errorCode?`（失败：${item.errorCode}）`:''} · 点击查看完整链路`"
            @click="$emit('open-trace',item.queryId)">
            <i class="kb-dot" :class="historyDot(item)"></i>
            <span class="min-w-0 flex-1 truncate text-[13px] text-ink-700">{{ item.originalQuery }}</span>
            <span class="flex-none text-[11px] text-ink-500 tabular-nums">{{ time(item.createdTime) }}</span>
            <b class="kb-badge flex-none tabular-nums" :class="item.errorCode?'kb-state-danger':'kb-state-neutral'"
              :title="`耗时 ${item.latencyMs || 0}ms`">{{ latency(item.latencyMs) }}</b>
            <span class="flex-none text-[12px] text-ink-500 opacity-0 transition-opacity group-hover:opacity-100">→</span>
          </button>
          <div v-if="!history.length" class="px-2 py-7 text-center">
            <p class="text-[13px] text-ink-600">暂无问答历史</p>
            <p class="kb-muted mt-1">提问后，历史与完整链路会汇总到这里</p>
          </div>
        </div>
      </div>
    </aside>
  </div>
</section>
</template>
<script setup lang="ts">
import { computed, nextTick } from 'vue'
import type { QueryCitation, QueryHistoryItem, QueryResult, QuerySupportClaim } from '../types'
import { ANSWER_FORMATS } from '../api/contracts'
import type { AnswerFormat } from '../api/contracts'
import type { SampleQuestion } from '../api/knowledge'
import { renderMarkdown } from '../utils/markdown'
import SampleQuestionChips from './SampleQuestionChips.vue'

const props = defineProps<{
  query:string; result:QueryResult|null; asking:boolean; history:QueryHistoryItem[]
  /** 请求与展示共用：服务端回传实际生效的格式，避免未知取值被当成默认。 */
  answerFormat:AnswerFormat
  /** 当前知识库的场景测试语句：换库即换示例，点选后直接提问。 */
  samples?:SampleQuestion[]
  /** 知识库预置的默认检索语句：作输入框提示，并排在示例句首位，点选即发起。 */
  defaultQuery?:string|null
}>()
const emit = defineEmits<{
  ask:[]; 'update:query':[value:string]; 'update:answerFormat':[value:AnswerFormat]
  'open-trace':[qid:string]; 'refresh-history':[]; 'open-citation':[citation:QueryCitation]
}>()

/** 库自带的默认句排在评测示例之前：它是这个库的“入口问题”，点一下即可发起。 */
const chips=computed<SampleQuestion[]>(()=>{
  const text=(props.defaultQuery||'').trim()
  const items:SampleQuestion[]=text?[{question:text,difficulty:'库默认',expectedObjectCodes:[]}]:[]
  return items.concat((props.samples||[]).filter(v=>v.question!==text))
})
/** 点选示例后等 query 回写到父级再发起提问，避免用旧问题检索。 */
function pickQuestion(text:string) {
  emit('update:query', text)
  nextTick(() => emit('ask'))
}

/** 以服务端回传的为准：它把无法识别的取值退回 default 后原样带回。 */
const effectiveFormat = computed<AnswerFormat>(() => {
  const delivered = props.result?.answerFormat
  if (delivered === 'markdown' || delivered === 'text' || delivered === 'html') return delivered
  return props.answerFormat
})
const isHtml = computed(() => effectiveFormat.value === 'html')
const isText = computed(() => effectiveFormat.value === 'text')
/** 历史行内的时间只到「月-日 时:分」：列表单行排布，完整时间戳会挤掉问题文本。 */
const time=(v?:string)=>{
  if(!v)return '—'
  const d=new Date(v), p=(n:number)=>String(n).padStart(2,'0')
  return `${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
/** 耗时胶囊：秒级以上折成 s，避免 5 位数字把单行挤满；精确值留在 title 里。 */
const latency=(ms?:number)=>{const v=Number(ms||0); return v>=1000?`${(v/1000).toFixed(1)}s`:`${v}ms`}
/** 历史项状态点：失败红 / 有回答绿 / 其余中性，与「知识治理」同一套语义色。 */
const historyDot=(item:QueryHistoryItem)=>
  item.errorCode ? 'kb-dot-danger' : item.answer ? 'kb-dot-ok' : 'kb-dot-neutral'

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
  'kb-state-ok': gateStatus.value === 'PASS',
  'kb-state-warn': gateStatus.value === 'PARTIAL',
  'kb-state-danger': gateStatus.value === 'BLOCKED',
}))
/**
 * 结论来源：一句话说清这个答案是怎么来的。
 * 查到的和推出来的在界面上长得一样时，没人知道该不该追问一句"依据呢"。
 */
const conclusionType = computed(() => String(props.result?.conclusionSource?.type || ''))
const conclusionLabel = computed(() => String(props.result?.conclusionSource?.label || ''))
const conclusionClass = computed(() => ({
  'kb-state-info': conclusionType.value === 'GRAPH_TRAVERSAL',
  'kb-state-warn': conclusionType.value === 'DERIVED',
  'kb-state-ok': !conclusionType.value || conclusionType.value === 'RETRIEVED',
}))
const conclusionHint = computed(() => {
  if (conclusionType.value === 'GRAPH_TRAVERSAL') return '沿图谱关系遍历得出，原文未直接写出'
  if (conclusionType.value === 'DERIVED') return '由衍生规则推导，可展开基础断言核对'
  return '来自资料原文检索'
})
const graphPaths = computed(() => props.result?.conclusionSource?.graphPaths || [])

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
/* 答案排版已提到 src/answer.css（全局）：那是全站「检索结果长什么样」的唯一基准，
   与嵌入组件 public/embed/kos-chat.js 内 .md-* 规则同值，由 scripts/check-answer-style.mjs 校验。
   这里不再重复定义，避免出现第二套 .md-answer 规则。 */
</style>
