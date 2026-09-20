<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">INDEX &amp; RELEASE</div>
        <h1 class="kb-title mt-1">索引与发布</h1>
        <p class="kb-muted mt-1">
          元数据先发布、索引异步构建：索引失败不影响知识版本本身，可重放恢复；发布采用蓝绿切换，任一阶段异常都可回滚到上一 ACTIVE 版本。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-outline" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="Outbox Pending" :value="outbox.pending ?? 0" hint="待投递事件" />
      <MetricCard label="Retry" :value="outbox.retry ?? 0" hint="自动重试中" />
      <MetricCard label="Dead Letter" :value="outbox.dead ?? 0" :hint="deadHint" />
      <MetricCard label="索引漂移" :value="driftCount" :hint="driftHint" />
    </div>

    <div v-if="error" class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-700">
      {{ error }}
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">蓝绿索引版本</b><span class="kb-muted">Index Releases</span></div>
        <div class="p-3">
          <PanelState
            :loading="loading"
            :empty="!schemas.length"
            empty-text="暂无索引发布记录"
            empty-hint="首次发布后会出现 release 记录"
            retryable
            @retry="load">
            <el-table :data="schemas" border size="small">
              <el-table-column prop="releaseId" label="Release" min-width="160" show-overflow-tooltip />
              <el-table-column label="Schema" width="90">
                <template #default="{ row }">{{ row.schemaVersion || '—' }}</template>
              </el-table-column>
              <el-table-column label="一致性" width="120">
                <template #default="{ row }">
                  <el-tag :type="integrityType(row.integrityState)" size="small" effect="plain">
                    {{ row.integrityState || '—' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="isActive(row) ? 'success' : 'info'" size="small" effect="plain">{{ row.status || '—' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="180" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="!isActive(row)" size="small" plain :disabled="busy" @click="activate(row)">激活</el-button>
                  <el-button v-if="!isActive(row)" size="small" type="warning" plain :disabled="busy" @click="rollback(row)">回滚</el-button>
                  <span v-if="isActive(row)" class="text-[11px] text-ink-400">当前生效</span>
                </template>
              </el-table-column>
            </el-table>
          </PanelState>
          <div class="mt-3 flex flex-wrap items-center gap-2 border-t border-ink-100 pt-3">
            <input v-model="schemaVersion" class="kb-input w-40" placeholder="Schema 版本，如 v8" />
            <button class="kb-btn-primary" :disabled="busy || !schemaVersion.trim()" @click="rebuild">重建索引（蓝绿）</button>
            <span class="kb-muted">重建在后台异步执行，完成后需激活才会对外生效。</span>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">索引事件（Outbox）</b>
          <div class="flex items-center gap-2">
            <select v-model="eventStatus" class="kb-input w-32" @change="resetEvents">
              <option value="">全部状态</option>
              <option v-for="status in outboxStatuses" :key="status" :value="status">{{ status }}</option>
            </select>
          </div>
        </div>
        <div class="p-3">
          <PanelState
            :loading="eventsLoading"
            :error="eventsError"
            :empty="!events.length"
            empty-text="没有符合条件的索引事件"
            empty-hint="事件会在知识发布后自动产生"
            retryable
            @retry="loadEvents">
            <el-table :data="events" border size="small">
              <el-table-column prop="eventId" label="事件" min-width="150" show-overflow-tooltip />
              <el-table-column label="对象" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="text-[11px]">{{ row.aggregateType }} · {{ row.aggregateKey }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="eventTagType(row.status)" size="small" effect="plain">{{ row.status || '—' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="重试" width="80">
                <template #default="{ row }">{{ row.attemptCount ?? 0 }}/{{ row.maxAttempts ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="!isDone(row)" size="small" type="primary" plain :disabled="busy" @click="replay(row)">重放</el-button>
                  <el-button v-if="!isDone(row)" size="small" plain :disabled="busy" @click="skip(row)">跳过</el-button>
                  <span v-if="isDone(row)" class="text-[11px] text-ink-400">已完成</span>
                </template>
              </el-table-column>
            </el-table>
          </PanelState>
          <div
            v-if="eventsTotal > 0"
            class="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-ink-100 pt-3 text-xs">
            <span class="kb-muted">
              共 {{ eventsTotal }} 条 · 第 {{ eventsPage }}/{{ eventsTotalPages }} 页
            </span>
            <div class="flex flex-wrap items-center gap-2">
              <select v-model.number="eventsSize" class="kb-input w-20" @change="resetEvents">
                <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条/页</option>
              </select>
              <button
                class="kb-btn-outline" :disabled="eventsLoading || eventsPage <= 1"
                @click="changeEventsPage(eventsPage - 1)">上一页
              </button>
              <button
                class="kb-btn-outline" :disabled="eventsLoading || eventsPage >= eventsTotalPages"
                @click="changeEventsPage(eventsPage + 1)">下一页
              </button>
            </div>
          </div>
          <p class="kb-muted mt-2">
            重放与跳过属于运维操作，需要运维角色令牌；跳过会让该对象在派生索引中缺失，需在下次重建时修复。
          </p>
        </div>
      </article>
    </div>

    <article class="kb-card mt-4 border-rose-200">
      <div class="kb-card-head">
        <b class="text-sm text-rose-600">危险操作 · 清空知识库</b>
        <span class="kb-muted">不可恢复，知识库本身保留</span>
      </div>
      <div class="p-4">
        <p class="kb-muted">
          清空会删除该知识库下的全部资料、文档、内容块、知识卡片、断言、证据、实体与图谱关系、摄入记录、问答痕迹与索引事件，
          并同步清理检索视图与已上传的源文件；知识库本身保留，可重新上传资料重建。
        </p>
        <div class="mt-3 flex flex-wrap items-center gap-2">
          <button class="kb-btn-danger" :disabled="busy" @click="purge">清空知识库数据</button>
          <span class="kb-muted">执行前会展示待删除规模，并要求输入知识库名称二次确认。</span>
        </div>
      </div>
    </article>

    <article class="kb-card mt-4">
      <div class="kb-card-head"><b class="text-sm">发布流程</b><span class="kb-muted">无漂移即为一致</span></div>
      <div class="p-4">
        <div class="flex flex-wrap items-center gap-2 text-[11px]">
          <template v-for="(step, index) in flowSteps" :key="step">
            <span class="rounded-lg border border-ink-200 bg-ink-50 px-3 py-1.5 font-medium text-ink-700">{{ step }}</span>
            <span v-if="index < flowSteps.length - 1" class="text-ink-300">→</span>
          </template>
        </div>
        <div v-if="driftPairs.length" class="mt-3 rounded-lg border border-ink-100 bg-ink-50 p-3">
          <b class="text-xs">漂移检测明细</b>
          <div class="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-ink-600">
            <span v-for="pair in driftPairs" :key="pair.key">
              {{ pair.key }}：<b>{{ pair.value }}</b>
            </span>
          </div>
        </div>
        <div v-else class="mt-3 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-[11px] text-emerald-700">
          权威库与派生索引（Elasticsearch / Milvus / Neo4j）一致，无漂移。
        </div>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { DriftResult, IndexSchema, OutboxEvent, OutboxStats } from '../types'
import { errorMessage as message } from '../utils/feedback'
import {
  activateIndexSchema, getIndexDrift, getIndexSchemas, getOutboxEvents, getOutboxStats,
  purgeKnowledgeBase,
  rebuildIndexSchema, replayOutboxEvent, rollbackIndexSchema, skipOutboxEvent, unwrap,
} from '../api/knowledge'
import MetricCard from './MetricCard.vue'
import PanelState from './PanelState.vue'

const props = defineProps<{ knowledgeBaseId:number; baseName?:string }>()
const emit = defineEmits<{ refresh:[] }>()

const outbox = ref<OutboxStats>({})
const drift = ref<DriftResult>({})
const schemas = ref<IndexSchema[]>([])
const events = ref<OutboxEvent[]>([])
const eventsPage = ref(1)
const eventsSize = ref(10)
const eventsTotal = ref(0)
const loading = ref(false)
const eventsLoading = ref(false)
const eventsError = ref('')
const error = ref('')
const busy = ref(false)
const schemaVersion = ref('')
const eventStatus = ref('')

const flowSteps = ['裁决通过', '元数据发布', 'Outbox 投递', '索引构建', '一致性校验', '蓝绿激活']
/** 与后端 IndexOutboxStatus 保持一致：早期前端里的 DONE 过滤器永远查不到数据。 */
const outboxStatuses = ['PENDING', 'PROCESSING', 'RETRY', 'SUCCEEDED', 'DEAD', 'SKIPPED']
const pageSizes = [10, 20, 50]

const eventsTotalPages = computed(() => Math.max(1, Math.ceil(eventsTotal.value / eventsSize.value)))

const driftCount = computed(() => {
  const value = drift.value?.driftCount ?? drift.value?.drifted ?? drift.value?.driftTotal
  if (typeof value === 'number') return value
  return driftPairs.value.length ? 1 : 0
})
const driftHint = computed(() => driftCount.value === 0 ? '权威库与派生索引一致' : '存在漂移，建议重建')
const deadHint = computed(() => (outbox.value.dead ?? 0) > 0 ? '需要人工介入' : '无人工介入')
/** 漂移响应结构随实现演进，这里只展示标量字段，避免误读嵌套数据。 */
const driftPairs = computed(() => Object.entries(drift.value || {})
  .filter(([, value]) => typeof value === 'number' || typeof value === 'string' || typeof value === 'boolean')
  .map(([key, value]) => ({ key, value: String(value) })))

async function load() {
  loading.value = true
  error.value = ''
  try {
    outbox.value = unwrap(await getOutboxStats(props.knowledgeBaseId), {})
  } catch (e) {
    error.value = message(e)
  }
  try {
    drift.value = unwrap(await getIndexDrift(props.knowledgeBaseId), {})
  } catch {
    drift.value = {}
  }
  try {
    schemas.value = unwrap(await getIndexSchemas(props.knowledgeBaseId), [])
    if (!schemaVersion.value && schemas.value.length) {
      schemaVersion.value = String(schemas.value[0].schemaVersion || '')
    }
  } catch {
    schemas.value = []
  }
  loading.value = false
  await loadEvents()
}

async function loadEvents() {
  eventsLoading.value = true
  eventsError.value = ''
  try {
    const page = unwrap(
      await getOutboxEvents(
        props.knowledgeBaseId, eventStatus.value, eventsPage.value, eventsSize.value),
      { items:[], total:0, page:1, size:eventsSize.value, totalPages:1 })
    events.value = page.items || []
    eventsTotal.value = page.total ?? 0
    // 服务端已把越界页码收敛到最后 1 页，这里同步前端页码，避免按钮状态错位。
    eventsPage.value = page.page || 1
  } catch (e) {
    eventsError.value = message(e)
  } finally {
    eventsLoading.value = false
  }
}

/** 筛选条件或页大小变化时回到第一页，否则会停在空白页。 */
function resetEvents() {
  eventsPage.value = 1
  void loadEvents()
}

function changeEventsPage(page:number) {
  if (page < 1 || page > eventsTotalPages.value || page === eventsPage.value) return
  eventsPage.value = page
  void loadEvents()
}

async function replay(row:OutboxEvent) {
  busy.value = true
  try {
    unwrap(await replayOutboxEvent(row.eventId), {})
    ElMessage.success('事件已重新投递')
    await load()
    emit('refresh')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function skip(row:OutboxEvent) {
  try {
    await ElMessageBox.confirm(
      `跳过事件 ${row.eventId}？该对象在派生索引中会缺失，需在下次索引重建时修复。`,
      '跳过索引事件',
      { type:'warning', confirmButtonText:'确认跳过', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    unwrap(await skipOutboxEvent(row.eventId), {})
    ElMessage.success('事件已跳过')
    await load()
    emit('refresh')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

/** 清空不可恢复：先展示待删除规模，再要求输入知识库名称确认，避免误触。 */
async function purge() {
  let counts:Record<string, number> = {}
  try {
    const preview = unwrap(await purgeKnowledgeBase(props.knowledgeBaseId, true), null)
    counts = preview?.counts || {}
  } catch (e) {
    ElMessage.error(message(e))
    return
  }
  const total = Number(counts.total || 0)
  if (!total) {
    ElMessage.info('该知识库暂无可清空的数据')
    return
  }
  const expected = (props.baseName || '').trim() || 'CLEAR'
  try {
    await ElMessageBox.prompt(
      `将删除约 ${total} 条对象：资料 ${counts.sources ?? 0}、文档 ${counts.documents ?? 0}、内容块 ${counts.contentUnits ?? 0}、卡片 ${counts.cards ?? 0}、`
      + `断言 ${counts.claims ?? 0}、证据 ${counts.evidence ?? 0}、实体 ${counts.entities ?? 0}、关系 ${counts.relations ?? 0}。`
      + `请输入「${expected}」确认。`,
      '清空知识库数据',
      {
        type:'warning', confirmButtonText:'确认清空', cancelButtonText:'取消',
        inputPlaceholder:expected,
        inputValidator:value =>
          String(value || '').trim() === expected ? true : `输入不匹配，需要输入「${expected}」`,
      },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    const result = unwrap(await purgeKnowledgeBase(props.knowledgeBaseId), null)
    ElMessage.success(`已清空 ${total} 条对象，移除源文件 ${result?.removedSourceFiles ?? 0} 个`)
    await load()
    emit('refresh')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function rebuild() {
  const version = schemaVersion.value.trim()
  try {
    await ElMessageBox.confirm(
      `按 Schema ${version} 重建索引？重建在蓝绿新版本上进行，不会影响当前生效的查询。`,
      '重建索引',
      { type:'warning', confirmButtonText:'确认重建', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    unwrap(await rebuildIndexSchema(props.knowledgeBaseId, version), {})
    ElMessage.success('索引重建任务已提交，完成后请激活新版本')
    await load()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function activate(row:IndexSchema) {
  try {
    await ElMessageBox.confirm(
      `激活 release ${row.releaseId}？激活后查询会立即切到该版本。`,
      '激活索引版本',
      { type:'warning', confirmButtonText:'确认激活', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    unwrap(await activateIndexSchema(props.knowledgeBaseId, String(row.releaseId)), {})
    ElMessage.success('索引版本已激活')
    await load()
    emit('refresh')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function rollback(row:IndexSchema) {
  try {
    await ElMessageBox.confirm(
      `回滚到 release ${row.releaseId}？回滚后问答结果应与该版本一致，可用于发布事故恢复。`,
      '回滚索引版本',
      { type:'warning', confirmButtonText:'确认回滚', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    unwrap(await rollbackIndexSchema(props.knowledgeBaseId, String(row.releaseId)), {})
    ElMessage.success('已回滚到指定索引版本')
    await load()
    emit('refresh')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

function isActive(row:IndexSchema) {
  return String(row.status || '').toUpperCase() === 'ACTIVE'
}
function isDone(row:OutboxEvent) {
  const status = String(row.status || '').toUpperCase()
  return status === 'SUCCEEDED' || status === 'SKIPPED'
}
function integrityType(state?:string) {
  switch (String(state || '').toUpperCase()) {
    case 'OK': case 'CONSISTENT': case 'PASSED': return 'success' as const
    case 'DRIFT': case 'MISMATCH': return 'warning' as const
    case 'FAILED': return 'danger' as const
    default: return 'info' as const
  }
}
function eventTagType(status?:string) {
  switch (String(status || '').toUpperCase()) {
    case 'DONE': case 'SUCCEEDED': return 'success' as const
    case 'PENDING': case 'PROCESSING': return 'info' as const
    case 'RETRY': return 'warning' as const
    case 'DEAD': return 'danger' as const
    case 'SKIPPED': return 'info' as const
    default: return 'info' as const
  }
}


watch(() => props.knowledgeBaseId, () => { eventsPage.value = 1; void load() })
onMounted(load)
</script>
