<template>
<!-- 认证探测未完成时不渲染业务页面：此时还不知道角色权限，
     若按上次会话的页面直接渲染，无权账号会先发出被拒的请求（例如用户列表 403）。 -->
<div v-if="!authReady" class="flex h-screen items-center justify-center text-[13px] text-ink-500">
  正在进入工作台…
</div>
<LoginView v-else-if="needLogin" :hint="loginHint" @success="onLogin"/>
<div v-else class="kos-shell">
<AppHeader :base-name="activeBase?.name" :visibility="visibilityLabel(activeBase?.visibility)"
  :show-base-switch="active==='home'"
  v-model:keyword="globalKeyword" :avatar="avatarText" :display-name="me?.user?.displayName||me?.user?.username"
  :role-title="roleTitle" :logged-in="!!me?.user" :role-accent="roleAccent"
  @switch-base="showBases=true" @search="globalSearch" @logout="doLogout"/>
<div class="kos-body">
<!-- 设计 §1 / §2.2：左侧只放业务一级入口，且不挂任何二级菜单；
     Chunk、Assertion、Graph、Evaluation、凭证等全部进入模块页面内部的 Tab、卡片与抽屉。
     一级入口按业务域分组：工作台（知识概览）/ 知识库（知识生成、知识检索）/ 系统集成（接口服务）。
     系统配置属于低频平台配置，收进侧栏底部的用户工具区（设置图标），不再占用业务导航。 -->
<AppSidebar :groups="navGroups" :active-section="activeSection" :badge="sectionBadge"
  :role-brief="me?.user?roleBrief:''" :role-name="me?.user?roleName:''"
  :user-name="me?.user?(me.user.displayName||me.user.username):''"
  :avatar="avatarText" :role-accent="roleAccent" :can-system="canSystem"
  :actions="roleQuickActions" :logged-in="!!me?.user"
  :system-active="activeSection==='system'" :system-hint="systemHint"
  :system-leaves="shell.tabs('system')" :system-leaf="active"
  :environment="environmentLabel" :version="APP_VERSION"
  @enter="enterSection" @enter-system="enterSystem" @go="key=>goto(key)" @logout="doLogout"/>
<main class="kos-main">
  <div v-if="failure" role="alert" aria-live="assertive" class="mb-3 flex items-start gap-3 rounded-lg border px-4 py-3" style="background:var(--danger-bg);border-color:var(--danger)">
    <span class="text-[13px] leading-5 text-[color:var(--danger)]">⚠</span>
    <div class="min-w-0 flex-1">
      <b class="text-[12px] text-[color:var(--danger)]">操作失败</b>
      <pre class="mt-1 whitespace-pre-wrap break-words font-sans text-[11px] leading-5 text-[color:var(--danger)]">{{failure}}</pre>
    </div>
    <button class="text-[12px] text-[color:var(--danger)] hover:text-[color:var(--danger)]" @click="failure=''">✕</button>
  </div>
  <!-- 主页面顶部的当前知识库栏：所有右侧主页面统一在最上方显示「当前知识库 + 环境 + 索引状态」，
       不再只限于需要知识库上下文的页面——停在哪个页面都能看到、也能一键切换知识库。
       例外是「知识仪表盘」：它是跨知识库的视角，页面本身就把可见的知识库全列了出来，
       顶部再挂一个"当前空间"既重复又会让人以为下面的数据只是这一个库的。 -->
  <SpaceContextBar
    v-if="active!=='home'"
    :base="activeBase" :environment="environmentLabel" :index="indexState" :loading="contextLoading"
    @switch="showBases=true" @refresh="loadSpaceContext"/>

  <!-- 角色信息（角色名 / 职责说明 / 常用入口）收在侧栏底部用户块里，点用户名展开查看与操作，
       主页面顶部不再叠加横幅，把首屏留给页面自己的内容。 -->

  <!-- 一级入口条：进入「知识概览」后由页面顶部横向切换
       知识仪表盘 / 知识库工作台 / 知识资产 / 知识版本 / 知识质量。
       统一放在主内容最上方，页面内部的欢迎区与标题都排在它下面，避免入口条随内容沉到下方。 -->
  <ModuleTabs
    v-if="activeSection==='workbench'" label="知识概览" :leaves="shell.tabs('workbench')" :active="active"
    @select="selectKey"/>

  <!-- 知识仪表盘：登录后的主页面（原型 dashboard）——数据总览 + 可访问的知识库。
       这一页是跨库视角，不放「当前空间」栏（见上方 SpaceContextBar 的 v-if）。 -->
  <HomeDashboard
    v-if="active==='home'"
    :bases="bases" :base-stats="baseStats" :recent-ids="recentIds"
    :loading="basesLoading||baseStatsLoading" :can-create="canCreateBase"
    @create="showBases=true" @open-bases="showBases=true" @open-base="openHomeBase"/>

  <!-- 设计 §1：五个一级页面各自内部用 Tab 组织能力，App 只负责选择模块页面。 -->
  <WorkbenchPage
    v-if="activeSection==='workbench'"
    :key="knowledgeBaseId"
    :active="active" :knowledge-base-id="knowledgeBaseId"
    :overview="overview" :capabilities="capabilities"
    :summary="dashboard" :lifecycle="lifecycle" :governance="governance"
    :asset-type="assetType" @update:asset-type="assetType=$event"
    @select="selectKey"/>

  <GeneratePage
    v-else-if="activeSection==='create'"
    :key="knowledgeBaseId"
    :active="active" :knowledge-base-id="knowledgeBaseId"
    :pending-reviews="pendingReviews" :conflicts="conflicts" :assessments="assessments"
    :adjudicating="adjudicating" :regenerating="regenerating"
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
    :key="knowledgeBaseId"
    :active="active" :knowledge-base-id="knowledgeBaseId"
    :default-query="activeBase?.defaultQuery||''"
    :query="q" @update:query="q=$event"
    :result="result" :asking="asking"
    :history="queryHistory" :history-loading="historyLoading"
    :graph-code="graphCode" @update:graph-code="graphCode=$event"
    :graph-relation-type="graphRelationType" :graph-focus-node="graphFocusNode"
    :answer-format="answerFormat" @update:answer-format="answerFormat=$event"
    :use-memory="useMemory" @update:use-memory="useMemory=$event"
    @select="selectKey" @ask="ask" @refresh-history="loadQueryHistory"
    @open-trace-center="openTraceCenter"/>

  <IntegrationPage
    v-else-if="activeSection==='integration'"
    :key="knowledgeBaseId"
    :active="active" :knowledge-base-id="knowledgeBaseId" :bases="bases"
    @select="selectKey"/>

  <SystemPage
    v-else
    :key="knowledgeBaseId"
    :active="active" :knowledge-base-id="knowledgeBaseId" :base-name="activeBase?.name||''"
    :bases="bases" :overview="overview"
    :acl-subjects="aclSubjects" :acl-roles="aclRoles" :acl-changes="aclChanges" :acl-audits="aclAudits"
    :change-total="changeTotal" :change-page="changePage" :change-size="changeSize"
    :audit-total="auditTotal" :audit-page="auditPage" :audit-size="auditSize"
    :can-manage-users="!!me?.canManageUsers"
    @select="selectKey" @acl-change="changeAcl" @acl-log-page="p=>onAclLogPage(p.which,p.page,p.size)"
    @select-base="selectBase" @create-base="showBases=true" @refresh="loadOverview"/>

  <!-- 版本与授权信息：所有主页面共用同一条底部说明，侧栏不再单独放版本号。 -->
  <AppFooter :product-version="APP_VERSION" :environment="environmentLabel"/>
</main>
</div>
<KnowledgeBaseDialog
  :visible="showBases" :bases="bases" :groups="groups" :active-id="knowledgeBaseId"
  :can-manage="canCreateBase" :current-user-id="me?.user?.id??null"
  @close="showBases=false" @select="selectBase" @create="createBase" @update="updateBase"/>
<TaskCenterDrawer v-model:visible="taskCenterVisible" :key="knowledgeBaseId" :knowledge-base-id="knowledgeBaseId"
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
import{computed,onMounted,onUnmounted,provide,reactive,ref,watch}from'vue'
import{ElMessage,type UploadFile}from'element-plus'
import type {
  AssessmentRow, Capabilities, ConflictRow,
  IngestionRun, IngestionRunDetail, KnowledgeBase, KnowledgeDocument,
  KnowledgeSource, Overview, AclSubject, AclRole, AclAudit, AclChange, AclLogPage, ConflictDetail,
  QueryCitation, QueryHistoryItem, QueryResult, QueryTrace, ReviewRow, EvidenceDetail,
  SourcePreview,
  KnowledgeObjectDetail, AuthMe, AuthStatus, UserGroupRow,
  IndexState, OutboxStats, DriftResult,
  DashboardSummary, DashboardLifecycle, DashboardGovernance,
  BatchIngestStatus, IntegrationCallDetail,
} from './types'
import type { AnswerFormat } from './api/contracts'
import type { KbScenarioProfile } from './types/knowledge'
/** 建库 / 改库提交体：基础信息 + 场景模板 + 该库的生成 / 衍生 / 查询配置。 */
type KbBasePayload={
  name:string;description:string;visibility:string;ownerGroupId:number|null
  scenario:string;defaultQuery:string;profile:KbScenarioProfile|null
}
import type { AssetType } from './components/AssetBrowserPanel.vue'
import TaskCenterDrawer from './components/TaskCenterDrawer.vue'
import SpaceContextBar from'./components/SpaceContextBar.vue';
import AppHeader from './components/AppHeader.vue'
import AppSidebar from './components/AppSidebar.vue'
/** 主内容底部：版本与授权信息统一显示区 */
import AppFooter from './components/AppFooter.vue'
import KnowledgeBaseDialog from './components/KnowledgeBaseDialog.vue'
import KnowledgeDetailWorkspace from './components/KnowledgeDetailWorkspace.vue'
import QueryTracePanel from './components/QueryTracePanel.vue'
import ApiCallTracePanel from './components/ApiCallTracePanel.vue'
import EvidenceViewer from './components/EvidenceViewer.vue'
import LoginView from './components/LoginView.vue'
/** 登录后的主页面（原型 dashboard）：作为「知识概览」模块的「首页」Tab 挂载。 */
import HomeDashboard from './components/HomeDashboard.vue'
/** 一级入口条：模块内二级 Tab 统一由外壳在最上方渲染。 */
import ModuleTabs from './components/ModuleTabs.vue'
/** 五个一级模块页面：左侧导航不再挂二级菜单，模块内部自持 Tab（设计 §1）。 */
import WorkbenchPage from './components/WorkbenchPage.vue'
import GeneratePage from './components/GeneratePage.vue'
import SearchPage from './components/SearchPage.vue'
import IntegrationPage from './components/IntegrationPage.vue'
import SystemPage from './components/SystemPage.vue'
import { KOS_SHELL } from './shell'
import type { GraphSeed, KosShell, RegenerationProgress } from './shell'
import {
  createKnowledgeBase, updateKnowledgeBase, getKnowledgeBases, getOverview, getSources, getDocuments,
  getPendingReviews, getOpenConflicts, getCapabilities, getAssessments,
  getIngestionRuns, getIngestionRun,
  uploadDocument, queryKnowledge, runAdjudication, reviewKnowledge, regenerateKnowledge,
  startRegeneration, getTaskDetail,
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
  NAV_SECTIONS, defaultLeafOf, isAdminView, leavesOfSection, locate, normalizeViewKey,
  visibleGroups, visibleLeaves,
} from './navigation'
import type { NavLeaf, NavSection } from './navigation'
import { PERM, hasPermission } from './permissions'
import { APP_VERSION } from './version'
import { roleExperience } from './role-experience'
import {
  AUTH_EXPIRED_EVENT, getAuthStatus, getMe, getToken, listGroups, logout, setToken,
} from './api/auth'
import { visibilityLabel } from './types'
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
/** 审计流水只增不减：审计与变更各自记住页码，翻页由 SystemPage 回传。 */
const auditPage=ref(1),auditSize=ref(20),auditTotal=ref(0)
const changePage=ref(1),changeSize=ref(20),changeTotal=ref(0)
const overview=ref<Overview>({
  knowledgeBaseId:1, sourceCount:0, documentCount:0, chunkCount:0, cardCount:0,
  effectiveCardCount:0, claimCount:0, pendingReviewCount:0, openConflictCount:0, assessmentCount:0,
})
const sources=ref<KnowledgeSource[]>([]),
    documents=ref<KnowledgeDocument[]>([]),
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
    /** 输入框初值留空：真正的问题由当前知识库的「默认问题」回填（见下方 watch）。
        在这里写死一句话，切到别的库时还会留着上一句，问出来的内容与这个库无关。 */
    q=ref(''),
    result=ref<QueryResult|null>(null),
    graphCode=ref(''),
    /** 图谱的附加查询条件：关系类型收敛边、聚焦节点指定看哪个节点，随 openGraph 一起下发。 */
    graphRelationType=ref(''), graphFocusNode=ref('')
/** 问答的返回格式：默认 / markdown / 纯文本 / html，随提问一起提交。
    默认取 markdown（服务端规范化后的正文 + 引用原文章节），与嵌入组件 public/embed/kos-chat.js
    的默认 answerFormat 保持一致 —— 两边取不同格式时，同一个问题会拿到两份不同的文本，
    排版再怎么对齐也还是「不一样」。 */
const answerFormat=ref<AnswerFormat>('markdown')
/** 是否把当前登录用户的记忆并入生成上下文。默认关：记忆会改变答案的侧重，不该悄悄生效。 */
const useMemory=ref(false)
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
 * 重新生成的进度：整库重跑是异步任务，这里保存任务中心回读的阶段与计数，
 * 生成概览与生成配置两处展示的都是这一份状态，不会出现两个页面进度不一致。
 */
const regeneration=reactive<RegenerationProgress>({
  running:false,taskCode:'',status:'',stage:'',progress:0,
  documentCount:0,processed:0,failed:0,skipped:0,cardCount:0,claimCount:0,
  currentDocument:'',error:'',
})
const regenerating=computed(()=>regeneration.running)
/**
 * 上传复位令牌：一次摄入结束后自增，摄入面板收到后清空 el-upload 内部文件列表。
 * 面板已下沉到模块页面内部，App 拿不到组件实例，改用令牌广播（limit=1 时否则无法继续选文件）。
 */
const uploadReset=ref(0)
/** 最近一次操作失败信息：错误需要在页面上停留可见，而不是一闪而过的 toast。 */
const failure=ref('')
const activeBase=computed(()=>bases.value.find(v=>v.id===knowledgeBaseId.value))
/** 知识库的「默认问题」是建库时配好的示例问句，进了检索页就该直接躺在输入框里。
    只把它当 placeholder 等于没用：用户看到的是一行提示，真正发出去的还是另一次输入。
    切换知识库时上下文变了，一并换掉；该库没配就清空，不留着上一句误导人。 */
watch([knowledgeBaseId,()=>activeBase.value?.defaultQuery||''],([,text])=>{
  q.value=text
},{immediate:true})
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
/** 各知识库的概览统计：主页面的汇总数字按库聚合，拉取失败的库按 0 计入。 */
const baseStats=ref<Record<number,Overview>>({})
const baseStatsLoading=ref(false)
/** 知识库列表的加载态：首屏骨架由主页面自己呈现，避免先空后填的跳动。 */
const basesLoading=ref(false)
/** 最近访问的知识库 id：主页面「最近访问」按它排序。 */
const recentIds=ref<number[]>(readRecentIds())
function readRecentIds():number[]{
  try{
    const raw=JSON.parse(localStorage.getItem('kos.recentBases')||'[]')
    return Array.isArray(raw)?raw.filter(v=>typeof v==='number'):[]
  }catch{return []}
}
function pushRecentBase(id:number){
  recentIds.value=[id,...recentIds.value.filter(v=>v!==id)].slice(0,8)
  try{localStorage.setItem('kos.recentBases',JSON.stringify(recentIds.value))}catch{/* 忽略：仅影响排序 */}
}
async function loadBaseStats(){
  if(!bases.value.length){baseStats.value={};return}
  baseStatsLoading.value=true
  try{
    const pairs=await Promise.all(bases.value.map(async base=>{
      try{
        const stat=unwrap(await getOverview(base.id),null as unknown as Overview)
        return stat?([base.id,stat] as const):null
      }catch{return null}
    }))
    const map:Record<number,Overview>={}
    for(const pair of pairs)if(pair)map[pair[0]]=pair[1]
    baseStats.value=map
  }finally{baseStatsLoading.value=false}
}
/** 知识库管理权限：主页面的「创建知识库」入口按它裁剪。 */
const canCreateBase=computed(()=>can(PERM.SYSTEM_SPACE))
/** 主页面点知识库卡片：选中该库、记入最近访问，再进入工作台。 */
function openHomeBase(id:number){
  selectBase(id)
  goto('workbench')
}
const avatarText=computed(()=>
  String(me.value?.user?.displayName||activeBase.value?.name||'知识库').trim().slice(0,1)||'K')
/**
 * 权限判定：后端按角色下发权限码，未开启认证时 me 为空，此时按全权限处理，
 * 与后端 PermissionInterceptor 对空 AuthContext 的放行保持一致。
 */
const can=(code:string)=>hasPermission(me.value?.permissions,code)
/**
 * 角色决定模块内 Tab 的详略：管理员（ADMIN / KNOWLEDGE_ADMIN）看完整流水线，
 * 其余角色只看「干活」的那几个入口（如知识生成只剩文档导入 / 知识裁决 / 知识衍生）。
 * 角色缺省表示未开启认证或 me 尚未就绪，此时按管理员视图渲染，与权限全放行保持一致。
 */
const role=computed(()=>me.value?.user?.role)
/**
 * 角色可见菜单：后端按「系统配置 · 角色菜单」的配置下发，是菜单裁剪的权威来源，
 * 取代原先的「用户视图 / 管理视图」二值切换——每个角色看到的就是自己被授权的菜单。
 * 未下发（老会话 / 未登录）时为 null，回落到内置的 adminOnly + 权限码规则，避免白屏。
 */
const menuKeys=computed(()=>{
  const menu=me.value?.menu
  return menu&&menu.length?new Set(menu):null
})
/** 某个菜单项对当前角色是否可见：页面区块与快捷入口的裁剪都用它。 */
const menuHas=(key:string)=>!!menuKeys.value?.has(key)
/**
 * 一级导航：IA 的业务模块，二级入口全部下沉到模块页面内部的 Tab。
 * 左侧按业务域分组展示：工作台（知识概览）/ 知识库（知识生成、知识检索）/ 系统集成（接口服务）。
 * 「系统配置」不再作为业务一级入口，改由侧栏底部的设置图标进入（低频平台配置不占业务导航）。
 */
const navGroups=computed(()=>visibleGroups(can,role.value,menuKeys.value))
/** 系统配置模块本身：只在底部工具区使用，是否展示由该模块内是否有可见 Tab 决定。 */
const systemSection=NAV_SECTIONS.find(section=>section.key==='system')
/** 无任何系统配置权限时不展示设置入口，避免点进去处处 403。 */
const canSystem=computed(()=>!!systemSection&&visibleLeaves(systemSection,can,role.value,menuKeys.value).length>0)
const activeLocation=computed(()=>locate(active.value))
const activeSection=computed(()=>activeLocation.value?.section.key||'workbench')
/** 一级菜单收起二级后，把该模块内所有待处理计数汇总到一级入口上。 */
function sectionBadge(section:NavSection):string{
  let total=0
  for(const leaf of leavesOf(section)){
    const value=leafBadge(leaf)
    if(typeof value==='number')total+=value
  }
  return total?String(total):''
}
/** 模块页内 Tab：按角色的可见菜单裁剪，避免普通账号看到运维入口与无权操作的入口。 */
function leavesOf(section:NavSection){
  return visibleLeaves(section,can,role.value,menuKeys.value)
}
/** 进入一级模块：落到该角色可见的第一个 Tab（普通成员进知识生成即到文档导入）。 */
function enterSection(section:NavSection){
  active.value=defaultLeafOf(section,can,role.value,menuKeys.value).key
}
/** 点击侧栏底部设置图标：进入系统配置的默认页（按可见菜单取第一个 Tab）。 */
function enterSystem(){
  if(!systemSection)return
  goto(defaultLeafOf(systemSection,can,role.value,menuKeys.value).key)
}
/** 模块页面回传的视图 key：规划中与无权访问的入口不进入，避免落到未实现页面。 */
function selectKey(key:string){
  const leaf=locate(key)?.leaf
  if(!leaf||leaf.planned)return
  if(!canLeaf(leaf))return
  active.value=key
}
function canLeaf(leaf:NavLeaf){
  // 角色菜单配置优先：菜单里没有的入口一律不可达（原先靠「是否管理视图」判断）。
  const menu=menuKeys.value
  if(menu)return menu.has(leaf.key)
  return (!leaf.adminOnly||isAdminView(role.value))&&(!leaf.permission||can(leaf.permission))
}
/** 页面内的快捷跳转（KPI 下钻、治理待办、质量页）先用它裁剪，无权的目标置灰而不是点了才报错。 */
function canNavigate(key:string){
  const leaf=locate(normalizeViewKey(key))?.leaf
  return !leaf||canLeaf(leaf)
}
/** 顶栏角色名：优先用后端下发的中文名，避免出现角色码。 */
const currentRoleExperience=computed(()=>roleExperience(
  me.value?.user?.role,me.value?.roleLabel||me.value?.user?.roleLabel))
const roleName=computed(()=>currentRoleExperience.value.name)
const roleBrief=computed(()=>currentRoleExperience.value.brief)
/** 系统配置入口不可点时的说明：入口置灰保留可见，需说明是角色限制而非功能缺失。 */
const systemHint=computed(()=>`当前角色（${roleName.value}）无权访问系统配置`)
/** 角色身份色：接入顶栏头像底色 / 侧栏 / 欢迎区视觉标识（纯展示，不参与权限判定）。 */
const roleAccent=computed(()=>currentRoleExperience.value.accent)
/** 顶栏头像 hover 说明：角色名与职责从顶栏收起后仍可查，但不再占顶栏位置。 */
const roleTitle=computed(()=>me.value?.user?`${roleName.value} · ${roleBrief.value}`:'未登录')
/** 角色欢迎区常用入口：按当前角色权限预置常用目标，无权者置灰而非隐藏。 */
const roleQuickActions=computed(()=>{
  const Actions:{key:string;label:string;icon:string;disabled?:boolean}[]=[]
  /** 图标直接取 IA 里的图标名，避免同一入口在菜单与常用入口里出现两套图标。 */
  const push=(key:string,label:string)=>{
    const leaf=locate(normalizeViewKey(key))?.leaf
    if(!leaf)return
    Actions.push({key:leaf.key,label,icon:leaf.icon,disabled:!canLeaf(leaf)})
  }
  const r=me.value?.user?.role
  if(r==='ADMIN'){push('system.audit','审计日志');push('system.schema','知识Schema')}
  else if(r==='KNOWLEDGE_ADMIN'){push('system.space','知识空间');push('system.schema','知识Schema');push('workbench.assets','资产浏览')}
  else if(r==='KNOWLEDGE_OPERATOR'){push('create.import','上传文档');push('create.derive','知识衍生');push('search.query','知识检索')}
  /** 检索评测只在管理视图开放，用户视图下换成同域可用的知识检索，不放一个永远点不动的入口。 */
  else if(r==='KNOWLEDGE_AUDITOR'){
    push('create.judgment','知识裁决')
    push(menuHas('search.benchmark')?'search.benchmark':'search.query',
      menuHas('search.benchmark')?'检索评测':'知识检索')
  }
  else{push('workbench.assets','资产浏览');push('search.query','知识检索')}
  return Actions
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
/** 任务中心 / 创建首页点击任务：选中该次运行并进入文档中心（生成任务已合并进文档导入）。 */
function openRun(runId:number){
if(!goto('create.import',true))return
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
async function loadBases(){
basesLoading.value=true
try{bases.value=unwrap(await getKnowledgeBases(),[]);
if(!bases.value.some(v=>v.id===knowledgeBaseId.value)&&bases.value.length)knowledgeBaseId.value=bases.value[0].id}
finally{basesLoading.value=false}}
async function createBase(payload:KbBasePayload){
try{
  const base=unwrap(
    await createKnowledgeBase(payload.name,payload.description,payload.visibility,payload.ownerGroupId,payload.scenario,
      payload.defaultQuery),
    null as unknown as KnowledgeBase)
await loadBases();
selectBase(base.id);
showBases.value=false;
ElMessage.success('知识库已创建')}catch(e){err(e)}}
/** 编辑知识库：改名 / 改描述 / 调整可见范围，改的是当前库时顺带刷新概览。 */
async function updateBase(payload:KbBasePayload&{id:number}){
try{
  unwrap(
    await updateKnowledgeBase(payload.id,{
      name:payload.name,
      description:payload.description,
      visibility:payload.visibility,
      ownerGroupId:payload.ownerGroupId,
      scenario:payload.scenario,
      defaultQuery:payload.defaultQuery,
    }),
    null as unknown as KnowledgeBase)
await loadBases();
if(payload.id===knowledgeBaseId.value)await loadOverview();
ElMessage.success('知识库已更新')}catch(e){err(e)}}
function selectBase(id:number){knowledgeBaseId.value=id;
pushRecentBase(id);
showBases.value=false}
/** 主体与角色是全量字典；审计与变更按页取，因此拆成两个互不阻塞的加载。 */
async function loadAcl(){
  const r=await Promise.allSettled([getAclSubjects(), getAclRoles()])
  if(r[0].status==='fulfilled')aclSubjects.value=unwrap(r[0].value,[])
  if(r[1].status==='fulfilled')aclRoles.value=unwrap(r[1].value,[])
  await loadAclLogs()
}
async function loadAclLogs(){
  const r=await Promise.allSettled([
    getAclAudits(knowledgeBaseId.value,auditPage.value,auditSize.value),
    getAclChanges(knowledgeBaseId.value,changePage.value,changeSize.value),
  ])
  const empty={items:[],total:0,page:1,size:20,pageCount:0}
  if(r[0].status==='fulfilled'){
    const d=unwrap(r[0].value,empty as AclLogPage<AclAudit>)
    aclAudits.value=d.items||[]
    auditTotal.value=d.total||0
    auditPage.value=d.page||auditPage.value
  }
  if(r[1].status==='fulfilled'){
    const d=unwrap(r[1].value,empty as AclLogPage<AclChange>)
    aclChanges.value=d.items||[]
    changeTotal.value=d.total||0
    changePage.value=d.page||changePage.value
  }
}
/** 审计/变更翻页：页码由面板回传，换页后重新取当前页。 */
function onAclLogPage(which:'audit'|'change',page:number,size:number){
  if(which==='audit'){auditPage.value=page;auditSize.value=size}
  else{changePage.value=page;changeSize.value=size}
  loadAclLogs()
}
async function changeAcl(p:{
  resourceType:string; resourceCode:string; permissionTags:string[]; reason:string
}){
  try{
    unwrap(await changeResourceAcl(
      knowledgeBaseId.value,p.resourceType,p.resourceCode,p.permissionTags,p.reason,
    ),{})
    ElMessage.success('ACL 已保存并写入传播队列')
    // 新变更插在队首，回到第一页才能看到它。
    auditPage.value=1
    changePage.value=1
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
/** 各视图需要的后台数据：进入模块 Tab 时按需加载，避免一次性拉取全站数据。 */
const DATA_KEYS=['create.overview','create.import','create.connector']
const REVIEW_KEYS=['create.judgment']
const QUERY_KEYS=['search.query','search.test','search.trace']
const ACL_KEYS=['system.acl','system.audit']
async function loadActive(){try{
if(active.value==='home')await loadBaseStats();
else if(active.value==='workbench')await Promise.all([loadOverview(),loadDashboard()]);
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
async function ask(){
  if(!q.value.trim())return
  asking.value=true
  try{
    result.value=unwrap(await queryKnowledge(knowledgeBaseId.value,q.value,[],answerFormat.value,useMemory.value),null)
    await loadQueryHistory()
  }catch(e){err(e)}
  finally{asking.value=false}
}
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
try{
  // 问答面板只摆最近 5 条，完整记录去「检索链路追踪」翻页看
  const page=unwrap(await getQueryHistory(knowledgeBaseId.value,1,5),{items:[],total:0,page:1,pageSize:5})
  queryHistory.value=page.items||[]
}catch(e){err(e)}
finally{historyLoading.value=false}}
/** 历史面板的「更多」：切到检索链路追踪。没有该页权限时给一句明确提示，
 *  而不是静默不跳——点了没反应比说清楚更让人困惑。 */
function openTraceCenter(){
  const leaf=locate('search.trace')?.leaf
  if(!leaf||!canLeaf(leaf)){ElMessage.info('检索链路追踪需要管理员权限');return}
  active.value='search.trace'
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
/**
 * 重新生成：抽取只在文档导入时跑一次，抽取逻辑改进后旧产物不会自动重算。
 * 这里用已入库的正文对整个知识库重跑一次抽取，不需要重新上传文件。
 */
/** 任务中心的 result 落成的是 JSON 文本，读回来要看它是字符串还是已经解析过的对象。 */
function parseTaskResult(raw:unknown):Record<string,unknown>|null{
if(typeof raw==='string'){try{const v=JSON.parse(raw);return v&&typeof v==='object'?v:null}catch{return null}}
return raw&&typeof raw==='object'?raw as Record<string,unknown>:null}
/**
 * 重新生成：提交任务后轮询任务中心，而不是让这条请求挂在那里等几分钟。
 * 期间进度、阶段与计数写进 regeneration，生成概览与生成配置两处都读它，
 * 任务中心里也能看到同一条任务。
 */
async function runRegeneration(purgeUnpublished:boolean){
if(regeneration.running){ElMessage.info('已有一个重新生成任务在进行中');return}
regeneration.running=true;regeneration.error='';regeneration.progress=0;regeneration.failed=0
regeneration.processed=0;regeneration.skipped=0;regeneration.cardCount=0;regeneration.claimCount=0
regeneration.stage='正在提交任务…';regeneration.status='QUEUED'
try{
const d=unwrap(await startRegeneration(knowledgeBaseId.value,purgeUnpublished),{taskCode:'',documentCount:0,status:''})
const taskCode=String(d.taskCode||'')
/** 拿不到任务码时退回同步调用：没有进度，但至少还有结果。 */
if(!taskCode){await runRegenerationSync(purgeUnpublished);return}
regeneration.taskCode=taskCode
regeneration.documentCount=Number(d.documentCount||0)
await watchRegeneration(taskCode)
}catch(e){
/** 后端还没升级出 start 接口（404/405）时退回同步重跑：宁可没有进度，也不能用不了。 */
const httpStatus=Number((e as {response?:{status?:number}}|null)?.response?.status||0)
if(httpStatus===404||httpStatus===405){await runRegenerationSync(purgeUnpublished);return}
regeneration.running=false;err(e)}}
/** 轮询到终态为止：中途一次读失败只是丢了一次采样，继续下一次。 */
async function watchRegeneration(taskCode:string){
const deadline=Date.now()+60*60*1000
let last:Record<string,unknown>|null=null
while(Date.now()<deadline){
await new Promise(r=>setTimeout(r,1500))
let task:Record<string,unknown>={}
try{task=unwrap(await getTaskDetail(taskCode),{})}catch{continue}
const status=String(task.status||'')
regeneration.status=status
regeneration.stage=String(task.stage||task.currentStage||'')
regeneration.progress=Number(task.progress||0)
const result=parseTaskResult(task.result)
if(result){
last=result
regeneration.processed=Number(result.processed||0)
regeneration.failed=Number(result.failed||0)
regeneration.skipped=Number(result.skipped||0)
regeneration.cardCount=Number(result.cardCount||0)
regeneration.claimCount=Number(result.claimCount||0)
regeneration.documentCount=Number(result.documentCount||regeneration.documentCount||0)
regeneration.currentDocument=String(result.currentDocument||'')
}
if(['SUCCEEDED','FAILED','DEAD','CANCELED'].indexOf(status)>=0)break
}
regeneration.running=false
if(regeneration.status==='SUCCEEDED'){
const failed=regeneration.failed
ElMessage[failed>0?'warning':'success'](
  `重新生成完成：处理 ${regeneration.processed} 篇，卡片 ${regeneration.cardCount}、断言 ${regeneration.claimCount}`
  + (failed>0?`，${failed} 篇失败`:'')
  + (regeneration.skipped>0?`，跳过 ${regeneration.skipped} 篇`:''))
}else{
ElMessage.error(`重新生成${regeneration.status==='CANCELED'?'已取消':'失败'}：${regeneration.stage||'请到任务中心查看失败原因'}`)
}
try{await Promise.all([loadReview(),loadOverview(),loadTasks()])}catch(e){err(e)}
void last}
/** 同步兜底：拿不到任务码时走原来的整库重跑，结果一样，只是没有过程可见。 */
async function runRegenerationSync(purgeUnpublished:boolean){
try{
const d=unwrap(await regenerateKnowledge(knowledgeBaseId.value,purgeUnpublished),{})
const failed=Number(d.failed||0)
ElMessage[failed>0?'warning':'success'](
  `重新生成完成：处理 ${Number(d.processed||0)} 篇，新增卡片 ${Number(d.cardCount||0)}、断言 ${Number(d.claimCount||0)}`
  + (failed>0?`，${failed} 篇失败`:'')
  + (Number(d.skipped||0)>0?`，跳过 ${Number(d.skipped||0)} 篇`:''))
await Promise.all([loadReview(),loadOverview()])
}catch(e){err(e)}finally{regeneration.running=false}}
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
/**
 * 打开图谱：V2.1 基线变更②——知识图谱归入知识检索，起点编码写入 graphCode 后由图谱面板加载邻域。
 * 带 seed 时一并下发关系类型与聚焦节点：从某条关系进来，看到的就是这条关系本身，
 * 而不是起点的整片邻域。只传 code 的调用（卡片 / 断言的「查看图谱」）行为不变。
 */
function openGraph(code:string,seed?:GraphSeed){
graphCode.value=code
graphRelationType.value=seed?.relationType||''
graphFocusNode.value=seed?.focus||''
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
  }catch{
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
  can, canNavigate, menuHas, tabs:key=>leavesOfSection(key,can,role.value,menuKeys.value),
  upload, pick, remove,
  uploadFiles:files=>{void uploadFiles(files)},
  cancelBatch:code=>{void cancelBatch(code)},
  retryBatch:code=>{void retryBatch(code)},
  selectRun:id=>{void selectRun(id)},
  refresh:()=>{void loadOverview()},
  reloadAfterRetry:()=>{void reloadAfterRetry()},
  adjudicate:()=>{void runIntelligentAdjudication()},
  regenerate:(purgeUnpublished:boolean)=>{void runRegeneration(purgeUnpublished)},
  regeneration,
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
  await Promise.all([loadOverview(),loadReview(),loadDashboard(),loadTasks(),loadBaseStats()]);
  if(active.value!=='workbench')await loadActive();
  void loadEnvironment();
  void loadSpaceContext()}catch(e){err(e)}
}
watch(active,
    v=>{if(!v)return
localStorage.setItem('kos.activeView',v);
/* 顶部知识库栏在所有主页面都显示，切页时同样要刷新索引状态。 */
void loadSpaceContext();
void loadActive()});
watch(knowledgeBaseId,
    v=>{localStorage.setItem('llmkb.kbId',String(v));
auditPage.value=1;changePage.value=1;
void loadDashboard();
void loadTasks();
result.value=null;
// 浮层里装的是上一个库的对象详情 / 证据 / 调用链，换库后它们指向的东西已经不存在了。
// 继续展示等于把上一个库的内容当成新库的，所以一并关掉清空。
detailVisible.value=false;knowledgeDetail.value=null;detailCode.value='';
evidenceVisible.value=false;evidenceDetail.value=null;
traceVisible.value=false;queryTrace.value=null;
apiCallVisible.value=false;apiCallDetail.value=null;
// 换库后旧库的图谱起点连同附加查询条件一起失效，不清会带着上一个库的条件去查新库。
graphCode.value='';graphRelationType.value='';graphFocusNode.value='';
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
 * 这时回到主页面。没有历史位置时一律进主页面——主页面是登录后统一的首屏，
 * 角色专属的深入入口（系统配置 / 裁决 / 连接器）由侧栏与页面内快捷入口承担。
 * 必须在拿到 /auth/me 的权限之后执行，否则会按「未知角色」渲染出无权页面。
 */
function resolveInitialView(){
  const remembered=localStorage.getItem('kos.activeView')||localStorage.getItem('llmkb.activeTab')
  const leaf=remembered?locate(normalizeViewKey(remembered))?.leaf:undefined
  if(leaf&&canLeaf(leaf)){active.value=leaf.key;return}
  active.value='home'
}
onMounted(async()=>{
  window.addEventListener(AUTH_EXPIRED_EVENT,onAuthExpired)
  try{await bootstrapAuth()}catch{authReady.value=true}
  if(needLogin.value)return
  resolveInitialView()
  await initialLoad()
})
</script>
<style src="./app.css"></style>
