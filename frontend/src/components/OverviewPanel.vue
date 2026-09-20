<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">KNOWLEDGE WORKBENCH</div>
        <h1 class="kb-title mt-1">知识工作台</h1>
        <p class="kb-muted mt-1">
          看知识：资产规模、生命周期进度与需要人工介入的治理项，一处发现、三处闭环。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-3">
        <div class="rounded-xl border border-ink-200 bg-white px-4 py-2 text-center">
          <div class="text-[11px] text-ink-500">知识健康度</div>
          <div class="text-xl font-semibold" :class="healthClass">{{ healthScore }}%</div>
        </div>
        <button class="kb-btn-primary" @click="$emit('navigate','create.home')">＋ 创建知识</button>
        <button class="kb-btn-outline" @click="$emit('navigate','search.query')">知识搜索</button>
        <div class="flex flex-col items-start gap-0.5">
          <button class="text-xs font-medium text-indigo-600 hover:text-indigo-700" @click="$emit('navigate','workbench.version')">版本 →</button>
          <button class="text-xs font-medium text-indigo-600 hover:text-indigo-700" @click="$emit('navigate','workbench.quality')">质量 →</button>
        </div>
      </div>
    </header>

    <!-- §3.1 KPI 卡：六类资产均可点击，进入工作台内的资产浏览二级页面 -->
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
      <button
        v-for="card in kpiCards"
        :key="card.type"
        class="kb-metric group transition hover:-translate-y-0.5 hover:border-indigo-200 hover:shadow-md"
        @click="$emit('open-assets',card.type)">
        <span class="kb-metric-label">{{ card.label }}</span>
        <strong class="kb-metric-value">{{ card.value }}</strong>
        <small class="kb-metric-hint">{{ card.hint }} · 查看 →</small>
      </button>
    </div>

    <!-- §3.2 生命周期组件：接入 → 解析 → 抽取 → 衍生 → 裁决 → 发布 -->
    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">知识生命周期</b>
        <span class="kb-muted">点击任一阶段进入对应处理页面</span>
      </div>
      <div class="overflow-x-auto p-4">
        <div class="flex min-w-max items-stretch gap-2">
          <template v-for="(stage,i) in stages" :key="stage.code">
            <button
              class="min-w-[120px] rounded-xl border px-3 py-2 text-left transition hover:border-indigo-200 hover:bg-indigo-50/60"
              :class="stage.abnormal > 0 ? 'border-amber-200 bg-amber-50/60' : 'border-ink-200 bg-white'"
              @click="$emit('navigate',stage.target)">
              <div class="flex items-center justify-between">
                <span class="text-[13px] font-medium text-ink-800">{{ stage.label }}</span>
                <span v-if="stage.abnormal > 0" class="rounded bg-amber-100 px-1.5 text-[11px] text-amber-700">
                  {{ stage.abnormal }} 异常
                </span>
              </div>
              <div class="mt-1 text-lg font-semibold text-ink-900">{{ stage.count }}</div>
            </button>
            <span v-if="i < stages.length-1" class="self-center text-ink-300">→</span>
          </template>
        </div>
      </div>
    </article>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
      <!-- §3.3 治理卡片：只展示需要行动的数字 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">知识治理</b>
          <span class="kb-muted">红色阻断 · 橙色待处理 · 绿色正常</span>
        </div>
        <div class="divide-y divide-ink-100 px-4">
          <button
            v-for="item in governanceItems"
            :key="item.key"
            class="flex w-full items-center justify-between py-3 text-sm transition hover:bg-ink-50/60"
            @click="$emit('navigate',item.target)">
            <span class="flex items-center gap-2 text-ink-600">
              <i class="h-2 w-2 rounded-full" :class="levelDot(item.level)"></i>{{ item.label }}
            </span>
            <b :class="levelText(item.level)">{{ item.count }}</b>
          </button>
          <div v-if="!governanceItems.length" class="py-6 text-center text-[12px] text-ink-400">
            治理数据加载中
          </div>
        </div>
      </article>

      <!-- 知识资产分布：来源类型 / 文档类型 -->
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">知识资产分布</b><span class="kb-muted">Distribution</span></div>
        <div class="space-y-3 p-4">
          <div>
            <div class="mb-1 text-[12px] text-ink-500">来源类型</div>
            <div v-if="sourceTypes.length" class="space-y-1.5">
              <div v-for="row in sourceTypes" :key="row.label" class="flex items-center gap-2 text-[12px]">
                <span class="w-24 shrink-0 truncate text-ink-600">{{ row.label }}</span>
                <span class="h-1.5 flex-1 overflow-hidden rounded bg-ink-100">
                  <span class="block h-full rounded bg-indigo-400" :style="{ width:ratio(row.count,sourceTotal) }"></span>
                </span>
                <b class="w-8 text-right">{{ row.count }}</b>
              </div>
            </div>
            <div v-else class="text-[12px] text-ink-400">暂无来源数据</div>
          </div>
          <div>
            <div class="mb-1 text-[12px] text-ink-500">文档状态</div>
            <div v-if="documentStatus.length" class="space-y-1.5">
              <div v-for="row in documentStatus" :key="row.label" class="flex items-center gap-2 text-[12px]">
                <span class="w-24 shrink-0 truncate text-ink-600">{{ row.label }}</span>
                <span class="h-1.5 flex-1 overflow-hidden rounded bg-ink-100">
                  <span class="block h-full rounded bg-cyan-400" :style="{ width:ratio(row.count,documentTotal) }"></span>
                </span>
                <b class="w-8 text-right">{{ row.count }}</b>
              </div>
            </div>
            <div v-else class="text-[12px] text-ink-400">暂无文档数据</div>
          </div>
        </div>
      </article>

      <!-- 最近更新 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">最近更新</b>
          <button class="text-xs font-medium text-indigo-600 hover:text-indigo-700" @click="$emit('open-assets','card')">
            全部资产 →
          </button>
        </div>
        <div class="divide-y divide-ink-100">
          <button
            v-for="row in recent"
            :key="row.objectCode"
            class="block w-full px-4 py-3 text-left transition hover:bg-ink-50/60"
            @click="$emit('open-assets',row.objectType==='CLAIM' ? 'assertion' : 'card')">
            <div class="flex items-center gap-2">
              <el-tag size="small" effect="plain">{{ row.objectType === 'CLAIM' ? '断言' : '卡片' }}</el-tag>
              <span class="truncate text-[13px] text-ink-800">{{ row.displayTitle || row.objectCode }}</span>
            </div>
            <div class="mt-1 flex items-center gap-2 text-[11px] text-ink-400">
              <span>{{ row.status || '—' }}</span>
              <span v-if="row.confidence != null">置信 {{ Number(row.confidence).toFixed(2) }}</span>
              <span>{{ row.updatedTime ? String(row.updatedTime).slice(0,16) : '' }}</span>
            </div>
          </button>
          <div v-if="!recent.length" class="px-4 py-6 text-center text-[12px] text-ink-400">
            <p>该空间还没有知识对象</p>
            <button class="kb-btn-outline mt-2" @click="$emit('navigate','create.import')">导入文档</button>
          </div>
        </div>
      </article>
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
      <!-- 图谱概览 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">图谱概览</b>
          <button class="text-xs font-medium text-indigo-600 hover:text-indigo-700" @click="$emit('navigate','workbench.graph')">
            进入图谱 →
          </button>
        </div>
        <div class="grid grid-cols-2 gap-3 p-4">
          <div class="rounded-lg bg-ink-50 p-3">
            <div class="text-[11px] text-ink-500">实体</div>
            <b class="text-lg">{{ summary?.entityCount ?? 0 }}</b>
          </div>
          <div class="rounded-lg bg-ink-50 p-3">
            <div class="text-[11px] text-ink-500">关系</div>
            <b class="text-lg">{{ summary?.relationCount ?? 0 }}</b>
          </div>
        </div>
      </article>

      <!-- 检索质量：与工作台同一屏，便于发现「可检索性」问题 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">检索质量</b>
          <span class="kb-muted">最近一次评测</span>
        </div>
        <div v-if="quality" class="grid grid-cols-2 gap-3 p-4 text-[12px]">
          <div class="rounded-lg bg-ink-50 p-3">
            <div class="text-[11px] text-ink-500">Hit@1 / Hit@5</div>
            <b class="text-base">{{ pct(quality.retrieval?.hitAt1Rate) }} / {{ pct(quality.retrieval?.hitAt5Rate) }}</b>
          </div>
          <div class="rounded-lg bg-ink-50 p-3">
            <div class="text-[11px] text-ink-500">引用准确率</div>
            <b class="text-base">{{ pct(quality.citation?.accuracy) }}</b>
          </div>
          <div class="rounded-lg bg-ink-50 p-3">
            <div class="text-[11px] text-ink-500">已生效知识占比</div>
            <b class="text-base">{{ pct(quality.trustedCoverage?.ratio) }}</b>
          </div>
          <div class="rounded-lg bg-ink-50 p-3">
            <div class="text-[11px] text-ink-500">过期知识</div>
            <b class="text-base">{{ quality.freshness?.expiredCount ?? 0 }}</b>
          </div>
        </div>
        <div v-else class="p-4 text-[12px] text-ink-400">
          尚未产生检索质量数据，可先在「知识检索 / Benchmark」运行一次评测。
        </div>
      </article>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { Capabilities, DashboardGovernance, DashboardLifecycle, DashboardSummary, GovernanceItem, KnowledgeDocument, KnowledgeSource, Overview, QualityOverview, RecentObject } from '../types'
import type { AssetType } from './AssetBrowserPanel.vue'
import { getDashboardGovernance, getDashboardLifecycle, getDocuments, getQualityOverview, getSources, unwrap } from '../api/knowledge'

const props = defineProps<{
  knowledgeBaseId:number
  overview:Overview
  capabilities:Capabilities|null
  summary:DashboardSummary|null
  lifecycle:DashboardLifecycle|null
  governance:DashboardGovernance|null
}>()
defineEmits<{ navigate:[key:string]; 'open-assets':[type:AssetType] }>()

/** KPI 卡：六类资产，点击后进入资产浏览并带上类型（设计 §3.1）。 */
const kpiCards = computed<{ type:AssetType; label:string; value:number; hint:string }[]>(() => {
  const s = props.summary
  return [
    { type:'source', label:'来源', value:s?.sourceCount ?? props.overview.sourceCount ?? 0, hint:'权威源与版本' },
    { type:'document', label:'文档', value:s?.documentCount ?? props.overview.documentCount ?? 0, hint:'解析产生的文档' },
    { type:'card', label:'卡片', value:s?.cardCount ?? props.overview.cardCount ?? 0, hint:'可直接引用的知识' },
    { type:'assertion', label:'断言', value:s?.claimCount ?? props.overview.claimCount ?? 0, hint:'带证据的命题' },
    { type:'entity', label:'实体', value:s?.entityCount ?? 0, hint:'归一后的规范实体' },
    { type:'relation', label:'关系', value:s?.relationCount ?? 0, hint:'实体之间的关系' },
  ]
})

const healthScore = computed(() =>
  Number(props.summary?.healthScore ?? props.governance?.healthScore ?? 0))
const healthClass = computed(() => {
  const score = healthScore.value
  if (score >= 90) return 'text-emerald-600'
  if (score >= 70) return 'text-amber-600'
  return 'text-rose-600'
})

/** 生命周期阶段：后端未返回时退化为静态阶段，避免页面出现空洞。 */
const stages = computed(() => {
  if (props.lifecycle?.stages?.length) return props.lifecycle.stages
  return [
    { code:'INGEST', label:'接入', count:props.overview.sourceCount ?? 0, abnormal:0, target:'create.sources' },
    { code:'PARSE', label:'解析', count:props.overview.documentCount ?? 0, abnormal:0, target:'create.jobs' },
    { code:'EXTRACT', label:'抽取', count:props.overview.chunkCount ?? 0, abnormal:0, target:'create.jobs' },
    { code:'DERIVE', label:'衍生', count:0, abnormal:0, target:'create.derivation' },
    { code:'JUDGE', label:'裁决', count:props.overview.pendingReviewCount ?? 0, abnormal:props.overview.openConflictCount ?? 0, target:'create.judgment' },
    { code:'PUBLISH', label:'发布', count:props.overview.effectiveCardCount ?? 0, abnormal:0, target:'workbench.assets' },
  ]
})

const governanceItems = computed<GovernanceItem[]>(() => props.governance?.items ?? [])
const recent = computed<RecentObject[]>(() => props.summary?.recent ?? [])

const sources = ref<KnowledgeSource[]>([])
const documents = ref<KnowledgeDocument[]>([])
const quality = ref<QualityOverview|null>(null)

const sourceTypes = computed(() => groupBy(sources.value.map(v => v.sourceType)))
const sourceTotal = computed(() => sourceTypes.value.reduce((sum, row) => sum + row.count, 0))
const documentStatus = computed(() => groupBy(documents.value.map(v => String(v.status || v.parseStatus || 'UNKNOWN'))))
const documentTotal = computed(() => documentStatus.value.reduce((sum, row) => sum + row.count, 0))

function groupBy(values:string[]):{ label:string; count:number }[] {
  const counter = new Map<string, number>()
  for (const value of values) {
    const key = String(value || 'UNKNOWN')
    counter.set(key, (counter.get(key) || 0) + 1)
  }
  return [...counter.entries()]
    .map(([label, count]) => ({ label, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 5)
}
function ratio(count:number, total:number):string {
  if (!total) return '0%'
  return `${Math.round(count * 100 / total)}%`
}
function pct(value:number|null|undefined):string {
  if (value == null) return '—'
  return `${Math.round(Number(value) * 100)}%`
}
function levelDot(level:string):string {
  if (level === 'block') return 'bg-rose-500'
  if (level === 'warn') return 'bg-amber-500'
  return 'bg-emerald-500'
}
function levelText(level:string):string {
  if (level === 'block') return 'text-rose-600'
  if (level === 'warn') return 'text-amber-600'
  return 'text-ink-700'
}

async function loadDistribution() {
  const [sourceRows, documentRows] = await Promise.all([
    getSources(props.knowledgeBaseId).then(r => unwrap(r, [] as KnowledgeSource[])).catch(() => [] as KnowledgeSource[]),
    getDocuments(props.knowledgeBaseId).then(r => unwrap(r, [] as KnowledgeDocument[])).catch(() => [] as KnowledgeDocument[]),
  ])
  sources.value = sourceRows
  documents.value = documentRows
}
async function loadQuality() {
  quality.value = await getQualityOverview(props.knowledgeBaseId, 30)
    .then(r => unwrap(r, null as QualityOverview|null))
    .catch(() => null)
}
onMounted(() => { void loadDistribution(); void loadQuality() })
watch(() => props.knowledgeBaseId, () => { void loadDistribution(); void loadQuality() })
</script>
