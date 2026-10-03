<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识资产</div>
        <h1 class="kb-title mt-1">资产浏览</h1>
        <p class="kb-muted mt-1">
          工作台 KPI 的下钻页面：同一入口按对象类型切换，来源与文档侧重接入，卡片与断言侧重治理，实体与关系侧重图谱。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <input
          v-model="keyword" class="kb-input w-52" placeholder="搜索名称 / 编码"
          @input="onKeywordInput" @keyup.enter="onSearch">
        <button class="kb-btn-outline" @click="onSearch">检索</button>
        <button class="kb-btn-outline" @click="load">刷新</button>
      </div>
    </header>

    <div class="mb-3 flex flex-wrap gap-2">
      <button
        v-for="tab in tabs"
        :key="tab.type"
        class="rounded-lg border px-3 py-1.5 text-[13px] transition"
        :class="type===tab.type
          ? 'border-[color:var(--info-bd)] kb-state-info'
          : 'border-ink-200 bg-white text-ink-600 hover:border-[color:var(--info-bd)]'"
        @click="$emit('update:type',tab.type)">
        {{ tab.label }}
      </button>
    </div>

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">{{ currentLabel }}</b>
        <span class="kb-muted">
          共 {{ total }} 条<template v-if="keyword.trim()">· 命中「{{ keyword.trim() }}」</template>
        </span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="error"
          :empty="!rows.length"
          :empty-text="hint.empty"
          :empty-hint="hint.detail"
          retryable
          @retry="load">
          <el-table :data="rows" border size="small" @row-click="onRowClick">
            <el-table-column v-if="type==='source'" prop="sourceCode" label="编码" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sourceCode || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='source'" prop="name" label="来源名称" min-width="200" show-overflow-tooltip />
            <el-table-column v-if="type==='source'" prop="sourceType" label="类型" width="120" />
            <el-table-column v-if="type==='source'" label="权威度" width="90">
              <template #default="{ row }">{{ row.authorityScore ?? '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='source'" prop="status" label="状态" width="100" />

            <el-table-column v-if="type==='document'" prop="documentCode" label="编码" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.documentCode || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='document'" prop="title" label="文档标题" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="type==='document'" prop="sourceName" label="来源" width="160" show-overflow-tooltip />
            <el-table-column v-if="type==='document'" prop="status" label="状态" width="110" />
            <el-table-column v-if="type==='document'" label="Chunk" width="90">
              <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
            </el-table-column>

            <el-table-column v-if="type==='card'" prop="knowledgeCode" label="编码" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.knowledgeCode || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='card'" prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="type==='card'" prop="status" label="状态" width="120" />
            <el-table-column v-if="type==='card'" label="置信度" width="90">
              <template #default="{ row }">{{ row.confidence ?? '—' }}</template>
            </el-table-column>

            <el-table-column v-if="type==='assertion'" prop="claimCode" label="编码" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.claimCode || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='assertion'" prop="subject" label="主体" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="type==='assertion'" prop="predicate" label="谓词" width="110" />
            <el-table-column v-if="type==='assertion'" prop="objectValue" label="客体" min-width="160" show-overflow-tooltip />
            <el-table-column v-if="type==='assertion'" prop="status" label="状态" width="120" />

            <el-table-column v-if="type==='entity'" prop="entityCode" label="编码" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.entityCode || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='entity'" prop="canonicalName" label="规范名" min-width="180" show-overflow-tooltip />
            <el-table-column v-if="type==='entity'" prop="entityType" label="类型" width="140" />
            <el-table-column v-if="type==='entity'" prop="status" label="状态" width="110" />
            <el-table-column v-if="type==='entity'" label="使用量" width="100">
              <template #default="{ row }">{{ (row.claimCount || 0) + (row.cardCount || 0) }}</template>
            </el-table-column>

            <el-table-column v-if="type==='relation'" prop="code" label="编码" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.code || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='relation'" prop="sourceName" label="主体" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="type==='relation'" prop="relationType" label="关系类型" width="140" />
            <el-table-column v-if="type==='relation'" prop="targetName" label="客体" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="type==='relation'" prop="status" label="状态" width="100" />

            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <span class="text-[12px] text-accent">
                  {{ actionable(row) ? '查看详情' : '—' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="total > size" class="mt-3 flex justify-end">
            <el-pagination
              small layout="prev,pager,next,total,sizes" :current-page="page" :page-size="size"
              :page-sizes="[20,50,100]" :total="total" :pager-count="5"
              @current-change="setPage" @size-change="setSize" />
          </div>
        </PanelState>
      </div>
    </article>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import PanelState from './PanelState.vue'
import { searchAssets, unwrap } from '../api/knowledge'
import type { AssetPage, AssetRow } from '../types/knowledge'

export type AssetType = 'source'|'document'|'card'|'assertion'|'entity'|'relation'

const props = defineProps<{ knowledgeBaseId:number; type:AssetType }>()
const emit = defineEmits<{
  'update:type':[type:AssetType]
  openDetail:[mode:'CARD'|'CLAIM', code:string]
  openGraph:[code:string]
  navigate:[key:string]
}>()

const tabs:{ type:AssetType; label:string }[] = [
  { type:'source', label:'来源' },
  { type:'document', label:'文档' },
  { type:'card', label:'知识卡片' },
  { type:'assertion', label:'断言' },
  { type:'entity', label:'实体' },
  { type:'relation', label:'关系' },
]

/** 前端类型名 → 后端资产类型：后端只认领域名词，断言/关系在这里翻译。 */
const backendType = computed(() => props.type === 'assertion' ? 'CLAIM' : props.type.toUpperCase())

const rows = ref<AssetRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
let timer:number|undefined

const currentLabel = computed(() => tabs.find(v => v.type === props.type)?.label || '资产')
const hint = computed(() => keyword.value.trim()
  ? { empty:`没有匹配「${keyword.value.trim()}」的${currentLabel.value}`, detail:'换个关键词，或清空后重新浏览' }
  : { empty:`暂无${currentLabel.value}`, detail:'先在「知识生成 / 文档导入」接入资料并完成解析' })

/** 输入停顿 300ms 再检索：避免每敲一个字都打一次后端。 */
function onKeywordInput() {
  if (timer) window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    page.value = 1
    load()
  }, 300)
}

function onSearch() {
  if (timer) window.clearTimeout(timer)
  page.value = 1
  load()
}

function setPage(next:number) {
  page.value = next
  load()
}

function setSize(next:number) {
  size.value = next
  page.value = 1
  load()
}

function actionable(row:AssetRow):boolean {
  if (props.type === 'card') return !!row.knowledgeCode
  if (props.type === 'assertion') return !!row.claimCode
  if (props.type === 'entity') return !!row.entityCode
  return false
}

function onRowClick(row:AssetRow) {
  if (props.type === 'card' && row.knowledgeCode) {
    emit('openDetail', 'CARD', String(row.knowledgeCode))
    return
  }
  if (props.type === 'assertion' && row.claimCode) {
    emit('openDetail', 'CLAIM', String(row.claimCode))
    return
  }
  if (props.type === 'entity' && row.entityCode) {
    emit('openGraph', String(row.entityCode))
    return
  }
  if (props.type === 'relation') emit('navigate', 'search.graph')
}

/** 分页与检索都在服务端完成：拿回的就是当前页数据与真实命中总数。 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(await searchAssets(props.knowledgeBaseId, backendType.value, keyword.value.trim(), page.value, size.value),
      { type:backendType.value, keyword:'', items:[], total:0, page:1, size:size.value, pageCount:0 } as AssetPage)
    rows.value = data.items || []
    total.value = data.total || 0
    page.value = data.page || 1
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => [props.knowledgeBaseId, props.type], () => {
  // 换库或换对象类型：关键词与页码一起重置，避免停在空白页。
  keyword.value = ''
  page.value = 1
  load()
})
</script>
