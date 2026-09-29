<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">文本分块</div>
        <h1 class="kb-title mt-1">文本分块</h1>
        <p class="kb-muted mt-1">
          按文档核对切分结果：切分策略、页码 / 字符区间、token 与 revision，重跑后旧版本保留为 SUPERSEDED。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <select v-model.number="selectedId" class="kb-input w-64" @change="resetPage">
          <option :value="0">选择文档…</option>
          <option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.title }}</option>
        </select>
        <button class="kb-btn-outline" :disabled="loading || !selectedId" @click="load">刷新</button>
      </div>
    </header>

    <!-- 切分策略：决定召回粒度，手册与报告不该用同一个窗口（设计 §4.1） -->
    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">切分策略</b>
        <span class="kb-muted">
          {{ policyState.customized ? '已自定义' : '内置默认' }} · 保存后对新接入的文档生效
        </span>
      </div>
      <div class="p-4">
        <div v-if="policyLoading" class="py-4 text-center text-sm text-ink-500">正在读取分块策略...</div>
        <div v-else class="space-y-3">
          <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <label class="block">
              <span class="mb-1 block text-[11px] text-ink-500">切分算法</span>
              <select v-model="policy.strategy" class="kb-input w-full">
                <option v-for="item in strategies" :key="item.name" :value="item.name">{{ item.label }}</option>
              </select>
            </label>
            <label class="block">
              <span class="mb-1 block text-[11px] text-ink-500">
                窗口大小（{{ policy.measure==='TOKEN'?'Token':'字符' }}）
              </span>
              <input v-model.number="policy.chunkSize" type="number" class="kb-input w-full"
                :min="ranges.chunkSize?.min ?? 200" :max="ranges.chunkSize?.max ?? 4000" />
            </label>
            <label class="block">
              <span class="mb-1 block text-[11px] text-ink-500">重叠</span>
              <input v-model.number="policy.chunkOverlap" type="number" class="kb-input w-full"
                :min="ranges.chunkOverlap?.min ?? 0" :max="ranges.chunkOverlap?.max ?? 1000" />
            </label>
            <label class="block">
              <span class="mb-1 block text-[11px] text-ink-500">最小块（过小则并入相邻块）</span>
              <input v-model.number="policy.minChunkSize" type="number" class="kb-input w-full"
                :min="ranges.minChunkSize?.min ?? 0" :max="ranges.minChunkSize?.max ?? 1000" />
            </label>
          </div>
          <div class="flex flex-wrap items-center gap-6">
            <label class="flex items-center gap-2 text-[12px] text-ink-600">
              <span>窗口度量</span>
              <select v-model="policy.measure" class="kb-input w-32">
                <option v-for="item in measures" :key="item.name" :value="item.name">{{ item.label }}</option>
              </select>
            </label>
            <el-checkbox v-model="policy.keepStructure" label="保留结构（表格 / 代码独立成块）" />
            <el-checkbox v-model="policy.respectSentence" label="句末断句" />
            <el-checkbox v-model="policy.parentChild" label="父子分块（章节父块 + 窗口子块）" />
          </div>
          <p class="kb-muted">{{ strategyDescription }}</p>
          <div class="flex flex-wrap items-center gap-2">
            <button class="kb-btn" :disabled="policySaving" @click="savePolicy">保存策略</button>
            <button class="kb-btn-outline" :disabled="policySaving" @click="resetPolicy">恢复默认</button>
            <span v-if="policyState.customized" class="kb-muted">
              最近由 {{ policyState.updatedBy || '—' }} 于 {{ policyState.updatedTime || '—' }} 修改
            </span>
          </div>
          <p class="kb-muted">
            已有文档不会自动重切：策略只影响接入之后的解析，需要重切请重新上传该文档。
          </p>
        </div>
      </div>
    </article>

    <!-- 切分结果画像：一眼看出这段文档被切成了什么样 -->
    <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
      <div class="kb-card p-3">
        <span class="block text-[11px] text-ink-500">分块总数</span>
        <b class="text-xl text-ink-900">{{ filtered.length }}</b>
      </div>
      <div class="kb-card p-3">
        <span class="block text-[11px] text-ink-500">平均 Token 数</span>
        <b class="text-xl text-ink-900">{{ avgToken }}</b>
      </div>
      <div class="kb-card p-3">
        <span class="block text-[11px] text-ink-500">字符覆盖</span>
        <b class="text-xl text-ink-900">{{ coveredChars }}</b>
      </div>
      <div class="kb-card p-3">
        <span class="block text-[11px] text-ink-500">类型分布</span>
        <div class="mt-1 flex flex-wrap gap-1">
          <span v-for="item in typeStats" :key="item.type" class="rounded bg-[color:var(--info-bg)] px-2 py-0.5 text-[11px] text-accent">
            {{ item.type }} {{ item.count }}
          </span>
          <span v-if="!typeStats.length" class="text-[12px] text-ink-500">—</span>
        </div>
      </div>
      <div class="kb-card p-3">
        <span class="block text-[11px] text-ink-500">最新版本号</span>
        <b class="text-xl text-ink-900">{{ maxRevision }}</b>
      </div>
    </div>

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">分块清单</b>
        <div class="flex flex-wrap items-center gap-2">
          <select v-model="typeFilter" class="kb-input w-32">
            <option value="">全部类型</option>
            <option v-for="item in typeStats" :key="item.type" :value="item.type">{{ item.type }}</option>
          </select>
          <input v-model="keyword" class="kb-input w-48" placeholder="搜索路径 / 编码 / 正文" />
          <el-radio-group v-model="view" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="json">接口原生报文</el-radio-button>
          </el-radio-group>
        </div>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="error"
          :empty="!filtered.length && !loading && !error"
          :empty-text="selectedId ? (keyword || typeFilter ? '没有匹配的文本分块' : '该文档暂无文本分块') : '请先选择文档'"
          empty-hint="文档可能仍在解析中，或摄入在切分之前失败"
          retryable
          @retry="load">
          <el-table
            v-if="view==='table'" :data="pageRows" border size="small" max-height="460"
            :row-class-name="rowClass" @row-click="openChunk">
            <el-table-column prop="unitCode" label="单元编码" min-width="150" show-overflow-tooltip />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ row.chunkType || '—' }}</template>
            </el-table-column>
            <el-table-column label="标题路径" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.headingPath || '—' }}</template>
            </el-table-column>
            <el-table-column label="页码" width="90">
              <template #default="{ row }">{{ pageRange(row) }}</template>
            </el-table-column>
            <el-table-column label="字符区间" width="110">
              <template #default="{ row }">{{ charRange(row) }}</template>
            </el-table-column>
            <el-table-column prop="tokenCount" label="Token" width="80" />
            <el-table-column label="版本" width="130">
              <template #default="{ row }">
                <span>rev {{ row.revision ?? 1 }}</span>
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">
                  {{ statusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="内容" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.excerpt || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <button class="kb-btn-outline text-[12px]" @click.stop="openChunk(row)">原文</button>
              </template>
            </el-table-column>
          </el-table>
          <!-- 原生 JSON：与接口返回逐字一致，便于对照契约核对字段 -->
          <pre v-else class="max-h-[460px] overflow-auto rounded-lg bg-ink-900 p-4 text-[12px] leading-6 text-[color:var(--ok)]">{{ jsonText }}</pre>
          <div v-if="filtered.length" class="mt-3 flex justify-end">
            <el-pagination
              small layout="prev,pager,next,total" :current-page="page" :page-size="size"
              :total="filtered.length" :pager-count="5" @current-change="setPage" />
          </div>
        </PanelState>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { statusLabel } from '../types'
import type { ChunkPolicy, ChunkPolicyState, DocumentChunk, KnowledgeDocument } from '../types'
import {
  getChunkPolicy, getDocumentChunks, resetChunkPolicy, saveChunkPolicy, unwrap,
} from '../api/knowledge'
import { errorMessage as message, notifyError, notifySuccess } from '../utils/feedback'
import { useShell } from '../shell'
import PanelState from './PanelState.vue'

const props = defineProps<{
  knowledgeBaseId:number
  documents:KnowledgeDocument[]
  /** 外部指定的文档：从「已解析文档」行的「文本分块」直接进入时不必再手动选择。 */
  documentId?:number|null
}>()
const shell = useShell()

const selectedId = ref(0)
const chunks = ref<DocumentChunk[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const typeFilter = ref('')
const keyword = ref('')
/** 表格看画像，JSON 看接口原生报文（需求：对象支持原生样式展示）。 */
const view = ref<'table' | 'json'>('table')

// ---- 切分策略 ----
const policyState = ref<ChunkPolicyState>({ policy: {} })
const policy = ref<ChunkPolicy>({})
const policyLoading = ref(false)
const policySaving = ref(false)

const strategies = computed(() => policyState.value.schema?.strategies || [])
const measures = computed(() => policyState.value.schema?.measures || [])
const ranges = computed(() => policyState.value.schema?.ranges || {})
const strategyDescription = computed(() =>
  strategies.value.find(item => item.name === policy.value.strategy)?.description || '')
const filtered = computed(() => chunks.value.filter(row => {
  if (typeFilter.value && String(row.chunkType || '') !== typeFilter.value) return false
  const key = keyword.value.trim().toLowerCase()
  if (!key) return true
  return [row.unitCode, row.headingPath, row.excerpt].some(text => String(text || '').toLowerCase().includes(key))
}))
const pageRows = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))
const jsonText = computed(() => JSON.stringify(pageRows.value, null, 2))
const maxRevision = computed(() =>
  chunks.value.reduce((max, row) => Math.max(max, Number(row.revision || 0)), 0))
const typeStats = computed(() => {
  const counter = new Map<string, number>()
  for (const row of chunks.value) {
    const type = String(row.chunkType || 'PARAGRAPH')
    counter.set(type, (counter.get(type) || 0) + 1)
  }
  return [...counter.entries()].map(([type, count]) => ({ type, count }))
    .sort((a, b) => b.count - a.count)
})
const avgToken = computed(() => {
  if (!filtered.value.length) return 0
  const total = filtered.value.reduce((sum, row) => sum + Number(row.tokenCount || 0), 0)
  return Math.round(total / filtered.value.length)
})
const coveredChars = computed(() => filtered.value.reduce(
  (sum, row) => sum + Math.max(0, Number(row.charEnd || 0) - Number(row.charStart || 0)), 0))

async function loadPolicy() {
  policyLoading.value = true
  try {
    policyState.value = unwrap(await getChunkPolicy(props.knowledgeBaseId), { policy: {} })
    policy.value = { ...(policyState.value.policy || {}) }
  } catch (e) {
    notifyError(message(e))
  } finally {
    policyLoading.value = false
  }
}
async function savePolicy() {
  policySaving.value = true
  try {
    policyState.value = unwrap(await saveChunkPolicy(props.knowledgeBaseId, policy.value), { policy: {} })
    policy.value = { ...(policyState.value.policy || {}) }
    notifySuccess('分块策略已保存，下次接入生效')
  } catch (e) {
    notifyError(message(e))
  } finally {
    policySaving.value = false
  }
}
async function resetPolicy() {
  policySaving.value = true
  try {
    policyState.value = unwrap(await resetChunkPolicy(props.knowledgeBaseId), { policy: {} })
    policy.value = { ...(policyState.value.policy || {}) }
    notifySuccess('已恢复内置默认分块策略')
  } catch (e) {
    notifyError(message(e))
  } finally {
    policySaving.value = false
  }
}

async function load() {
  if (!selectedId.value) return
  loading.value = true
  error.value = ''
  try {
    chunks.value = unwrap(await getDocumentChunks(selectedId.value), [])
  } catch (e) {
    error.value = message(e)
    chunks.value = []
  } finally {
    loading.value = false
  }
}

function resetPage() {
  page.value = 1
  void load()
}
function setPage(next:number) {
  page.value = next
}
/** 点开 Chunk 原文：核对引用落点时要看的正是这一段，而不是派生的卡片。 */
function openChunk(row:DocumentChunk) {
  if (row?.unitCode) shell.openDetail('CHUNK', String(row.unitCode))
}
function rowClass({ row }:{ row:DocumentChunk }) {
  return row.status && row.status !== 'ACTIVE' ? 'is-superseded' : ''
}
function pageRange(row:DocumentChunk) {
  if (row.pageNo == null) return '—'
  return row.pageEnd && row.pageEnd !== row.pageNo ? `${row.pageNo}-${row.pageEnd}` : String(row.pageNo)
}
function charRange(row:DocumentChunk) {
  if (row.charStart == null) return '—'
  return `${row.charStart}-${row.charEnd ?? row.charStart}`
}

onMounted(() => { void loadPolicy() })
watch(() => props.knowledgeBaseId, () => { void loadPolicy() })
watch([typeFilter, keyword], () => { page.value = 1 })
watch(view, () => { page.value = 1 })
/** 外部指定文档时直接选中并加载：从文档行进入的用户不该再选一次。 */
watch(() => props.documentId, id => {
  if (!id || id === selectedId.value) return
  selectedId.value = id
  page.value = 1
  void load()
}, { immediate:true })
/** 文档列表首次就绪时自动选中第一个文档，避免进入页面就是空态。 */
watch(() => props.documents, docs => {
  if (!selectedId.value && docs.length) {
    selectedId.value = docs[0].id
    void load()
  }
}, { immediate:true })
</script>
<style scoped>
.is-superseded{opacity:.6}
</style>
