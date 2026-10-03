<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">连接器</div>
        <h1 class="kb-title mt-1">外部知识</h1>
        <p class="kb-muted mt-1">
          按外部系统自己的类型与属性浏览已同步的知识：左边挑类型与属性，右边看条目，点开看详情。
          这里只是外部系统当前的样子，不会进入知识库主结构。
        </p>
      </div>
      <div class="flex shrink-0 flex-wrap items-center gap-2">
        <select v-model="connectorId" class="kb-input w-56" @change="onConnectorChange">
          <option v-for="row in connectors" :key="row.id" :value="row.id">
            {{ row.name }}（{{ row.type }}）
          </option>
        </select>
        <input
          v-model="keyword" class="kb-input w-48" placeholder="搜索标题 / 编号"
          @keyup.enter="search">
        <button class="kb-btn-outline" :disabled="!connectorId" @click="search">检索</button>
        <button class="kb-btn-primary" :disabled="!connectorId || syncing" @click="syncNow">
          {{ syncing ? '同步中…' : '同步' }}
        </button>
      </div>
    </header>

    <div v-if="syncMessage" class="mb-3 text-[12px]" :class="syncOk ? 'text-emerald-600' : 'text-rose-600'">
      {{ syncMessage }}
    </div>

    <div class="grid grid-cols-[268px_1fr] gap-3">
      <!-- 左：类型与属性分面 -->
      <aside class="kb-card self-start p-3">
        <div class="flex items-center justify-between">
          <b class="text-[13px]">类型</b>
          <span class="kb-muted text-[11px]">共 {{ facets.total }} 条</span>
        </div>
        <div class="mt-2 flex flex-wrap gap-1.5">
          <button
            class="rounded-lg border px-2.5 py-1 text-[12px] transition"
            :class="type ? 'border-ink-200 bg-white text-ink-600 hover:border-[color:var(--info-bd)]'
              : 'border-[color:var(--info-bd)] kb-state-info'"
            @click="setType('')">
            全部
          </button>
          <button
            v-for="entry in facets.types" :key="entry.value"
            class="rounded-lg border px-2.5 py-1 text-[12px] transition"
            :class="type===entry.value
              ? 'border-[color:var(--info-bd)] kb-state-info'
              : 'border-ink-200 bg-white text-ink-600 hover:border-[color:var(--info-bd)]'"
            @click="setType(entry.value)">
            {{ entry.value }} · {{ entry.count }}
          </button>
        </div>

        <template v-if="facets.attributes.length">
          <div class="my-3 border-t border-[color:var(--line-soft)]" />
          <div class="kb-muted mb-2 text-[11px]">
            同一属性内多选取「或」，不同属性之间取「且」。
          </div>
          <div v-for="facet in facets.attributes" :key="facet.key" class="mb-3">
            <div class="mb-1 text-[12px] font-semibold text-ink-700">{{ facet.label }}</div>
            <label
              v-for="value in facet.values" :key="value.value"
              class="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-[12px] hover:bg-[color:var(--bg-sub)]">
              <input
                type="checkbox" :checked="isSelected(facet.key, value.value)"
                @change="toggle(facet.key, value.value)">
              <span class="truncate">{{ value.value }}</span>
              <span class="kb-muted ml-auto">{{ value.count }}</span>
            </label>
          </div>
        </template>
        <div v-else-if="facets.total" class="kb-muted mt-2 text-[11px]">
          该类型下没有可筛选的属性。
        </div>
        <div v-else class="kb-muted mt-2 text-[11px]">
          还没有同步过数据，点右上角「同步」把外部知识拉进来。
        </div>

        <button
          v-if="filters.length" class="kb-btn-outline mt-1 w-full"
          @click="clearFilters">
          清除筛选（{{ filters.length }}）
        </button>
      </aside>

      <!-- 右：知识列表 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">{{ type || '全部类型' }}</b>
          <span class="kb-muted">
            共 {{ total }} 条<template v-if="lastSyncText"> · {{ lastSyncText }}</template>
          </span>
        </div>
        <div class="p-3">
          <PanelState
            :loading="loading" :error="error" :empty="!rows.length"
            empty-text="没有匹配的知识"
            :empty-hint="connectorId ? '换个类型或清除属性筛选试试' : '先在上方选择一个连接器'"
            retryable @retry="load">
            <el-table :data="rows" border size="small" highlight-current-row @row-click="openDetail">
              <el-table-column prop="externalId" label="编号" width="120" />
              <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
              <el-table-column prop="type" label="类型" width="110" show-overflow-tooltip />
              <!-- 列表列随类型走：取当前类型的两个主属性，而不是写死一套列。 -->
              <el-table-column
                v-for="column in columns" :key="column.key"
                :label="column.label" width="130" show-overflow-tooltip>
                <template #default="{ row }">{{ attrValue(row, column.key) || '—' }}</template>
              </el-table-column>
              <el-table-column label="更新时间" width="150">
                <template #default="{ row }">{{ attrTime(row.updatedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <button class="kb-btn-ghost" @click.stop="openDetail(row)">详情</button>
                </template>
              </el-table-column>
            </el-table>
          </PanelState>
          <div v-if="total > size" class="mt-3 flex justify-end">
            <el-pagination
              small layout="prev,pager,next,total,sizes" :current-page="page" :page-size="size"
              :page-sizes="[20,50,100]" :total="total" :pager-count="5"
              @current-change="setPage" @size-change="setSize" />
          </div>
        </div>
      </article>
    </div>

    <!-- 详情：属性表 + 摘要 + 原文 -->
    <el-drawer v-model="detailVisible" :title="detail?.title || '条目详情'" size="46%">
      <div v-if="detailLoading" class="kb-muted text-[13px]">加载中…</div>
      <div v-else-if="detail">
        <div class="grid grid-cols-[88px_1fr] gap-y-1.5 text-[13px]">
          <span class="kb-muted">外部编号</span><span>{{ detail.externalId || '—' }}</span>
          <span class="kb-muted">类型</span><span>{{ detail.type || '—' }}</span>
          <span class="kb-muted">更新时间</span><span>{{ attrTime(detail.updatedAt) }}</span>
          <span class="kb-muted">同步时间</span><span>{{ attrTime(detail.syncedTime) }}</span>
          <span class="kb-muted">来源</span>
          <span>
            {{ detail.connectorName || '—' }}
            <a
              v-if="detail.url" :href="detail.url" target="_blank" rel="noopener"
              class="ml-1 text-[color:var(--accent-text)] hover:underline">在源系统打开</a>
          </span>
        </div>

        <div v-if="detailAttributes.length" class="mt-4">
          <b class="text-[13px]">属性</b>
          <div class="mt-2 grid grid-cols-2 gap-2">
            <div
              v-for="attribute in detailAttributes" :key="attribute.key"
              class="rounded-lg border border-[color:var(--line-soft)] px-2.5 py-2">
              <div class="kb-muted text-[11px]">{{ attribute.label }}</div>
              <div class="truncate text-[13px]" :title="String(attribute.value ?? '')">
                {{ attribute.value || '—' }}
              </div>
            </div>
          </div>
        </div>

        <div v-if="detail.excerpt" class="mt-4">
          <b class="text-[13px]">摘要</b>
          <p class="kb-muted mt-1 whitespace-pre-wrap text-[13px] leading-6">{{ detail.excerpt }}</p>
        </div>

        <details v-if="detail.raw" class="mt-4">
          <summary class="cursor-pointer text-[13px]">外部系统原文</summary>
          <pre class="mt-2 max-h-80 overflow-auto rounded-lg bg-[color:var(--bg-sub)] p-3 text-[12px] leading-5">{{ detail.raw }}</pre>
        </details>
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type {
  CatalogAttribute, CatalogFacets, CatalogItem, CatalogPage, CatalogSyncResult, ConnectorSource,
} from '../types'
import {
  connectorCatalog, connectorCatalogFacets, connectorCatalogItem, listConnectors, syncConnectorCatalog,
} from '../api/knowledge'
import { unwrap } from '../api/knowledge'
import { fromNow } from '../utils/time'
import { notifyError, notifySuccess } from '../utils/feedback'
import PanelState from './PanelState.vue'

const props = defineProps<{ knowledgeBaseId:number }>()

const connectors = ref<ConnectorSource[]>([])
const connectorId = ref<number|null>(null)
const type = ref('')
const keyword = ref('')
/** 属性筛选在 URL 上的形态：key=value，可多选、可跨属性。 */
const filters = ref<string[]>([])
const page = ref(1)
const size = ref(20)

const rows = ref<CatalogItem[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const facets = ref<CatalogFacets>({ types:[], attributes:[], total:0 })

const syncing = ref(false)
const syncMessage = ref('')
const syncOk = ref(true)

/** 详情里的属性按「标签 + 值」逐个卡片展示，键的顺序由后端采集顺序决定。 */
const detailAttributes = computed<CatalogAttribute[]>(() => {
  const attributes = detail.value?.attributes
  if (!attributes) return []
  return Object.values(attributes).filter(item => item && item.value !== null && item.value !== '')
})

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<CatalogItem|null>(null)

/** 列表的动态列：类型决定看哪些属性，最多两列，避免把表格撑成属性墙。 */
const columns = computed(() =>
  facets.value.attributes.slice(0, 2).map(facet => ({ key:facet.key, label:facet.label })))

const lastSyncText = computed(() => {
  const time = facets.value.lastSyncTime
  const count = facets.value.lastSyncCount
  if (!time && count == null) return ''
  return `同步于 ${fromNow(time)}${count == null ? '' : ` · ${count} 条`}`
})

async function loadConnectors() {
  try {
    const response = await listConnectors(props.knowledgeBaseId)
    connectors.value = unwrap(response, [] as ConnectorSource[])
    const first = connectors.value[0]
    if (first && connectorId.value === null) {
      connectorId.value = first.id
    }
  } catch (e) {
    error.value = (e as Error).message || '连接器加载失败'
  }
}

async function loadFacets() {
  if (connectorId.value === null) return
  try {
    const response = await connectorCatalogFacets(connectorId.value, type.value || undefined)
    facets.value = unwrap(response, { types:[], attributes:[], total:0 } as CatalogFacets)
  } catch (e) {
    // 分面只是筛选辅助，失败不该把整个列表也带走。
    facets.value = { types:[], attributes:[], total:0 }
  }
}

async function load() {
  if (connectorId.value === null) return
  loading.value = true
  error.value = ''
  try {
    const response = await connectorCatalog(connectorId.value, {
      type:type.value || undefined,
      keyword:keyword.value || undefined,
      attr:filters.value.length ? filters.value : undefined,
      page:page.value,
      size:size.value,
    })
    const data = unwrap(response, {} as CatalogPage)
    rows.value = data.items || []
    total.value = data.total || 0
    await loadFacets()
  } catch (e) {
    error.value = (e as Error).message || '知识列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function syncNow() {
  if (connectorId.value === null) return
  syncing.value = true
  syncMessage.value = ''
  try {
    const response = await syncConnectorCatalog(connectorId.value)
    const result = unwrap(response, {} as CatalogSyncResult)
    syncOk.value = true
    syncMessage.value = result.message || '同步完成'
    notifySuccess(syncMessage.value)
    page.value = 1
    await load()
  } catch (e) {
    syncOk.value = false
    syncMessage.value = (e as Error).message || '同步失败'
    notifyError('同步失败', syncMessage.value)
  } finally {
    syncing.value = false
  }
}

function search() {
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

function setType(next:string) {
  if (type.value === next) return
  type.value = next
  // 换了类型，属性取值随之变化，旧的筛选条件已经没有意义。
  filters.value = []
  page.value = 1
  load()
}

function isSelected(key:string, value:string) {
  return filters.value.includes(`${key}=${value}`)
}

function toggle(key:string, value:string) {
  const filter = `${key}=${value}`
  filters.value = filters.value.includes(filter)
    ? filters.value.filter(item => item !== filter)
    : [...filters.value, filter]
  page.value = 1
  load()
}

function clearFilters() {
  filters.value = []
  page.value = 1
  load()
}

function onConnectorChange() {
  type.value = ''
  filters.value = []
  keyword.value = ''
  page.value = 1
  detail.value = null
  load()
}

async function openDetail(row:CatalogItem) {
  detail.value = row
  detailVisible.value = true
  detailLoading.value = true
  try {
    const response = await connectorCatalogItem(row.id)
    detail.value = unwrap(response, row)
  } catch (e) {
    notifyError('详情加载失败', (e as Error).message)
  } finally {
    detailLoading.value = false
  }
}

function attrValue(row:CatalogItem, key:string):string {
  const attribute = row.attributes?.[key] as CatalogAttribute | undefined
  return attribute ? String(attribute.value ?? '') : ''
}

function attrTime(value?:string|null) {
  return value ? fromNow(value) : '—'
}

watch(() => props.knowledgeBaseId, () => {
  connectorId.value = null
  detail.value = null
  rows.value = []
  facets.value = { types:[], attributes:[], total:0 }
  loadConnectors().then(() => load())
})

onMounted(async () => {
  await loadConnectors()
  await load()
})
</script>
