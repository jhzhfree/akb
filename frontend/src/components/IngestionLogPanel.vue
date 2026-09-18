<template>
<div class="ingestion-log">
  <div class="run-list">
    <button v-for="run in runs" :key="run.id" :class="{active:run.id===selectedId}" @click="$emit('select',run.id)">
      <span class="run-dot" :class="run.status.toLowerCase()">
</span>
      <span class="run-copy">
<b>{{run.sourceName}}</b>
<small>{{run.currentStage}} · {{formatTime(run.startedTime)}}</small>
</span>
      <el-tag size="small" :type="tagType(run.status)">{{run.status}}</el-tag>
    </button>
    <div v-if="!runs.length" class="empty-run">还没有摄入记录</div>
  </div>
  <div ref="timeline" class="event-timeline">
    <div v-if="detail" class="run-summary">
<b>{{detail.sourceName}}</b>
<span class="run-summary-meta">
<el-tag v-if="live" size="small" type="warning" effect="dark" class="live-tag">实时跟踪</el-tag>
<span>{{detail.chunkCount}} chunks · {{detail.cardCount}} cards · {{detail.claimCount}} claims</span>
</span>
</div>
    <div v-for="event in detail?.events||[]" :key="event.id" class="event">
      <span class="event-node" :class="event.status.toLowerCase()">
</span>
      <div>
<b>{{event.stage}}</b>
<p>{{event.message||event.eventType}}</p>
<small>{{formatTime(event.createdTime)}}</small>
</div>
    </div>
    <div v-if="!detail" class="event-empty">选择一次运行查看 Source → Parse → Persist → Extract → Index 的全过程</div>
  </div>
</div>
</template>
<script setup lang="ts">
import{nextTick,ref,watch}from'vue'
import type{IngestionRun,IngestionRunDetail}from'../types'
const props=defineProps<{
  runs:IngestionRun[]; detail:IngestionRunDetail|null; selectedId:number|null
  /** 上传进行中：日志随运行持续刷新，跟随最新一条事件滚动。 */
  live?:boolean
}>()
defineEmits<{select:[id:number]}>()
const timeline=ref<HTMLElement|null>(null)
const formatTime=(v?:string)=>v?new Date(v).toLocaleString():'—'
watch(()=>props.detail?.events?.length,()=>{
  if(!props.live)return
  void nextTick(()=>{const el=timeline.value;
if(el)el.scrollTop=el.scrollHeight})
})
const tagType=(s:string):'success'|'danger'|'warning'|'info'=>s==='SUCCEEDED'?'success':s==='FAILED'?'danger':s==='RUNNING'?'warning':'info'
</script>
<style scoped>
.ingestion-log{display:grid;
grid-template-columns:300px 1fr;
min-height:330px;
border:1px solid #e5e7eb;
border-radius:10px;
overflow:hidden}
.run-list{border-right:1px solid #e5e7eb;
background:#fafafa;
max-height:430px;
overflow:auto}
.run-list button{width:100%;
border:0;
border-bottom:1px solid #eee;
background:transparent;
padding:10px;
display:flex;
align-items:center;
gap:8px;
text-align:left;
cursor:pointer}
.run-list button:hover,.run-list button.active{background:#eef2ff}
.run-dot,.event-node{width:8px;
height:8px;
border-radius:50%;
background:#94a3b8;
flex:none}
.run-dot.succeeded,.event-node.success{background:#10b981}
.run-dot.failed,.event-node.failed{background:#ef4444}
.run-dot.running,.event-node.running{background:#f59e0b}
.run-copy{display:flex;
flex:1;
min-width:0;
flex-direction:column}
.run-copy b{font-size:12px;
overflow:hidden;
text-overflow:ellipsis;
white-space:nowrap}
.run-copy small{font-size:10px;
color:#94a3b8}
.event-timeline{padding:18px 22px;
overflow:auto}
.run-summary{display:flex;
justify-content:space-between;
border-bottom:1px solid #e5e7eb;
padding-bottom:12px;
margin-bottom:12px}
.run-summary-meta{display:flex;
align-items:center;
gap:6px;
font-size:11px;
color:#64748b}
.live-tag{animation:live-pulse 1.4s ease-in-out infinite}
@keyframes live-pulse{0%,100%{opacity:1}50%{opacity:.45}}
.event{display:grid;
grid-template-columns:14px 1fr;
gap:8px;
padding:8px 0;
position:relative}
.event:after{content:"";
position:absolute;
left:3px;
top:18px;
bottom:-8px;
border-left:1px solid #e2e8f0}
.event:last-child:after{display:none}
.event b{font-size:12px}
.event p{margin:3px 0;
font-size:12px;
color:#475569}
.event small,.event-empty,.empty-run{font-size:11px;
color:#94a3b8}
.empty-run,.event-empty{padding:24px;
text-align:center}
@media(max-width:800px){.ingestion-log{grid-template-columns:1fr}
.run-list{border-right:0;
border-bottom:1px solid #e5e7eb;
max-height:180px}
}

</style>
