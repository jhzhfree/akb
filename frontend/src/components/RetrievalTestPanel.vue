<template>
  <DebugLayout
    eyebrow="RETRIEVAL TEST"
    title="检索测试"
    hint="只跑检索链不生成答案：查询改写 → 路由 → 多路召回 → RRF 融合 → Rerank，结果逐条可比，链路可回放。"
    config-hint="查询改写 / Hybrid / RRF / Rerank"
    result-hint="排序候选与链路摘要">
    <template #config>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-[minmax(0,1fr)_120px_auto]">
        <input v-model="question" class="kb-input" placeholder="输入要检索的问题…" @keyup.enter="run" />
        <input v-model.number="topK" type="number" min="1" max="50" class="kb-input" placeholder="topK" />
        <button class="kb-btn-primary shrink-0" :disabled="running || !question.trim()" @click="run">
          {{ running ? '检索中…' : '执行检索' }}
        </button>
      </div>
      <SampleQuestionChips :items="samples || []" @pick="pickQuestion" />
      <p class="kb-muted mt-2">纯检索不调用生成模型，适合对比不同 topK 与改写结果下的召回质量。</p>
    </template>

    <template #result>
      <PanelState
        :loading="running"
        :error="error"
        :empty="!candidates.length && !running && !error"
        empty-text="尚无检索结果"
        empty-hint="输入问题后执行检索，候选会按融合分数排序展示"
        retryable
        @retry="run">
        <el-table :data="candidates" border size="small" max-height="460">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column label="标题" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.title || row.objectCode || row.unitCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="通道命中" width="180">
            <template #default="{ row }">
              <span v-if="hits(row).length" class="flex flex-wrap gap-1">
                <span v-for="hit in hits(row)" :key="hit.channel" class="kb-badge">{{ hit.label }} #{{ hit.rank ?? '—' }}</span>
              </span>
              <span v-else>{{ row.channel || row.source || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="分数" width="110">
            <template #default="{ row }">
              <div>{{ score(row) }}</div>
              <div class="text-[10px] text-ink-500">RRF {{ rrfScore(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ row.objectType || row.chunkType || '—' }}</template>
          </el-table-column>
          <el-table-column label="内容" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ row.excerpt || row.content || '—' }}</template>
          </el-table-column>
        </el-table>

        <div class="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-ink-100 pt-3">
          <span class="kb-muted">
            召回 {{ recallCount }} · 融合 {{ fusionLabel }} · 重排 {{ rerankLabel }}
          </span>
          <button v-if="queryId" class="kb-btn-outline text-[12px]" @click="emit('open-trace', queryId)">
            查看 Trace
          </button>
        </div>
        <ScoreBreakdownPanel v-if="breakdownCount" class="mt-4" :candidates="candidates" />
      </PanelState>
    </template>

    <template #side>
      <article class="kb-card">
        <div class="kb-card-head">
          <div><b class="text-sm">链路回放</b><div class="text-[10px] text-ink-500">链路回放</div></div>
        </div>
        <div class="p-3">
          <p class="kb-muted">
            每次检索都会生成一条 trace，包含改写、路由、各通道召回、融合与重排明细。
          </p>
          <div v-if="recent.length" class="mt-3 space-y-2">
            <button
              v-for="item in recent" :key="item.queryId"
              class="block w-full rounded-lg border border-ink-100 px-3 py-2 text-left text-[12px] hover:border-[color:var(--line-soft)] hover:bg-[color:var(--bg-sub)]"
              @click="emit('open-trace', item.queryId)">
              <b class="line-clamp-2 font-medium text-ink-700">{{ item.query }}</b>
              <div class="mt-1 flex items-center justify-between text-[11px] text-ink-500">
                <span>{{ item.at }}</span><span>{{ item.count }} 条候选</span>
              </div>
            </button>
          </div>
          <p v-else class="kb-muted mt-3">执行检索后可在此回放链路。</p>
        </div>
      </article>
    </template>
  </DebugLayout>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { retrieveKnowledge, unwrap } from '../api/knowledge'
import { errorMessage as message } from '../utils/feedback'
import DebugLayout from './DebugLayout.vue'
import PanelState from './PanelState.vue'
import ScoreBreakdownPanel from './ScoreBreakdownPanel.vue'
import SampleQuestionChips from './SampleQuestionChips.vue'
import type { SampleQuestion } from '../api/knowledge'
import type { ScoreBreakdown, ScoreChannelHit } from '../types'

type Candidate = Record<string, unknown>

const props = defineProps<{ knowledgeBaseId:number; samples?:SampleQuestion[] }>()
const emit = defineEmits<{ 'open-trace':[qid:string] }>()

const question = ref('')
const topK = ref(8)
/** 点选场景测试语句后直接跑一次检索，省去复制粘贴。 */
function pickQuestion(text:string) {
  question.value = text
  run()
}
const running = ref(false)
const error = ref('')
const result = ref<Record<string, unknown> | null>(null)
const recent = ref<{ queryId:string; query:string; at:string; count:number }[]>([])

const candidates = computed<Candidate[]>(() => {
  const raw = result.value?.candidates
  return Array.isArray(raw) ? raw as Candidate[] : []
})
const queryId = computed(() => String(result.value?.queryId || ''))
/** 召回数量：recall 可能是按通道分组的对象，也可能是数组。 */
const recallCount = computed(() => {
  const recall = result.value?.recall
  if (Array.isArray(recall)) return recall.length
  if (recall && typeof recall === 'object') {
    return Object.values(recall as Record<string, unknown>)
      .reduce<number>((sum, value) => sum + (Array.isArray(value) ? value.length : Number(value) || 0), 0)
  }
  return candidates.value.length
})
const fusionLabel = computed(() => {
  const fusion = result.value?.fusion
  if (!fusion) return '—'
  if (typeof fusion === 'string') return fusion
  return String((fusion as Record<string, unknown>).method || (fusion as Record<string, unknown>).strategy || 'RRF')
})
const rerankLabel = computed(() => {
  const reranker = result.value?.reranker as Record<string, unknown> | undefined
  if (!reranker) return '—'
  return String(reranker.enabled ?? reranker.applied ?? '') === 'true' ? '已启用' : String(reranker.model || '已启用')
})

function score(row:Candidate) {
  const value = row.score ?? row.finalScore ?? row.rerankScore
  return typeof value === 'number' ? value.toFixed(4) : '—'
}
/** 融合分（RRF）与最终分并列展示：重排开与关时两条曲线的差异一眼可见。 */
function rrfScore(row:Candidate) {
  const breakdown = row.scoreBreakdown as ScoreBreakdown | undefined
  const value = breakdown?.rrfScore ?? row.rrfScore
  return typeof value === 'number' ? Number(value).toFixed(4) : '—'
}
/** 命中通道：优先取分数明细里的通道项，缺失时回退到单一 channel 字段。 */
function hits(row:Candidate):ScoreChannelHit[] {
  const breakdown = row.scoreBreakdown as ScoreBreakdown | undefined
  if (breakdown && Array.isArray(breakdown.channels)) return breakdown.channels
  const ranks = row.channelRanks as Record<string, number> | undefined
  if (ranks && Object.keys(ranks).length) {
    return Object.entries(ranks).map(([channel, rank]) => ({ channel, label:channel, rank }))
  }
  return []
}
const breakdownCount = computed(() => candidates.value.filter(c => c.scoreBreakdown).length)

async function run() {
  if (!question.value.trim()) return
  running.value = true
  error.value = ''
  try {
    result.value = unwrap(await retrieveKnowledge({
      knowledgeBaseId:props.knowledgeBaseId,
      query:question.value.trim(),
      topK:topK.value,
    }), null)
    const queryId = String(result.value?.queryId || '')
    if (queryId) {
      recent.value = [
        { queryId, query:question.value.trim(), at:new Date().toLocaleTimeString(), count:candidates.value.length },
        ...recent.value.filter(item => item.queryId !== queryId),
      ].slice(0, 5)
    }
  } catch (e) {
    error.value = message(e)
    result.value = null
  } finally {
    running.value = false
  }
}
</script>
