<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">KNOWLEDGE LIFECYCLE</div>
        <h1 class="kb-title mt-1">知识生命周期治理</h1>
        <p class="kb-muted mt-1">
          知识会随时间衰减：到期失效、版本替代与来源撤销都必须可解释、可留痕。影响分析只计算不删除，是否退役由人决定。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <select v-model.number="withinDays" class="kb-input w-28">
          <option :value="7">7 天内</option>
          <option :value="30">30 天内</option>
          <option :value="90">90 天内</option>
        </select>
        <button class="kb-btn-outline" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="生效中" :value="summary?.effectiveTotal ?? 0" :hint="`断言 ${summary?.claimEffective ?? 0} 条`" />
      <MetricCard label="已过期" :value="summary?.claimExpired ?? 0" hint="到期未复核，已退出检索" />
      <MetricCard label="已替代" :value="supersededTotal" hint="版本演进，非质量事故" />
      <MetricCard
        :label="`即将过期（${withinDays} 天内）`"
        :value="summary?.expiringSoonCount ?? 0"
        :hint="`待执行到期扫描 ${summary?.dueNowCount ?? 0} 条`" />
    </div>

    <div
      v-if="summaryError"
      class="mt-3 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-700">
      <span>⚠</span>
      <span class="flex-1">生命周期指标加载失败：{{ summaryError }}（下方治理操作仍可使用）</span>
      <button class="shrink-0 underline" @click="load">重试</button>
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">治理操作</b><span class="kb-muted">Lifecycle Actions</span></div>
        <div class="space-y-3 p-4">
          <div class="rounded-lg bg-ink-50 p-3">
            <b class="text-xs">① 到期失效扫描</b>
            <p class="kb-muted mt-1">
              扫描 <code>valid_to &lt; now</code> 且仍为生效状态的断言，置为 EXPIRED 并写入版本快照；重复执行幂等，不会重复快照。
            </p>
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <el-tag :type="(summary?.dueNowCount ?? 0) > 0 ? 'warning' : 'success'" size="small" effect="plain">
                预计影响 {{ summary?.dueNowCount ?? 0 }} 条
              </el-tag>
              <button class="kb-btn-outline" :disabled="busy" @click="previewExpire">仅预览</button>
              <button class="kb-btn-primary" :disabled="busy || !(summary?.dueNowCount ?? 0)" @click="runExpire">执行扫描</button>
            </div>
            <div v-if="expireResult" class="mt-2 rounded-md border border-ink-200 bg-white p-2 text-[11px] text-ink-600">
              <b>{{ expireResult.dryRun ? '预览结果' : '执行结果' }}：</b>
              {{ expireResult.dryRun
                ? `本次将失效 ${expireResult.candidateClaimCodes?.length ?? 0} 条断言（未改动数据）`
                : `已失效 ${expireResult.expired ?? 0} 条断言` }}
              <div v-if="expireCodes.length" class="mt-1 flex flex-wrap gap-1">
                <span v-for="code in expireCodes" :key="code" class="kb-badge bg-ink-100 text-ink-600">{{ code }}</span>
              </div>
            </div>
          </div>

          <div class="rounded-lg bg-ink-50 p-3">
            <b class="text-xs">② 版本替代</b>
            <p class="kb-muted mt-1">用新版本替代旧对象：旧对象置 SUPERSEDED 并保留历史，不物理删除。</p>
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <select v-model="supersedeType" class="kb-input w-28">
                <option value="CARD">卡片</option>
                <option value="CLAIM">断言</option>
              </select>
              <input v-model="oldCode" class="kb-input w-40" placeholder="旧对象编码" />
              <span class="text-ink-400">→</span>
              <input v-model="newCode" class="kb-input w-40" placeholder="新对象编码" />
              <button class="kb-btn-primary" :disabled="busy || !oldCode.trim() || !newCode.trim()" @click="supersede">执行替代</button>
            </div>
          </div>

          <div class="rounded-lg bg-ink-50 p-3">
            <b class="text-xs">③ 来源撤销影响分析</b>
            <p class="kb-muted mt-1">撤回来源前先看清会影响哪些知识对象；系统只给出影响面，不执行级联删除。</p>
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <select v-model.number="impactSourceId" class="kb-input w-64">
                <option :value="0">选择来源…</option>
                <option v-for="source in sources" :key="source.id" :value="source.id">
                  {{ source.name }}（{{ source.sourceCode }}）
                </option>
              </select>
              <button class="kb-btn-primary" :disabled="busy || !impactSourceId" @click="analyseImpact">分析影响面</button>
            </div>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">影响面分析结果</b>
          <span v-if="impact" class="kb-muted">来源 #{{ impact.sourceId }}</span>
        </div>
        <div class="p-4">
          <PanelState
            :loading="impactLoading"
            :empty="!impact"
            empty-text="选择来源后查看影响面"
            empty-hint="影响面按 CARD / CLAIM / ENTITY 分类给出受影响对象编码"
            retryable
            @retry="analyseImpact">
            <div v-if="impact">
              <div class="text-sm">
                共影响 <b>{{ impact.affectedCount ?? 0 }}</b> 个知识对象
              </div>
              <div v-if="impact.degraded" class="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[11px] text-amber-700">
                未查询到关联对象：该来源可能尚未完成抽取，或证据链未建立。
              </div>
              <div class="mt-3 space-y-3">
                <div v-for="(codes, type) in impact.affectedByType || {}" :key="type">
                  <div class="flex items-center gap-2">
                    <el-tag size="small" effect="plain">{{ objectTypeLabel(type) }}</el-tag>
                    <span class="kb-muted">{{ codes.length }} 个</span>
                  </div>
                  <div class="mt-1 flex flex-wrap gap-1">
                    <span v-for="code in codes.slice(0, 30)" :key="code" class="kb-badge bg-ink-100 text-ink-600">{{ code }}</span>
                    <span v-if="codes.length > 30" class="kb-muted">等 {{ codes.length }} 个</span>
                  </div>
                </div>
              </div>
              <p class="kb-muted mt-3">
                是否退役由知识管理员决定：分析结果可导出或加入复核队列，系统不会自动级联删除。
              </p>
            </div>
          </PanelState>
        </div>
      </article>
    </div>

    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">即将过期</b>
        <span class="kb-muted">共 {{ expiring.length }} 条</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="tableError"
          :empty="!expiring.length"
          :empty-text="`${withinDays} 天内没有断言到期`"
          empty-hint="可在上方调整时间窗口"
          retryable
          @retry="load">
          <el-table :data="expiring" border size="small">
            <el-table-column prop="claimCode" label="断言编码" width="130" />
            <el-table-column label="断言内容" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="text-[12px]">
                  {{ row.subject }} <span class="text-ink-400">{{ row.predicate }}</span> {{ row.objectValue }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="有效期至" width="120">
              <template #default="{ row }">{{ (row.validTo || '').slice(0, 10) || '—' }}</template>
            </el-table-column>
            <el-table-column label="剩余" width="100">
              <template #default="{ row }">
                <el-tag :type="remainingType(row.validTo)" size="small" effect="plain">{{ remainingDays(row.validTo) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="70">
              <template #default="{ row }">{{ row.version ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }">{{ statusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain @click="$emit('open-detail','CLAIM',row.claimCode)">查看</el-button>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ExpireResult, ExpiringClaim, KnowledgeSource, LifecycleSummary, SourceImpact } from '../types'
import { objectTypeLabel, statusLabel } from '../types'
import {
  expireDueKnowledge, getExpiringClaims, getLifecycleSummary, getSourceImpact, getSources,
  supersedeKnowledge, unwrap,
} from '../api/knowledge'
import MetricCard from './MetricCard.vue'
import PanelState from './PanelState.vue'

const props = defineProps<{ knowledgeBaseId:number }>()
const emit = defineEmits<{ refresh:[]; 'open-detail':[mode:'CARD'|'CLAIM',code:string] }>()

const withinDays = ref(30)
const summary = ref<LifecycleSummary|null>(null)
const expiring = ref<ExpiringClaim[]>([])
const sources = ref<KnowledgeSource[]>([])
const loading = ref(false)
const summaryError = ref('')
const tableError = ref('')
const busy = ref(false)

const expireResult = ref<ExpireResult|null>(null)
const supersedeType = ref<'CARD'|'CLAIM'>('CARD')
const oldCode = ref('')
const newCode = ref('')
const impactSourceId = ref(0)
const impact = ref<SourceImpact|null>(null)
const impactLoading = ref(false)

const supersededTotal = computed(() =>
  (summary.value?.claimSuperseded ?? 0) + (summary.value?.cardSuperseded ?? 0))
const expireCodes = computed(() =>
  expireResult.value?.dryRun
    ? (expireResult.value?.candidateClaimCodes || [])
    : (expireResult.value?.expiredClaimCodes || []))

async function load() {
  loading.value = true
  summaryError.value = ''
  tableError.value = ''
  try {
    summary.value = unwrap(await getLifecycleSummary(props.knowledgeBaseId, withinDays.value), null)
  } catch (e) {
    summaryError.value = message(e)
  }
  try {
    expiring.value = unwrap(await getExpiringClaims(props.knowledgeBaseId, withinDays.value), [])
  } catch (e) {
    tableError.value = message(e)
  }
  try {
    sources.value = unwrap(await getSources(props.knowledgeBaseId), [])
  } catch {
    sources.value = []
  }
  loading.value = false
}

/** 预览是执行前的必要一步：失效对运营方不可逆。 */
async function previewExpire() {
  busy.value = true
  try {
    expireResult.value = unwrap(await expireDueKnowledge(props.knowledgeBaseId, true), null)
    ElMessage.info(`预览完成：${expireResult.value?.candidateClaimCodes?.length ?? 0} 条断言将失效`)
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function runExpire() {
  const count = summary.value?.dueNowCount ?? 0
  try {
    await ElMessageBox.confirm(
      `确认将 ${count} 条已过期的断言置为 EXPIRED？操作会写入版本快照与索引事件，但可通过版本历史追溯。`,
      '执行到期失效扫描',
      { type:'warning', confirmButtonText:'确认执行', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    expireResult.value = unwrap(await expireDueKnowledge(props.knowledgeBaseId, false), null)
    ElMessage.success(`已失效 ${expireResult.value?.expired ?? 0} 条断言`)
    await load()
    emit('refresh')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function supersede() {
  busy.value = true
  try {
    unwrap(await supersedeKnowledge(
      props.knowledgeBaseId, supersedeType.value, oldCode.value.trim(), newCode.value.trim()), {})
    ElMessage.success(`${oldCode.value.trim()} 已标记为被 ${newCode.value.trim()} 替代`)
    oldCode.value = ''
    newCode.value = ''
    await load()
    emit('refresh')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    busy.value = false
  }
}

async function analyseImpact() {
  if (!impactSourceId.value) return
  impactLoading.value = true
  try {
    impact.value = unwrap(await getSourceImpact(impactSourceId.value), null)
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    impactLoading.value = false
  }
}

function remainingDays(validTo?:string) {
  if (!validTo) return '—'
  const time = new Date(String(validTo).replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) return '—'
  const days = Math.ceil((time - Date.now()) / 86400000)
  return days < 0 ? '已过期' : `${days} 天`
}
function remainingType(validTo?:string) {
  if (!validTo) return 'info' as const
  const time = new Date(String(validTo).replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) return 'info' as const
  const days = (time - Date.now()) / 86400000
  if (days < 0) return 'danger' as const
  if (days <= 7) return 'warning' as const
  return 'success' as const
}
function message(e:unknown) {
  const x = e as { response?:{ data?:{ message?:string } }; message?:string }
  return x.response?.data?.message || x.message || '操作失败'
}

watch([() => props.knowledgeBaseId, withinDays], () => { void load() })
onMounted(load)
</script>
