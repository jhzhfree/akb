<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">CHUNK WORKBENCH</div>
        <h1 class="kb-title mt-1">Chunk</h1>
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

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">Chunk 清单</b>
        <span class="kb-muted">共 {{ chunks.length }} 个 · 最新 revision {{ maxRevision }}</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="error"
          :empty="!chunks.length && !loading && !error"
          :empty-text="selectedId ? '该文档暂无 Chunk' : '请先选择文档'"
          empty-hint="文档可能仍在解析中，或摄入在切分之前失败"
          retryable
          @retry="load">
          <el-table :data="pageRows" border size="small" max-height="460">
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
                  {{ row.status || '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="内容" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.excerpt || '—' }}</template>
            </el-table-column>
          </el-table>
          <div v-if="chunks.length" class="mt-3 flex justify-end">
            <el-pagination
              small layout="prev,pager,next,total" :current-page="page" :page-size="size"
              :total="chunks.length" :pager-count="5" @current-change="setPage" />
          </div>
        </PanelState>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DocumentChunk, KnowledgeDocument } from '../types'
import { getDocumentChunks, unwrap } from '../api/knowledge'
import { errorMessage as message } from '../utils/feedback'
import PanelState from './PanelState.vue'

const props = defineProps<{ documents: KnowledgeDocument[] }>()

const selectedId = ref(0)
const chunks = ref<DocumentChunk[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)

const maxRevision = computed(() =>
  chunks.value.reduce((max, row) => Math.max(max, Number(row.revision || 0)), 0))
const pageRows = computed(() => chunks.value.slice((page.value - 1) * size.value, page.value * size.value))

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
function pageRange(row:DocumentChunk) {
  if (row.pageNo == null) return '—'
  return row.pageEnd && row.pageEnd !== row.pageNo ? `${row.pageNo}-${row.pageEnd}` : String(row.pageNo)
}
function charRange(row:DocumentChunk) {
  if (row.charStart == null) return '—'
  return `${row.charStart}-${row.charEnd ?? row.charStart}`
}

/** 文档列表首次就绪时自动选中第一个文档，避免进入页面就是空态。 */
watch(() => props.documents, docs => {
  if (!selectedId.value && docs.length) {
    selectedId.value = docs[0].id
    void load()
  }
}, { immediate:true })
</script>
