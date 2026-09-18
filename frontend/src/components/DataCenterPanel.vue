<template>
<section class="kb-page">
<div class="kb-section-head">
<div>
<div class="kb-eyebrow">INGESTION PIPELINE</div>
<h2 class="kb-title mt-1">资料与摄入</h2>
<p class="kb-muted mt-1">原始资料 → 解析 → 权威数据持久化 → 知识抽取 → Outbox / Index，全过程可审计。</p>
</div>
</div>
<div class="ingest-layout">
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
<el-card shadow="never" class="panel-card">
<template #header>
<div class="card-head">
<b>摄入运行日志</b>
<span>{{ingestLive?`实时跟踪 · ${stageText}`:'Lifecycle audit'}}</span>
</div>
</template>
  <IngestionLogPanel :runs="runs" :detail="runDetail" :selected-id="selectedRunId" :live="ingestLive" @select="$emit('select-run',$event)"/>
</el-card>
<el-card shadow="never" class="panel-card">
<template #header>
<b>已解析文档 · {{documents.length}}</b>
</template>
  <el-table :data="documents" max-height="360" @row-click="onDocumentClick">
<el-table-column prop="title" label="文档"/>
<el-table-column prop="parseStatus" label="解析" width="110"/>
<el-table-column prop="chunkCount" label="内容块" width="100"/>
</el-table>
</el-card>
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
import{computed,ref}from'vue'
import type{UploadFile,UploadInstance,UploadRawFile}from'element-plus'
import type{IngestionRun,IngestionRunDetail,KnowledgeDocument,KnowledgeSource,SourcePreview}from'../types'
import{stageLabel}from'../types'
import IngestionLogPanel from'./IngestionLogPanel.vue'
import PanelState from'./PanelState.vue'
const props=defineProps<{
  authority:number; permissionTags:string; sources:KnowledgeSource[]; documents:KnowledgeDocument[]; runs:IngestionRun[];
  runDetail:IngestionRunDetail|null; selectedRunId:number|null; uploading:boolean;
  preview:SourcePreview|null; previewLoading:boolean; previewError:string; previewId:number|null;
  /** 上传期间实时跟踪最新一次运行，日志随阶段推进刷新。 */
  ingestLive:boolean; ingestStage:string; ingestMessage:string;
}>()
const emit=defineEmits<{
  pick:[file:UploadFile]; remove:[]; upload:[]
  'update:authority':[value:number]; 'update:permission-tags':[value:string]
  'select-run':[id:number]; preview:[id:number]
}>()
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
.ingest-layout{display:grid;
grid-template-columns:1.25fr .75fr;
gap:14px}
.ingest-box{display:grid;
grid-template-columns:1fr 190px;
gap:18px;
align-items:center}
.ingest-settings{display:flex;
flex-direction:column;
gap:10px}
.ingest-settings label{font-size:12px;
color:#64748b}
.picked-file{display:flex;
align-items:center;
justify-content:space-between;
gap:8px;
margin-top:6px;
padding:6px 8px;
border:1px dashed #c7d2fe;
border-radius:8px;
background:#f5f7ff;
font-size:11px;
color:#475569}
.picked-file span{overflow:hidden;
white-space:nowrap;
text-overflow:ellipsis}
.ingest-live{display:flex;
align-items:center;
gap:8px;
padding:8px 10px;
border:1px solid #fcd34d;
border-radius:10px;
background:#fffbeb}
.ingest-live>span:last-child{display:flex;
flex:1;
min-width:0;
flex-direction:column}
.ingest-live b{font-size:12px;
color:#92400e}
.ingest-live small{font-size:11px;
color:#a16207;
white-space:nowrap;
overflow:hidden;
text-overflow:ellipsis}
.live-dot{width:8px;
height:8px;
flex:none;
border-radius:50%;
background:#f59e0b;
animation:live-blink 1.2s ease-in-out infinite}
@keyframes live-blink{0%,100%{opacity:1;
transform:scale(1)}50%{opacity:.35;
transform:scale(.7)}}
.upload-icon{font-size:30px;
margin-bottom:4px}
.el-upload small,.el-upload p{display:block;
color:#94a3b8;
font-size:11px;
margin:5px 0}
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
.preview-note{margin:0;
font-size:12px;
color:#64748b}
.empty{padding:35px;
text-align:center;
color:#94a3b8;
font-size:12px}
@media(max-width:950px){.ingest-layout,.ingest-box{grid-template-columns:1fr}
}
</style>
