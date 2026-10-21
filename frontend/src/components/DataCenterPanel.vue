<template>
<section class="kb-page">
<div class="kb-section-head">
<div>
<div class="kb-eyebrow">知识生成</div>
<h2 class="kb-title mt-1">文档导入</h2>
<p class="kb-muted mt-1">文档状态机：解析失败与索引失效可重跑，Chunk 与 revision 全程可追溯。</p>
</div>
</div>

<!-- 与「生成概览」共用 DocumentIngestPanel：拖入资料 + 已解析文档是同一套结构，
     同一条摄入链路只保留一份实现，本页只负责页面外壳（标题与说明）。 -->
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
import type {
  BatchIngestStatus, IngestionRun, IngestionRunDetail, KnowledgeDocument, SourcePreview,
} from '../types'
import DocumentIngestPanel from './DocumentIngestPanel.vue'

defineProps<{
  knowledgeBaseId:number
  authority:number
  permissionTags:string
  documents:KnowledgeDocument[]
  runs:IngestionRun[]
  runDetail:IngestionRunDetail|null
  selectedRunId:number|null
  uploading:boolean
  /** 上传期间实时跟踪最新一次运行，日志随阶段推进刷新。 */
  ingestLive:boolean
  ingestStage:string
  ingestMessage:string
  preview:SourcePreview|null
  previewLoading:boolean
  previewError:string
  previewId:number|null
  /** 上传复位令牌：一次摄入完成后由外壳自增，透传给摄入面板清空已选文件。 */
  resetToken?:number
  /** 批量接入批次状态：多文件上传的进度与逐文件结果由外壳轮询后下发。 */
  batchStatus?:BatchIngestStatus|null
  batchRunning?:boolean
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

</script>
