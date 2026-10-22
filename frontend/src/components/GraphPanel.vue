<template>
  <div class="kb-page">
    <div class="hero">
      <div>
        <div class="eyebrow">知识检索</div>
        <h1>知识图谱</h1>
        <p>从卡片、断言、实体或证据出发做有界图遍历，追溯「卡片 → 证据 → 断言 → 实体」的可解释链路。</p>
      </div>
      <span class="tag gray">{{ sourceLabel }}</span>
    </div>

    <section class="kb-card">
      <div class="kb-card-head">
        <div>
          <b>邻域检索</b>
          <div class="muted">默认 2 跳有界遍历，避免全图扩散；关系标签携带置信度，虚线表示候选或低置信度关系。</div>
        </div>
      </div>
      <div class="kb-card-body grid gap-3 md:grid-cols-[2fr_1fr_1fr_auto]">
        <label class="block">
          <span class="kb-field-label">起点对象编码</span>
          <input :value="startCode" class="kb-input mt-1" placeholder="KC-… / CLM-… / ENT-… / EV-…"
            @input="setStartCode(($event.target as HTMLInputElement).value)" />
        </label>
        <label class="block">
          <span class="kb-field-label">遍历跳数</span>
          <select v-model.number="depth" class="kb-input mt-1" @change="reloadIfReady">
            <option :value="1">1 跳（直接邻居）</option>
            <option :value="2">2 跳（默认）</option>
            <option :value="3">3 跳（深度追溯）</option>
          </select>
        </label>
        <label class="block">
          <span class="kb-field-label">关系类型</span>
          <select v-model="relationFilter" class="kb-input mt-1">
            <option value="">全部关系</option>
            <option v-for="item in relationOptions" :key="item" :value="item">{{ item }}</option>
          </select>
        </label>
        <div class="flex items-end gap-2">
          <button class="kb-btn-primary" :disabled="loading" @click="loadGraph()">查询邻域</button>
          <button class="kb-btn-outline" @click="focused=''">清除聚焦</button>
        </div>
      </div>
      <p v-if="seedHint" class="px-4 pb-2 text-[12px] text-ink-500">当前查询条件：{{ seedHint }}</p>
      <div class="flex flex-wrap gap-2 px-4 pb-3">
        <span class="muted mr-1">常用起点：</span>
        <button
          v-for="item in centerNodes.slice(0, 6)" :key="item.knowledgeCode"
          class="tag" @click="setStartCode(item.knowledgeCode); loadGraph()">
          {{ item.title || item.knowledgeCode }}
        </button>
      </div>
    </section>

    <div v-if="!graphAvailable" class="note warn">
      {{ unavailableHint }}
    </div>
    <div v-if="truncated" class="note warn">
      邻域已达节点上限，仅展示前 {{ nodes.length }} 个对象；缩小跳数或指定更具体的起点可获得完整视图。
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
      <section class="kb-card">
        <div class="kb-card-head"><b>关系视图</b><span>点击节点可聚焦其一跳邻域</span></div>
        <div class="kb-card-body">
          <div v-if="loading" class="py-24 text-center text-sm text-ink-500">正在加载邻域…</div>
          <GraphCanvas
            v-else-if="visibleNodes.length"
            :key="canvasKey"
            :nodes="visibleNodes" :edges="visibleEdges" :focused="focused"
            @select="focused = $event" />
          <div v-else class="py-24 text-center text-sm text-ink-500">
            {{ emptyHint }}
          </div>
        </div>
      </section>

      <section class="kb-card">
        <div class="kb-card-head"><b>对象明细</b><span>{{ focused || '未聚焦' }}</span></div>
        <div class="kb-card-body">
          <template v-if="focusedNode">
            <div class="mb-2 text-sm font-semibold">{{ focusedNode.title || focusedNode.code }}</div>
            <div class="status-row"><span>类型</span><b>{{ objectTypeLabel(focusedNode.type) }}</b></div>
            <div class="status-row"><span>编码</span><code class="text-[11px]">{{ focusedNode.code }}</code></div>
            <div class="status-row"><span>状态</span><b>{{ statusLabel(focusedNode.status) }}</b></div>
            <div class="status-row"><span>距起点</span><b>{{ focusedNode.distance ?? '-' }} 跳</b></div>
            <div class="status-row"><span>关联度</span><b>{{ focusedNode.degree ?? 0 }}</b></div>
            <div class="mt-2">
              <span class="tag">{{ objectTypeLabel(focusedNode.type) }}</span>
              <span v-if="focusedNode.objectType" class="tag gray ml-1">{{ objectTypeLabel(focusedNode.objectType) }}</span>
            </div>
            <div class="note mt-3" v-if="neighborSummary">
              一跳邻居：{{ neighborSummary }}
            </div>
          </template>
          <div v-else class="py-10 text-center text-sm text-ink-500">
            在左侧视图中点击节点，查看该对象的类型、状态与关联度。
          </div>
        </div>
      </section>
    </div>

    <section class="kb-card mt-4">
      <div class="kb-card-head">
        <b>关系明细</b>
        <span>{{ visibleEdges.length }} 条{{ relationFilter ? ` · 已过滤为 ${relationFilter}` : '' }}</span>
      </div>
      <el-table :data="edgeRows" size="small" max-height="320">
        <el-table-column prop="sourceTitle" label="起点" min-width="180" show-overflow-tooltip />
        <el-table-column prop="type" label="关系" width="140" />
        <el-table-column prop="targetTitle" label="终点" min-width="180" show-overflow-tooltip />
        <el-table-column label="置信度" width="110">
          <template #default="{ row }">
            <span v-if="typeof row.confidence === 'number'" :class="row.confidence < 0.7 ? 'tag new' : 'tag ok'">
              {{ row.confidence.toFixed(2) }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="来源视图" width="120">
          <template #default="{ row }">{{ row.semanticType || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <button class="kb-btn-ghost" @click="focused = row.source">聚焦</button>
          </template>
        </el-table-column>
        <template #empty><span class="muted">暂无关系</span></template>
      </el-table>
    </section>

    <section class="kb-card mt-4">
      <div class="kb-card-head"><b>节点清单</b><span>{{ visibleNodes.length }} 个对象</span></div>
      <el-table :data="visibleNodes" size="small" max-height="320" @row-click="(row: GraphNode) => focused = String(row.code)">
        <el-table-column label="类型" width="150">
          <template #default="{ row }"><span class="tag">{{ objectTypeLabel(row.type) }}</span></template>
        </el-table-column>
        <el-table-column prop="title" label="对象" min-width="240" show-overflow-tooltip />
        <el-table-column prop="code" label="编码" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="距起点" width="90">
          <template #default="{ row }">{{ row.distance ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="关联度" width="90">
          <template #default="{ row }">{{ row.degree ?? 0 }}</template>
        </el-table-column>
        <template #empty><span class="muted">暂无节点</span></template>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getCards, getGraph, unwrap } from '../api/knowledge'
import type { GraphEdge, GraphNode, GraphResult, KnowledgeCard } from '../types'
import { objectTypeLabel, statusLabel } from '../types'
import GraphCanvas from './GraphCanvas.vue'

const props = defineProps<{
  knowledgeBaseId?: number|null
  code?: string
  /** 外部指定的关系类型：从某条关系进来时，结果只保留这类边。 */
  relationType?: string
  /** 加载完成后聚焦的节点：关系场景传另一端，让这条边的两端都进视野。 */
  focusNode?: string
}>()
const emit = defineEmits<{ 'update:code':[value:string] }>()

const startCode = ref('')
const depth = ref(2)
const relationFilter = ref('')
/** 已应用到当前结果的关系类型：判断外部条件是否真的变了，避免同一条关系重复查询。 */
const relationFilterApplied = ref('')
const limit = ref(80)
const loading = ref(false)
const graph = ref<GraphResult|null>(null)
const focused = ref('')
const centerNodes = ref<KnowledgeCard[]>([])
const canvasKey = ref(0)

const nodes = computed<GraphNode[]>(() => graph.value?.nodes || [])
const edges = computed<GraphEdge[]>(() => graph.value?.edges || [])
const graphAvailable = computed(() => graph.value?.available !== false)
const truncated = computed(() => graph.value?.truncated === true)

/** Relation filter re-projects the node set so the canvas never shows orphaned dots. */
const visibleEdges = computed<GraphEdge[]>(() =>
  relationFilter.value
    ? edges.value.filter(edge => String(edge.type || '') === relationFilter.value)
    : edges.value)

const visibleNodes = computed<GraphNode[]>(() => {
  if (!relationFilter.value) return nodes.value
  const kept = new Set<string>()
  for (const edge of visibleEdges.value) {
    if (edge.source) kept.add(String(edge.source))
    if (edge.target) kept.add(String(edge.target))
  }
  return nodes.value.filter(node => kept.has(String(node.code)))
})

const relationOptions = computed(() =>
  Array.from(new Set(edges.value.map(edge => String(edge.type || '')).filter(Boolean))))

const nodeByCode = computed(() => {
  const map = new Map<string, GraphNode>()
  for (const node of nodes.value) map.set(String(node.code), node)
  return map
})

const focusedNode = computed(() => nodeByCode.value.get(focused.value) || null)

/** 把当前生效的查询条件摊开显示：从资产页跳进来时，用户得能确认带的确实是那一条。 */
const seedHint = computed(() => {
  const parts:string[] = []
  if (startCode.value) parts.push(`起点 ${startCode.value}`)
  if (relationFilter.value) parts.push(`关系类型 ${relationFilter.value}`)
  if (focused.value) parts.push(`聚焦 ${focused.value}`)
  return parts.join(' · ')
})

const neighborSummary = computed(() => {
  if (!focused.value) return ''
  const counter = new Map<string, number>()
  for (const edge of edges.value) {
    if (String(edge.source) === focused.value) {
      const key = objectTypeLabel(edge.targetType)
      counter.set(key, (counter.get(key) || 0) + 1)
    }
    if (String(edge.target) === focused.value) {
      const key = objectTypeLabel(edge.sourceType)
      counter.set(key, (counter.get(key) || 0) + 1)
    }
  }
  if (!counter.size) return '暂无一跳邻居'
  return Array.from(counter.entries()).map(([key, count]) => `${key} ×${count}`).join('、')
})

const edgeRows = computed(() => visibleEdges.value.map(edge => ({
  source: String(edge.source || ''),
  target: String(edge.target || ''),
  sourceTitle: nodeByCode.value.get(String(edge.source || ''))?.title || String(edge.source || ''),
  targetTitle: nodeByCode.value.get(String(edge.target || ''))?.title || String(edge.target || ''),
  type: String(edge.type || ''),
  semanticType: String(edge.semanticType || ''),
  confidence: typeof edge.confidence === 'number' ? edge.confidence : null,
})))

const sourceLabel = computed(() => {
  const source = graph.value?.source
  if (source === 'neo4j') return 'Neo4j 图视图'
  if (source === 'authoritative') return '权威模型投影'
  return graph.value ? '图视图' : '未加载'
})

const unavailableHint = computed(() => {
  if (graph.value?.reason === 'SEED_NOT_FOUND') {
    return '未在当前知识库找到该对象编码，请确认编码属于卡片、断言、实体或证据。'
  }
  if (graph.value?.reason === 'SEED_REQUIRED') return '请填写起点对象编码后再查询邻域。'
  return '图存储不可用，已回退到权威模型投影（按证据关联构建关系）。'
})

const emptyHint = computed(() => {
  if (loading.value) return '正在加载邻域…'
  if (!graph.value) return '输入起点对象编码后查询邻域，即可查看关系视图。'
  return '该起点暂无可展示的关联关系，可尝试增大跳数。'
})

/**
 * @param seedFocus 外部指定的聚焦节点：从关系进来时传另一端，加载完直接选中它。
 *                  手动点「查询邻域」不传，保留重新检索时清空聚焦的原有行为。
 */
async function loadGraph(seedFocus?:string) {
  if (!props.knowledgeBaseId || !startCode.value.trim()) return
  loading.value = true
  focused.value = seedFocus || ''
  try {
    graph.value = unwrap(await getGraph(props.knowledgeBaseId, startCode.value.trim(), depth.value, limit.value), null)
    /**
     * 外部带来的关系类型在当前结果里不存在时退回不过滤：图源可能是 neo4j，
     * 边类型的命名未必与 t_relation.relation_type 一致，硬筛会把图筛成空白，
     * 那比多看几条边糟糕得多。
     */
    if (relationFilter.value && !relationOptions.value.includes(relationFilter.value)) relationFilter.value = ''
    relationFilterApplied.value = relationFilter.value
    /**
     * 聚焦的节点不在结果里时（图源换了，节点编码未必一致）退回聚焦起点：
     * 聚焦落空的话，跳转条件看起来就像没带进来。
     */
    if (focused.value && !nodeByCode.value.has(focused.value)) focused.value = startCode.value
    canvasKey.value++
  } catch {
    graph.value = { available:false, nodes:[], edges:[] }
  } finally {
    loading.value = false
  }
}

/** 手动输入或点击常用起点时同步回父组件，保持 v-model:code 生效。 */
function setStartCode(value: string) {
  startCode.value = value
  emit('update:code', value)
}

/** 外部起点编码变化（卡片/断言列表的「查看图谱」入口）时自动加载邻域。 */
/**
 * 外部起点（含附加查询条件）变化：起点、关系类型与聚焦节点一起生效。
 * 关系类型先落再查，否则查完再筛会先把整片邻域渲染出来闪一下。
 */
watch(() => [props.code, props.relationType, props.focusNode], ([rawCode, rawRelation, rawFocus]) => {
  const code = String(rawCode || '').trim()
  if (!code) return
  relationFilter.value = String(rawRelation || '')
  const focus = String(rawFocus || '')
  // 起点没变也要重来：换的是关系类型或聚焦节点，结果集本来就不一样。
  const same = code === startCode.value && relationFilter.value === relationFilterApplied.value
    && focus === focused.value
  if (same) return
  startCode.value = code
  if (props.knowledgeBaseId) loadGraph(focus)
}, { immediate:true })

async function loadCenterNodes() {
  if (!props.knowledgeBaseId) return
  try {
    const cards = unwrap(await getCards(props.knowledgeBaseId), [] as KnowledgeCard[])
    centerNodes.value = cards
    // 外部已指定起点（props.code）时优先保留，不覆盖为第一张卡片。
    if (!startCode.value && !props.code && cards.length) {
      startCode.value = String(cards[0].knowledgeCode || '')
      emit('update:code', startCode.value)
      await loadGraph()
    }
  } catch {
    centerNodes.value = []
  }
}

/**
 * 换知识库时清空重来，但起点、关系类型与聚焦节点一律回到外部传入的值：
 * 这个 watch 在起点 watch 之后执行且是 immediate 的，首次挂载时外部条件刚被写好，
 * 这里要是清成空，就等于把资产页跳进来的对象/关系条件丢了——
 * 表现正是「图谱页开了，但起点空、也没聚焦」。换库时 App 已把这三个值清掉，
 * 所以「保留外部值」同时也覆盖了换库场景。
 */
watch(() => props.knowledgeBaseId, () => {
  graph.value = null
  startCode.value = String(props.code || '')
  relationFilter.value = String(props.relationType || '')
  relationFilterApplied.value = relationFilter.value
  focused.value = String(props.focusNode || '')
  loadCenterNodes()
}, { immediate:true })

function reloadIfReady() {
  if (props.knowledgeBaseId && startCode.value.trim()) loadGraph()
}
</script>
