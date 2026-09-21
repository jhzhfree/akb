<template>
<!-- 认证探测未完成时不渲染业务页面：此时还不知道角色权限，
     若按上次会话的页面直接渲染，无权账号会先发出被拒的请求（例如用户列表 403）。 -->
<div v-if="!authReady" class="flex h-screen items-center justify-center text-[13px] text-slate-500">
  正在进入工作台…
</div>
<LoginView v-else-if="needLogin" :hint="loginHint" @success="onLogin"/>
<div v-else class="kos-shell">
<header class="kos-topbar">
  <div class="kos-brand"><span class="kos-logo">◈</span>智能知识平台</div>
  <span class="kos-pill">V2.2</span>
  <div class="kos-grow"></div>
  <!-- 设计 §2.2：知识空间选择器固定在顶栏；全局搜索回车后进入搜索页并直接执行检索。 -->
  <button class="kos-kbswitch" @click="showBases=true">
    <span>知识库：<b>{{activeBase?.name||'选择知识库'}}</b></span>
    <em class="ml-2 text-[11px] text-slate-400 not-italic">{{visibilityLabel(activeBase?.visibility)}}</em>
    <i>▾</i>
  </button>
  <form class="kos-search" @submit.prevent="globalSearch">
    <input v-model="globalKeyword" class="kos-search-input" placeholder="搜索知识…" aria-label="全局搜索" />
    <button type="submit" class="kos-search-btn" aria-label="执行搜索">⌕</button>
  </form>
  <!-- 任务中心：异步任务统一入口，失败任务可直接进入详情。只对有生成或运维权限的角色开放。 -->
  <button v-if="canSeeTasks" class="kos-task" :class="{alert:failedTaskCount>0}" @click="taskCenterVisible=true">
    任务<em v-if="failedTaskCount">{{failedTaskCount}}</em>
  </button>
  <div class="kos-status"><i class="kos-dot" :class="{ok:capabilities}"></i>{{capabilities?'服务已连接':'服务状态未知'}}</div>
  <div class="kos-user">
    <span class="kos-avatar">{{avatarText}}</span>
    <div class="kos-user-copy">
      <b>{{me?.user?.displayName||me?.user?.username||'未登录'}}</b>
      <small :title="roleBrief">{{me?.user?roleName:'匿名访问'}}</small>
    </div>
    <button v-if="me?.user" class="kos-logout" @click="doLogout">退出</button>
  </div>
</header>
<div class="kos-body">
<!-- 设计 §1 / §2.2：左侧只放五个一级入口，且不挂任何二级菜单；
     Chunk、Assertion、Graph、Evaluation、凭证等全部进入模块页面内部的 Tab、卡片与抽屉。 -->
<aside class="kos-side">
  <div class="kos-nav-title">信息架构</div>
  <button
    v-for="section in navSections"
    :key="section.key"
    class="kos-nav-item kos-nav-l1"
    :class="{active:activeSection===section.key}"
    :title="section.desc"
    @click="enterSection(section)">
    <span class="kos-nav-ic">{{section.icon}}</span>
    <span class="kos-nav-tx">{{section.label}}</span>
    <em v-if="sectionBadge(section)" class="kos-nav-dot">{{sectionBadge(section)}}</em>
  </button>
</aside>
<main class="kos-main">
  <div v-if="failure" class="mb-3 flex items-start gap-3 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3">
    <span class="text-[13px] leading-5 text-rose-500">⚠</span>
    <div class="min-w-0 flex-1">
      <b class="text-[12px] text-rose-700">操作失败</b>
      <pre class="mt-1 whitespace-pre-wrap break-words font-sans text-[11px] leading-5 text-rose-700">{{failure}}</pre>
    </div>
    <button class="text-[12px] text-rose-400 hover:text-rose-600" @click="failure=''">✕</button>
  </div>
  <!-- 第 4 章：进入知识空间后，页面顶部保留「当前空间 + 环境 + 索引状态」。 -->
  <SpaceContextBar
    v-if="scopedView"
    :base="activeBase" :environment="environmentLabel" :index="indexState" :loading="contextLoading"
    @switch="showBases=true" @refresh="loadSpaceContext"/>

  <!-- 设计 §1：五个一级页面各自内部用 Tab 组织能力，App 只负责选择模块页面。 -->
  <WorkbenchPage
    v-if="activeSection==='workbench'"
    :active="active" :knowledge-base-id="knowledgeBaseId"
    :overview="overview" :capabilities="capabilities"
    :summary="dashboard" :lifecycle="lifecycle" :governance="governance"
    :asset-type="assetType" @update:asset-type="assetType=$event"
    @select="selectKey"/>

  <GeneratePage
    v-else-if="activeSection==='create'"
    :active="active" :knowledge-base-id="knowledgeBaseId"
    :pending-reviews="pendingReviews" :conflicts="conflicts" :assessments="assessments"
    :adjudicating="adjudicating"
    :selected-conflict-id="selectedConflictId" :conflict-detail="conflictDetail"
    :conflict-detail-loading="conflictDetailLoading"
    :selected-review="selectedReview" :review-detail="reviewDetail"
    :authority="authority" @update:authority="authority=$event"
    :permission-tags="sourcePermissionTags" @update:permission-tags="sourcePermissionTags=$event"
    :documents="documents" :sources="sources"
    :runs="runs" :run-detail="runDetail" :selected-run-id="selectedRunId"
    :uploading="uploading"
    :ingest-live="ingestLive" :ingest-stage="ingestStage" :ingest-message="ingestMessage"
    :preview="preview" :preview-loading="previewLoading" :preview-error="previewError" :preview-id="previewId"
    :reset-token="uploadReset" :batch-status="batchStatus" :batch-running="batchRunning"
    @select="selectKey"/>

  <SearchPage
    v-else-if="activeSection==='search'"
    :active="active" :knowledge-base-id="knowledgeBaseId"
    :query="q" @update:query="q=$event"
    :result="result" :asking="asking"
    :history="queryHistory" :history-loading="historyLoading"
    :graph-code="graphCode" @update:graph-code="graphCode=$event"
    @select="selectKey" @ask="ask" @refresh-history="loadQueryHistory"/>

  <IntegrationPage
    v-else-if="activeSection==='integration'"
    :active="active" :knowledge-base-id="knowledgeBaseId" :bases="bases"
    @select="selectKey"/>

  <SystemPage
    v-else
    :active="active" :knowledge-base-id="knowledgeBaseId" :base-name="activeBase?.name||''"
    :bases="bases" :overview="overview"
    :acl-subjects="aclSubjects" :acl-roles="aclRoles" :acl-changes="aclChanges" :acl-audits="aclAudits"
    :can-manage-users="!!me?.canManageUsers"
    @select="selectKey" @acl-change="changeAcl"
    @select-base="selectBase" @create-base="showBases=true" @refresh="loadOverview"/>
</main>
</div>
<KnowledgeBaseDialog :visible="showBases" :bases="bases" :groups="groups" :active-id="knowledgeBaseId" @close="showBases=false" @select="selectBase" @create="createBase"/>
<TaskCenterDrawer v-model:visible="taskCenterVisible" :knowledge-base-id="knowledgeBaseId"
  @navigate="navigate" @open-run="openRun"/>
<KnowledgeDetailWorkspace
    :visible="detailVisible"
    :mode="detailMode"
    :code="detailCode"
    :detail="knowledgeDetail"
    :loading="detailLoading"
    @close="detailVisible=false"
    @open-graph="openGraph"
    @open-evidence="openEvidence"/>
<QueryTracePanel :visible="traceVisible" :trace="queryTrace" :loading="traceLoading"
  @close="traceVisible=false" @open-candidate="openCitation"/>
<ApiCallTracePanel :visible="apiCallVisible" :detail="apiCallDetail" :loading="apiCallLoading"
  @close="apiCallVisible=false"/>
<EvidenceViewer :visible="evidenceVisible" :detail="evidenceDetail" :loading="evidenceLoading" @close="evidenceVisible=false"/>
</div>
</template>
<script setup lang="ts">
import{computed,onMounted,onUnmounted,provide,ref,watch}from'vue'
import{ElMessage,type UploadFile}from'element-plus'
import type {
  AssessmentRow, Capabilities, Claim, ConflictRow,
  IngestionRun, IngestionRunDetail, KnowledgeBase, KnowledgeCard, KnowledgeDocument,
  KnowledgeSource, Overview, AclSubject, AclRole, AclAudit, AclChange, ConflictDetail,
  QueryCitation, QueryHistoryItem, QueryResult, QueryTrace, ReviewRow, EvidenceDetail,
  SourcePreview,
  KnowledgeObjectDetail, AuthMe, AuthStatus, UserGroupRow,
  IndexState, OutboxStats, DriftResult,
  DashboardSummary, DashboardLifecycle, DashboardGovernance,
  BatchIngestStatus, IntegrationCallDetail,
} from './types'
import type { AssetType } from './components/AssetBrowserPanel.vue'
import TaskCenterDrawer from './components/TaskCenterDrawer.vue'
import SpaceContextBar from'./components/SpaceContextBar.vue';
import KnowledgeBaseDialog from './components/KnowledgeBaseDialog.vue'
import KnowledgeDetailWorkspace from './components/KnowledgeDetailWorkspace.vue'
import QueryTracePanel from './components/QueryTracePanel.vue'
import ApiCallTracePanel from './components/ApiCallTracePanel.vue'
import EvidenceViewer from './components/EvidenceViewer.vue'
import LoginView from './components/LoginView.vue'
/** 五个一级模块页面：左侧导航不再挂二级菜单，模块内部自持 Tab（设计 §1）。 */
import WorkbenchPage from './components/WorkbenchPage.vue'
import GeneratePage from './components/GeneratePage.vue'
import SearchPage from './components/SearchPage.vue'
import IntegrationPage from './components/IntegrationPage.vue'
import SystemPage from './components/SystemPage.vue'
import { KOS_SHELL } from './shell'
import type { KosShell } from './shell'
import {
  createKnowledgeBase, getKnowledgeBases, getOverview, getSources, getDocuments, getCards,
  getClaims, getPendingReviews, getOpenConflicts, getCapabilities, getAssessments,
  getIngestionRuns, getIngestionRun,
  uploadDocument, queryKnowledge, runAdjudication, reviewKnowledge,
  uploadDocuments, getBatchStatus, cancelBatch as cancelBatchRequest, retryBatch as retryBatchRequest,
  listBatches,
  resolveKnowledgeConflict, getConflictTargets, adjudicateConflict,
  getAclSubjects, getAclRoles, getAclAudits, getAclChanges,
  changeResourceAcl, getKnowledgeObjectDetail, getEvidenceDetail,
  getContentUnitDetail, getQueryHistory, getTrace, getIntegrationCallDetail, unwrap,
  getSourcePreview, getEnvironment, getOutboxStats, getIndexDrift,
  getDashboardSummary, getDashboardLifecycle, getDashboardGovernance,
} from './api/knowledge'
import {
  defaultLeafOf, leavesOfSection, locate, normalizeViewKey, visibleLeaves, visibleSections,
} from './navigation'
import type { NavLeaf, NavSection } from './navigation'
import { PERM, ROLE_BRIEF, ROLE_HOME, hasPermission } from './permissions'
import type { UserRoleValue } from './types'
import {
  AUTH_EXPIRED_EVENT, getAuthStatus, getMe, getToken, listGroups, logout, setToken,
} from './api/auth'
import { userRoleLabel, visibilityLabel } from './types'
import { errorMessage } from './utils/feedback'
/**
 * 当前视图：取 IA 二级入口 key（见 navigation.ts），旧的 tab 值会自动归一化。
 * 初值留空——登录前拿不到角色权限，不能沿用上次会话的页面（可能是当前角色无权访问的页面），
 * 真正取值在认证探测完成后的 resolveInitialView()。
 */
const active=ref<string>('')
const knowledgeBaseId=ref(Number(localStorage.getItem('llmkb.kbId')||1));
const showBases=ref(false);
const bases=ref<KnowledgeBase[]>([])
const file=ref<File|null>(null);
const authority=ref(.85);
const sourcePermissionTags=ref('')
const aclSubjects=ref<AclSubject[]>([]),aclRoles=ref<AclRole[]>([]),aclAudits=ref<AclAudit[]>([]),aclChanges=ref<AclChange[]>([])
const overview=ref<Overview>({
  knowledgeBaseId:1, sourceCount:0, documentCount:0, chunkCount:0, cardCount:0,
  effectiveCardCount:0, claimCount:0, pendingReviewCount:0, openConflictCount:0, assessmentCount:0,
})
const sources=ref<KnowledgeSource[]>([]),
    documents=ref<KnowledgeDocument[]>([]),
    cards=ref<KnowledgeCard[]>([]),
    claims=ref<Claim[]>([]),
    pendingReviews=ref<ReviewRow[]>([]),
    conflicts=ref<ConflictRow[]>([]),
    assessments=ref<AssessmentRow[]>([])
/** 裁决工作台选中态：冲突选中的是双方对照，待审核选中的是对象详情与证据链。 */
const selectedConflictId=ref<number|null>(null),
    conflictDetail=ref<ConflictDetail|null>(null),
    conflictDetailLoading=ref(false),
    selectedReview=ref<ReviewRow|null>(null),
    reviewDetail=ref<Record<string,unknown>|null>(null)
const queryHistory=ref<QueryHistoryItem[]>([])
const queryTrace=ref<QueryTrace|null>(null), traceVisible=ref(false), traceLoading=ref(false)
const apiCallDetail=ref<IntegrationCallDetail|null>(null), apiCallVisible=ref(false), apiCallLoading=ref(false)
const evidenceDetail=ref<EvidenceDetail|null>(null), evidenceVisible=ref(false), evidenceLoading=ref(false)
const detailVisible=ref(false), detailLoading=ref(false), detailMode=ref<'CARD'|'CLAIM'|'CHUNK'>('CARD')
const detailCode=ref(''), knowledgeDetail=ref<KnowledgeObjectDetail|null>(null)
const capabilities=ref<Capabilities|null>(null),
    q=ref('知识为什么需要裁决？'),
    result=ref<QueryResult|null>(null),
    graphCode=ref('')
const runs=ref<IngestionRun[]>([]),
    runDetail=ref<IngestionRunDetail|null>(null),
    selectedRunId=ref<number|null>(null)
/** 工作台三个投影：KPI / 生命周期 / 治理待办，与通用 overview 并存（设计 §3）。 */
const dashboard=ref<DashboardSummary|null>(null),
    lifecycle=ref<DashboardLifecycle|null>(null),
    governance=ref<DashboardGovernance|null>(null)
/** 资产浏览的当前对象类型：工作台 KPI 下钻时带入。 */
const assetType=ref<AssetType>('card')
/** 顶栏全局搜索关键词与任务中心抽屉。 */
const globalKeyword=ref(''), taskCenterVisible=ref(false)
/** 失败任务数：顶栏任务入口的红点，读取已加载的任务列表。 */
const failedTaskCount=computed(()=>
  runs.value.filter(v=>String(v.status||'').toUpperCase()==='FAILED').length)
/** 源文件预览：选中资料后单独加载，失败只在页面内提示，不打断摄入流程。 */
const preview=ref<SourcePreview|null>(null),
    previewLoading=ref(false),
    previewError=ref(''),
    previewId=ref<number|null>(null)
/** 摄入实时跟踪：上传是一个长请求，轮询让 Source→Parse→Persist→Extract 的日志逐条展开。 */
const ingestLive=ref(false), ingestStage=ref(''), ingestMessage=ref('')
let liveTimer:ReturnType<typeof setInterval>|undefined
let liveBaseline=0, liveBusy=false
const uploading=ref(false),
    asking=ref(false),
    adjudicating=ref(false)
/**
 * 上传复位令牌：一次摄入结束后自增，摄入面板收到后清空 el-upload 内部文件列表。
 * 面板已下沉到模块页面内部，App 拿不到组件实例，改用令牌广播（limit=1 时否则无法继续选文件）。
 */
const uploadReset=ref(0)
/** 卡片/断言列表的加载与错误状态，交由面板统一样式呈现。 */
const listLoading=ref(false), listError=ref('')
/** 最近一次操作失败信息：错误需要在页面上停留可见，而不是一闪而过的 toast。 */
const failure=ref('')
const activeBase=computed(()=>bases.value.find(v=>v.id===knowledgeBaseId.value))
/** 顶部上下文条：环境一次加载即可；索引状态随知识库与刷新动作更新。 */
const environment=ref<Record<string,unknown>|null>(null)
const environmentLabel=computed(()=>{
  const raw=environment.value
  if(!raw)return ''
  const env=String(raw.environment||raw.profile||'')
  const version=String(raw.version||'')
  return version?`${env} · ${version}`:env
})
const indexState=ref<IndexState>({drift:0,pending:0,dead:0,loading:false})
/**
 * 批量接入状态：单文件上传是同步请求（等一次返回结果），
 * 多文件走批次——后端异步执行，这里持有批次视图并轮询到终态，
 * 期间批次卡片显示每个文件的成功/失败，避免「点了没反应」的黑盒。
 */
const batchStatus=ref<BatchIngestStatus|null>(null)
let batchTimer:ReturnType<typeof setInterval>|undefined
const batchRunning=computed(()=>batchStatus.value?.status==='RUNNING')
const contextLoading=ref(false)
const historyLoading=ref(false)
async function loadEnvironment(){
  try{environment.value=unwrap(await getEnvironment(),null)}catch{environment.value=null}
}
/** 索引状态：漂移来自一致性检测，积压与死信来自 Outbox 统计，任一缺失都不阻塞页面。 */
async function loadSpaceContext(){
  contextLoading.value=true
  indexState.value={...indexState.value,loading:true}
  try{
    const [stats,drift]=await Promise.all([
      getOutboxStats(knowledgeBaseId.value).catch(()=>null),
      getIndexDrift(knowledgeBaseId.value).catch(()=>null),
    ])
    const s=stats?unwrap(stats,{} as OutboxStats):{}
    const d=drift?unwrap(drift,{} as DriftResult):{}
    indexState.value={
      drift:Number(d.driftCount??d.drifted??d.driftTotal??0)||0,
      pending:Number(s.pending??0)||0,
      dead:Number(s.dead??0)||0,
      loading:false,
    }
  }finally{
    contextLoading.value=false
    indexState.value={...indexState.value,loading:false}
  }
}
/** 登录态：后端关闭认证或允许匿名访问时 me.user 为 null，页面照常可用。 */
const authStatus=ref<AuthStatus|null>(null)
const me=ref<AuthMe|null>(null)
const authReady=ref(false)
const groups=ref<UserGroupRow[]>([])
const needLogin=computed(()=>authReady.value&&!!authStatus.value?.enabled&&!me.value?.user)
const loginHint=computed(()=>{
  if(authStatus.value&&!authStatus.value.initialized)return '系统尚未创建管理员账号：请查看后端启动日志中的初始密码'
  return '登录后可访问自己创建、所属用户组与公开的知识库'
})
const avatarText=computed(()=>
  String(me.value?.user?.displayName||activeBase.value?.name||'知识库').trim().slice(0,1)||'K')
/**
 * 权限判定：后端按角色下发权限码，未开启认证时 me 为空，此时按全权限处理，
 * 与后端 PermissionInterceptor 对空 AuthContext 的放行保持一致。
 */
const can=(code:string)=>hasPermission(me.value?.permissions,code)
/** 一级导航固定为 IA 的五个入口，二级入口全部下沉到模块页面内部的 Tab。 */
const navSections=computed(()=>visibleSections(can))
const activeLocation=computed(()=>locate(active.value))
const activeSection=computed(()=>activeLocation.value?.section.key||'workbench')
/** 属于知识空间的页面才展示「空间 + 环境 + 索引状态」上下文条。 */
const scopedView=computed(()=>!!activeLocation.value?.section.scoped)
/** 一级菜单收起二级后，把该模块内所有待处理计数汇总到一级入口上。 */
function sectionBadge(section:NavSection):string{
  let total=0
  for(const leaf of leavesOf(section)){
    const value=leafBadge(leaf)
    if(typeof value==='number')total+=value
  }
  return total?String(total):''
}
/** 模块页内 Tab：按权限裁剪，避免普通账号看到无权操作的入口。 */
function leavesOf(section:NavSection){
  return visibleLeaves(section,can)
}
function enterSection(section:NavSection){
  active.value=defaultLeafOf(section,can).key
}
/** 模块页面回传的视图 key：规划中与无权访问的入口不进入，避免落到未实现页面。 */
function selectKey(key:string){
  const leaf=locate(key)?.leaf
  if(!leaf||leaf.planned)return
  if(!canLeaf(leaf))return
  active.value=key
}
function canLeaf(leaf:NavLeaf){
  return !leaf.permission||can(leaf.permission)
}
/** 页面内的快捷跳转（KPI 下钻、治理待办、质量页）先用它裁剪，无权的目标置灰而不是点了才报错。 */
function canNavigate(key:string){
  const leaf=locate(normalizeViewKey(key))?.leaf
  return !leaf||canLeaf(leaf)
}
/** 顶栏任务入口：只有参与知识生产或平台运维的角色需要看任务。 */
const canSeeTasks=computed(()=>
  can(PERM.GENERATE_IMPORT)||can(PERM.GENERATE_DERIVE)||can(PERM.SYSTEM_TASK))
/** 顶栏角色名：优先用后端下发的中文名，避免出现角色码。 */
const roleName=computed(()=>
  me.value?.roleLabel||me.value?.user?.roleLabel||userRoleLabel(me.value?.user?.role))
const roleBrief=computed(()=>{
  const role=String(me.value?.user?.role||'') as UserRoleValue
  return ROLE_BRIEF[role]||''
})
/** 角标只用于需要人工介入的入口：待审核与未解决冲突。 */
function leafBadge(leaf:NavLeaf):number|string{
  if(leaf.badge==='pendingReview')return pendingReviews.value.length||''
  if(leaf.badge==='openConflict')return conflicts.value.length||''
  return ''
}
/**
 * 统一跳转入口：所有改 active 的地方都走这里，无权访问的页面直接拦下并提示，
 * 避免进入页面后每个数据接口都返回 403 刷屏。
 */
function goto(key:string,silent=false):boolean{
  const target=normalizeViewKey(key)
  const leaf=locate(target)?.leaf
  if(leaf&&!canLeaf(leaf)){
    if(!silent)ElMessage.warning(`当前角色（${roleName.value}）无权访问「${leaf.label}」`)
    return false
  }
  active.value=target
  return true
}
const navigate=(tab:string)=>{goto(tab)}
/** 工作台 KPI 下钻：按对象类型进入资产浏览（设计 §3.1）。 */
function openAssets(type:AssetType){
if(!goto('workbench.assets',true))return
assetType.value=type}
/** 任务中心 / 创建首页点击任务：选中该次运行并进入解析任务页。 */
function openRun(runId:number){
if(!goto('create.jobs',true))return
selectedRunId.value=runId;
void selectRun(runId)}
/** 顶栏全局搜索：进入搜索页并直接执行一次检索，减少一次点击。 */
async function globalSearch(){
  const keyword=globalKeyword.value.trim()
  if(!keyword)return
  if(!goto('search.query',true))return
  q.value=keyword
  await ask()
}
function pick(f:UploadFile){file.value=f.raw||null} function remove(){file.value=null}
/**
 * 全局失败处理：toast 之外把完整文案（含 traceId）留在页面顶部，
 * 便于用户复制给运维。全站统一走 utils/feedback 的 errorMessage，
 * 避免各处重复解析信封导致有的地方看得到后端原因、有的看不到。
 */
function err(e:unknown, fallback='操作失败'){
  failure.value=errorMessage(e,fallback)
  ElMessage.error(failure.value)
}
async function loadBases(){bases.value=unwrap(await getKnowledgeBases(),[]);
if(!bases.value.some(v=>v.id===knowledgeBaseId.value)&&bases.value.length)knowledgeBaseId.value=bases.value[0].id}
async function createBase(payload:{name:string;description:string;visibility:string;ownerGroupId:number|null}){
try{
  const base=unwrap(
    await createKnowledgeBase(payload.name,payload.description,payload.visibility,payload.ownerGroupId),
    null as unknown as KnowledgeBase)
await loadBases();
selectBase(base.id);
showBases.value=false;
ElMessage.success('知识库已创建')}catch(e){err(e)}}
function selectBase(id:number){knowledgeBaseId.value=id;
showBases.value=false}
async function loadAcl(){
  const r=await Promise.allSettled([
    getAclSubjects(), getAclRoles(), getAclAudits(knowledgeBaseId.value),
    getAclChanges(knowledgeBaseId.value),
  ])
  if(r[0].status==='fulfilled')aclSubjects.value=unwrap(r[0].value,[])
  if(r[1].status==='fulfilled')aclRoles.value=unwrap(r[1].value,[])
  if(r[2].status==='fulfilled')aclAudits.value=unwrap(r[2].value,[])
  if(r[3].status==='fulfilled')aclChanges.value=unwrap(r[3].value,[])
}
async function changeAcl(p:{
  resourceType:string; resourceCode:string; permissionTags:string[]; reason:string
}){
  try{
    unwrap(await changeResourceAcl(
      knowledgeBaseId.value,p.resourceType,p.resourceCode,p.permissionTags,p.reason,
    ),{})
    ElMessage.success('ACL 已保存并写入传播队列')
    await loadAcl()
  }catch(e){err(e)}
}
async function loadOverview(){const[o,c]=await Promise.allSettled([getOverview(knowledgeBaseId.value),getCapabilities()]);
if(o.status==='fulfilled')overview.value=unwrap(o.value,overview.value);
if(c.status==='fulfilled')capabilities.value=unwrap(c.value,null)}
/** 工作台聚合：三块数据相互独立，任一失败只影响对应卡片，不阻塞整页。 */
async function loadDashboard(){
  const[s,l,g]=await Promise.allSettled([
    getDashboardSummary(knowledgeBaseId.value),
    getDashboardLifecycle(knowledgeBaseId.value),
    getDashboardGovernance(knowledgeBaseId.value),
  ]);
if(s.status==='fulfilled')dashboard.value=unwrap(s.value,null as DashboardSummary|null);
if(l.status==='fulfilled')lifecycle.value=unwrap(l.value,null as DashboardLifecycle|null);
if(g.status==='fulfilled')governance.value=unwrap(g.value,null as DashboardGovernance|null)}
/** 任务列表：顶栏红点需要失败任务数，因此登录后即加载一次。 */
async function loadTasks(){
  try{runs.value=unwrap(await getIngestionRuns(knowledgeBaseId.value),runs.value)}catch{/* 任务列表失败不影响主流程 */}}
async function loadData(){const r=await Promise.allSettled([getSources(knowledgeBaseId.value),
    getDocuments(knowledgeBaseId.value),
    getIngestionRuns(knowledgeBaseId.value)]);
if(r[0].status==='fulfilled')sources.value=unwrap(r[0].value,
    []);
if(r[1].status==='fulfilled')documents.value=unwrap(r[1].value,
    []);
if(r[2].status==='fulfilled'){runs.value=unwrap(r[2].value,[]);
if(runs.value.length&&!selectedRunId.value)await selectRun(runs.value[0].id)}
void resumeBatch()}
/**
 * 刷新后接续仍在跑的批次：批量会持续好几分钟，
 * 重载页面后进度若凭空消失，用户大概率会再提交一次同一批文件。
 */
async function resumeBatch(){
  if(batchStatus.value||batchTimer!==undefined)return
  try{
    const running=unwrap(await listBatches(knowledgeBaseId.value,5),[]).find(item=>item.status==='RUNNING')
    if(!running)return
    batchStatus.value=running
    startBatchWatch()
  }catch{/* 恢复监视只是增强：拿不到批次回归普通使用，不该弹错打扰 */}
}
async function selectRun(id:number){selectedRunId.value=id;
try{runDetail.value=unwrap(await getIngestionRun(id),null)}catch(e){err(e)}}
/** 文档重跑结束后：状态、Chunk revision 与运行日志都已变化，一并刷新。 */
async function reloadAfterRetry(){try{await Promise.all([loadData(),loadOverview()])}catch(e){err(e,'刷新失败')}}
/** 拉取最新一次运行的阶段与事件；基线之前的运行属于历史，不参与跟踪。 */
async function pollLiveRun(){
  const list=unwrap(await getIngestionRuns(knowledgeBaseId.value),runs.value)
  runs.value=list
  const run=list.find(v=>v.id>liveBaseline)
  if(!run)return
  selectedRunId.value=run.id
  const detail=unwrap(await getIngestionRun(run.id),null)
  if(!detail)return
  runDetail.value=detail
  ingestStage.value=detail.currentStage||''
  const last=detail.events[detail.events.length-1]
  ingestMessage.value=last?String(last.message||last.eventType):''
}
function stopLive(){
  if(liveTimer!==undefined){clearInterval(liveTimer);
liveTimer=undefined}
  liveBusy=false
  ingestLive.value=false
}
async function startLive(){
  stopLive()
  const before=unwrap(await getIngestionRuns(knowledgeBaseId.value),[])
  liveBaseline=before.length?Math.max(...before.map(v=>v.id)):0
  ingestLive.value=true
  ingestStage.value=''
  ingestMessage.value=''
  liveTimer=setInterval(()=>{if(liveBusy)return;
liveBusy=true;
void pollLiveRun().catch(()=>undefined).finally(()=>{liveBusy=false})},500)
}
async function previewSource(id:number){previewId.value=id;
preview.value=null;
previewError.value='';
if(!id)return;
previewLoading.value=true;
try{preview.value=unwrap(await getSourcePreview(knowledgeBaseId.value,id),null)}
catch(e){previewError.value=errorMessage(e,'源文件预览加载失败')}
finally{previewLoading.value=false}}
function resetPreview(){previewId.value=null;
preview.value=null;
previewError.value=''}
async function loadReview(){
const r=await Promise.allSettled([
  getPendingReviews(knowledgeBaseId.value), getOpenConflicts(knowledgeBaseId.value),
  getAssessments(knowledgeBaseId.value),
]);
if(r[0].status==='fulfilled')pendingReviews.value=unwrap(r[0].value,[]);
if(r[1].status==='fulfilled')conflicts.value=unwrap(r[1].value,[]);
if(r[2].status==='fulfilled')assessments.value=unwrap(r[2].value,[])}
/** 卡片与断言列表改由资产浏览面板按需加载，这里只保留失败提示的写入口。 */
function loadList(){listError.value=''}
/** 各视图需要的后台数据：进入模块 Tab 时按需加载，避免一次性拉取全站数据。 */
const DATA_KEYS=['create.overview','create.import','create.connector','create.jobs']
const REVIEW_KEYS=['create.judgment']
const QUERY_KEYS=['search.query','search.test','search.trace']
const ACL_KEYS=['system.acl','system.audit']
async function loadActive(){try{
if(active.value==='workbench')await Promise.all([loadOverview(),loadDashboard()]);
else if(DATA_KEYS.includes(active.value))await loadData();
else if(REVIEW_KEYS.includes(active.value))await loadReview();
else if(QUERY_KEYS.includes(active.value))await loadQueryHistory();
else if(ACL_KEYS.includes(active.value))await loadAcl()}catch(e){err(e)}}
async function upload(){if(!file.value)return ElMessage.warning('请先选择文件');
uploading.value=true;
failure.value='';
// 上传尚未返回 runId，先记录基线运行，随后跟踪本次新建的那一次。
await startLive();
try{const d=unwrap(await uploadDocument(knowledgeBaseId.value,
    file.value,
    authority.value, sourcePermissionTags.value.split(',').map(x=>x.trim()).filter(Boolean)),
    {});
ElMessage.success(`处理完成：${Number(d.chunkCount||0)} 个内容块，可继续上传下一个文件`);
// 复位上传区：清空 el-upload 内部文件列表与已选文件，否则 limit=1 会占着名额无法再选。
file.value=null;
uploadReset.value++;
selectedRunId.value=Number(d.runId||0)||null;
await Promise.all([loadData(),loadOverview()]);
if(selectedRunId.value)await selectRun(selectedRunId.value)}catch(e){err(e)}
finally{await pollLiveRun().catch(()=>undefined);
stopLive();
uploading.value=false}}
/**
 * 批量上传：一次提交多个文件，后台按队列解析。
 * 单文件仍走上面的同步路径——实时阶段跟踪依赖那一次运行的 runId，
 * 只有多文件才需要批次（若干个文件一个个串行等待会超出请求超时）。
 */
async function uploadFiles(files:File[]){
  const picked=(files||[]).filter(Boolean)
  if(!picked.length)return ElMessage.warning('请先选择文件')
  if(picked.length===1){file.value=picked[0];await upload();return}
  uploading.value=true
  failure.value=''
  try{
    batchStatus.value=unwrap(await uploadDocuments(knowledgeBaseId.value,picked,
      authority.value,sourcePermissionTags.value.split(',').map(x=>x.trim()).filter(Boolean)),null)
    ElMessage.success(`已提交 ${batchStatus.value?.fileCount||picked.length} 个文件，正在后台按队列解析`)
    uploadReset.value++
    startBatchWatch()
  }catch(e){err(e)}
  finally{uploading.value=false}
}
function startBatchWatch(){
  stopBatchWatch()
  if(!batchStatus.value)return
  batchTimer=setInterval(()=>{void watchBatch()},2000)
  void watchBatch()
}
function stopBatchWatch(){
  if(batchTimer!==undefined){clearInterval(batchTimer);batchTimer=undefined}
}
/** 轮询批次状态：终态到达即停止，随后刷新来源/文档/统计。 */
async function watchBatch(){
  if(!batchStatus.value)return stopBatchWatch()
  try{
    const data=unwrap(await getBatchStatus(batchStatus.value.batchCode),null)
    if(!data)return
    batchStatus.value=data
    if(data.status!=='RUNNING')await settleBatch(data)
  }catch(e){stopBatchWatch();err(e,'批量进度查询失败')}
}
async function settleBatch(data:BatchIngestStatus){
  stopBatchWatch()
  // 列表刷新失败不该掩盖批量结果：进度轮询已给出结论，这里只补足列表展现。
  await Promise.all([loadData(),loadOverview()]).catch(()=>undefined)
  if(data.failedCount){
    failure.value=`批量接入完成：成功 ${data.succeededCount} 个，失败 ${data.failedCount} 个`
      + `${data.canceledCount?`，取消 ${data.canceledCount} 个`:''}；失败项可在批次卡片上一键重跑。`
    ElMessage.warning(`批量完成，${data.failedCount} 个文件解析失败`)
  }else{
    ElMessage.success(`批量完成：${data.succeededCount||data.fileCount} 个文件已生成知识`)
  }
}
/** 取消批次：排队的文件不再启动，已在解析的文件会跑完自己的事务。 */
async function cancelBatch(code:string){
  try{
    await cancelBatchRequest(code)
    batchStatus.value=unwrap(await getBatchStatus(code),batchStatus.value)
  }catch(e){err(e,'取消批次失败')}
}
/** 重跑批次内的失败文件：成功文件的 Chunk 与引用保持原样。 */
async function retryBatch(code:string){
  try{
    await retryBatchRequest(code)
    batchStatus.value=unwrap(await getBatchStatus(code),batchStatus.value)
    startBatchWatch()
  }catch(e){err(e,'重跑失败文件失败')}
}
async function ask(){if(!q.value.trim())return;
asking.value=true;
try{result.value=unwrap(await queryKnowledge(knowledgeBaseId.value,q.value),null);await loadQueryHistory()}catch(e){err(e)}finally{asking.value=false}}
async function openEvidence(evidenceCode:string){
evidenceVisible.value=true;evidenceLoading.value=true
try{evidenceDetail.value=unwrap(await getEvidenceDetail(knowledgeBaseId.value,evidenceCode),null)}
catch(e){err(e)}finally{evidenceLoading.value=false}}
async function openCitation(citation:QueryCitation){
if(citation.evidenceCode){await openEvidence(citation.evidenceCode);return}
if(citation.contentUnitId){
evidenceVisible.value=true;evidenceLoading.value=true
try{evidenceDetail.value=unwrap(await getContentUnitDetail(knowledgeBaseId.value,citation.contentUnitId),null)}
catch(e){err(e)}finally{evidenceLoading.value=false}
return}
const type=String(citation.objectType||'').toUpperCase()
const code=String(citation.objectCode||citation.objectId||'')
if((type==='CARD'||type==='CLAIM'||type==='CHUNK')&&code){
  await openDetail(type as 'CARD'|'CLAIM'|'CHUNK',code);return}
ElMessage.info('该引用暂未返回证据/对象定位标识')
}
async function loadQueryHistory(){
historyLoading.value=true
try{queryHistory.value=unwrap(await getQueryHistory(knowledgeBaseId.value),[])}catch(e){err(e)}
finally{historyLoading.value=false}}
/** 版本治理页点开详情：版本快照只带类型与对象码，详情仍走统一的详情抽屉。 */
function openDetailFromVersion(type:string,code:string){
  const normalized=String(type||'').toUpperCase()
  if((normalized==='CARD'||normalized==='CLAIM')&&code)openDetail(normalized as 'CARD'|'CLAIM',code)
}
async function openTrace(qid:string){traceVisible.value=true;traceLoading.value=true;try{
queryTrace.value=unwrap(await getTrace(qid),null)}catch(e){err(e)}finally{traceLoading.value=false}}
/** 调用日志的 traceId 由网关下发，落在调用审计里；它和问答链路的 queryId 是两套 id，
 *  所以这里查的是集成侧的单次调用明细，而不是检索链路。 */
async function openCallTrace(traceId:string){apiCallVisible.value=true;apiCallLoading.value=true;try{
apiCallDetail.value=unwrap(await getIntegrationCallDetail(traceId),null)}catch(e){err(e)}finally{apiCallLoading.value=false}}
async function openDetail(mode:'CARD'|'CLAIM'|'CHUNK',code:string){detailMode.value=mode;detailCode.value=code;
detailVisible.value=true;detailLoading.value=true;try{
knowledgeDetail.value=unwrap(await getKnowledgeObjectDetail(knowledgeBaseId.value,mode,code),null)
}catch(e){err(e)}finally{detailLoading.value=false}}
async function runIntelligentAdjudication(){adjudicating.value=true;
try{const d=unwrap(await runAdjudication(knowledgeBaseId.value),
    {});
ElMessage.success(`裁决完成：自动批准 ${Number(d.autoApproved||0)}，人工复核 ${Number(d.requiresHumanReview||0)}`);
await Promise.all([loadReview(),loadOverview()])}catch(e){err(e)}finally{adjudicating.value=false}}
async function review(row:ReviewRow, decision:string, comment=''){
try{await reviewKnowledge(row,decision,comment);
ElMessage.success('裁决已提交');
selectedReview.value=null;reviewDetail.value=null;
await Promise.all([loadReview(),loadOverview()])}catch(e){err(e)}}
async function resolveConflict(row:ConflictRow,
    status:string){try{await resolveKnowledgeConflict(row.id,status);
await loadReview()}catch(e){err(e)}}
/** 选中冲突：拉取双方的值 / 条件 / 版本 / 置信度与各自证据，裁决才有依据（设计 §4.4）。 */
async function selectConflict(id:number){
selectedConflictId.value=id;selectedReview.value=null;reviewDetail.value=null
conflictDetailLoading.value=true
try{conflictDetail.value=unwrap(await getConflictTargets(id),null)}
catch(e){err(e);conflictDetail.value=null}finally{conflictDetailLoading.value=false}}
/** 选中待审核对象：详情接口带 Evidence 与历史裁决记录，右栏据此展示原文与权威度。 */
async function selectReview(row:ReviewRow){
selectedReview.value=row;selectedConflictId.value=null;conflictDetail.value=null
const type=String(row.objectType||'').toUpperCase()
const code=String(row.objectCode||'')
if((type==='CARD'||type==='CLAIM')&&code){
  try{reviewDetail.value=unwrap(await getKnowledgeObjectDetail(knowledgeBaseId.value,type as 'CARD'|'CLAIM',code),null) as unknown as Record<string,unknown>}
  catch(e){err(e);reviewDetail.value=null}
  return}
reviewDetail.value=null}
async function adjudicateConflicted(id:number, decision:string, comment=''){
try{await adjudicateConflict(id,decision,comment);
ElMessage.success('冲突裁决已提交');
selectedConflictId.value=null;conflictDetail.value=null;
await Promise.all([loadReview(),loadOverview()])}catch(e){err(e)}}
/** 打开图谱：V2.1 基线变更②——知识图谱归入知识检索，起点编码写入 graphCode 后由图谱面板加载邻域。 */
function openGraph(code:string){graphCode.value=code;
active.value='search.graph'}
/** 用户组列表：知识库可见范围选择「用户组」时需要，非管理员也加载（创建者可选自己所在组）。 */
async function loadGroups(){
  try{groups.value=unwrap(await listGroups(),[])}catch{groups.value=[]}
}
/** 启动时的认证探测：先确认是否开启认证，再决定是显示登录页还是直接进入工作台。 */
async function bootstrapAuth(){
  try{
    authStatus.value=unwrap(await getAuthStatus(),null)
    if(authStatus.value?.enabled&&getToken()){
      me.value=unwrap(await getMe(),null)
      if(me.value?.user)await loadGroups()
    }
  }catch(e){
    setToken('')
    me.value=null
  }finally{
    authReady.value=true
  }
}
async function onLogin(){
  try{me.value=unwrap(await getMe(),null)}catch(e){err(e)}
  if(!me.value?.user)return
  await loadGroups()
  // 拿到角色权限后再决定落地页，登录前不能沿用旧的会话位置。
  resolveInitialView()
  await initialLoad()
}
async function doLogout(){
  try{await logout()}catch{/* 会话可能已过期，本地状态仍需清理 */}
  setToken('')
  me.value=null
}
/**
 * 模块页面统一从这里取动作：左侧导航收起二级菜单后，
 * 跳转详情、打开 Trace、执行裁决等跨模块能力不必在页面间层层透传。
 */
const shell:KosShell={
  navigate, openAssets, openGraph, openRun, openTrace, openCallTrace, openDetail, openEvidence, openCitation,
  can, canNavigate, tabs:key=>leavesOfSection(key,can),
  upload, pick, remove,
  uploadFiles:files=>{void uploadFiles(files)},
  cancelBatch:code=>{void cancelBatch(code)},
  retryBatch:code=>{void retryBatch(code)},
  selectRun:id=>{void selectRun(id)},
  refresh:()=>{void loadOverview()},
  reloadAfterRetry:()=>{void reloadAfterRetry()},
  adjudicate:()=>{void runIntelligentAdjudication()},
  review:(row,decision,comment)=>{void review(row,decision,comment)},
  resolveConflict:(row,status)=>{void resolveConflict(row,status)},
  selectConflict:id=>{void selectConflict(id)},
  selectReview:row=>{void selectReview(row)},
  adjudicateConflict:(id,decision,comment)=>{void adjudicateConflicted(id,decision,comment)},
  previewSource:id=>{void previewSource(id)},
}
provide(KOS_SHELL, shell)

/** 任意请求返回 401 时回到登录页，避免半登录状态下反复报错。 */
function onAuthExpired(){
  me.value=null
  authReady.value=true
}
async function initialLoad(){
  try{await loadBases();
  await Promise.all([loadOverview(),loadReview(),loadDashboard(),loadTasks()]);
  if(active.value!=='workbench')await loadActive();
  void loadEnvironment();
  void loadSpaceContext()}catch(e){err(e)}
}
watch(active,
    v=>{if(!v)return
localStorage.setItem('kos.activeView',v);
if(scopedView.value)void loadSpaceContext();
void loadActive()});
watch(knowledgeBaseId,
    v=>{localStorage.setItem('llmkb.kbId',String(v));
void loadDashboard();
void loadTasks();
result.value=null;
graphCode.value='';
runDetail.value=null;
selectedRunId.value=null;
resetPreview();
stopLive();
void loadSpaceContext();
void loadActive()})
onUnmounted(()=>{
  stopLive()
  stopBatchWatch()
  window.removeEventListener(AUTH_EXPIRED_EVENT,onAuthExpired)
})
/**
 * 决定登录后的第一个页面：沿用上次访问的位置，但它可能已无权访问（换账号或角色被调整），
 * 这时回到该角色的默认工作区；没有历史位置时直接进角色落地页。
 * 必须在拿到 /auth/me 的权限之后执行，否则会按「未知角色」渲染出无权页面。
 */
function resolveInitialView(){
  const remembered=localStorage.getItem('kos.activeView')||localStorage.getItem('llmkb.activeTab')
  const leaf=remembered?locate(normalizeViewKey(remembered))?.leaf:undefined
  if(leaf&&canLeaf(leaf)){active.value=leaf.key;return}
  const home=me.value?.homeView||'workbench'
  active.value=locate(home)?.leaf?home:'workbench'
}
onMounted(async()=>{
  window.addEventListener(AUTH_EXPIRED_EVENT,onAuthExpired)
  try{await bootstrapAuth()}catch(e){authReady.value=true}
  if(needLogin.value)return
  resolveInitialView()
  await initialLoad()
})
</script>
<style src="./app.css"></style>
