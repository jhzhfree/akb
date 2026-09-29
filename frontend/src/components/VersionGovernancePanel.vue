<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识概览</div>
        <h1 class="kb-title mt-1">版本治理</h1>
        <p class="kb-muted mt-1">
          每次新建、裁决、复核、冲突检测都会留下不可变快照。这里回答四个问题：现在是什么、以前是什么、为什么变、原文在哪。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <span class="kb-muted">共 {{ total }} 个快照</span>
        <button class="kb-btn-outline" @click="load">刷新</button>
      </div>
    </header>

    <!-- 页头之后收进一张卡片：与工作台概览、资产浏览同构，不再整页包在卡片里 -->
    <div class="kb-card p-4">
    <div class="grid grid-cols-1 gap-2 md:grid-cols-[130px_160px_minmax(0,1fr)]">
      <select v-model="objectType" class="kb-input" @change="load">
        <option value="">全部对象</option>
        <option value="CARD">知识卡片</option>
        <option value="CLAIM">断言</option>
      </select>
      <select v-model="changeType" class="kb-input" @change="load">
        <option value="">全部变更</option>
        <option v-for="option in changeTypes" :key="option" :value="option">{{ option }}</option>
      </select>
      <input v-model="keyword" class="kb-input" placeholder="按对象码 / 标题搜索…" @keyup.enter="load">
    </div>

    <div v-if="groups.length" class="mt-3 flex flex-wrap gap-2">
      <button
        v-for="group in groups" :key="group.objectType + group.changeType"
        class="kb-badge transition"
        :class="group.objectType === objectType && group.changeType === changeType
          ? 'kb-state-info' : 'bg-ink-50 text-ink-600 hover:bg-ink-100'"
        @click="applyGroup(group)">
        {{ labelOf(group.objectType) }} · {{ group.changeType }} · {{ group.total }}
      </button>
    </div>

    <PanelState
      class="mt-4"
      :loading="loading"
      :error="error"
      :empty="!rows.length"
      empty-text="暂无版本快照"
      empty-hint="知识被创建、裁决或复核后会自动留痕"
      retryable
      @retry="load">
      <el-table :data="rows" border size="small" max-height="520" @row-click="select">
        <el-table-column label="版本" width="70">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column label="对象" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="truncate">{{ row.title || row.objectCode }}</div>
            <div class="text-[10px] text-ink-500">{{ row.objectType }} · {{ row.objectCode.slice(0, 16) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="变更" width="140">
          <template #default="{ row }">{{ row.changeType }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="kb-badge">{{ statusLabel(row.status || '') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">{{ row.confidence == null ? '—' : Number(row.confidence).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="130">
          <template #default="{ row }">{{ row.changedBy || 'SYSTEM' }}</template>
        </el-table-column>
        <el-table-column label="替代" width="80">
          <template #default="{ row }">{{ row.supersedesVersionNo ? 'V' + row.supersedesVersionNo : '—' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ time(row.createdTime) }}</template>
        </el-table-column>
      </el-table>
    </PanelState>

    <!-- 抽屉：版本链 + 差异 + 证据原文定位 -->
    <div v-if="selected" class="mt-4 rounded-xl border border-ink-200 bg-ink-50 p-4">
      <div class="flex items-start justify-between gap-3">
        <div>
          <b class="text-sm text-ink-800">{{ selected.title || selected.objectCode }}</b>
          <div class="kb-muted mt-1">
            {{ selected.objectType }} · {{ selected.objectCode }} · V{{ selected.versionNo }}
            <span v-if="selected.supersedesVersionNo"> ← 替代 V{{ selected.supersedesVersionNo }}</span>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <button class="kb-btn-outline text-[12px]" @click="openDetail">打开知识详情</button>
          <button class="kb-btn-outline text-[12px]" @click="selected = null">关闭</button>
        </div>
      </div>

      <div class="mt-3 grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div>
          <b class="text-xs font-medium text-ink-700">版本链</b>
          <div v-if="chainLoading" class="kb-muted mt-2">载入版本链…</div>
          <VersionDiffPanel v-else class="mt-2" :versions="chain" />
        </div>
        <div>
          <b class="text-xs font-medium text-ink-700">证据原文定位</b>
          <div v-if="evidenceLoading" class="kb-muted mt-2">载入证据链…</div>
          <div v-else-if="!evidence.length" class="kb-muted mt-2">
            该对象当前没有绑定证据；没有原文支撑的版本差异只能算推测。
          </div>
          <ul v-else class="mt-2 space-y-2">
            <li v-for="item in evidence" :key="item.evidenceCode" class="rounded-lg border border-ink-100 bg-white p-3">
              <div class="flex items-center justify-between">
                <span class="text-xs text-ink-700">{{ item.sourceName || '—' }} · {{ item.documentTitle || item.documentCode || '—' }}</span>
                <span class="kb-badge">{{ pageLabel(item) }}</span>
              </div>
              <div v-if="item.headingPath" class="mt-1 text-[11px] text-ink-500">{{ item.headingPath }}</div>
              <div class="mt-1 text-[11px] text-accent">{{ item.contentUnitCode }}</div>
              <p class="mt-2 text-xs leading-5 text-ink-600">{{ item.quoteText || '—' }}</p>
            </li>
          </ul>
        </div>
      </div>
    </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import PanelState from './PanelState.vue'
import VersionDiffPanel from './VersionDiffPanel.vue'
import { getKnowledgeObjectDetail, getVersionEvidence, getVersionSnapshots } from '../api/knowledge'
import { statusLabel } from '../types'
import type { KnowledgeVersion, VersionEvidenceRow, VersionSnapshotRow } from '../types'

const props = defineProps<{ knowledgeBaseId:number }>()
const emit = defineEmits<{ openDetail:[objectType:string, objectCode:string] }>()

const rows = ref<VersionSnapshotRow[]>([])
const groups = ref<{ objectType:string; changeType:string; total:number }[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const objectType = ref('')
const changeType = ref('')
const keyword = ref('')
const selected = ref<VersionSnapshotRow | null>(null)
const chain = ref<KnowledgeVersion[]>([])
const chainLoading = ref(false)
const evidence = ref<VersionEvidenceRow[]>([])
const evidenceLoading = ref(false)

const changeTypes = computed(() => [...new Set(groups.value.map(g => g.changeType))].sort())
const labelOf = (type:string) => type === 'CARD' ? 'Knowledge Card' : type === 'CLAIM' ? 'Assertion' : type
const time = (value?:string) => value ? new Date(value).toLocaleString() : '—'
const pageLabel = (item:VersionEvidenceRow) =>
  item.pageNo == null ? '未分页'
    : item.pageEnd && item.pageEnd !== item.pageNo ? `P${item.pageNo}-${item.pageEnd}` : `P${item.pageNo}`

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await getVersionSnapshots(props.knowledgeBaseId, {
      objectType:objectType.value || undefined,
      changeType:changeType.value || undefined,
      keyword:keyword.value || undefined,
      limit:100,
    })
    const data = res.data.data
    rows.value = data?.rows || []
    groups.value = data?.groups || []
    total.value = data?.total || 0
  } catch (e) {
    error.value = (e as Error).message || '版本快照加载失败'
  } finally { loading.value = false }
}

/**
 * 选中快照时补齐同一对象的完整版本链。
 * 走详情接口而不是列表接口：列表只回 400 字摘要，摘要做不了正文差异比对。
 */
async function select(row:VersionSnapshotRow) {
  selected.value = row
  chainLoading.value = true
  try {
    const res = await getKnowledgeObjectDetail(
      props.knowledgeBaseId, row.objectType as 'CARD' | 'CLAIM', row.objectCode)
    chain.value = res.data.data?.versions || []
  } catch {
    // 详情拿不到时退回列表摘要：链仍在，只是正文差异为空，比什么都不显示强。
    const res = await getVersionSnapshots(props.knowledgeBaseId, {
      objectType:row.objectType, keyword:row.objectCode, limit:100,
    })
    chain.value = (res.data.data?.rows || [])
      .filter(v => v.objectCode === row.objectCode)
      .map(v => ({ ...v, supersedesVersionNo:v.supersedesVersionNo ?? undefined }))
  } finally { chainLoading.value = false }
  await loadEvidence(row)
}

async function loadEvidence(row:VersionSnapshotRow) {
  evidenceLoading.value = true
  evidence.value = []
  try {
    const res = await getVersionEvidence(props.knowledgeBaseId, row.objectType, row.objectCode)
    evidence.value = res.data.data?.items || []
  } catch {
    evidence.value = []
  } finally { evidenceLoading.value = false }
}

function applyGroup(group:{ objectType:string; changeType:string }) {
  objectType.value = group.objectType
  changeType.value = group.changeType
  load()
}

function openDetail() {
  if (!selected.value) return
  emit('openDetail', selected.value.objectType, selected.value.objectCode)
}

watch(() => props.knowledgeBaseId, () => { selected.value = null; load() })
load()
</script>
