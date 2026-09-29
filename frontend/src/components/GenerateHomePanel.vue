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
    </header>

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
import { computed } from 'vue'
import type {
  BatchIngestStatus, ConflictRow, IngestionRun, IngestionRunDetail,
  KnowledgeDocument, ReviewRow, SourcePreview,
} from '../types'
import DocumentIngestPanel from './DocumentIngestPanel.vue'

const props = defineProps<{
  knowledgeBaseId:number
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
