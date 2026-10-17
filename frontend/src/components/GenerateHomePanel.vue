<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识生成</div>
        <h1 class="kb-title mt-1">生成概览</h1>
        <p class="kb-muted mt-1">
          从原始数据到可信知识资产：拖入资料即可走完「接入 → 解析 → 抽取 → 索引」，运行日志从文档行回看。
        </p>
      </div>
      <!-- 抽取只在文档导入时跑一次，抽取逻辑改进后旧产物不会自动重算。
           这里提供整库重跑的入口，用已入库的正文重新抽取，不必再上传一次文件。 -->
      <div class="shrink-0">
        <button class="kb-btn kb-btn-outline" :disabled="regenerating || !documents.length"
          :title="documents.length ? '' : '当前知识库还没有文档'"
          @click="regenerateDialog = true">
          {{ regenerating ? '重新生成中…' : '重新生成知识' }}
        </button>
      </div>
    </header>

    <el-dialog v-model="regenerateDialog" title="重新生成知识" width="520px">
      <p class="kb-muted">
        将对本知识库 {{ documents.length }} 篇文档重新抽取卡片与断言，使用已入库的正文，不需要重新上传文件。
        文档较多时会持续一段时间。
      </p>
      <label class="mt-3 flex items-start gap-2">
        <input v-model="purgeUnpublished" type="checkbox" class="mt-1" />
        <span>
          同时清除未发布的旧候选
          <em class="kb-muted not-italic">（草稿 / 待复核 / 冲突状态的卡片与断言）</em>
        </span>
      </label>
      <p class="kb-muted mt-2">
        已通过人工裁决、标记为已批准或生效的知识不会被清除；不勾选时新抽取结果会与旧候选并存。
      </p>
      <template #footer>
        <button class="kb-btn kb-btn-outline" @click="regenerateDialog = false">取消</button>
        <button class="kb-btn" :disabled="regenerating"
          @click="confirmRegenerate">
          {{ regenerating ? '生成中…' : '开始生成' }}
        </button>
      </template>
    </el-dialog>

    <!-- 重新生成的进度：整库重跑已改成异步任务，进度来自任务中心，
         与「文档导入」里看到的是同一条任务，不会两处数字不一致。 -->
    <article v-if="regenVisible" class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">重新生成进度</b>
        <span class="kb-muted">{{ regenStateText }}</span>
      </div>
      <div class="p-4">
        <div class="h-2 w-full overflow-hidden rounded bg-ink-100">
          <div class="h-full transition-all" :style="{width:Math.max(2,regen.progress)+'%',background:'var(--accent)'}"></div>
        </div>
        <div class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[12px] text-ink-600">
          <span>{{ regen.progress }}%</span>
          <span>{{ regen.processed + regen.failed + regen.skipped }} / {{ regen.documentCount || documents.length }} 篇</span>
          <span>卡片 {{ regen.cardCount }}</span>
          <span>断言 {{ regen.claimCount }}</span>
          <span v-if="regen.failed" class="text-[color:var(--danger)]">失败 {{ regen.failed }} 篇</span>
        </div>
        <p class="mt-1 truncate text-[11px] text-ink-500">{{ regen.stage || '正在排队…' }}</p>
      </div>
    </article>

    <!-- §4 生成概览：四个数字说明「今天进来多少、正在跑多少、多少卡在人工环节」。
         文档导入 / 连接器 / 裁决 / 衍生的入口在页面顶部动作卡里，这里不再重复一遍。 -->
    <div class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <div class="kb-metric"><span>今日导入</span><b>{{ todayRuns }}</b><small class="kb-muted">已接收的资料运行</small></div>
      <div class="kb-metric"><span>运行中</span><b>{{ runningRuns }}</b><small class="kb-muted">流水线未结束</small></div>
      <div class="kb-metric"><span>待裁决</span><b>{{ pendingTotal }}</b><small class="kb-muted">待审核 + 冲突</small></div>
      <div class="kb-metric"><span>候选衍生</span><b>—</b><small class="kb-muted">衍生能力规划中</small></div>
    </div>

    <!-- §4.1 / §4.2 「拖入资料 → 已解析文档」：与「文档导入」共用 DocumentIngestPanel，
         同一条摄入链路只保留一份实现，概览不再自己维护上传与重跑逻辑。 -->
    <DocumentIngestPanel
      :knowledge-base-id="knowledgeBaseId"
      :authority="authority" :permission-tags="permissionTags"
      @update:authority="emit('update:authority',$event)"
      @update:permission-tags="emit('update:permission-tags',String($event))"
      :documents="documents"
      :runs="runs" :run-detail="runDetail" :selected-run-id="selectedRunId"
      :uploading="uploading"
      :ingest-live="ingestLive" :ingest-stage="ingestStage" :ingest-message="ingestMessage"
      :reset-token="resetToken"
      :batch-status="batchStatus" :batch-running="batchRunning"
      :preview="preview" :preview-loading="previewLoading" :preview-error="previewError" :preview-id="previewId"
      @upload-files="emit('upload-files',$event)" @remove="emit('remove')"
      @cancel-batch="emit('cancel-batch',$event)" @retry-batch="emit('retry-batch',$event)"
      @preview="emit('preview',$event)"
      @select-run="emit('select-run',$event)" @refresh="emit('refresh')"
      @open-graph="emit('open-graph',$event)" @reloaded="emit('reloaded')" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type {
  BatchIngestStatus, ConflictRow, IngestionRun, IngestionRunDetail,
  KnowledgeDocument, ReviewRow, SourcePreview,
} from '../types'
import { useShell } from '../shell'
import DocumentIngestPanel from './DocumentIngestPanel.vue'

const shell = useShell()
/** 重新生成：确认框与「是否清除旧候选」的选择属于本页交互，动作本身由外壳执行。 */
const regenerateDialog = ref(false)
const purgeUnpublished = ref(false)
/** 进度由外壳持有：提交后本页可能已经切走，回来时仍能看到同一条任务跑到了哪。 */
const regen = shell.regeneration
const regenVisible = computed(() => regen.running || ['SUCCEEDED', 'FAILED', 'DEAD', 'CANCELED'].includes(regen.status))
const regenStateText = computed(() => {
  if (regen.status === 'SUCCEEDED') return '已完成'
  if (regen.status === 'CANCELED') return '已取消'
  if (regen.status === 'FAILED' || regen.status === 'DEAD') return '已失败'
  return regen.status === 'QUEUED' ? '排队中' : '进行中'
})

function confirmRegenerate(){
  shell.regenerate(purgeUnpublished.value)
  regenerateDialog.value = false
}

const props = defineProps<{
  knowledgeBaseId:number
  /** 整库重新生成进行中。 */
  regenerating?:boolean
  pendingReviews:ReviewRow[]
  conflicts:ConflictRow[]
  /** 上传相关的状态由外壳持有：生成概览与文档导入共用同一条摄入链路。 */
  authority:number
  permissionTags:string
  documents:KnowledgeDocument[]
  runs:IngestionRun[]
  runDetail:IngestionRunDetail|null
  selectedRunId:number|null
  uploading:boolean
  ingestLive:boolean
  ingestStage:string
  ingestMessage:string
  /** 上传复位令牌：一次摄入完成后由外壳自增，透传给摄入面板清空已选文件。 */
  resetToken?:number
  /** 批量接入批次状态：多文件上传的进度与逐文件结果由外壳轮询后下发。 */
  batchStatus?:BatchIngestStatus|null
  batchRunning?:boolean
  preview?:SourcePreview|null
  previewLoading?:boolean
  previewError?:string
  previewId?:number|null
}>()
const emit = defineEmits<{
  'upload-files':[files:File[]]
  remove:[]
  'cancel-batch':[code:string]
  'retry-batch':[code:string]
  'update:authority':[value:number]
  'update:permission-tags':[value:string]
  preview:[id:number]
  'select-run':[id:number]
  refresh:[]
  'open-graph':[code:string]
  reloaded:[]
}>()

/** 概览四数：只统计本次已加载的运行数据，缺失字段按 0 处理，不臆造。 */
const todayRuns = computed(() => {
  const today = new Date().toISOString().slice(0, 10)
  return props.runs.filter(v => String(v.startedTime || '').slice(0, 10) === today).length
})
const runningRuns = computed(() =>
  props.runs.filter(v => ['RUNNING','PENDING','QUEUED'].includes(String(v.status || '').toUpperCase())).length)
const pendingTotal = computed(() => props.pendingReviews.length + props.conflicts.length)
</script>
