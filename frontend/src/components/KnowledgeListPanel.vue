<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">{{ mode === 'cards' ? 'KNOWLEDGE CARDS' : 'CLAIMS' }}</div>
        <h1 class="kb-title mt-1">{{ mode === 'cards' ? '知识卡片' : '断言' }}</h1>
        <p class="kb-muted mt-1">
          {{ mode === 'cards'
            ? '已裁决、结构化、可直接引用的正式知识。'
            : '断言携带限定词、极性、确定性与有效期：条件不同即视为不同命题，有效期互斥视为版本演进而非冲突。' }}
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <input
          v-model="keyword"
          class="kb-input w-52"
          :placeholder="mode === 'cards' ? '搜索标题 / 编码' : '搜索主体 / 客体 / 编码'" />
        <select v-model="statusFilter" class="kb-input w-44">
          <option value="">全部状态</option>
          <option v-for="s in statusOptions" :key="s" :value="s">{{ statusLabel(s) }}</option>
        </select>
      </div>
    </header>

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">{{ mode === 'cards' ? '卡片清单' : '断言清单' }}</b>
        <span class="kb-muted">{{ filtered.length }} / {{ items.length }} 条</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="error"
          :empty="!filtered.length"
          :empty-text="items.length ? '没有符合筛选条件的记录' : (mode === 'cards' ? '暂无知识卡片' : '暂无断言')"
          :empty-hint="items.length ? '调整状态或关键词后重试' : '先在「资料与摄入」上传资料，抽取与裁决完成后会出现在这里'"
          retryable
          @retry="$emit('retry')">
          <el-table :data="filtered" border size="small" @row-click="openDetail">
            <template v-if="mode === 'cards'">
              <el-table-column label="类型" width="150">
                <template #default="{ row }">{{ cardTypeLabel(row.type) }}</template>
              </el-table-column>
              <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
              <el-table-column label="版本" width="70">
                <template #default="{ row }">{{ row.version ?? '—' }}</template>
              </el-table-column>
            </template>
            <template v-else>
              <el-table-column prop="claimCode" label="断言编码" width="120" />
              <el-table-column prop="subject" label="主体" min-width="140" show-overflow-tooltip />
              <el-table-column prop="predicate" label="谓词" width="120" show-overflow-tooltip />
              <el-table-column prop="objectValue" label="客体" min-width="140" show-overflow-tooltip />
              <el-table-column label="限定词" width="150" show-overflow-tooltip>
                <template #default="{ row }">
                  <span :class="qualifierText(row) ? '' : 'text-ink-300'">{{ qualifierText(row) || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="极性" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.polarity" :type="isNegative(row) ? 'danger' : 'success'" size="small" effect="plain">
                    {{ polarityLabel(row.polarity) }}
                  </el-tag>
                  <span v-else class="text-[11px] text-ink-400">未标注</span>
                </template>
              </el-table-column>
              <el-table-column label="确定性" width="88">
                <template #default="{ row }">{{ row.certainty ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="有效期" width="190">
                <template #default="{ row }">
                  <div class="flex items-center gap-1">
                    <span class="text-[11px]">{{ validityText(row) }}</span>
                    <el-tag v-if="validityLabel(row)" :type="validityType(row)" size="small" effect="plain">
                      {{ validityLabel(row) }}
                    </el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="版本" width="70">
                <template #default="{ row }">{{ row.version ?? '—' }}</template>
              </el-table-column>
            </template>
            <el-table-column label="置信度" width="90">
              <template #default="{ row }">{{ row.confidence ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small" effect="plain">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain @click.stop="openDetail(row)">详情</el-button>
                <el-button size="small" @click.stop="$emit('open-graph', objectCode(row))">图谱</el-button>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Claim, KnowledgeCard } from '../types'
import { cardTypeLabel, polarityLabel, statusLabel, statusTagType } from '../types'
import PanelState from './PanelState.vue'

const props = defineProps<{
  mode:'cards'|'claims'
  items:Array<KnowledgeCard|Claim>
  loading?:boolean
  error?:string
}>()
const emit = defineEmits<{
  'open-graph':[code:string]
  'open-detail':[mode:'CARD'|'CLAIM',code:string]
  retry:[]
}>()

const keyword = ref('')
const statusFilter = ref('')

const statusOptions = computed(() => {
  const set = new Set<string>()
  props.items.forEach(item => { if (item.status) set.add(String(item.status)) })
  return Array.from(set).sort()
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.items.filter(item => {
    if (statusFilter.value && String(item.status) !== statusFilter.value) return false
    if (!kw) return true
    const row = item as Record<string, unknown>
    return ['claimCode','knowledgeCode','title','subject','predicate','objectValue','summary']
      .some(field => String(row[field] ?? '').toLowerCase().includes(kw))
  })
})

function objectCode(row:KnowledgeCard|Claim) {
  return props.mode === 'cards' ? (row as KnowledgeCard).knowledgeCode : (row as Claim).claimCode
}
function openDetail(row:KnowledgeCard|Claim) {
  emit('open-detail', props.mode === 'cards' ? 'CARD' : 'CLAIM', objectCode(row))
}

/** 限定词可能来自结构化字段，也可能是历史数据里的 JSON 字符串。 */
function qualifierText(row:Claim) {
  const raw = row.qualifierJson ?? row.conditionJson
  if (raw === null || raw === undefined || raw === '') return ''
  if (typeof raw === 'object') return pairText(raw as Record<string, unknown>)
  const text = String(raw).trim()
  if (!text) return ''
  try {
    const parsed = JSON.parse(text)
    if (parsed && typeof parsed === 'object') return pairText(parsed as Record<string, unknown>)
    return String(parsed)
  } catch {
    return text
  }
}
function pairText(value:Record<string, unknown>) {
  return Object.entries(value).map(([k, v]) => `${k}=${String(v)}`).join(' · ')
}
function fmtDate(value:unknown) {
  if (!value) return ''
  return String(value).slice(0, 10)
}
function validityText(row:Claim) {
  const from = fmtDate(row.validFrom)
  const to = fmtDate(row.validTo)
  if (!from && !to) return '—'
  return `${from || '未知'} ~ ${to || '未知'}`
}
/** 有效期为空表示未知而非永久，因此这里只依据明确期限给出提示。 */
function validityType(row:Claim) {
  const time = toTime(row.validTo)
  if (time === null) return 'info' as const
  const days = (time - Date.now()) / 86400000
  if (days < 0) return 'danger' as const
  if (days <= 30) return 'warning' as const
  return 'success' as const
}
function validityLabel(row:Claim) {
  const time = toTime(row.validTo)
  if (time === null) return ''
  const days = (time - Date.now()) / 86400000
  if (days < 0) return '已过期'
  if (days <= 30) return '即将过期'
  return ''
}
function toTime(value:unknown) {
  if (!value) return null
  const time = new Date(String(value).replace(' ', 'T')).getTime()
  return Number.isNaN(time) ? null : time
}
function isNegative(row:Claim) {
  return String(row.polarity || '').toUpperCase() === 'NEGATIVE'
}
</script>
