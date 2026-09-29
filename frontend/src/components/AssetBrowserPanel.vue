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
        <input v-model="keyword" class="kb-input w-52" placeholder="搜索名称 / 编码" />
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
        <span class="kb-muted">{{ filtered.length }} / {{ rows.length }} 条</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="error"
          :empty="!filtered.length"
          :empty-text="hint.empty"
          :empty-hint="hint.detail"
          retryable
          @retry="load">
          <el-table :data="filtered" border size="small" @row-click="onRowClick">
            <el-table-column v-if="type==='source'" prop="name" label="来源名称" min-width="200" show-overflow-tooltip />
            <el-table-column v-if="type==='source'" prop="sourceType" label="类型" width="120" />
            <el-table-column v-if="type==='source'" label="权威度" width="90">
              <template #default="{ row }">{{ row.authorityScore ?? '—' }}</template>
            </el-table-column>
            <el-table-column v-if="type==='source'" prop="status" label="状态" width="100" />

            <el-table-column v-if="type==='document'" prop="title" label="文档标题" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="type==='document'" prop="sourceName" label="来源" width="160" show-overflow-tooltip />
            <el-table-column v-if="type==='document'" prop="status" label="状态" width="110" />
            <el-table-column v-if="type==='document'" label="Chunk" width="90">
              <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
            </el-table-column>

            <el-table-column v-if="type==='card'" prop="knowledgeCode" label="卡片编码" width="160" />
            <el-table-column v-if="type==='card'" prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="type==='card'" prop="status" label="状态" width="120" />
            <el-table-column v-if="type==='card'" label="置信度" width="90">
              <template #default="{ row }">{{ row.confidence ?? '—' }}</template>
            </el-table-column>

            <el-table-column v-if="type==='assertion'" prop="claimCode" label="断言编码" width="140" />
            <el-table-column v-if="type==='assertion'" prop="subject" label="主体" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="type==='assertion'" prop="predicate" label="谓词" width="110" />
            <el-table-column v-if="type==='assertion'" prop="objectValue" label="客体" min-width="160" show-overflow-tooltip />
            <el-table-column v-if="type==='assertion'" prop="status" label="状态" width="120" />

            <el-table-column v-if="type==='entity'" prop="canonicalName" label="规范名" min-width="180" show-overflow-tooltip />
            <el-table-column v-if="type==='entity'" prop="entityType" label="类型" width="140" />
            <el-table-column v-if="type==='entity'" prop="status" label="状态" width="110" />
            <el-table-column v-if="type==='entity'" label="使用量" width="100">
              <template #default="{ row }">{{ (row.claimCount || 0) + (row.cardCount || 0) }}</template>
            </el-table-column>

            <el-table-column v-if="type==='relation'" prop="code" label="关系编码" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <span class="text-[12px] text-accent">
                  {{ actionable(row) ? '查看详情' : '—' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import PanelState from './PanelState.vue'
import {
  getCards, getClaims, getDocuments, getEntities, getSources, unwrap,
} from '../api/knowledge'
import type { Claim, EntityRow, KnowledgeCard, KnowledgeDocument, KnowledgeSource } from '../types'

export type AssetType = 'source'|'document'|'card'|'assertion'|'entity'|'relation'
/** 列表行：不同对象类型字段不同，统一按索引访问，表格列由类型决定。 */
type AssetRow = Record<string, unknown>

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

const rows = ref<AssetRow[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')

const currentLabel = computed(() => tabs.find(v => v.type === props.type)?.label || '资产')
/** 关系没有独立清单接口：通过图谱邻域查看，避免为列表另造数据。 */
const hint = computed(() => props.type === 'relation'
  ? { empty:'请在图谱中查看关系', detail:'关系依附于实体，选中实体后展开邻域即可看到关系边与证据' }
  : { empty:`暂无${currentLabel.value}`, detail:'先在「知识生成 / 文档导入」接入资料并完成解析' })

const filtered = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return rows.value
  return rows.value.filter(row =>
    Object.values(row).some(value => String(value ?? '').toLowerCase().includes(key)))
})

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

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (props.type === 'source') {
      rows.value = unwrap(await getSources(props.knowledgeBaseId), [] as KnowledgeSource[]) as unknown as AssetRow[]
    } else if (props.type === 'document') {
      rows.value = unwrap(await getDocuments(props.knowledgeBaseId), [] as KnowledgeDocument[]) as unknown as AssetRow[]
    } else if (props.type === 'card') {
      rows.value = unwrap(await getCards(props.knowledgeBaseId), [] as KnowledgeCard[]) as unknown as AssetRow[]
    } else if (props.type === 'assertion') {
      rows.value = unwrap(await getClaims(props.knowledgeBaseId), [] as Claim[]) as unknown as AssetRow[]
    } else if (props.type === 'entity') {
      rows.value = unwrap(await getEntities(props.knowledgeBaseId), [] as EntityRow[]) as unknown as AssetRow[]
    } else {
      rows.value = []
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => [props.knowledgeBaseId, props.type], load)
</script>
