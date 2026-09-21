<template>
  <!--
    设计 §4.5 / PRD §5.5：衍生规则 → 候选衍生知识 → 运行记录。
    衍生知识必须能区分于原文知识：保存来源断言、方法、规则与算法版本、证据链、置信度与状态；
    基础知识变更后标记 STALE，而不是悄悄变错。
  -->
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识衍生</div>
        <h1 class="kb-title mt-1">知识衍生</h1>
        <p class="kb-muted mt-1">
          图谱路径、模板或模型推导出的知识以「候选」落地，保留来源断言与推导路径，人工确认后才成为知识；规则可手动执行或按计划执行。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-outline" :disabled="loading" @click="load">刷新</button>
        <button class="kb-btn" @click="openRule()">新增规则</button>
      </div>
    </header>

    <div class="kb-stats mt-4">
      <div class="kb-stat"><span>规则</span><b>{{ rules.length }}</b></div>
      <div class="kb-stat"><span>计划执行</span><b>{{ rules.filter(r => r.scheduleType==='SCHEDULED').length }}</b></div>
      <div class="kb-stat"><span>候选</span><b>{{ stats.total || 0 }}</b></div>
      <div class="kb-stat"><span>失效</span><b>{{ stats.stale || 0 }}</b></div>
      <div class="kb-stat"><span>已批准</span><b>{{ stats.approved || 0 }}</b></div>
    </div>

    <div v-if="error" class="kb-alert-error mt-3">{{ error }}</div>

    <div class="mt-4 flex flex-wrap gap-2">
      <button v-for="item in tabs" :key="item.key" class="kb-chip" :class="tab===item.key?'kb-chip-active':''"
        @click="tab=item.key">{{ item.label }}</button>
    </div>

    <!-- 衍生规则 -->
    <article v-if="tab==='rules'" class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">衍生规则</b>
        <span class="kb-muted">手动执行一次，或按 cron 计划执行；两者是同一条执行链路</span>
      </div>
      <div class="p-4">
        <div v-if="loading && !rules.length" class="py-6 text-center text-sm text-ink-400">正在读取规则...</div>
        <div v-else-if="!rules.length" class="py-6 text-center text-sm text-ink-400">
          还没有衍生规则：新增一条，选择图谱路径、模板或模型推导
        </div>
        <table v-else class="kb-table">
          <thead>
            <tr><th>规则</th><th>推导方式</th><th>计划</th><th>下次执行</th><th>候选 / 失效</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="rule in rules" :key="rule.ruleCode">
              <td>
                <b class="text-ink-700">{{ rule.name }}</b>
                <div class="kb-muted text-[11px]">{{ rule.ruleCode }} · {{ rule.algorithmVersion }}</div>
                <div v-if="rule.lastError" class="mt-1 text-[11px] text-rose-600">{{ rule.lastError }}</div>
              </td>
              <td>{{ typeLabel(rule.derivationType) }}</td>
              <td>{{ rule.scheduleText }}<span v-if="rule.cronExpr" class="kb-muted"> · {{ rule.cronExpr }}</span></td>
              <td class="kb-muted text-[11px]">{{ rule.nextFireAt || '—' }}</td>
              <td>{{ rule.candidateCount || 0 }} / {{ rule.staleCount || 0 }}</td>
              <td>
                <span class="kb-badge" :class="rule.enabled?'kb-badge-emerald':'kb-badge-ink'">
                  {{ rule.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="whitespace-nowrap text-right">
                <button class="kb-link" :disabled="running===rule.ruleCode" @click="run(rule)">
                  {{ running===rule.ruleCode ? '执行中...' : '手动执行' }}
                </button>
                <button class="kb-link ml-2" @click="openRule(rule)">编辑</button>
                <button class="kb-link ml-2" @click="toggleRule(rule)">{{ rule.enabled ? '停用' : '启用' }}</button>
                <button class="kb-link ml-2 text-rose-600" @click="removeRule(rule)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <!-- 候选衍生知识 -->
    <article v-else-if="tab==='candidates'" class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">候选衍生知识</b>
        <select v-model="statusFilter" class="kb-input w-40" @change="load">
          <option value="">全部状态</option>
          <option v-for="s in candidateStatuses" :key="s" :value="s">{{ statusLabel(s) }}</option>
        </select>
      </div>
      <div class="p-4">
        <div v-if="!candidates.length" class="py-6 text-center text-sm text-ink-400">当前没有候选衍生知识</div>
        <table v-else class="kb-table">
          <thead>
            <tr><th>候选知识</th><th>来源断言</th><th>方法</th><th>置信度</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="candidate in candidates" :key="candidate.candidateCode">
              <td>
                <button class="text-left text-ink-700 hover:text-indigo-600" @click="openCandidate(candidate)">
                  {{ candidate.title }}
                </button>
                <div class="kb-muted text-[11px]">{{ candidate.candidateCode }} · {{ candidate.ruleCode }}</div>
                <div v-if="candidate.staleReason" class="mt-1 text-[11px] text-amber-600">{{ candidate.staleReason }}</div>
              </td>
              <td>{{ candidate.baseCount ?? 0 }}</td>
              <td class="kb-muted text-[11px]">{{ candidate.derivationType }} · {{ candidate.algorithmVersion }}</td>
              <td>{{ ((candidate.confidence ?? 0) * 100).toFixed(1) }}%</td>
              <td><span class="kb-badge" :class="candidateBadge(candidate.status)">{{ statusLabel(candidate.status) }}</span></td>
              <td class="whitespace-nowrap text-right">
                <button class="kb-link" v-if="candidate.status!=='APPROVED'" @click="review(candidate,'APPROVED')">批准</button>
                <button class="kb-link ml-2" v-if="candidate.status!=='REJECTED'" @click="review(candidate,'REJECTED')">驳回</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <!-- 运行记录 -->
    <article v-else class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">运行记录</b>
        <span class="kb-muted">手动与计划的执行历史，含扫描量与产出量</span>
      </div>
      <div class="p-4">
        <div v-if="!runs.length" class="py-6 text-center text-sm text-ink-400">还没有执行记录</div>
        <table v-else class="kb-table">
          <thead>
            <tr><th>运行编号</th><th>规则</th><th>触发</th><th>扫描 / 产出</th><th>状态</th><th>开始</th></tr>
          </thead>
          <tbody>
            <tr v-for="run in runs" :key="run.runCode">
              <td class="kb-muted text-[11px]">{{ run.runCode }}</td>
              <td>{{ run.ruleCode }}</td>
              <td>{{ run.triggerType==='SCHEDULED' ? '计划' : '手动' }}</td>
              <td>{{ run.scannedCount || 0 }} / {{ run.candidateCount || 0 }}</td>
              <td>
                <span class="kb-badge" :class="run.status==='SUCCEEDED'?'kb-badge-emerald':'kb-badge-rose'">
                  {{ run.status }}
                </span>
                <div v-if="run.errorMessage" class="mt-1 text-[11px] text-rose-600">{{ run.errorMessage }}</div>
              </td>
              <td class="kb-muted text-[11px]">{{ run.startedTime || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <!-- 候选详情：来源、路径与置信度 -->
    <el-drawer v-model="candidateVisible" :title="candidate?.title || '候选详情'" size="52%">
      <div v-if="candidate" class="space-y-3 text-[12px]">
        <div class="kb-card p-3">
          <div class="grid grid-cols-2 gap-2">
            <div><span class="kb-muted">状态</span><div>{{ statusLabel(candidate.status) }}</div></div>
            <div><span class="kb-muted">置信度</span><div>{{ ((candidate.confidence ?? 0) * 100).toFixed(1) }}%</div></div>
            <div><span class="kb-muted">推导方式</span><div>{{ candidate.derivationType }}</div></div>
            <div><span class="kb-muted">算法版本</span><div>{{ candidate.algorithmVersion }}</div></div>
          </div>
        </div>
        <div><b>内容</b><p class="mt-1 leading-6 text-ink-700">{{ candidate.content || '—' }}</p></div>
        <div v-if="candidate.baseAssertionCodes?.length">
          <b>来源断言</b>
          <div class="mt-1 flex flex-wrap gap-1">
            <span v-for="code in candidate.baseAssertionCodes" :key="code" class="kb-tag">{{ code }}</span>
          </div>
        </div>
        <div v-if="candidate.graphPath">
          <b>图谱路径</b>
          <pre class="kb-pre mt-1">{{ JSON.stringify(candidate.graphPath, null, 2) }}</pre>
        </div>
        <div v-if="candidate.derivedFromJson">
          <b>来自（derivedFrom）</b>
          <pre class="kb-pre mt-1">{{ pretty(candidate.derivedFromJson) }}</pre>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="ruleVisible" :title="ruleForm.ruleCode ? '编辑衍生规则' : '新增衍生规则'" width="560px">
      <label class="block"><span class="mb-1 block text-[11px] text-ink-500">规则名称</span>
        <input v-model="ruleForm.name" class="kb-input w-full" placeholder="服务部署位置推导" /></label>
      <div class="mt-3 grid grid-cols-2 gap-3">
        <label class="block"><span class="mb-1 block text-[11px] text-ink-500">推导方式</span>
          <select v-model="ruleForm.derivationType" class="kb-input w-full">
            <option v-for="t in meta?.derivationTypes || []" :key="t" :value="t">{{ typeLabel(t) }}</option>
          </select></label>
        <label class="block"><span class="mb-1 block text-[11px] text-ink-500">触发方式</span>
          <select v-model="ruleForm.scheduleType" class="kb-input w-full">
            <option value="MANUAL">手动衍生</option>
            <option value="SCHEDULED">计划衍生</option>
          </select></label>
      </div>
      <label v-if="ruleForm.scheduleType==='SCHEDULED'" class="block mt-3">
        <span class="mb-1 block text-[11px] text-ink-500">计划表达式（{{ meta?.cronHint }}）</span>
        <input v-model="ruleForm.cronExpr" class="kb-input w-full" placeholder="0 3 * * 1" /></label>
      <label class="block mt-3"><span class="mb-1 block text-[11px] text-ink-500">规则定义（JSON）</span>
        <textarea v-model="definitionText" rows="7" class="kb-input w-full font-mono text-[11px]" /></label>
      <p class="mt-1 kb-muted text-[11px]">留空则使用该推导方式的默认定义。</p>
      <label class="mt-3 flex items-center gap-2 text-[12px]">
        <input v-model="ruleForm.enabled" type="checkbox" /> 启用该规则
      </label>
      <template #footer>
        <button class="kb-btn-outline" @click="ruleVisible=false">取消</button>
        <button class="kb-btn ml-2" :disabled="saving" @click="saveRule">保存</button>
      </template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createDerivationRule, deleteDerivationRule, getDerivedCandidate, getDerivationOverview,
  planDerivationRule, reviewDerivedCandidate, runDerivationRule, unwrap, updateDerivationRule,
} from '../api/knowledge'
import type { DerivedCandidate, DerivedCandidateDetail, DerivationRule, DerivationRun } from '../types'
import { errorMessage } from '../utils/feedback'

const props = defineProps<{ knowledgeBaseId:number }>()

const TYPE_LABEL:Record<string,string> = { GRAPH_PATH:'图谱路径', TEMPLATE:'模板', AI:'模型推导' }
const STATUS_LABEL:Record<string,string> = {
  DRAFT:'草稿', PENDING:'待审', APPROVED:'已批准', REJECTED:'已驳回', STALE:'已失效',
}

const tab = ref<'rules'|'candidates'|'runs'>('rules')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const rules = ref<DerivationRule[]>([])
const candidates = ref<DerivedCandidate[]>([])
const runs = ref<DerivationRun[]>([])
const stats = ref<Record<string, number>>({})
const meta = ref<{ derivationTypes:string[]; cronHint:string; definitionDefaults:Record<string, Record<string, unknown>> } | null>(null)
const running = ref('')
const statusFilter = ref('')
const candidateStatuses = ['DRAFT','PENDING','APPROVED','REJECTED','STALE']

const ruleVisible = ref(false)
const ruleForm = ref<Record<string, unknown>>({ ruleCode:'', name:'', derivationType:'GRAPH_PATH', scheduleType:'MANUAL', cronExpr:'', enabled:true })
const definitionText = ref('')

const candidateVisible = ref(false)
const candidate = ref<DerivedCandidateDetail|null>(null)

const tabs = computed(() => [
  { key:'rules' as const, label:'衍生规则' },
  { key:'candidates' as const, label:'候选衍生知识' },
  { key:'runs' as const, label:'运行记录' },
])

function typeLabel(type?:string) { return TYPE_LABEL[type || ''] || type || '-' }
function statusLabel(status?:string) { return STATUS_LABEL[status || ''] || status || '-' }
function candidateBadge(status?:string) {
  if (status === 'APPROVED') return 'kb-badge-emerald'
  if (status === 'STALE') return 'kb-badge-amber'
  if (status === 'REJECTED') return 'kb-badge-rose'
  return 'kb-badge-ink'
}
function pretty(raw?:string|null) {
  if (!raw) return '—'
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch { return raw }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(await getDerivationOverview({
      kb:props.knowledgeBaseId, status:statusFilter.value || undefined, limit:100,
    }), null)
    if (data) {
      rules.value = data.rules || []
      candidates.value = data.candidates || []
      runs.value = data.runs || []
      stats.value = data.stats || {}
      meta.value = data.schema || null
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function run(rule:DerivationRule) {
  running.value = rule.ruleCode
  try {
    const result = unwrap(await runDerivationRule(rule.ruleCode, props.knowledgeBaseId), null)
    ElMessage.success(result?.candidates ? `执行完成，产出 ${result.candidates} 条候选` : '执行完成')
    tab.value = 'candidates'
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    running.value = ''
  }
}

function openRule(rule?:DerivationRule) {
  if (rule) {
    ruleForm.value = {
      ruleCode:rule.ruleCode, name:rule.name, derivationType:rule.derivationType,
      scheduleType:rule.scheduleType, cronExpr:rule.cronExpr || '', enabled:rule.enabled !== false,
    }
    definitionText.value = ''
  } else {
    ruleForm.value = { ruleCode:'', name:'', derivationType:'GRAPH_PATH', scheduleType:'MANUAL', cronExpr:'', enabled:true }
    definitionText.value = JSON.stringify(meta.value?.definitionDefaults?.GRAPH_PATH || {}, null, 2)
  }
  ruleVisible.value = true
}

async function saveRule() {
  if (!ruleForm.value.name) { ElMessage.warning('请填写规则名称'); return }
  let definition:Record<string, unknown>|undefined
  if (definitionText.value.trim()) {
    try { definition = JSON.parse(definitionText.value) }
    catch { ElMessage.warning('规则定义不是合法 JSON'); return }
  }
  saving.value = true
  try {
    const payload:Record<string, unknown> = {
      name:ruleForm.value.name, derivationType:ruleForm.value.derivationType,
      scheduleType:ruleForm.value.scheduleType, enabled:ruleForm.value.enabled,
    }
    if (definition) payload.definition = definition
    if (ruleForm.value.ruleCode) {
      if (ruleForm.value.scheduleType === 'SCHEDULED') {
        // 计划衍生：cron 走独立端点，保证表达式在保存时就被校验
        unwrap(await planDerivationRule(String(ruleForm.value.ruleCode), String(ruleForm.value.cronExpr || '')), null)
      }
      payload.cronExpr = ruleForm.value.cronExpr || null
      unwrap(await updateDerivationRule(String(ruleForm.value.ruleCode), payload), null)
    } else {
      if (ruleForm.value.scheduleType === 'SCHEDULED') payload.cronExpr = ruleForm.value.cronExpr
      unwrap(await createDerivationRule(props.knowledgeBaseId, payload), null)
    }
    ruleVisible.value = false
    await load()
    ElMessage.success('衍生规则已保存')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function toggleRule(rule:DerivationRule) {
  try {
    unwrap(await updateDerivationRule(rule.ruleCode, { enabled:!rule.enabled }), null)
    await load()
  } catch (e) { error.value = errorMessage(e) }
}

async function removeRule(rule:DerivationRule) {
  try {
    await ElMessageBox.confirm(`删除规则「${rule.name}」不会影响已产出的候选。`, '删除衍生规则', { type:'warning' })
  } catch { return }
  try {
    unwrap(await deleteDerivationRule(rule.ruleCode), null)
    await load()
  } catch (e) { error.value = errorMessage(e) }
}

async function openCandidate(row:DerivedCandidate) {
  try {
    candidate.value = unwrap(await getDerivedCandidate(row.candidateCode), null)
    candidateVisible.value = true
  } catch (e) { error.value = errorMessage(e) }
}

async function review(row:DerivedCandidate, decision:'APPROVED'|'REJECTED') {
  try {
    unwrap(await reviewDerivedCandidate(row.candidateCode, decision, decision === 'APPROVED' ? '人工批准' : '人工驳回'), null)
    await load()
    ElMessage.success(decision === 'APPROVED' ? '候选已批准' : '候选已驳回')
  } catch (e) { error.value = errorMessage(e) }
}

onMounted(load)
</script>
