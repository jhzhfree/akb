<template>
  <section class="kb-page max-w-[1180px]">
    <header class="kb-section-head">
      <div><div class="kb-eyebrow">知识检索</div><h2 class="kb-title mt-1">回答反馈</h2>
        <p class="kb-muted mt-1">收集真实问答中的问题与修正建议，经人工确认后再进入知识治理流程。</p></div>
      <button class="kb-btn kb-btn-outline" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </header>

    <div class="mb-4 grid grid-cols-2 gap-2 md:grid-cols-4 xl:grid-cols-8">
      <div v-for="item in summaryCards" :key="item.key" class="kb-card p-3">
        <div class="text-[11px] text-ink-500">{{ item.label }}</div>
        <div class="mt-1 text-xl font-semibold tabular-nums text-ink-900">{{ summary[item.key] ?? 0 }}</div>
      </div>
    </div>

    <article class="kb-card overflow-hidden">
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-ink-100 px-4 py-3">
        <div><b class="text-sm">反馈队列</b><span class="ml-2 text-xs text-ink-500">{{ rows.length }} 条</span></div>
        <select v-model="statusFilter" class="rounded border border-ink-200 bg-white px-2 py-1.5 text-xs">
          <option value="">全部状态</option>
          <option value="NEW">待处理</option><option value="IN_REVIEW">审核中</option>
          <option value="ACTION_REQUIRED">待知识整改</option><option value="RESOLVED">已解决</option><option value="REJECTED">已驳回</option>
        </select>
      </div>
      <div v-if="error" class="m-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{{ error }}</div>
      <div v-else-if="!loading && !rows.length" class="p-10 text-center text-sm text-ink-500">该知识库暂无回答反馈</div>
      <div v-else class="divide-y divide-ink-100">
        <article v-for="row in rows" :key="row.id" class="p-4">
          <div class="flex flex-wrap items-center gap-2">
            <span class="kb-badge" :class="row.rating==='HELPFUL'?'kb-state-ok':'kb-state-warn'">{{ row.rating==='HELPFUL'?'有帮助':'需要改进' }}</span>
            <span class="kb-badge" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
            <span v-if="row.reasonCode" class="text-xs text-ink-500">{{ reasonLabel(row.reasonCode) }}</span>
            <span class="ml-auto text-[11px] text-ink-500">{{ row.createdTime || '—' }} · {{ row.feedbackCode }}</span>
          </div>
          <div class="mt-3 rounded-lg bg-ink-50 p-3">
            <div class="text-xs font-semibold text-ink-600">用户问题</div>
            <p class="mt-1 whitespace-pre-wrap text-sm text-ink-800">{{ row.question || '（问题记录不可用）' }}</p>
            <div class="mt-3 text-xs font-semibold text-ink-600">系统回答</div>
            <p class="mt-1 line-clamp-4 whitespace-pre-wrap text-sm text-ink-700">{{ row.answer || '（回答记录不可用）' }}</p>
          </div>
          <p v-if="row.comment" class="mt-3 whitespace-pre-wrap text-sm text-ink-700"><b>补充说明：</b>{{ row.comment }}</p>
          <p
            v-if="row.suggestedAnswer"
            class="mt-2 whitespace-pre-wrap rounded-md border border-brand-200 bg-brand-50 p-3 text-sm text-ink-800">
            <b>用户建议：</b>{{ row.suggestedAnswer }}
          </p>
          <div class="mt-3 grid gap-2 md:grid-cols-[180px_1fr_auto] md:items-end">
            <label class="text-xs text-ink-600">处理结果
              <select v-model="draftStatus[row.id]" class="mt-1 w-full rounded border border-ink-200 bg-white px-2 py-1.5 text-xs">
                <option value="IN_REVIEW">审核中</option><option value="ACTION_REQUIRED">待知识整改</option>
                <option value="RESOLVED">已解决</option><option value="REJECTED">已驳回</option>
              </select>
            </label>
            <label class="text-xs text-ink-600">处理说明
              <input v-model="draftNote[row.id]" maxlength="1000" class="mt-1 w-full rounded border border-ink-200 bg-white px-2 py-1.5 text-xs" placeholder="整改事项、关联资产或驳回原因" />
            </label>
            <button class="kb-btn-primary text-xs" :disabled="savingId===row.id" @click="save(row)">{{ savingId===row.id?'保存中…':'保存处理' }}</button>
          </div>
        </article>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { listQueryFeedback, reviewQueryFeedback, unwrap } from '../api/knowledge'
import type { QueryFeedback } from '../api/knowledge-impl'
import { errorMessage, notifyError, notifySuccess } from '../utils/feedback'

const props = defineProps<{ knowledgeBaseId:number }>()
const rows = ref<QueryFeedback[]>([])
const summary = ref<Record<string,number>>({})
const statusFilter = ref('')
const loading = ref(false)
const savingId = ref<number|null>(null)
const error = ref('')
const draftStatus = ref<Record<number,QueryFeedback['status']>>({})
const draftNote = ref<Record<number,string>>({})
const summaryCards = [
  { key:'totalCount', label:'反馈总数' }, { key:'newCount', label:'待处理' },
  { key:'inReviewCount', label:'审核中' }, { key:'actionRequiredCount', label:'待知识整改' },
  { key:'resolvedCount', label:'已解决' }, { key:'rejectedCount', label:'已驳回' },
  { key:'helpfulCount', label:'有帮助' }, { key:'unhelpfulCount', label:'需改进' },
]
const currentKb = computed(() => props.knowledgeBaseId)
watch([currentKb, statusFilter], () => { void load() }, { immediate:true })

async function load() {
  if (!props.knowledgeBaseId) return
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(await listQueryFeedback(props.knowledgeBaseId, statusFilter.value, 100), { items:[], summary:{} })
    rows.value = data?.items || []
    summary.value = data?.summary || {}
    const statuses:Record<number,QueryFeedback['status']> = {}
    const notes:Record<number,string> = {}
    for (const row of rows.value) {
      statuses[row.id] = row.status === 'NEW' ? 'IN_REVIEW' : row.status
      notes[row.id] = row.reviewNote || ''
    }
    draftStatus.value = statuses
    draftNote.value = notes
  } catch (cause) {
    error.value = errorMessage(cause, '读取反馈队列失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function save(row:QueryFeedback) {
  const status = draftStatus.value[row.id] || 'IN_REVIEW'
  const note = (draftNote.value[row.id] || '').trim()
  if (['ACTION_REQUIRED','RESOLVED','REJECTED'].includes(status) && !note) {
    notifyError(new Error('此处理状态需要填写处理说明'), '请补充处理说明')
    return
  }
  savingId.value = row.id
  try {
    unwrap(await reviewQueryFeedback(row.id, status, note), row)
    notifySuccess('反馈处理状态已更新')
    await load()
  } catch (cause) {
    notifyError(cause, '更新反馈处理状态失败')
  } finally {
    savingId.value = null
  }
}

function statusLabel(status:string) {
  return ({ NEW:'待处理', IN_REVIEW:'审核中', ACTION_REQUIRED:'待知识整改', RESOLVED:'已解决', REJECTED:'已驳回' } as Record<string,string>)[status] || status
}
function statusClass(status:string) {
  if (status==='RESOLVED') return 'kb-state-ok'
  if (status==='REJECTED') return 'kb-state-danger'
  if (status==='ACTION_REQUIRED') return 'kb-state-warn'
  return 'kb-state-info'
}
function reasonLabel(reason:string) {
  return ({ INACCURATE:'内容不准确', INCOMPLETE:'回答不完整', OUTDATED:'知识已过期', NO_CITATION:'缺少依据或引用', IRRELEVANT:'回答无关', OTHER:'其他' } as Record<string,string>)[reason] || reason
}
</script>
