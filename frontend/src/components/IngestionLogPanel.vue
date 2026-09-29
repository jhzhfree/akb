<template>
<!-- 高度用 CSS 变量下发而非内联 height：窄屏列布局时可由媒体查询改回 auto，
     且类名必须避开 Tailwind 的 .fixed（position:fixed），否则整块会脱离文档流浮在页面上。 -->
<div class="ingestion-log" :class="{'log-fixed':!!height}" :style="height?{'--log-h':`${height}px`}:undefined">
  <div class="run-list">
    <button v-for="run in runs" :key="run.id" :class="{active:run.id===selectedId}" @click="$emit('select',run.id)">
      <span class="run-dot" :class="run.status.toLowerCase()">
</span>
      <span class="run-copy">
<b>{{run.sourceName}}</b>
<small>{{run.currentStage}} · {{formatTime(run.startedTime)}}</small>
</span>
      <span class="kb-state" :class="stateClass(run.status)">{{stateText(run.status)}}</span>
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
    <div v-if="!detail" class="event-empty">选择一次运行查看 接入 → 解析 → 入库 → 抽取 → 建索引 的全过程</div>
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
  /** 宿主指定高度（px）：固定高度后两栏各自内部滚动，避免整块被撑长。 */
  height?:number
}>()
defineEmits<{select:[id:number]}>()
const timeline=ref<HTMLElement|null>(null)
const formatTime=(v?:string)=>v?new Date(v).toLocaleString():'—'
watch(()=>props.detail?.events?.length,()=>{
  if(!props.live)return
  void nextTick(()=>{const el=timeline.value;
if(el)el.scrollTop=el.scrollHeight})
})
/* 运行状态：全站统一用 .kb-state-* 胶囊，配色由主题三件套给出；
   同时把后端枚举翻译成中文，列表里不再出现 SUCCEEDED 这类原始值。 */
const RUN_STATE:Record<string,{cls:string;text:string}>={
  SUCCEEDED:{cls:'kb-state-ok',text:'成功'},
  FAILED:{cls:'kb-state-danger',text:'失败'},
  RUNNING:{cls:'kb-state-warn',text:'进行中'},
  CANCELED:{cls:'kb-state-neutral',text:'已取消'},
  PENDING:{cls:'kb-state-neutral',text:'排队中'}
}
const stateClass=(s:string)=>RUN_STATE[s]?.cls||'kb-state-neutral'
const stateText=(s:string)=>RUN_STATE[s]?.text||s
</script>
<style scoped>
.ingestion-log{
display:flex;
align-items:stretch;
align-content:stretch;
min-height:330px;
border:1px solid var(--line-d);
border-radius:10px;
background:var(--bg-raised);
overflow:hidden}
/* 已上传文件列表：弹性定宽一列，自身独立滚动，不与右侧时间线互相挤压。 */
.run-list{flex:0 0 clamp(220px,32%,300px);
min-width:0;
min-height:0;
border-right:1px solid var(--line-d);
background:var(--bg-sub);
overflow:auto}
.run-list button{width:100%;
border:0;
border-bottom:1px solid var(--line-d);
background:transparent;
padding:10px;
display:flex;
align-items:center;
gap:8px;
text-align:left;
cursor:pointer}
/* 选中行：左侧强调条 + 强调底色，深色下也不会和 hover 混成同一块深色 */
.run-list button:hover,.run-list button.active{background:var(--accent-bg)}
.run-list button.active{box-shadow:inset 3px 0 0 var(--accent-text);color:var(--ink-950)}
.run-dot,.event-node{width:8px;
height:8px;
border-radius:50%;
background:var(--ink-3);
flex:none}
.run-dot.succeeded,.event-node.success{background:var(--ok)}
.run-dot.failed,.event-node.failed{background:var(--danger)}
.run-dot.running,.event-node.running{background:var(--warn)}
.run-copy{display:flex;
flex:1;
min-width:0;
flex-direction:column}
.run-copy b{font-size:12px;
overflow:hidden;
text-overflow:ellipsis;
white-space:nowrap}
.run-copy small{font-size:10px;
color:var(--ink-3)}
/* 日志时间线：吃掉剩余宽度，min-width/min-height:0 保证内容只在自身内部滚动。 */
.event-timeline{flex:1;
min-width:0;
min-height:0;
padding:18px 22px;
overflow:auto}
.run-summary{display:flex;
justify-content:space-between;
border-bottom:1px solid var(--line-d);
padding-bottom:12px;
margin-bottom:12px}
.run-summary-meta{display:flex;
align-items:center;
gap:6px;
font-size:11px;
color:var(--ink-3)}
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
border-left:1px solid var(--line-d)}
.event:last-child:after{display:none}
.event b{font-size:12px}
.event p{margin:3px 0;
font-size:12px;
color:var(--ink-2)}
.event small,.event-empty,.empty-run{font-size:11px;
color:var(--ink-3)}
.empty-run,.event-empty{padding:24px;
text-align:center}
/* 固定高度模式：高度由宿主的 --log-h 决定，容器不再被内容撑长，两栏各自内部滚动。 */
.ingestion-log.log-fixed{min-height:0;
height:var(--log-h,330px)}
/* 窄屏改为上下两栏：固定高度会把两栏一起压扁，这里交回内容高度、各自限高滚动。 */
@media(max-width:800px){.ingestion-log{flex-direction:column}
.ingestion-log.log-fixed{height:auto}
.run-list{flex:none;
width:auto;
border-right:0;
border-bottom:1px solid var(--line-d);
max-height:180px}
.event-timeline{max-height:320px}
}

</style>
