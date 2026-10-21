<template>
  <DebugLayout
    eyebrow="知识检索"
    title="检索评测"
    hint="回放标注问题集，逐题计算 Hit@K / Recall / MRR / NDCG 与延迟分位；平均值看趋势，逐用例证据定位坏题，基线对比判定回归。"
    config-hint="数据集与运行参数"
    result-hint="运行指标与逐用例结果">
    <template #config>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
        <select v-model="datasetCode" class="kb-input" @change="loadCases">
          <option value="">选择评测数据集…</option>
          <option v-for="ds in datasets" :key="String(ds.datasetCode)" :value="String(ds.datasetCode)">
            {{ ds.name }}（{{ ds.caseCount ?? 0 }} 题）
          </option>
        </select>
        <button class="kb-btn-outline shrink-0" @click="showCreate = !showCreate">
          {{ showCreate ? '收起' : '新建数据集' }}
        </button>
      </div>

      <div v-if="showCreate" class="mt-3 grid grid-cols-1 gap-2 rounded-lg border border-ink-100 bg-ink-50 p-3 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_auto]">
        <input v-model="newDataset.name" class="kb-input" placeholder="数据集名称" />
        <input v-model="newDataset.description" class="kb-input" placeholder="描述（可选）" />
        <button class="kb-btn-primary shrink-0" :disabled="!newDataset.name.trim() || busy" @click="createDataset">
          创建
        </button>
      </div>

      <div v-if="datasetCode" class="mt-3 grid grid-cols-1 gap-2 md:grid-cols-[110px_120px_auto]">
        <input v-model.number="newCase.topK" type="number" min="1" max="50" class="kb-input" placeholder="topK" />
        <select v-model="mode" class="kb-input">
          <option value="RETRIEVAL">仅检索</option>
          <option value="FULL">含生成</option>
        </select>
        <button class="kb-btn-primary shrink-0" :disabled="running || !caseRows.length" @click="runEvaluationNow">
          {{ running ? '回放中…' : '运行评测' }}
        </button>
      </div>
      <p class="kb-muted mt-2">
        期望命中填知识标识：内容单元 id、文档名或证据码均可；
        <b>仅检索</b>模式不调用生成模型，延迟才是检索延迟。
      </p>

      <div v-if="datasetCode" class="mt-3 rounded-lg border border-ink-100 p-3">
        <div class="mb-2 flex items-center justify-between">
          <b class="text-xs font-medium text-ink-700">评测用例</b>
          <span class="kb-muted">{{ caseRows.length }} 题</span>
        </div>
        <div class="grid grid-cols-1 gap-2 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_auto]">
          <input v-model="newCase.question" class="kb-input" placeholder="问题" />
          <input v-model="newCase.expected" class="kb-input" placeholder="期望命中，逗号分隔，如 71 / sample.txt" />
          <button class="kb-btn-outline shrink-0" :disabled="!newCase.question.trim() || !newCase.expected.trim()" @click="addCase">
            添加用例
          </button>
        </div>
        <ul v-if="caseRows.length" class="mt-2 divide-y divide-ink-100">
          <li v-for="row in caseRows" :key="String(row.caseCode)" class="flex items-start gap-2 py-2">
            <div class="min-w-0 flex-1">
              <div class="truncate text-xs text-ink-700">{{ row.question }}</div>
              <div class="text-[11px] text-ink-500">期望：{{ codes(row.expectedObjectCodesJson) }}</div>
            </div>
            <button class="kb-btn-outline text-[11px]" @click="removeCase(row.id)">删除</button>
          </li>
        </ul>
        <p v-else class="kb-muted mt-2">还没有用例；没有期望答案的用例只会永远记零分，先把题目标好。</p>
      </div>
    </template>

    <template #result>
      <PanelState
        :loading="running"
        :error="error"
        :empty="!current && !runs.length"
        empty-text="尚无评测运行"
        empty-hint="选择数据集并运行评测，指标与逐用例结果会在此展示"
        retryable
        @retry="loadRuns">
        <!-- 指标卡：平均值 + 与基线的差值，回归结论单独成色。 -->
        <div v-if="current" class="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
          <div v-for="card in metricCards" :key="card.label" class="rounded-lg border border-ink-100 px-3 py-2">
            <div class="kb-muted">{{ card.label }}</div>
            <div class="text-lg font-semibold text-ink-800">{{ card.value }}</div>
            <div v-if="card.delta" class="text-[11px]" :class="card.delta.startsWith('-') ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]'">
              基线 {{ card.delta }}
            </div>
          </div>
        </div>

        <div v-if="current" class="mb-3 flex flex-wrap items-center gap-2">
          <span class="kb-badge">{{ current.runCode }}</span>
          <span class="kb-badge">{{ current.mode === 'FULL' ? '含生成' : '仅检索' }}</span>
          <span class="kb-badge">{{ current.caseCount }} 题 · topK {{ current.topK }}</span>
          <span v-if="current.policyVersion" class="kb-badge">策略 {{ current.policyVersion }}</span>
          <span v-if="current.regressionVerdict" class="kb-badge" :class="verdictClass">
            {{ verdictLabel }}
          </span>
          <button class="kb-btn-outline ml-auto text-[12px]" @click="useAsBaseline">
            设为基线
          </button>
        </div>

        <el-table v-if="results.length" :data="results" border size="small" max-height="420">
          <el-table-column label="用例" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.caseCode }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span :class="row.status === 'SUCCESS' ? 'text-[color:var(--ok)]' : 'text-[color:var(--danger)]'">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="命中率@1" width="70">
            <template #default="{ row }">{{ flag(row.hitAt1) }}</template>
          </el-table-column>
          <el-table-column label="命中率@5" width="70">
            <template #default="{ row }">{{ flag(row.hitAt5) }}</template>
          </el-table-column>
          <el-table-column label="MRR" width="80">
            <template #default="{ row }">{{ fixed(row.reciprocalRank) }}</template>
          </el-table-column>
          <el-table-column label="召回率@5" width="90">
            <template #default="{ row }">{{ fixed(row.recallAt5) }}</template>
          </el-table-column>
          <el-table-column label="排序质量@10" width="90">
            <template #default="{ row }">{{ fixed(row.ndcgAt10) }}</template>
          </el-table-column>
          <el-table-column label="延迟" width="80">
            <template #default="{ row }">{{ row.latencyMs ?? '—' }} ms</template>
          </el-table-column>
          <el-table-column label="期望" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ codes(row.expectedCodesJson) }}</template>
          </el-table-column>
          <el-table-column label="实际召回" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ codes(row.retrievedCodesJson) }}</template>
          </el-table-column>
          <el-table-column v-if="current?.mode === 'FULL'" label="引用" width="90">
            <template #default="{ row }">{{ row.citationHit ?? 0 }}/{{ row.citationTotal ?? 0 }}</template>
          </el-table-column>
        </el-table>

        <!-- 知识缺口：未召回期望知识的问题，直接指向要补的内容。 -->
        <div v-if="gapCount" class="mt-3 rounded-lg border border-[color:var(--warn-bd)] bg-[color:var(--warn-bg)] px-3 py-2">
          <b class="text-xs text-[color:var(--warn)]">知识缺口：{{ gapCount }} 题未召回期望知识</b>
          <div class="mt-1 flex flex-wrap gap-1">
            <span v-for="code in gapCodes" :key="code" class="kb-badge">{{ code.slice(0, 12) }}</span>
          </div>
        </div>

        <div class="mt-4">
          <div class="mb-2 flex items-center justify-between">
            <b class="text-xs font-medium text-ink-700">运行历史</b>
            <div class="flex items-center gap-2">
              <label class="kb-muted flex items-center gap-1">
                <input v-model="baselineRunCode" type="checkbox" class="kb-input w-3" />
                下一轮与所选运行对比
              </label>
            </div>
          </div>
          <el-table :data="runs" border size="small" max-height="260" @row-click="selectRun">
            <el-table-column label="运行" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.runCode }}</template>
            </el-table-column>
            <el-table-column label="数据集" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.datasetName || '—' }}</template>
            </el-table-column>
            <el-table-column label="模式" width="80">
              <template #default="{ row }">{{ row.mode === 'FULL' ? '含生成' : '仅检索' }}</template>
            </el-table-column>
            <el-table-column label="命中率@1" width="80">
              <template #default="{ row }">{{ percent(row.hitAt1Rate) }}</template>
            </el-table-column>
            <el-table-column label="MRR" width="80">
              <template #default="{ row }">{{ fixed(row.mrr) }}</template>
            </el-table-column>
            <el-table-column label="P50" width="80">
              <template #default="{ row }">{{ row.latencyP50Ms ?? '—' }} ms</template>
            </el-table-column>
            <el-table-column label="结论" width="90">
              <template #default="{ row }">
                <span :class="verdictOf(row) === 'REGRESSED' ? 'text-[color:var(--danger)]' : verdictOf(row) === 'IMPROVED' ? 'text-[color:var(--ok)]' : 'text-ink-500'">
                  {{ verdictOf(row) === 'REGRESSED' ? '回归' : verdictOf(row) === 'IMPROVED' ? '改善' : '持平' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ row.finishedTime || row.startedTime || '—' }}</template>
            </el-table-column>
          </el-table>
        </div>
      </PanelState>
    </template>
  </DebugLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import DebugLayout from './DebugLayout.vue'
import PanelState from './PanelState.vue'
import {
  createEvaluationCase, createEvaluationDataset, deleteEvaluationCase, getEvaluationGaps,
  getEvaluationRun, listEvaluationCases, listEvaluationDatasets, listEvaluationRuns, runEvaluation,
} from '../api/knowledge'
import { statusLabel } from '../types'
import type { EvaluationRun } from '../types'

const props = defineProps<{ knowledgeBaseId:number }>()

type Row = Record<string, unknown>
const datasets = ref<Row[]>([])
const caseRows = ref<Row[]>([])
const runs = ref<Row[]>([])
const current = ref<Row | null>(null)
const gaps = ref<Row | null>(null)
const datasetCode = ref('')
const baselineRunCode = ref('')
const mode = ref<'RETRIEVAL'|'FULL'>('RETRIEVAL')
const running = ref(false)
const busy = ref(false)
const error = ref('')
const showCreate = ref(false)
const newDataset = ref({ name:'', description:'' })
const newCase = ref({ question:'', expected:'', topK:5 })

const num = (v:unknown):number|null => typeof v === 'number' ? v : v == null ? null : Number(v)
const fixed = (v:unknown, digits=3):string => num(v) == null ? '—' : Number(v).toFixed(digits)
const percent = (v:unknown):string => num(v) == null ? '—' : (Number(v) * 100).toFixed(1) + '%'
const flag = (v:unknown):string => v === 1 ? '✓' : v === 0 ? '—' : '—'
/** 期望/召回码列表在库里是 JSON 字符串，展示时还原成可读列表。 */
function codes(json:unknown):string {
  if (!json) return '—'
  try {
    const parsed = typeof json === 'string' ? JSON.parse(json) : json
    return Array.isArray(parsed) ? parsed.join('、') : String(json)
  } catch { return String(json) }
}
const verdictOf = (row:Row):string => String(row.regressionVerdict || '')
const verdictLabel = computed(() =>
  verdictOf(current.value ?? {}) === 'REGRESSED' ? '回归' :
  verdictOf(current.value ?? {}) === 'IMPROVED' ? '改善' : '与基线持平')
const verdictClass = computed(() =>
  verdictOf(current.value ?? {}) === 'REGRESSED' ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]')
const results = computed<Row[]>(() => {
  const list = (current.value as Row | null)?.results
  return Array.isArray(list) ? list as Row[] : []
})
const gapCodes = computed<string[]>(() => {
  const list = (gaps.value as Row | null)?.gaps
  if (!Array.isArray(list)) return []
  return (list as Row[]).map(g => String(g.caseCode || ''))
})
const gapCount = computed(() => num((gaps.value as Row | null)?.gapCount) ?? gapCodes.value.length)

/** 基线差值从 delta JSON 取，展示为带符号的增量，正负一眼可辨。 */
const deltaOf = (key:string):string => {
  const raw = current.value?.baselineDeltaJson
  if (!raw || typeof raw !== 'string') return ''
  try {
    const map = JSON.parse(raw) as Record<string, number | null>
    const value = map[key]
    if (value == null) return ''
    const sign = value > 0 ? '+' : ''
    return key === 'latencyP50Ms' ? `${sign}${value} ms` : `${sign}${(value * 100).toFixed(1)}%`
  } catch { return '' }
}
const metricCards = computed(() => {
  const run = current.value
  if (!run) return []
  return [
    { label:'命中率@1', value:percent(run.hitAt1Rate), delta:deltaOf('hitAt1Rate') },
    { label:'MRR', value:fixed(run.mrr), delta:deltaOf('mrr') },
    { label:'召回率@5', value:percent(run.recallAt5), delta:deltaOf('recallAt5') },
    { label:'延迟 P50', value:run.latencyP50Ms == null ? '—' : `${run.latencyP50Ms} ms`, delta:deltaOf('latencyP50Ms') },
  ]
})

async function loadDatasets() {
  try {
    const res = await listEvaluationDatasets(props.knowledgeBaseId)
    datasets.value = res.data.data || []
  } catch (e) { error.value = (e as Error).message || '数据集加载失败' }
}
async function loadCases() {
  caseRows.value = []
  if (!datasetCode.value) return
  try {
    const res = await listEvaluationCases(datasetCode.value)
    caseRows.value = res.data.data || []
  } catch (e) { error.value = (e as Error).message || '用例加载失败' }
}
async function loadRuns() {
  try {
    const res = await listEvaluationRuns(props.knowledgeBaseId, null, 20)
    runs.value = res.data.data || []
  } catch (e) { error.value = (e as Error).message || '运行历史加载失败' }
}
async function selectRun(row:Row) {
  baselineRunCode.value = String(row.runCode || '')
  if (!row.runCode) return
  try {
    const [detail, gapRes] = await Promise.all([
      getEvaluationRun(props.knowledgeBaseId, String(row.runCode)),
      getEvaluationGaps(props.knowledgeBaseId, String(row.runCode)),
    ])
    current.value = (detail.data.data || null) as Row | null
    gaps.value = (gapRes.data.data || null) as Row | null
  } catch (e) { error.value = (e as Error).message || '运行详情加载失败' }
}
function useAsBaseline() {
  if (current.value?.runCode) baselineRunCode.value = String(current.value.runCode)
}
async function createDataset() {
  busy.value = true
  try {
    const res = await createEvaluationDataset(props.knowledgeBaseId, {
      name:newDataset.value.name.trim(), description:newDataset.value.description.trim(),
    })
    const code = String((res.data.data as Row | undefined)?.datasetCode || '')
    if (code) datasetCode.value = code
    newDataset.value = { name:'', description:'' }
    showCreate.value = false
    await loadDatasets()
    await loadCases()
  } catch (e) { error.value = (e as Error).message || '创建数据集失败' } finally { busy.value = false }
}
async function addCase() {
  if (!datasetCode.value) return
  busy.value = true
  try {
    await createEvaluationCase(datasetCode.value, {
      question:newCase.value.question.trim(),
      expectedObjectCodes:newCase.value.expected.split(',').map(s => s.trim()).filter(Boolean),
    })
    newCase.value = { question:'', expected:'', topK:newCase.value.topK }
    await loadCases()
  } catch (e) { error.value = (e as Error).message || '新增用例失败' } finally { busy.value = false }
}
async function removeCase(id:unknown) {
  await deleteEvaluationCase(Number(id))
  await loadCases()
}
async function runEvaluationNow() {
  running.value = true
  error.value = ''
  try {
    const res = await runEvaluation(props.knowledgeBaseId, {
      datasetCode:datasetCode.value, mode:mode.value,
      topK:newCase.value.topK || 5,
      baselineRunCode:baselineRunCode.value || undefined,
    })
    const run = res.data.data as Row | undefined
    current.value = (run || null) as Row | null
    if (run?.runCode) {
      baselineRunCode.value = String(run.runCode)
      const gapRes = await getEvaluationGaps(props.knowledgeBaseId, String(run.runCode))
      gaps.value = (gapRes.data.data || null) as Row | null
    }
    await loadRuns()
  } catch (e) { error.value = (e as Error).message || '评测运行失败' } finally { running.value = false }
}

watch(() => props.knowledgeBaseId, () => {
  datasetCode.value = ''
  current.value = null
  gaps.value = null
  loadDatasets()
  loadRuns()
})
onMounted(async () => { await loadDatasets(); await loadRuns() })

// 仅用于类型标注，避免评测类型在模板里退化成 any。
export type { EvaluationRun }
</script>
