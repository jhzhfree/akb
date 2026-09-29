<template>
  <!--
    设计 §7：任务告警 = 任务 / DLQ / 告警规则。所有异步作业在同一本账上，
    失败可重试、死信需人工决策，告警规则决定什么叫异常。
  -->
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">TASK &amp; ALERT</div>
        <h1 class="kb-title mt-1">任务与告警</h1>
        <p class="kb-muted mt-1">
          解析、抽取、索引、衍生与评测作业统一登记：失败可重试，死信需人工决策；告警规则把指标阈值变成可处理的事件。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-outline" :disabled="loading" @click="load">刷新</button>
        <button class="kb-btn-outline" :disabled="evaluating" @click="evaluateNow">
          {{ evaluating ? '评估中...' : '立即评估告警' }}
        </button>
      </div>
    </header>

    <div class="kb-stats mt-4">
      <div class="kb-stat"><span>运行中</span><b>{{ stats.running || 0 }}</b></div>
      <div class="kb-stat"><span>排队</span><b>{{ stats.queued || 0 }}</b></div>
      <div class="kb-stat"><span>成功</span><b>{{ stats.succeeded || 0 }}</b></div>
      <div class="kb-stat"><span>失败 / 死信</span><b>{{ stats.failure || 0 }}</b></div>
      <div class="kb-stat"><span>投递死信</span><b>{{ stats.deadLetter || 0 }}</b></div>
      <div class="kb-stat"><span>未处理告警</span><b>{{ alertStats.unresolved || 0 }}</b></div>
    </div>

    <div v-if="error" class="kb-alert-error mt-3">{{ error }}</div>

    <div class="mt-4 flex flex-wrap gap-2">
      <button v-for="item in tabs" :key="item.key" class="kb-chip" :class="tab===item.key?'kb-chip-active':''"
        @click="tab=item.key">
        {{ item.label }}<em v-if="item.count!==undefined"> {{ item.count }}</em>
      </button>
    </div>

    <!-- 任务：统一账本 -->
    <article v-if="tab==='tasks'" class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">任务</b>
        <div class="flex flex-wrap items-center gap-2">
          <select v-model="statusFilter" class="kb-input w-36" @change="load">
            <option value="">全部状态</option>
            <option v-for="s in statuses" :key="s" :value="s">{{ labelOf(s) }}</option>
          </select>
          <select v-model="typeFilter" class="kb-input w-40" @change="load">
            <option value="">全部类型</option>
            <option v-for="t in taskTypes" :key="t" :value="t">{{ t }}</option>
          </select>
        </div>
      </div>
      <div class="p-4">
        <div v-if="loading && !tasks.length" class="py-6 text-center text-sm text-ink-500">正在读取任务...</div>
        <div v-else-if="!tasks.length" class="py-6 text-center text-sm text-ink-500">当前没有任务记录</div>
        <table v-else class="kb-table">
          <thead>
            <tr><th>任务</th><th>类型</th><th>触发</th><th>状态</th><th>重试</th><th>时间</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.taskCode">
              <td>
                <b class="text-ink-700">{{ task.taskName || task.taskCode }}</b>
                <div class="kb-muted text-[11px]">{{ task.targetType || '-' }} {{ task.targetCode || '' }}</div>
                <div v-if="task.errorMessage" class="mt-1 text-[11px] text-[color:var(--danger)]">{{ task.errorMessage }}</div>
              </td>
              <td>{{ task.taskType }}</td>
              <td>{{ triggerLabel(task.triggerType) }}</td>
              <td><span class="kb-badge" :class="badgeClass(task.status)">{{ labelOf(task.status) }}</span></td>
              <td>{{ task.retryCount || 0 }} / {{ task.maxAttempts || 3 }}</td>
              <td class="kb-muted text-[11px]">{{ task.createdTime || '-' }}</td>
              <td class="whitespace-nowrap text-right">
                <button class="kb-link" @click="openDetail(task)">详情</button>
                <button class="kb-link ml-2" :disabled="!retryable(task)" @click="retry(task)">重试</button>
                <button class="kb-link ml-2" :disabled="!cancelable(task)" @click="cancel(task)">取消</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <!-- 告警事件 -->
    <article v-else-if="tab==='alerts'" class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">告警事件</b>
        <span class="kb-muted">条件再次命中只累计次数，不刷屏；指标恢复后自动关闭</span>
      </div>
      <div class="p-4">
        <div v-if="!alerts.length" class="py-6 text-center text-sm text-ink-500">当前没有告警事件</div>
        <table v-else class="kb-table">
          <thead>
            <tr><th>级别</th><th>告警</th><th>次数</th><th>首次 / 最近</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="alert in alerts" :key="alert.alertCode">
              <td>
                <span class="kb-badge" :class="alert.severity==='CRITICAL'?'kb-badge-rose':(alert.severity==='WARNING'?'kb-badge-amber':'kb-badge-ink')">
                  {{ alert.severity }}
                </span>
              </td>
              <td>
                <b class="text-ink-700">{{ alert.title }}</b>
                <div class="kb-muted text-[11px]">规则 {{ alert.ruleCode }} · 指标值 {{ alert.metricValue }}</div>
              </td>
              <td>{{ alert.occurCount || 1 }}</td>
              <td class="kb-muted text-[11px]">{{ alert.firstSeen || '-' }} / {{ alert.lastSeen || '-' }}</td>
              <td><span class="kb-badge" :class="badgeClass(alert.status)">{{ alertLabel(alert.status) }}</span></td>
              <td class="whitespace-nowrap text-right">
                <button class="kb-link" v-if="alert.status==='OPEN'" @click="ack(alert)">认领</button>
                <button class="kb-link ml-2" v-if="alert.status!=='RESOLVED'" @click="resolve(alert)">关闭</button>
                <button class="kb-link ml-2" v-if="alert.status==='RESOLVED'" @click="reopen(alert)">重开</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <!-- 告警规则 -->
    <article v-else class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">告警规则</b>
        <button class="kb-btn-outline" @click="openRule()">新增规则</button>
      </div>
      <div class="p-4">
        <div v-if="!rules.length" class="py-6 text-center text-sm text-ink-500">当前没有告警规则</div>
        <table v-else class="kb-table">
          <thead>
            <tr><th>规则</th><th>指标</th><th>阈值 / 窗口</th><th>级别</th><th>范围</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="rule in rules" :key="rule.ruleCode">
              <td><b class="text-ink-700">{{ rule.name }}</b><div class="kb-muted text-[11px]">{{ rule.ruleCode }}</div></td>
              <td>{{ rule.metricLabel || rule.metric }}</td>
              <td>{{ rule.threshold }} / {{ rule.windowMinutes }} 分钟</td>
              <td>{{ rule.severity }}</td>
              <td>{{ rule.knowledgeBaseId ? '知识库' : '平台级' }}</td>
              <td>
                <span class="kb-badge" :class="rule.enabled?'kb-badge-emerald':'kb-badge-ink'">
                  {{ rule.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="whitespace-nowrap text-right">
                <button class="kb-link" @click="openRule(rule)">编辑</button>
                <button class="kb-link ml-2" @click="toggleRule(rule)">{{ rule.enabled ? '停用' : '启用' }}</button>
                <button class="kb-link ml-2 text-[color:var(--danger)]" @click="removeRule(rule)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <!-- 任务详情：状态、进度与结果报文 -->
    <el-drawer v-model="detailVisible" :title="detail?.taskName || '任务详情'" size="46%">
      <div v-if="detail" class="space-y-3 text-[12px]">
        <div class="kb-card p-3">
          <div class="grid grid-cols-2 gap-2">
            <div><span class="kb-muted">任务编号</span><div>{{ detail.taskCode }}</div></div>
            <div><span class="kb-muted">状态</span><div>{{ labelOf(detail.status) }}</div></div>
            <div><span class="kb-muted">阶段</span><div>{{ detail.stage || '-' }}</div></div>
            <div><span class="kb-muted">进度</span><div>{{ detail.progress || 0 }}%</div></div>
            <div><span class="kb-muted">触发方式</span><div>{{ triggerLabel(detail.triggerType) }}</div></div>
            <div><span class="kb-muted">重试</span><div>{{ detail.retryCount || 0 }} / {{ detail.maxAttempts || 3 }}</div></div>
            <div><span class="kb-muted">开始</span><div>{{ detail.startedTime || '-' }}</div></div>
            <div><span class="kb-muted">结束</span><div>{{ detail.finishedTime || '-' }}</div></div>
          </div>
          <div v-if="detail.errorMessage" class="mt-3 rounded bg-[color:var(--danger-bg)] p-2 text-[color:var(--danger)]">{{ detail.errorMessage }}</div>
        </div>
        <div>
          <b>结果报文</b>
          <pre class="kb-pre mt-1">{{ pretty(detail.resultJson || detail.payloadJson) }}</pre>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="ruleVisible" :title="ruleForm.ruleCode ? '编辑告警规则' : '新增告警规则'" width="460px">
      <label class="block"><span class="mb-1 block text-[11px] text-ink-500">名称</span>
        <input v-model="ruleForm.name" class="kb-input w-full" /></label>
      <label class="block mt-3"><span class="mb-1 block text-[11px] text-ink-500">指标</span>
        <select v-model="ruleForm.metric" class="kb-input w-full">
          <option v-for="m in dictionaries?.alert?.metrics || []" :key="m" :value="m">
            {{ dictionaries?.alert?.metricLabels?.[m] || m }}
          </option>
        </select></label>
      <div class="mt-3 grid grid-cols-2 gap-3">
        <label class="block"><span class="mb-1 block text-[11px] text-ink-500">阈值</span>
          <input v-model.number="ruleForm.threshold" type="number" step="0.1" class="kb-input w-full" /></label>
        <label class="block"><span class="mb-1 block text-[11px] text-ink-500">窗口（分钟）</span>
          <input v-model.number="ruleForm.windowMinutes" type="number" class="kb-input w-full" /></label>
      </div>
      <label class="block mt-3"><span class="mb-1 block text-[11px] text-ink-500">级别</span>
        <select v-model="ruleForm.severity" class="kb-input w-full">
          <option v-for="s in dictionaries?.alert?.severities || []" :key="s" :value="s">{{ s }}</option>
        </select></label>
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
  ackAlert, cancelTask, createAlertRule, deleteAlertRule, evaluateAlerts, getTaskDetail,
  getTaskDictionaries, getTaskOverview, reopenAlert, resolveAlert, retryTask,
  unwrap, updateAlertRule,
} from '../api/knowledge'
import type { AlertEvent, AlertRule, TaskDictionaries, TaskRun } from '../types'
import { errorMessage } from '../utils/feedback'

const props = defineProps<{ knowledgeBaseId:number }>()

const STATUS_LABEL:Record<string,string> = {
  QUEUED:'排队中', RUNNING:'运行中', SUCCEEDED:'成功', FAILED:'失败',
  CANCELED:'已取消', DEAD:'死信', OPEN:'未处理', ACKED:'已认领', RESOLVED:'已关闭',
}
const TRIGGER_LABEL:Record<string,string> = { MANUAL:'手动', SCHEDULED:'计划', EVENT:'事件', RETRY:'重试' }

const tab = ref<'tasks'|'alerts'|'rules'>('tasks')
const loading = ref(false)
const evaluating = ref(false)
const saving = ref(false)
const error = ref('')
const tasks = ref<TaskRun[]>([])
const alerts = ref<AlertEvent[]>([])
const rules = ref<AlertRule[]>([])
const stats = ref<Record<string,number>>({})
const alertStats = ref<Record<string,number>>({})
const taskTypes = ref<string[]>([])
const dictionaries = ref<TaskDictionaries|null>(null)
const statusFilter = ref('')
const typeFilter = ref('')
const statuses = ['QUEUED','RUNNING','SUCCEEDED','FAILED','DEAD','CANCELED']

const detailVisible = ref(false)
const detail = ref<TaskRun|null>(null)
const ruleVisible = ref(false)
const ruleForm = ref<Record<string, unknown>>({ ruleCode:'', name:'', metric:'TASK_FAILURE_COUNT', threshold:1, windowMinutes:60, severity:'WARNING', enabled:true })

const tabs = computed(() => [
  { key:'tasks' as const, label:'任务', count:stats.value.total },
  { key:'alerts' as const, label:'告警事件', count:alertStats.value.unresolved },
  { key:'rules' as const, label:'告警规则', count:rules.value.length },
])

function labelOf(status?:string) { return STATUS_LABEL[status || ''] || status || '-' }
function alertLabel(status?:string) { return STATUS_LABEL[status || ''] || status || '-' }
function triggerLabel(type?:string) { return TRIGGER_LABEL[type || ''] || type || '-' }
function badgeClass(status?:string) {
  if (status === 'SUCCEEDED' || status === 'RESOLVED') return 'kb-badge-emerald'
  if (status === 'FAILED' || status === 'DEAD' || status === 'OPEN') return 'kb-badge-rose'
  if (status === 'RUNNING' || status === 'ACKED') return 'kb-badge-amber'
  return 'kb-badge-ink'
}
function retryable(task:TaskRun) { return ['FAILED','DEAD','CANCELED'].includes(String(task.status)) }
function cancelable(task:TaskRun) { return ['QUEUED','RUNNING'].includes(String(task.status)) }
function pretty(raw?:string|null) {
  if (!raw) return '—'
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch { return raw }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(await getTaskOverview({
      kb:props.knowledgeBaseId, status:statusFilter.value || undefined,
      taskType:typeFilter.value || undefined, limit:100,
    }), null)
    if (data) {
      tasks.value = data.tasks || []
      stats.value = (data.stats || {}) as Record<string,number>
      alerts.value = data.alerts || []
      alertStats.value = (data.alertStats || {}) as Record<string,number>
      rules.value = data.rules || []
      taskTypes.value = data.taskTypes || []
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
  if (!dictionaries.value) {
    try { dictionaries.value = unwrap(await getTaskDictionaries(), null) } catch { /* 字典失败不阻断面板 */ }
  }
}

async function evaluateNow() {
  evaluating.value = true
  try {
    unwrap(await evaluateAlerts(props.knowledgeBaseId), null)
    await load()
    ElMessage.success('告警规则已评估')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    evaluating.value = false
  }
}

async function openDetail(task:TaskRun) {
  try {
    detail.value = (unwrap(await getTaskDetail(task.taskCode), null) as unknown as TaskRun) || task
    detailVisible.value = true
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function retry(task:TaskRun) {
  try {
    unwrap(await retryTask(task.taskCode, props.knowledgeBaseId), null)
    ElMessage.success('任务已重新入队')
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function cancel(task:TaskRun) {
  try {
    unwrap(await cancelTask(task.taskCode), null)
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function ack(alert:AlertEvent) {
  try { unwrap(await ackAlert(alert.alertCode), null); await load() }
  catch (e) { error.value = errorMessage(e) }
}
async function resolve(alert:AlertEvent) {
  try { unwrap(await resolveAlert(alert.alertCode, '人工关闭'), null); await load() }
  catch (e) { error.value = errorMessage(e) }
}
async function reopen(alert:AlertEvent) {
  try { unwrap(await reopenAlert(alert.alertCode), null); await load() }
  catch (e) { error.value = errorMessage(e) }
}

function openRule(rule?:AlertRule) {
  ruleForm.value = rule ? { ...rule } : { ruleCode:'', name:'', metric:'TASK_FAILURE_COUNT', threshold:1, windowMinutes:60, severity:'WARNING', enabled:true }
  ruleVisible.value = true
}

async function saveRule() {
  if (!ruleForm.value.name) { ElMessage.warning('请填写规则名称'); return }
  saving.value = true
  try {
    const payload = { ...ruleForm.value }
    delete payload.ruleCode
    delete payload.metricLabel
    if (ruleForm.value.ruleCode) {
      unwrap(await updateAlertRule(String(ruleForm.value.ruleCode), payload), null)
    } else {
      unwrap(await createAlertRule(props.knowledgeBaseId, payload), null)
    }
    ruleVisible.value = false
    await load()
    ElMessage.success('告警规则已保存')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function toggleRule(rule:AlertRule) {
  try {
    unwrap(await updateAlertRule(rule.ruleCode, { enabled:!rule.enabled }), null)
    await load()
  } catch (e) { error.value = errorMessage(e) }
}

async function removeRule(rule:AlertRule) {
  try {
    await ElMessageBox.confirm(`删除规则「${rule.name}」后不再产生该告警。`, '删除告警规则', { type:'warning' })
  } catch { return }
  try {
    unwrap(await deleteAlertRule(rule.ruleCode), null)
    await load()
  } catch (e) { error.value = errorMessage(e) }
}

onMounted(load)
</script>
