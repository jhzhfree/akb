<template>
<section class="kb-page">
<div class="kb-section-head">
<div>
<div class="kb-eyebrow">{{focusEyebrow}}</div>
<h2 class="kb-title mt-1">{{focusTitle}}</h2>
<p class="kb-muted mt-1">{{focusHint}}</p>
</div>
</div>

<!-- 文档导入与「生成概览」共用 DocumentIngestPanel：拖入资料 + 已解析文档是同一套结构，
     三个入口（数据源 / 文档 / 解析任务）只换标题文案，区块行为两处完全一致。 -->
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
  BatchIngestStatus, IngestionRun, IngestionRunDetail, KnowledgeDocument, SourcePreview,
} from '../types'
import DocumentIngestPanel from './DocumentIngestPanel.vue'

const props = defineProps<{
  /** IA 入口聚焦：文档导入 / 生成任务共用同一面板，只换标题与说明（连接器已独立成面板）。 */
  focus?:'documents'|'ingestion'
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

/** 两个入口共用同一区块，只有标题文案不同：聚焦不再重排区块。 */
const FOCUS_TEXT:Record<string,{eyebrow:string;title:string;hint:string}> = {
  documents:{eyebrow:'DOCUMENT CENTER',title:'文档中心',
    hint:'文档状态机：解析失败与索引失效可重跑，Chunk 与 revision 全程可追溯。'},
  ingestion:{eyebrow:'PARSING TASKS',title:'解析任务',
    hint:'Source → Parse → Persist → Extract 的运行日志与实时阶段跟踪，失败可定位到具体阶段。'},
}
const focusKey = computed(()=>props.focus||'documents')
const focusEyebrow = computed(()=>FOCUS_TEXT[focusKey.value]?.eyebrow||FOCUS_TEXT.documents.eyebrow)
const focusTitle = computed(()=>FOCUS_TEXT[focusKey.value]?.title||FOCUS_TEXT.documents.title)
const focusHint = computed(()=>FOCUS_TEXT[focusKey.value]?.hint||FOCUS_TEXT.documents.hint)
</script>
