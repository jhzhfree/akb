<template>
  <section class="kb-card p-5">
    <div class="kb-section-head">
      <div>
        <small>知识质量</small>
        <h3>知识质量</h3>
        <p class="kb-muted mt-1">
          可信覆盖率、引用准确率、检索命中、新鲜度。每个数字都能追到表里的数据；没有跑过的评测显示未测，不用 0 假装测过。
        </p>
      </div>
      <div class="flex items-center gap-2">
        <select v-model.number="withinDays" class="kb-input w-32" @change="load">
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
          <option :value="90">近 90 天</option>
        </select>
        <button class="kb-btn-outline text-[12px]" @click="load">刷新</button>
      </div>
    </div>

    <PanelState class="mt-4" :loading="loading" :error="error" :empty="!data" empty-text="暂无质量数据" retryable @retry="load">
      <div v-if="data" class="space-y-5">
        <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <div class="kb-metric">
            <span>可信覆盖率（TKC）</span>
            <b>{{ percent(data.trustedCoverage.ratio) }}</b>
            <small>{{ data.trustedCoverage.approved }} / {{ data.trustedCoverage.total }} 已批准</small>
          </div>
          <div class="kb-metric">
            <span>引用准确率</span>
            <b>{{ data.citation.answerCount ? percent(data.citation.accuracy) : '未测' }}</b>
            <small>
              {{ data.citation.supportedClaimCount }} / {{ data.citation.claimCount }} 条声明有证据 ·
              {{ data.citation.answerCount }} 次回答
            </small>
          </div>
          <div class="kb-metric">
            <span>召回命中率@5（最近评测）</span>
            <b>{{ data.retrieval?.hitAt5Rate == null ? '未测' : percent(data.retrieval.hitAt5Rate) }}</b>
            <small>
              {{ data.retrieval ? `${data.retrieval.datasetName || data.retrieval.runCode} · ${data.retrieval.caseCount} 题` : '到评测中心跑一次后自动带上' }}
            </small>
          </div>
          <div class="kb-metric">
            <span>Freshness（{{ data.withinDays }} 天）</span>
            <b>{{ percent(data.freshness.ratio) }}</b>
            <small>{{ data.freshness.freshCount }} 更新 · {{ data.freshness.staleCount }} 未更新</small>
          </div>
        </div>

        <!-- 健康度：每个数字都是一个待办入口，不只是看板装饰。 -->
        <div>
          <b class="text-xs font-medium text-ink-700">生产健康度</b>
          <div class="mt-2 grid grid-cols-2 gap-2 md:grid-cols-3">
            <button
              class="health-tile disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="!canGo('create.judgment')"
              :title="canGo('create.judgment') ? '' : '当前角色无权访问该页面'"
              @click="go('create.judgment')">
              <span>待审核知识</span>
              <b :class="data.health.pendingReview + data.health.pendingCardReview ? 'text-amber-600' : ''">
                {{ data.health.pendingReview + data.health.pendingCardReview }}
              </b>
            </button>
            <button
              class="health-tile disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="!canGo('create.judgment')"
              :title="canGo('create.judgment') ? '' : '当前角色无权访问该页面'"
              @click="go('create.judgment')">
              <span>开放冲突</span>
              <b :class="data.health.openConflict ? 'text-rose-600' : ''">{{ data.health.openConflict }}</b>
            </button>
            <button
              class="health-tile disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="!canGo('create.jobs')"
              :title="canGo('create.jobs') ? '' : '当前角色无权访问该页面'"
              @click="go('create.jobs')">
              <span>解析失败任务</span>
              <b :class="data.health.failedIngestion ? 'text-rose-600' : ''">{{ data.health.failedIngestion }}</b>
            </button>
            <button
              class="health-tile disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="!canGo('create.judgment')"
              :title="canGo('create.judgment') ? '' : '当前角色无权访问该页面'"
              @click="go('create.judgment')">
              <span>不确定声明</span>
              <b :class="data.health.uncertainClaim ? 'text-amber-600' : ''">{{ data.health.uncertainClaim }}</b>
            </button>
            <button
              class="health-tile disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="!canGo('create.judgment')"
              :title="canGo('create.judgment') ? '' : '当前角色无权访问该页面'"
              @click="go('create.judgment')">
              <span>冲突中声明</span>
              <b :class="data.health.conflictedClaim ? 'text-rose-600' : ''">{{ data.health.conflictedClaim }}</b>
            </button>
            <button
              class="health-tile disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="!canGo('search.benchmark')"
              :title="canGo('search.benchmark') ? '' : '当前角色无权访问该页面'"
              @click="go('search.benchmark')">
              <span>已过期声明</span>
              <b :class="data.freshness.expiredCount ? 'text-amber-600' : ''">{{ data.freshness.expiredCount }}</b>
            </button>
          </div>
        </div>

        <div class="grid gap-4 lg:grid-cols-2">
          <div>
            <b class="text-xs font-medium text-ink-700">状态分布</b>
            <ul class="mt-2 space-y-2">
              <li v-for="row in data.statusBreakdown" :key="row.objectType + row.status" class="flex items-center gap-2">
                <span class="w-24 shrink-0 text-[11px] text-ink-500">{{ labelOf(row.objectType) }}</span>
                <span class="kb-badge w-32">{{ statusLabel(row.status) }}</span>
                <span class="h-2 flex-1 overflow-hidden rounded bg-ink-100">
                  <span class="block h-full rounded bg-indigo-400" :style="{ width: barWidth(row.total) + '%' }" />
                </span>
                <span class="w-10 shrink-0 text-right text-[11px] text-ink-600">{{ row.total }}</span>
              </li>
            </ul>
          </div>
          <div>
            <b class="text-xs font-medium text-ink-700">答案侧质量</b>
            <div class="mt-2 grid grid-cols-2 gap-2">
              <div class="kb-metric"><span>忠实度</span><b>{{ fixed(data.citation.faithfulness) }}</b></div>
              <div class="kb-metric"><span>完整度</span><b>{{ fixed(data.citation.completeness) }}</b></div>
            </div>
            <p class="kb-muted mt-2">
              样本来自最近 {{ data.citation.answerCount }} 次带证据评估的回答；
              {{ data.citation.answerCount ? '样本太少时该指标只作参考。' : '还没有可评估的回答样本。' }}
            </p>
            <p v-if="data.retrieval" class="mt-2 text-xs text-ink-500">
              最近评测：{{ data.retrieval.runCode }} · MRR {{ fixed(data.retrieval.mrr) }} ·
              NDCG@10 {{ fixed(data.retrieval.ndcgAt10) }} · P95 {{ data.retrieval.latencyP95Ms ?? '—' }} ms
            </p>
          </div>
        </div>
      </div>
    </PanelState>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import PanelState from './PanelState.vue'
import { getQualityOverview } from '../api/knowledge'
import { statusLabel } from '../types'
import type { QualityOverview } from '../types'
import { useShell } from '../shell'

const props = defineProps<{ knowledgeBaseId:number }>()
const emit = defineEmits<{ navigate:[view:string] }>()

const shell = useShell()

const data = ref<QualityOverview | null>(null)
const loading = ref(false)
const error = ref('')
const withinDays = ref(30)

const percent = (value?:number|null) => value == null ? '—' : (value * 100).toFixed(1) + '%'
const fixed = (value?:number|null) => value == null ? '—' : Number(value).toFixed(3)
const labelOf = (type:string) => type === 'CARD' ? 'Card' : type === 'CLAIM' ? 'Assertion' : type
/** 状态条按当前知识库的最大值归一：看的是分布，不是跨库可比的分。 */
const barWidth = (total:number) => {
  const max = Math.max(1, ...(data.value?.statusBreakdown || []).map(r => r.total))
  return Math.round((total / max) * 100)
}
const go = (view:string) => emit('navigate', view)
/** 健康度磁贴的下钻目标可能超出当前角色（裁决 / 生成任务 / Benchmark），无权时置灰。 */
const canGo = (view:string) => shell.canNavigate(view)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await getQualityOverview(props.knowledgeBaseId, withinDays.value)
    data.value = res.data.data || null
  } catch (e) {
    error.value = (e as Error).message || '质量数据加载失败'
  } finally { loading.value = false }
}

watch(() => props.knowledgeBaseId, load)
load()
</script>

<style scoped>
.health-tile {
  @apply flex flex-col items-start rounded-lg border border-ink-100 bg-white px-3 py-2 text-left transition hover:border-indigo-200 hover:bg-indigo-50;
}
.health-tile span { @apply text-[11px] text-ink-500; }
.health-tile b { @apply text-lg font-semibold text-ink-800; }
</style>
