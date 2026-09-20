<template>
<section class="kb-page dc-stack">
<div class="kb-section-head">
<div>
<div class="kb-eyebrow">{{ focusEyebrow }}</div>
<h2 class="kb-title mt-1">{{ focusTitle }}</h2>
<p class="kb-muted mt-1">{{ focusHint }}</p>
</div>
</div>
<div class="ingest-layout" :style="{order:blockOrder('upload')}">
  <el-card shadow="never" class="panel-card upload-card">
    <div class="ingest-box">
    <el-upload ref="uploadRef" drag :auto-upload="false" :limit="1" :on-change="onPick" :on-remove="onRemove" :on-exceed="onExceed">
      <div class="upload-icon">📥</div>
<b>拖拽或选择资料</b>
<small>PDF / DOCX / Markdown / HTML / TXT</small>
      <p>Source 保存后进入 Parse → Persist → Extract → Outbox/Index</p>
    </el-upload>
    <div v-if="pickedName" class="picked-file">
<span>已选择：{{pickedName}}</span>
<el-button link type="primary" :disabled="uploading" @click="clearPicked">移除</el-button>
</div>
    <div class="ingest-settings">
<label>来源权威分</label>
<el-input-number :model-value="authority" :min="0" :max="1" :step=".05" @update:model-value="updateAuthority"/>
<label>来源 ACL 标签</label>
<el-input :model-value="permissionTags" placeholder="如 team-a,ops；留空=公开" @update:model-value="$emit('update:permission-tags',String($event))"/>
      <el-button type="primary" :loading="uploading" :disabled="!pickedName" @click="$emit('upload')">上传并生成知识</el-button>
      <div v-if="uploading" class="ingest-live">
<span class="live-dot"></span>
<span>
<b>摄入中 · {{stageText}}</b>
<small>{{ingestMessage||'等待后端写入阶段日志…'}}</small>
</span>
</div>
</div>
</div>
  </el-card>
  <el-card shadow="never" class="panel-card source-card">
<template #header>
<b>原始资料 · {{sources.length}}</b>
</template>
    <div class="source-list">
<div v-for="source in sources.slice(0,8)" :key="source.id" class="source-row" :class="{active:source.id===previewId}"
  @click="openPreview(source.id)">
<span class="source-state">
</span>
<span>
<b>{{source.name}}</b>
<small>{{source.sourceType}} · 权威分 {{source.authorityScore}}</small>
</span>
<el-tag size="small" type="success">{{source.status}}</el-tag>
<el-button link type="primary" size="small" class="preview-btn" @click.stop="openPreview(source.id)">预览</el-button>
</div>
<div v-if="!sources.length" class="empty">raw/source 暂无资料</div>
</div>
  </el-card>
</div>
<el-card shadow="never" class="panel-card" :style="{order:blockOrder('ingestion')}">
<template #header>
<div class="card-head">
<b>摄入运行日志</b>
<span>{{ingestLive?`实时跟踪 · ${stageText}`:'Lifecycle audit'}}</span>
</div>
</template>
  <IngestionLogPanel :runs="runs" :detail="runDetail" :selected-id="selectedRunId" :live="ingestLive" @select="$emit('select-run',$event)"/>
</el-card>
<el-card shadow="never" class="panel-card" :style="{order:blockOrder('documents')}">
<template #header>
<div class="card-head">
<b>已解析文档 · {{documents.length}}</b>
<span>状态机 · 失败与索引失效可重跑</span>
</div>
</template>
  <el-table :data="documents" max-height="360" @row-click="onDocumentClick">
<el-table-column prop="title" label="文档" min-width="180" show-overflow-tooltip/>
<el-table-column label="状态" width="110">
<template #default="{row}">
<el-tag size="small" :type="documentStatusTag(row.status)">{{documentStatusLabel(row.status)}}</el-tag>
</template>
</el-table-column>
<el-table-column label="阶段" width="110">
<template #default="{row}">{{stageLabel(row.currentStage)||'—'}}</template>
</el-table-column>
<el-table-column prop="chunkCount" label="内容块" width="90"/>
<el-table-column label="重跑" width="70">
<template #default="{row}">
<span v-if="Number(row.retryCount||0)>0" class="retry-count">{{row.retryCount}} 次</span>
<span v-else class="retry-count muted">—</span>
</template>
</el-table-column>
<el-table-column label="操作" width="150">
<template #default="{row}">
<el-button link type="primary" size="small" @click.stop="viewChunks(row)">Chunk</el-button>
<el-button link type="warning" size="small" :disabled="!isRetryableDocument(row)||retryingId===row.id"
  :loading="retryingId===row.id" @click.stop="retryDocumentRow(row)">重跑</el-button>
</template>
</el-table-column>
<template #empty>
<span class="empty-inline">尚未解析任何文档</span>
</template>
</el-table>
  <p v-if="failedCount" class="doc-hint">
  有 {{failedCount}} 个文档处于失败/失效状态，可用「重跑」从原始文件重新解析；旧 Chunk 版本保留为 SUPERSEDED，引用链不会断。
</p>
</el-card>
<!-- Chunk 清单弹窗：切分策略、页码/字符区间、token 数与 revision，用于核对切分是否符合预期。 -->
<el-dialog v-model="chunkVisible" :title="chunkTitle" width="820px" destroy-on-close>
  <div v-if="chunkDoc" class="chunk-meta">
<el-tag size="small" type="info">{{chunkDoc.documentCode}}</el-tag>
<span class="chip">最新 revision {{chunkMaxRevision}}</span>
<span class="chip">共 {{chunks.length}} 个 Chunk</span>
<span v-if="chunkSuperseded" class="chip">历史版本 {{chunkSuperseded}} 个</span>
</div>
  <PanelState
    :loading="chunkLoading"
    :error="chunkError"
    :empty="!chunks.length&&!chunkLoading&&!chunkError"
    empty-text="该文档暂无 Chunk"
    empty-hint="文档可能仍在解析中，或摄入在切分之前失败"
    retryable @retry="reloadChunks"/>
  <template v-if="chunks.length">
  <el-table :data="chunkPage" size="small" max-height="380">
<el-table-column prop="unitCode" label="单元编码" width="150" show-overflow-tooltip/>
<el-table-column label="类型" width="100">
<template #default="{row}">{{chunkTypeLabel(row.chunkType)}}</template>
</el-table-column>
<el-table-column prop="headingPath" label="标题路径" min-width="140" show-overflow-tooltip>
<template #default="{row}">{{row.headingPath||'—'}}</template>
</el-table-column>
<el-table-column label="页码" width="90">
<template #default="{row}">{{pageRange(row)}}</template>
</el-table-column>
<el-table-column label="字符区间" width="110">
<template #default="{row}">{{charRange(row)}}</template>
</el-table-column>
<el-table-column prop="tokenCount" label="Token" width="80"/>
<el-table-column label="版本" width="130">
<template #default="{row}">
<span>rev {{row.revision}}</span>
<el-tag size="small" :type="row.status==='ACTIVE'?'success':'info'">{{row.status||'—'}}</el-tag>
</template>
</el-table-column>
<el-table-column label="内容" min-width="180" show-overflow-tooltip>
<template #default="{row}">{{row.excerpt||'—'}}</template>
</el-table-column>
</el-table>
  <div class="chunk-pager">
<el-pagination small layout="prev,pager,next,total" :current-page="chunkPageNo"
  :page-size="chunkPageSize" :total="chunks.length" :pager-count="5"
  @current-change="setChunkPage"/>
</div>
</template>
</el-dialog>
<!-- 源文件预览弹窗：从「原始资料」行或「已解析文档」行发起，数据仍由父组件按 preview 事件拉取。 -->
<el-dialog v-model="dialogVisible" :title="preview?.source?.name||'源文件预览'" width="760px" destroy-on-close>
  <PanelState
    :loading="previewLoading"
    :error="previewError"
    :empty="!preview&&!previewLoading&&!previewError"
    empty-text="尚未选择资料"
    empty-hint="点击「原始资料」列表的「预览」按钮，即可查看源文件原文"/>
  <div v-if="preview" class="preview-body">
    <div class="preview-meta">
<el-tag size="small" type="info">{{preview.source?.sourceType||'未知格式'}}</el-tag>
<el-tag size="small" :type="preview.mode==='SOURCE'?'success':'warning'">{{modeLabel}}</el-tag>
<span v-if="preview.stored" class="preview-size">{{sizeLabel}}</span>
<a v-if="preview.stored&&preview.fileUrl" class="preview-link" :href="preview.fileUrl" target="_blank" rel="noopener">打开原文件 ↗</a>
</div>
    <p v-if="preview.available===false" class="preview-note">{{preview.reason}}</p>
    <pre v-else-if="preview.content" class="preview-text dialog-text">{{preview.content}}</pre>
    <p v-else class="preview-note">该格式无法以文本预览，请打开原文件查看。</p>
    <p v-if="preview.truncated" class="preview-note">已截断显示前 {{preview.limit}} 字，共 {{preview.totalChars}} 字。</p>
</div>
</el-dialog>
</section>
</template>
<script setup lang="ts">
import{computed,ref,watch}from'vue'
import type{UploadFile,UploadInstance,UploadRawFile}from'element-plus'
import type{
  DocumentChunk, IngestionRun, IngestionRunDetail, KnowledgeDocument, KnowledgeSource, SourcePreview,
}from'../types'
import{
  documentStatusLabel, documentStatusTag, isRetryableDocument, stageLabel,
}from'../types'
import{getDocumentChunks,retryDocument,unwrap}from'../api/knowledge'
import{confirmAction,notifyError,notifySuccess,paginate}from'../utils/feedback'
import IngestionLogPanel from'./IngestionLogPanel.vue'
import PanelState from'./PanelState.vue'
const props=defineProps<{
  authority:number; permissionTags:string; sources:KnowledgeSource[]; documents:KnowledgeDocument[]; runs:IngestionRun[];
  runDetail:IngestionRunDetail|null; selectedRunId:number|null; uploading:boolean;
  preview:SourcePreview|null; previewLoading:boolean; previewError:string; previewId:number|null;
  /** 上传期间实时跟踪最新一次运行，日志随阶段推进刷新。 */
  ingestLive:boolean; ingestStage:string; ingestMessage:string;
  /** IA 入口聚焦：知识生成的「文档导入 / 连接器 / 生成任务」共用同一面板。 */
  focus?:'sources'|'documents'|'ingestion';
  /** 上传复位令牌：一次摄入完成后由外壳自增，面板收到后清空已选文件。 */
  resetToken?:number;
}>()
const emit=defineEmits<{
  pick:[file:UploadFile]; remove:[]; upload:[]
  'update:authority':[value:number]; 'update:permission-tags':[value:string]
  'select-run':[id:number]; preview:[id:number]
  /** 重跑结束：文档状态与运行日志都已变化，交由父组件重新拉取。 */
  reloaded:[]
}>()
/**
 * 同一个面板服务三个 IA 入口（数据源 / 文档 / 解析任务）：
 * 用 order 调整区块前后，聚焦入口的区块置顶，其余按流程顺序排在后面。
 */
const BLOCK_ORDER:Record<string,Record<string,number>>={
  sources:{upload:1,ingestion:2,documents:3},
  documents:{documents:1,upload:2,ingestion:3},
  ingestion:{ingestion:1,upload:2,documents:3},
}
const FOCUS_TEXT:Record<string,{eyebrow:string;title:string;hint:string}>={
  sources:{eyebrow:'INGESTION PIPELINE',title:'资料与摄入',
    hint:'原始资料 → 解析 → 权威数据持久化 → 知识抽取 → Outbox / Index，全过程可审计。'},
  documents:{eyebrow:'DOCUMENT CENTER',title:'文档中心',
    hint:'文档状态机：解析失败与索引失效可重跑，Chunk 与 revision 全程可追溯。'},
  ingestion:{eyebrow:'PARSING TASKS',title:'解析任务',
    hint:'Source → Parse → Persist → Extract 的运行日志与实时阶段跟踪，失败可定位到具体阶段。'},
}
const focusKey=computed(()=>props.focus||'sources')
const focusEyebrow=computed(()=>FOCUS_TEXT[focusKey.value]?.eyebrow||FOCUS_TEXT.sources.eyebrow)
const focusTitle=computed(()=>FOCUS_TEXT[focusKey.value]?.title||FOCUS_TEXT.sources.title)
const focusHint=computed(()=>FOCUS_TEXT[focusKey.value]?.hint||FOCUS_TEXT.sources.hint)
function blockOrder(block:string){return BLOCK_ORDER[focusKey.value]?.[block]??9}

const uploadRef=ref<UploadInstance>(), pickedName=ref('')
function onPick(file:UploadFile){pickedName.value=file?.name||'';emit('pick',file)}
function onRemove(){pickedName.value='';emit('remove')}
/** 已选一个文件时再次拖拽/选择：替换旧文件，而不是被 limit=1 拦下。 */
function onExceed(files:File[]){
  const next=files[0]
  if(!next)return
  const raw=Object.assign(next,{uid:Date.now()}) as UploadRawFile
  uploadRef.value?.clearFiles()
  uploadRef.value?.handleStart(raw)
  pickedName.value=raw.name
  emit('pick',{name:raw.name,raw} as unknown as UploadFile)
}
function clearPicked(){uploadRef.value?.clearFiles();pickedName.value='';emit('remove')}
/** 摄入完成后由父组件复位上传区：清空 el-upload 内部列表，否则名额被占用无法再选下一个文件。 */
function resetUpload(){clearPicked()}
defineExpose({resetUpload})
/** 面板已下沉到模块页面内部，外壳拿不到实例，改用令牌触发上传区复位。 */
watch(() => props.resetToken, value => { if (value) resetUpload() })
function updateAuthority(value:number|undefined){emit('update:authority',value??.8)}
/** 预览弹窗：点击资料行的「预览」按钮（或整行）打开，同时通知父组件拉取源文件。 */
const dialogVisible=ref(false)
function openPreview(id:number){
  emit('preview',id)
  dialogVisible.value=true
}
/** 文档行按来源回到原件：预览的主体始终是 source，而不是派生出来的文档。 */
function onDocumentClick(row:KnowledgeDocument){
  if(row?.sourceId)openPreview(row.sourceId)
}
/* ---- 文档状态机：失败/失效文档从原件重跑（设计 §16.1） ---- */
const retryingId=ref<number|null>(null)
const failedCount=computed(()=>props.documents.filter(v=>isRetryableDocument(v)).length)
async function retryDocumentRow(row:KnowledgeDocument){
  // 重跑会重新生成 Chunk revision，是代价不低且影响引用版本的操作，必须二次确认。
  try{
    await confirmAction({
      title:'重跑该文档？',
      message:`将从原始文件重新解析《${row.title}》，生成新的 Chunk revision；`
        + '旧 Chunk 保留为 SUPERSEDED，已有引用不会断。',
      confirmText:'重跑',
    })
  }catch{return}
  retryingId.value=row.id
  try{
    const d=unwrap(await retryDocument(row.id),{})
    const revision=d.chunkRevision??d.revision
    notifySuccess(
      `重跑完成：${Number(d.chunkCount||0)} 个 Chunk`
      + `${revision!==undefined?`，revision ${revision}`:''}`,
    )
    emit('reloaded')
    if(chunkDoc.value?.id===row.id)await loadChunks(row.id)
  }catch(e){notifyError(e,'重跑失败')}
  finally{retryingId.value=null}
}

/* ---- Chunk 清单：切分元数据与版本（设计 §7.2） ---- */
const chunkVisible=ref(false), chunkLoading=ref(false), chunkError=ref('')
const chunkDoc=ref<KnowledgeDocument|null>(null), chunks=ref<DocumentChunk[]>([])
const chunkPageNo=ref(1)
const chunkPageSize=10
/** 翻页：页码由分页组件给出，重跑后自动回到第一页看最新 revision。 */
function setChunkPage(page:number){chunkPageNo.value=page}
const chunkPage=computed(()=>paginate(chunks.value,chunkPageNo.value,chunkPageSize))
const chunkTitle=computed(()=>`Chunk 清单 · ${chunkDoc.value?.title||'文档'}`)
const chunkMaxRevision=computed(()=>
  chunks.value.reduce((max,v)=>Math.max(max,Number(v.revision||0)),0))
const chunkSuperseded=computed(()=>
  chunks.value.filter(v=>String(v.status||'').toUpperCase()==='SUPERSEDED').length)
async function loadChunks(documentId:number){
  chunkLoading.value=true; chunkError.value=''
  try{chunks.value=unwrap(await getDocumentChunks(documentId),[])}
  catch(e){chunkError.value=notifyError(e,'Chunk 清单加载失败')}
  finally{chunkLoading.value=false}
}
async function viewChunks(row:KnowledgeDocument){
  chunkDoc.value=row
  chunkVisible.value=true
  chunkPageNo.value=1
  await loadChunks(row.id)
}
/** 弹窗内的「重试」：文档身份已知，重新拉一次即可。 */
async function reloadChunks(){
  const doc=chunkDoc.value
  if(doc)await loadChunks(doc.id)
}
/** 页码区间：单页显示为一个页码，跨页时显示起止。 */
function pageRange(row:DocumentChunk):string{
  const from=row.pageNo, to=row.pageEnd
  if(from==null&&to==null)return'—'
  if(from!=null&&to!=null&&Number(from)!==Number(to))return `${from}–${to}`
  return String(from??to)
}
function charRange(row:DocumentChunk):string{
  const from=row.charStart, to=row.charEnd
  if(from==null&&to==null)return'—'
  return `${from??0}–${to??''}`.replace(/–$/,'–?')
}
const CHUNK_TYPE_LABELS:Record<string,string> = {
  PARAGRAPH:'段落', HEADING:'标题', SECTION:'章节', TABLE:'表格', LIST:'列表',
  CODE:'代码', CAPTION:'题注', TITLE:'文档标题',
}
const chunkTypeLabel=(type?:string):string =>
  CHUNK_TYPE_LABELS[String(type||'').toUpperCase()]||String(type||'—')

const modeLabel=computed(()=>{
  if(props.preview?.mode==='SOURCE')return'原文'
  if(props.preview?.mode==='PARSED')return'解析文本'
  return'无可用文本'
})
const sizeLabel=computed(()=>{
  const bytes=Number(props.preview?.sizeBytes||0)
  if(!bytes)return''
  if(bytes<1024)return `${bytes} B`
  if(bytes<1024*1024)return `${(bytes/1024).toFixed(1)} KB`
  return `${(bytes/1024/1024).toFixed(1)} MB`
})
const stageText=computed(()=>stageLabel(props.ingestStage)||'准备中')
</script>
<style scoped>
/* 区块顺序由入口聚焦决定：focus 变化时置顶对应区块。 */
.dc-stack{display:flex;
flex-direction:column}
.source-list{max-height:245px;
overflow:auto}
.source-row{display:flex;
align-items:center;
gap:9px;
padding:9px 2px;
border-bottom:1px solid #f1f5f9;
cursor:pointer}
.source-row:hover{background:#f8fafc}
.source-row.active{background:#eef2ff}
.source-row>span:nth-child(2){display:flex;
flex:1;
min-width:0;
flex-direction:column}
.source-row b{font-size:12px;
white-space:nowrap;
overflow:hidden;
text-overflow:ellipsis}
.source-row small{font-size:10px;
color:#94a3b8}
.source-state{width:7px;
height:7px;
border-radius:50%;
background:#10b981}
.preview-body{display:flex;
flex-direction:column;
gap:8px}
.preview-meta{display:flex;
align-items:center;
gap:8px;
flex-wrap:wrap}
.preview-size,.preview-link{font-size:12px}
.preview-size{color:#94a3b8}
.preview-link{color:#4f46e5}
.preview-text{margin:0;
max-height:420px;
overflow:auto;
padding:12px;
border:1px solid #e2e8f0;
border-radius:10px;
background:#f8fafc;
font-size:12px;
line-height:1.75;
white-space:pre-wrap;
word-break:break-word}
.preview-text.dialog-text{max-height:56vh;
margin:0}
.preview-btn{flex:none}
.chunk-meta{display:flex;
align-items:center;
gap:8px;
flex-wrap:wrap;
margin-bottom:10px}
.chip{font-size:11px;
color:#64748b;
padding:2px 8px;
border:1px solid #e2e8f0;
border-radius:999px;
background:#f8fafc}
.chunk-pager{display:flex;
justify-content:flex-end;
margin-top:10px}
.empty-inline{color:#94a3b8;
font-size:12px}
.preview-note{margin:0;
font-size:12px;
color:#64748b}
.empty{padding:35px;
text-align:center;
color:#94a3b8;
font-size:12px}
@media(max-width:950px){.ingest-box{grid-template-columns:1fr}
}
</style>
