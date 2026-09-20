<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">ENTITY RESOLUTION</div>
        <h1 class="kb-title mt-1">实体与别名词典</h1>
        <p class="kb-muted mt-1">
          自动归一的结果必须可被人看见与纠正：别名词典命中为第一级判定，向量相似度为第二级；误合并可解绑别名并恢复实体。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <input v-model="keyword" class="kb-input w-52" placeholder="搜索实体 / 别名" />
        <button class="kb-btn-outline" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[1.35fr_1fr]">
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">规范实体</b>
          <span class="kb-muted">{{ filtered.length }} / {{ entities.length }} 个</span>
        </div>
        <div class="p-3">
          <PanelState
            :loading="loading"
            :error="error"
            :empty="!filtered.length"
            :empty-text="entities.length ? '没有匹配的实体' : '暂无实体'"
            :empty-hint="entities.length ? '换个关键词试试' : '资料抽取后会自动建立实体与别名'"
            retryable
            @retry="load">
            <el-table :data="filtered" border size="small" @row-click="select">
              <el-table-column prop="canonicalName" label="实体" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <div>
                    <b class="text-[13px]">{{ row.canonicalName || row.entityCode }}</b>
                    <div class="text-[11px] text-ink-400">{{ row.entityCode }}</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="140">
                <template #default="{ row }">{{ entityTypeLabel(row.entityType) }}</template>
              </el-table-column>
              <el-table-column label="别名" width="72">
                <template #default="{ row }">{{ row.aliasCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="断言" width="72">
                <template #default="{ row }">{{ row.claimCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="卡片" width="72">
                <template #default="{ row }">{{ row.cardCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="状态" width="140">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" size="small" effect="plain">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" plain @click.stop="select(row)">别名</el-button>
                  <el-button size="small" @click.stop="$emit('open-graph', row.entityCode)">图谱</el-button>
                </template>
              </el-table-column>
            </el-table>
          </PanelState>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">{{ selected ? '别名与归一依据' : '实体详情' }}</b>
          <span v-if="selected" class="kb-muted">{{ selected.entityCode }}</span>
        </div>
        <div class="p-4">
          <PanelState
            :loading="detailLoading"
            :error="detailError"
            :empty="!selected"
            empty-text="选择一个实体查看别名"
            empty-hint="左侧列表点击「别名」或任意一行"
            retryable
            @retry="selected && select(selected)">
            <div v-if="selected" class="space-y-3">
              <div class="rounded-lg bg-ink-50 p-3">
                <div class="flex items-center justify-between">
                  <b class="text-sm">{{ selected.canonicalName || selected.entityCode }}</b>
                  <el-tag :type="statusTagType(selected.status)" size="small" effect="plain">
                    {{ statusLabel(selected.status) }}
                  </el-tag>
                </div>
                <div class="mt-1 text-[11px] text-ink-500">
                  {{ entityTypeLabel(selected.entityType) }} · 置信度 {{ selected.confidence ?? '—' }}
                </div>
                <p v-if="selected.description" class="kb-muted mt-2">{{ selected.description }}</p>
              </div>

              <div class="rounded-lg bg-ink-50 p-3">
                <b class="text-xs">影响面</b>
                <div class="mt-1 text-[11px] text-ink-600">
                  关联断言 {{ selected.claimCount ?? 0 }} 条 · 知识卡片 {{ selected.cardCount ?? 0 }} 张 · 别名 {{ aliases.length }} 条
                </div>
              </div>

              <div>
                <div class="mb-1 flex items-center justify-between">
                  <b class="text-xs">别名词典</b>
                  <span class="kb-muted">{{ aliases.length }} 条</span>
                </div>
                <div v-if="aliases.length" class="flex flex-wrap gap-1.5">
                  <span
                    v-for="item in aliases"
                    :key="item.alias"
                    class="inline-flex items-center gap-1 rounded-md border border-ink-200 bg-white px-2 py-1 text-[11px]">
                    <span>{{ item.alias }}</span>
                    <small class="text-ink-400">{{ item.aliasType || 'SURFACE' }}</small>
                    <button class="text-rose-400 hover:text-rose-600" title="解绑该别名" @click="unbind(item.alias)">✕</button>
                  </span>
                </div>
                <p v-else class="kb-muted">暂无别名：该实体的表面形式尚未沉淀为词典条目。</p>
                <div class="mt-2 flex gap-2">
                  <input v-model="newAlias" class="kb-input flex-1" placeholder="补充人工别名" @keyup.enter="bindAlias" />
                  <button class="kb-btn-primary" :disabled="busy || !newAlias.trim()" @click="bindAlias">添加</button>
                </div>
              </div>

              <div class="border-t border-ink-100 pt-3">
                <b class="text-xs">归一纠错</b>
                <p class="kb-muted mt-1">
                  合并会把别名与提及链接迁移到目标实体，并把当前实体置为 MERGED；被合并的实体不会被物理删除，可随时恢复。
                </p>
                <div class="mt-2 flex flex-wrap items-center gap-2">
                  <select v-model="mergeTarget" class="kb-input w-48">
                    <option value="">选择目标实体…</option>
                    <option v-for="row in mergeCandidates" :key="row.entityCode" :value="row.entityCode">
                      {{ row.canonicalName || row.entityCode }}
                    </option>
                  </select>
                  <button class="kb-btn-primary" :disabled="busy || !mergeTarget" @click="mergeInto">确认为同一实体</button>
                  <button
                    v-if="isMerged"
                    class="kb-btn-outline"
                    :disabled="busy"
                    @click="setStatus('ACTIVE')">解除合并（恢复为 {{ statusLabel('ACTIVE') }}）</button>
                </div>
              </div>
            </div>
          </PanelState>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { EntityDetail, EntityRow } from '../types'
import { entityTypeLabel, statusLabel, statusTagType } from '../types'
import { errorMessage as message } from '../utils/feedback'
import {
  addEntityAlias, getEntities, getEntityDetail, mergeEntity, removeEntityAlias, updateEntityStatus, unwrap,
} from '../api/knowledge'
import PanelState from './PanelState.vue'

const props = defineProps<{ knowledgeBaseId:number }>()
const emit = defineEmits<{ refresh:[]; 'open-graph':[code:string] }>()

const entities = ref<EntityRow[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')

const selected = ref<EntityRow|null>(null)
const detail = ref<EntityDetail|null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const newAlias = ref('')
const mergeTarget = ref('')
const busy = ref(false)

const aliases = computed(() => detail.value?.aliases || [])
const isMerged = computed(() => String(selected.value?.status || '').toUpperCase() === 'MERGED')
const mergeCandidates = computed(() =>
  entities.value.filter(row => row.entityCode !== selected.value?.entityCode))
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return entities.value
  return entities.value.filter(row =>
    `${row.entityCode} ${row.canonicalName || ''} ${row.entityType || ''}`.toLowerCase().includes(kw))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    entities.value = unwrap(await getEntities(props.knowledgeBaseId), [])
  } catch (e) {
    error.value = message(e)
  } finally {
    loading.value = false
  }
}

/** 详情按需拉取：别名与影响面只在选中实体时查询，避免列表页放大请求。 */
async function select(row:EntityRow) {
  selected.value = row
  mergeTarget.value = ''
  newAlias.value = ''
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = unwrap(await getEntityDetail(props.knowledgeBaseId, row.entityCode), null)
  } catch (e) {
    detailError.value = message(e)
  } finally {
    detailLoading.value = false
  }
}

async function bindAlias() {
  const alias = newAlias.value.trim()
  if (!alias || !selected.value) return
  busy.value = true
  try {
    unwrap(await addEntityAlias(props.knowledgeBaseId, selected.value.entityCode, alias), {})
    newAlias.value = ''
    ElMessage.success('别名已写入词典，后续抽取会直接归一')
    await afterChange()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function unbind(alias:string) {
  if (!selected.value) return
  busy.value = true
  try {
    unwrap(await removeEntityAlias(props.knowledgeBaseId, selected.value.entityCode, alias), {})
    ElMessage.success('别名已解绑')
    await afterChange()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function mergeInto() {
  if (!selected.value || !mergeTarget.value) return
  const source = selected.value
  const target = entities.value.find(row => row.entityCode === mergeTarget.value)
  try {
    await ElMessageBox.confirm(
      `将「${source.canonicalName || source.entityCode}」合并到「${target?.canonicalName || mergeTarget.value}」？`
      + `别名与 ${source.claimCount ?? 0} 条断言关联会一并迁移，源实体转为 MERGED。`,
      '确认实体合并',
      { type:'warning', confirmButtonText:'确认合并', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    unwrap(await mergeEntity(props.knowledgeBaseId, source.entityCode, mergeTarget.value), {})
    ElMessage.success('实体已合并')
    await afterChange()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function setStatus(status:string) {
  if (!selected.value) return
  busy.value = true
  try {
    unwrap(await updateEntityStatus(props.knowledgeBaseId, selected.value.entityCode, status), {})
    ElMessage.success(`实体状态已更新为 ${statusLabel(status)}`)
    await afterChange()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

/** 归一纠错会改变图谱与统计，因此列表、详情与概览都要刷新。 */
async function afterChange() {
  await load()
  if (selected.value) {
    const code = selected.value.entityCode
    const latest = entities.value.find(row => row.entityCode === code)
    if (latest) await select(latest)
  }
  emit('refresh')
}



watch(() => props.knowledgeBaseId, () => {
  selected.value = null
  detail.value = null
  void load()
})
onMounted(load)
</script>
