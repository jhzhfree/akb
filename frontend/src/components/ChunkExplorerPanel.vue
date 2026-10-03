<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">文本分块</div>
        <h1 class="kb-title mt-1">文本分块</h1>
        <p class="kb-muted mt-1">
          按文档核对切分结果：页码 / 字符区间、token 与 revision，重跑后旧版本保留为 SUPERSEDED。
          切分策略不在这里改——请到上方「拖入资料」右上角的「分块策略」统一配置。
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
import { computed, ref, watch } from 'vue'
import { statusLabel } from '../types'
import type { DocumentChunk, KnowledgeDocument } from '../types'
import { getDocumentChunks, unwrap } from '../api/knowledge'
import { errorMessage as message } from '../utils/feedback'
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
