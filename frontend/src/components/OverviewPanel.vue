<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识概览</div>
        <h1 class="kb-title mt-1">知识库工作台</h1>
        <p class="kb-muted mt-1">
          看知识：资产规模、生命周期进度与需要人工介入的治理项，一处发现、三处闭环。
        </p>
      </div>
      <!-- 页头入口统一成一行胶囊：健康度是只读指标，其余四个是跳转入口，形态一致、层级清楚。 -->
      <div class="flex shrink-0 flex-wrap items-center gap-2">
        <div class="kb-entry-stat" title="按治理项与质量指标折算的综合健康度，构成见「质量」">
          <span class="kb-entry-label">知识健康度</span>
          <b :class="healthClass">{{ healthScore }}%</b>
        </div>
        <button v-if="canGenerate" class="kb-entry-primary" @click="$emit('navigate',generateTarget)">＋ 生成知识</button>
        <button class="kb-entry" @click="$emit('navigate','search.query')">知识搜索 →</button>
        <button class="kb-entry" @click="$emit('navigate','workbench.version')">版本 →</button>
        <button class="kb-entry" @click="$emit('navigate','workbench.quality')">质量 →</button>
      </div>
    </header>

    <!-- §3.1 KPI 卡：六类资产均可点击，进入工作台内的资产浏览二级页面 -->
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
      <button
        v-for="card in kpiCards"
        :key="card.type"
        class="kb-metric group transition hover:-translate-y-0.5 hover:border-[color:var(--info-bd)] hover:shadow-md"
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
              class="min-w-[120px] rounded-xl border px-3 py-2 text-left transition hover:border-[color:var(--info-bd)] hover:bg-[color:var(--info-bg)]/60"
              :class="stage.abnormal > 0 ? 'border-[color:var(--warn-bd)] bg-[color:var(--warn-bg)]/60' : 'border-ink-200'"
              @click="$emit('navigate',stage.target)">
              <div class="flex items-center justify-between">
                <span class="text-[13px] font-medium text-ink-800">{{ stage.label }}</span>
                <span v-if="stage.abnormal > 0" class="rounded bg-[color:var(--warn-bg)] px-1.5 text-[11px] text-[color:var(--warn)]">
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
      <!-- §3.3 治理卡片：只展示需要行动的数字。
           行形态与「最近更新」一致：语义圆点 + 名称 + 数字胶囊 + hover 才出现的跳转箭头，
           数字用状态胶囊而不是裸文字，阻断/待处理/正常三种级别一眼可分。 -->
      <article class="kb-card flex flex-col">
        <div class="kb-card-head">
          <b class="text-sm">知识治理</b>
          <span class="kb-muted">红色阻断 · 橙色待处理 · 绿色正常</span>
        </div>
        <div class="p-2">
          <!-- 列表是卡片内的一整块次级表面（--bg-sub），而不是散在卡片上的裸行：
               静态时与卡片有明确层次，hover 用品牌色低透明微染，两个主题下都不会出灰块补丁。 -->
          <div class="max-h-[268px] min-h-[132px] space-y-1 overflow-y-auto rounded-lg bg-[color:var(--bg-sub)] p-1.5">
            <button
              v-for="item in governanceItems"
              :key="item.key"
              class="group flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left transition-colors
                hover:bg-brand-500/10 disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:bg-transparent"
              :disabled="!canTarget(item.target)"
              :title="canTarget(item.target) ? '点击进入处理页面' : '当前角色无权访问该页面'"
              @click="$emit('navigate',item.target)">
              <i class="kb-dot" :class="levelDot(item.level)"></i>
              <span class="min-w-0 flex-1 truncate text-[13px] text-ink-700">{{ item.label }}</span>
              <b class="kb-badge flex-none tabular-nums" :class="levelBadge(item)">{{ item.count }}</b>
              <span
                v-if="canTarget(item.target)"
                class="flex-none text-[12px] text-ink-500 opacity-0 transition-opacity group-hover:opacity-100">→</span>
            </button>
            <div v-if="!governanceItems.length" class="px-2 py-7 text-center">
              <p class="text-[13px] text-ink-600">治理数据加载中</p>
              <p class="kb-muted mt-1">待审核、冲突与过期知识会自动汇总到这里</p>
            </div>
          </div>
        </div>
      </article>

      <!-- 知识资产分布：来源类型 / 文档类型 -->
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">知识资产分布</b><span class="kb-muted">分布</span></div>
        <div class="space-y-3 p-4">
          <div>
            <div class="mb-1 text-[12px] text-ink-500">来源类型</div>
            <div v-if="sourceTypes.length" class="space-y-1.5">
              <div v-for="row in sourceTypes" :key="row.label" class="flex items-center gap-2 text-[12px]">
                <span class="w-24 shrink-0 truncate text-ink-600">{{ row.label }}</span>
                <span class="h-1.5 flex-1 overflow-hidden rounded bg-ink-100">
                  <span class="block h-full rounded bg-[color:var(--info-bg)]" :style="{ width:ratio(row.count,sourceTotal) }"></span>
                </span>
                <b class="w-8 text-right">{{ row.count }}</b>
              </div>
            </div>
            <div v-else class="text-[12px] text-ink-500">暂无来源数据</div>
          </div>
          <div>
            <div class="mb-1 text-[12px] text-ink-500">文档状态</div>
            <div v-if="documentStatus.length" class="space-y-1.5">
              <div v-for="row in documentStatus" :key="row.label" class="flex items-center gap-2 text-[12px]">
                <span class="w-24 shrink-0 truncate text-ink-600">{{ row.label }}</span>
                <span class="h-1.5 flex-1 overflow-hidden rounded bg-ink-100">
                  <span class="block h-full rounded bg-[color:var(--info-bg)]" :style="{ width:ratio(row.count,documentTotal) }"></span>
                </span>
                <b class="w-8 text-right">{{ row.count }}</b>
              </div>
            </div>
            <div v-else class="text-[12px] text-ink-500">暂无文档数据</div>
          </div>
        </div>
      </article>

      <!-- 最近更新：类型 / 状态两个胶囊走全站 kb-state-* 配色（原 el-tag 是浅底深字，
           深色主题下很脏），时间改成相对时间，整行与治理列表同构。 -->
      <article class="kb-card flex flex-col">
        <div class="kb-card-head">
          <b class="text-sm">最近更新</b>
          <button
            class="text-xs font-medium text-[color:var(--accent-text)] transition-opacity hover:opacity-80"
            @click="$emit('open-assets','card')">
            全部资产 →
          </button>
        </div>
        <div class="p-2">
          <div class="max-h-[268px] min-h-[132px] space-y-1 overflow-y-auto rounded-lg bg-[color:var(--bg-sub)] p-1.5">
            <button
              v-for="row in recent"
              :key="row.objectCode"
              class="group flex w-full items-start gap-2.5 rounded-md px-2.5 py-2 text-left transition-colors hover:bg-brand-500/10"
              @click="$emit('open-assets',row.objectType==='CLAIM' ? 'assertion' : 'card')">
              <span
                class="kb-badge mt-0.5 flex-none"
                :class="row.objectType === 'CLAIM' ? 'kb-state-neutral' : 'kb-state-info'">
                {{ row.objectType === 'CLAIM' ? '断言' : '卡片' }}
              </span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-[13px] text-ink-800">{{ row.displayTitle || row.objectCode }}</span>
                <span class="mt-1 flex flex-wrap items-center gap-1.5">
                  <span class="kb-badge" :class="statusState(row.status)">{{ statusLabel(row.status) }}</span>
                  <span v-if="row.confidence != null" class="text-[11px] text-ink-500">
                    置信 {{ Number(row.confidence).toFixed(2) }}
                  </span>
                  <span class="text-[11px] text-ink-500">{{ recentTime(row.updatedTime) }}</span>
                </span>
              </span>
              <span class="mt-0.5 flex-none text-[12px] text-ink-500 opacity-0 transition-opacity group-hover:opacity-100">→</span>
            </button>
            <div v-if="!recent.length" class="px-2 py-7 text-center">
              <p class="text-[13px] text-ink-600">该空间还没有知识对象</p>
              <button v-if="canTarget('create.import')" class="kb-btn-outline mt-3" @click="$emit('navigate','create.import')">
                导入文档
              </button>
            </div>
          </div>
        </div>
      </article>
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
      <!-- 图谱概览：工作台只放关系视图本身，规模、枢纽对象这类明细留给图谱页 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">图谱概览</b>
          <button
            class="text-xs font-medium text-accent hover:text-accent disabled:cursor-not-allowed disabled:text-ink-300 disabled:hover:text-ink-300"
            :disabled="!canGraph" :title="canGraph ? '' : '当前角色无权访问知识图谱'"
            @click="$emit('navigate','search.graph')">
            进入图谱 →
          </button>
        </div>
        <div class="p-4">
          <div class="overflow-hidden rounded-lg border border-ink-200">
            <div v-if="graphLoading || !graphLoaded" class="py-20 text-center text-[12px] text-ink-500">
              正在加载关系视图…
            </div>
            <div v-else-if="!overviewNodes.length" class="py-16 text-center text-[12px] text-ink-500">
              {{ graphEmptyHint }}
            </div>
            <!-- 无图谱权限时画布只读：概览可以看，但点节点不会再跳进图谱 -->
            <GraphCanvas
              v-else compact :height="240"
              :class="canGraph ? '' : 'pointer-events-none'"
              :nodes="overviewNodes" :edges="overviewEdges" @select="openInGraph" />
          </div>
          <!-- 图例是读图的一部分，留在画布下方；其余明细不进工作台 -->
          <div v-if="overviewNodes.length" class="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1">
            <span
              v-for="item in graphLegend" :key="item.type"
              class="inline-flex items-center gap-1 text-[10px] text-ink-500">
              <i class="h-1.5 w-1.5 rounded-full" :style="{ background:item.color }"></i>{{ item.label }}
            </span>
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
            <div class="text-[11px] text-ink-500">命中率@1 / 命中率@5</div>
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
        <div v-else class="p-4 text-[12px] text-ink-500">
          尚未产生检索质量数据，可先在「知识检索 / Benchmark」运行一次评测。
        </div>
      </article>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { statusLabel } from '../types'
import type {
  Capabilities,
  DashboardGovernance,
  DashboardLifecycle,
  DashboardSummary,
  GovernanceItem,
  GraphEdge,
  GraphNode,
  GraphOverview,
  KnowledgeDocument,
  KnowledgeSource,
  Overview,
  QualityOverview,
  RecentObject
} from '../types'
import type { AssetType } from './AssetBrowserPanel.vue'
import { getDocuments, getGraphOverview, getQualityOverview, getSources, unwrap } from '../api/knowledge'
import { useShell } from '../shell'
import { PERM } from '../permissions'
import GraphCanvas, { GRAPH_LEGEND } from './GraphCanvas.vue'

const shell = useShell()
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
  if (score >= 90) return 'text-[color:var(--ok)]'
  if (score >= 70) return 'text-[color:var(--warn)]'
  return 'text-[color:var(--danger)]'
})

/** 生成入口只对有知识生成权限的角色开放；浏览员与无生成权限的角色看不到该按钮。 */
const canGenerate = computed(() => shell.can(PERM.GENERATE_IMPORT))
/**
 * 「＋ 生成知识」的落点按角色区分：管理员进生成概览，
 * 普通成员的生成模块里没有概览页（只有文档导入 / 裁决 / 衍生），因此直接进文档导入。
 */
const generateTarget = computed(() => shell.canNavigate('create.overview') ? 'create.overview' : 'create.import')
/**
 * 概览里的下钻目标可能超出当前角色（例如浏览员点「知识裁决」）。
 * 无权时把入口置灰，而不是等点了才提示或撞上 403。
 */
const canTarget = (key:string) => shell.canNavigate(key)
/** 生命周期阶段：后端未返回时退化为静态阶段，避免页面出现空洞。 */
const stages = computed(() => {
  if (props.lifecycle?.stages?.length) return props.lifecycle.stages
  return [
    { code:'INGEST', label:'接入', count:props.overview.sourceCount ?? 0, abnormal:0, target:'create.connector' },
    { code:'PARSE', label:'解析', count:props.overview.documentCount ?? 0, abnormal:0, target:'create.import' },
    { code:'EXTRACT', label:'抽取', count:props.overview.chunkCount ?? 0, abnormal:0, target:'create.import' },
    { code:'DERIVE', label:'衍生', count:0, abnormal:0, target:'create.derive' },
    { code:'JUDGE', label:'裁决', count:props.overview.pendingReviewCount ?? 0, abnormal:props.overview.openConflictCount ?? 0, target:'create.judgment' },
    { code:'PUBLISH', label:'发布', count:props.overview.effectiveCardCount ?? 0, abnormal:0, target:'workbench.assets' },
  ]
})

const governanceItems = computed<GovernanceItem[]>(() => props.governance?.items ?? [])
const recent = computed<RecentObject[]>(() => props.summary?.recent ?? [])

const sources = ref<KnowledgeSource[]>([])
const documents = ref<KnowledgeDocument[]>([])
const quality = ref<QualityOverview|null>(null)

/** 图谱概览：与图谱页同一张画布，只是取知识库级子图而不是以某个对象为起点。 */
const graphOverview = ref<GraphOverview|null>(null)
const graphLoading = ref(false)
/** 首次结果回来之前不算「空」：否则加载中会先闪一句「还没有关系」。 */
const graphLoaded = ref(false)
const graphLegend = GRAPH_LEGEND
const canGraph = computed(() => shell.canNavigate('search.graph'))
const overviewNodes = computed<GraphNode[]>(() => graphOverview.value?.nodes ?? [])
const overviewEdges = computed<GraphEdge[]>(() => graphOverview.value?.edges ?? [])
const graphEmptyHint = computed(() => {
  const reason = String(graphOverview.value?.reason ?? '')
  if (reason === 'KB_REQUIRED') return '请先选择知识空间'
  return '该空间还没有可展示的关系，完成文档解析与实体抽取后即可看到关系视图'
})
async function loadGraphOverview() {
  if (!props.knowledgeBaseId) {
    graphOverview.value = null
    graphLoaded.value = true
    return
  }
  graphLoading.value = true
  try {
    graphOverview.value = await getGraphOverview(props.knowledgeBaseId, 18).then(r => unwrap(r, null as GraphOverview|null))
  } catch {
    graphOverview.value = null
  } finally {
    graphLoading.value = false
    graphLoaded.value = true
  }
}
/** 从概览进入图谱并定位对象：无图谱权限时入口已置灰，这里再兜一层。 */
function openInGraph(code:string) {
  if (!canGraph.value || !code) return
  shell.openGraph(code)
}

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
/** 治理级别 → 状态圆点：用实心的语义色，浅色主题下 --danger-bg 这类浅底做圆点几乎看不见。 */
function levelDot(level:string):string {
  if (level === 'block') return 'kb-dot-danger'
  if (level === 'warn') return 'kb-dot-warn'
  return 'kb-dot-ok'
}
/** 治理级别 → 数字胶囊：0 不报警，中性显示；有量才按级别着色。 */
function levelBadge(item:GovernanceItem):string {
  if (!item.count) return 'kb-state-neutral'
  if (item.level === 'block') return 'kb-state-danger'
  if (item.level === 'warn') return 'kb-state-warn'
  return 'kb-state-ok'
}
/** 知识对象状态 → 胶囊配色：与「知识治理」同一套语义色，不额外引入新颜色。 */
function statusState(status:string):string {
  switch (String(status || '').toUpperCase()) {
    case 'EFFECTIVE': case 'APPROVED': case 'ACTIVE': case 'SUPPORTED': case 'RESOLVED':
      return 'kb-state-ok'
    case 'CONFLICTED': case 'REJECTED': case 'INVALID': case 'EXPIRED': case 'SUPERSEDED':
      return 'kb-state-danger'
    case 'PENDING': case 'PENDING_REVIEW': case 'UNCERTAIN': case 'DRAFT': case 'PARTIALLY_SUPPORTED':
      return 'kb-state-warn'
    default:
      return 'kb-state-neutral'
  }
}
/** 最近更新时间：近期用相对时间（更贴近「最近」的语义），超过一周回落到日期。 */
function recentTime(value?:string|null):string {
  if (!value) return ''
  const raw = String(value)
  const stamp = Date.parse(raw.includes('T') || raw.endsWith('Z') ? raw : raw.replace(' ', 'T'))
  const fallback = raw.slice(5, 16).replace('T', ' ')
  if (!Number.isFinite(stamp)) return fallback
  const diff = Date.now() - stamp
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return fallback.slice(0, 5)
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
onMounted(() => { void loadDistribution(); void loadQuality(); void loadGraphOverview() })
watch(() => props.knowledgeBaseId, () => {
  graphLoaded.value = false
  void loadDistribution(); void loadQuality(); void loadGraphOverview()
})
</script>
