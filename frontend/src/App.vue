<template>
<LoginView v-if="needLogin" :hint="loginHint" @success="onLogin"/>
<div v-else class="kos-shell">
<header class="kos-topbar">
  <div class="kos-brand"><span class="kos-logo">◈</span>AI 原生企业知识库平台</div>
  <span class="kos-pill">V1.0</span>
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
  <!-- 任务中心：异步任务统一入口，失败任务可直接进入详情。 -->
  <button class="kos-task" :class="{alert:failedTaskCount>0}" @click="taskCenterVisible=true">
    任务<em v-if="failedTaskCount">{{failedTaskCount}}</em>
  </button>
  <div class="kos-status"><i class="kos-dot" :class="{ok:capabilities}"></i>{{capabilities?'服务已连接':'服务状态未知'}}</div>
  <div class="kos-user">
    <span class="kos-avatar">{{avatarText}}</span>
    <div class="kos-user-copy">
      <b>{{me?.user?.displayName||me?.user?.username||'未登录'}}</b>
      <small>{{me?.user?userRoleLabel(me.user.role):'匿名访问'}}</small>
    </div>
    <button v-if="me?.user" class="kos-logout" @click="doLogout">退出</button>
  </div>
</header>
<div class="kos-body">
<!-- 设计 §2.2：一级导航固定为五个入口，点击直接进入该模块的默认页面；
     仅「系统配置」在左侧保留二级页面列表，其余模块的二级入口在默认页面与页面顶部导航条内提供。 -->
<aside class="kos-side">
  <div class="kos-nav-title">信息架构</div>
  <template v-for="section in navSections" :key="section.key">
    <button class="kos-nav-item kos-nav-l1" :class="{active:activeSection===section.key}" @click="enterSection(section)">
      <span class="kos-nav-ic">{{section.icon}}</span>
      <span class="kos-nav-tx">{{section.label}}</span>
      <em v-if="hasSubNav(section)" class="kos-nav-arrow" :class="{open:activeSection===section.key}">›</em>
      <em v-else-if="sectionBadge(section)" class="kos-nav-dot">{{sectionBadge(section)}}</em>
    </button>
    <div v-if="hasSubNav(section)&&activeSection===section.key" class="kos-nav-sub">
      <button v-for="leaf in leavesOf(section)" :key="leaf.key" class="kos-nav-item kos-nav-l2"
        :class="{active:active===leaf.key,planned:leaf.planned}"
        :disabled="leaf.planned"
        :title="leaf.planned?'规划中：该能力尚未实现':''"
        @click="selectLeaf(leaf)">
        <span class="kos-nav-ic">{{leaf.icon}}</span>
        <span class="kos-nav-tx">{{leaf.label}}</span>
        <em v-if="leafBadge(leaf)" class="kos-nav-dot">{{leafBadge(leaf)}}</em>
        <em v-else-if="leaf.planned" class="kos-nav-planned">规划</em>
      </button>
    </div>
  </template>
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

  <!-- 模块内导航：一级菜单的二级入口不再挂在左侧，改为进入子页面后在页面顶部横向切换。 -->
  <nav v-if="moduleNav" class="kos-modnav" :aria-label="moduleNav.label">
    <button class="kos-modnav-home" @click="enterSection(moduleNav.section)">
      ‹ {{moduleNav.section.label}}首页
    </button>
    <span class="kos-modnav-sep"></span>
    <button
      v-for="leaf in moduleNav.leaves"
      :key="leaf.key"
      class="kos-modnav-chip"
      :class="{active:active===leaf.key,planned:leaf.planned}"
      :disabled="leaf.planned"
      :title="leaf.planned?'规划中：该能力尚未实现':''"
      @click="selectLeaf(leaf)">
      <span class="kos-nav-ic">{{leaf.icon}}</span>
      <span>{{leaf.label}}</span>
      <em v-if="leafBadge(leaf)" class="kos-nav-dot">{{leafBadge(leaf)}}</em>
    </button>
  </nav>

  <!-- 知识工作台：工作台首页 / 资产浏览 / 图谱概览 / 版本 / 质量（设计 §3） -->
  <OverviewPanel
    v-if="active==='workbench'"
    :knowledge-base-id="knowledgeBaseId" :overview="overview" :capabilities="capabilities"
    :summary="dashboard" :lifecycle="lifecycle" :governance="governance"
    @navigate="navigate" @open-assets="openAssets"/>
  <AssetBrowserPanel
    v-else-if="active==='workbench.assets'"
    :knowledge-base-id="knowledgeBaseId" v-model:type="assetType"
    @open-detail="openDetail" @open-graph="openGraph" @navigate="navigate"/>
  <GraphPanel v-else-if="active==='workbench.graph'" :knowledge-base-id="knowledgeBaseId" v-model:code="graphCode"/>
  <VersionGovernancePanel v-else-if="active==='workbench.version'" :knowledge-base-id="knowledgeBaseId" @open-detail="openDetailFromVersion"/>
  <QualityPanel v-else-if="active==='workbench.quality'" :knowledge-base-id="knowledgeBaseId" @navigate="navigate"/>
  <!-- 知识创建：流水线首页承载 Chunk / 实体归一 / 待审核 / 冲突 / 裁决五个环节，
       外部（工作台治理入口、任务中心）跳这些 key 时也落到同一页对应流水段（设计 §4）。 -->
  <CreateHomePanel
    v-else-if="createHomeKey"
    ref="homePanel"
    :knowledge-base-id="knowledgeBaseId"
    :pending-reviews="pendingReviews" :conflicts="conflicts" :assessments="assessments"
    :adjudicating="adjudicating"
    :step="createHomeKey"
    v-model:authority="authority"
    :permission-tags="sourcePermissionTags" @update:permission-tags="sourcePermissionTags=$event"
    :documents="documents"
    :runs="runs"
    :run-detail="runDetail"
    :selected-run-id="selectedRunId"
    :uploading="uploading"
    :ingest-live="ingestLive"
    :ingest-stage="ingestStage"
    :ingest-message="ingestMessage"
    @navigate="navigate" @pick="pick" @remove="remove" @upload="upload"
    @select-run="selectRun" @reloaded="reloadAfterRetry"
    @refresh="loadOverview" @open-graph="openGraph"
    @adjudicate="runIntelligentAdjudication" @review="review" @resolve-conflict="resolveConflict"/>
  <SpacePanel v-else-if="active==='create.space'" :bases="bases" :overview="overview"
    :active-id="knowledgeBaseId" @select="selectBase" @create="showBases=true"/>
  <DataCenterPanel
    v-else-if="dcFocus"
    :focus="dcFocus"
    ref="dataPanel"
    v-model:authority="authority"
    :permission-tags="sourcePermissionTags" @update:permission-tags="sourcePermissionTags=$event"
    :documents="documents"
    :sources="sources"
    :runs="runs"
    :run-detail="runDetail"
    :selected-run-id="selectedRunId"
    :uploading="uploading"
    :ingest-live="ingestLive"
    :ingest-stage="ingestStage"
    :ingest-message="ingestMessage"
    :preview="preview"
    :preview-loading="previewLoading"
    :preview-error="previewError"
    :preview-id="previewId"
    @preview="previewSource"
    @pick="pick"
    @remove="remove"
    @upload="upload"
    @select-run="selectRun"
    @reloaded="reloadAfterRetry"/>
  <!-- 知识检索：搜索 / 检索测试 / 检索策略 / Query Trace / Benchmark（设计 §5） -->
  <QueryPanel v-else-if="active==='search.query'"
    v-model:query="q" :result="result" :asking="asking"
    :history="queryHistory" @ask="ask" @open-trace="openTrace" @refresh-history="loadQueryHistory" @open-citation="openCitation"
    @navigate="navigate"/>
  <RetrievalTestPanel v-else-if="active==='search.test'" :knowledge-base-id="knowledgeBaseId" @open-trace="openTrace"/>
  <QueryPolicyPanel v-else-if="active==='search.profile'||active==='system.policy'"/>
  <TraceCenterPanel v-else-if="active==='search.trace'" :history="queryHistory" :loading="historyLoading"
    @open-trace="openTrace" @refresh="loadQueryHistory"/>
  <EvaluationPanel v-else-if="active==='search.benchmark'" :knowledge-base-id="knowledgeBaseId"/>
  <!-- 知识集成：概览 / 服务与 API / MCP / SSE / 凭证 / 调用日志（设计 §6） -->
  <IntegrationOverviewPanel
    v-else-if="active==='integration.overview'"
    :knowledge-base-id="knowledgeBaseId" @navigate="navigate" @open-trace="openTrace"/>
  <ExternalServicePanel v-else-if="active==='integration.services'||active==='integration.credentials'" :bases="bases"/>
  <McpServicePanel v-else-if="active==='integration.mcp'"/>
  <ExternalServiceLab v-else-if="active==='integration.sse'||active==='system.lab'" :bases="bases"/>
  <CallLogPanel v-else-if="active==='integration.logs'" :knowledge-base-id="knowledgeBaseId" @open-trace="openTrace"/>
  <!-- 系统配置：模型 / 用户与组织 / 权限 / 存储索引 / 问答策略 / 接口联调（设计 §7） -->
  <ModelSettingsPanel v-else-if="active==='system.model'"/>
  <UserAdminPanel v-else-if="active==='system.users'"/>
  <AclAdministrationPanel v-else-if="active==='system.acl'" :subjects="aclSubjects" :roles="aclRoles" :changes="aclChanges" :audits="aclAudits" @refresh="loadAcl" @change="changeAcl"/>
  <OpsPanel v-else-if="active==='system.index'" :knowledge-base-id="knowledgeBaseId"
    :base-name="activeBase?.name||''" @refresh="loadOverview"/>
  <!-- IA 中保留但尚未实现的入口：不提供假数据页面，明确说明状态。 -->
  <PanelState v-else :empty="true" empty-text="该入口尚未实现"
    empty-hint="信息架构已保留该位置，能力落地后会在此开放"/>
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
<EvidenceViewer :visible="evidenceVisible" :detail="evidenceDetail" :loading="evidenceLoading" @close="evidenceVisible=false"/>
</div>
</template>
<script setup lang="ts">
import{computed,onMounted,onUnmounted,ref,watch}from'vue'
import{ElMessage,type UploadFile}from'element-plus'
import type {
  AssessmentRow, Capabilities, Claim, ConflictRow,
  IngestionRun, IngestionRunDetail, KnowledgeBase, KnowledgeCard, KnowledgeDocument,
  KnowledgeSource, Overview, AclSubject, AclRole, AclAudit, AclChange,
  QueryCitation, QueryHistoryItem, QueryResult, QueryTrace, ReviewRow, EvidenceDetail,
  SourcePreview,
  KnowledgeObjectDetail, AuthMe, AuthStatus, UserGroupRow,
  IndexState, OutboxStats, DriftResult,
  DashboardSummary, DashboardLifecycle, DashboardGovernance,
} from './types'
import OverviewPanel from'./components/OverviewPanel.vue';
import AssetBrowserPanel from './components/AssetBrowserPanel.vue'
import type { AssetType } from './components/AssetBrowserPanel.vue'
import CreateHomePanel from './components/CreateHomePanel.vue'
import IntegrationOverviewPanel from './components/IntegrationOverviewPanel.vue'
import CallLogPanel from './components/CallLogPanel.vue'
import TaskCenterDrawer from './components/TaskCenterDrawer.vue'
import SpacePanel from'./components/SpacePanel.vue';
import SpaceContextBar from'./components/SpaceContextBar.vue';
import TraceCenterPanel from'./components/TraceCenterPanel.vue';
import RetrievalTestPanel from'./components/RetrievalTestPanel.vue';
import EvaluationPanel from'./components/EvaluationPanel.vue';
import McpServicePanel from'./components/McpServicePanel.vue';
import VersionGovernancePanel from'./components/VersionGovernancePanel.vue';
import QualityPanel from'./components/QualityPanel.vue';
import PanelState from'./components/PanelState.vue';
import DataCenterPanel from'./components/DataCenterPanel.vue';
import QueryPanel from'./components/QueryPanel.vue';
import KnowledgeListPanel from'./components/KnowledgeListPanel.vue';
import GraphPanel from'./components/GraphPanel.vue';
import OpsPanel from'./components/OpsPanel.vue';
import AclAdministrationPanel from './components/AclAdministrationPanel.vue'
import KnowledgeBaseDialog from './components/KnowledgeBaseDialog.vue'
import KnowledgeDetailWorkspace from './components/KnowledgeDetailWorkspace.vue'
import QueryTracePanel from './components/QueryTracePanel.vue'
import EvidenceViewer from './components/EvidenceViewer.vue'
import ModelSettingsPanel from './components/ModelSettingsPanel.vue'
import QueryPolicyPanel from './components/QueryPolicyPanel.vue'
import UserAdminPanel from './components/UserAdminPanel.vue'
import ExternalServicePanel from './components/ExternalServicePanel.vue'
import ExternalServiceLab from './components/ExternalServiceLab.vue'
import LoginView from './components/LoginView.vue'
import {
  createKnowledgeBase, getKnowledgeBases, getOverview, getSources, getDocuments, getCards,
  getClaims, getPendingReviews, getOpenConflicts, getCapabilities, getAssessments,
  getIngestionRuns, getIngestionRun,
  uploadDocument, queryKnowledge, runAdjudication, reviewKnowledge,
  resolveKnowledgeConflict, getAclSubjects, getAclRoles, getAclAudits, getAclChanges,
  changeResourceAcl, getKnowledgeObjectDetail, getEvidenceDetail,
  getContentUnitDetail, getQueryHistory, getTrace, unwrap,
  getSourcePreview, getEnvironment, getOutboxStats, getIndexDrift,
  getDashboardSummary, getDashboardLifecycle, getDashboardGovernance,
} from './api/knowledge'
import { NAV_SECTIONS, defaultLeafOf, locate, normalizeViewKey } from './navigation'
import type { NavLeaf, NavSection } from './navigation'
import {
  AUTH_EXPIRED_EVENT, getAuthStatus, getMe, getToken, listGroups, logout, setToken,
} from './api/auth'
import { userRoleLabel, visibilityLabel } from './types'
import { errorMessage } from './utils/feedback'
/** 当前视图：取 IA 二级入口 key（见 navigation.ts），旧的 tab 值会自动归一化。 */
const active=ref<string>(normalizeViewKey(
  localStorage.getItem('kos.activeView')||localStorage.getItem('llmkb.activeTab')))
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
const queryHistory=ref<QueryHistoryItem[]>([])
const queryTrace=ref<QueryTrace|null>(null), traceVisible=ref(false), traceLoading=ref(false)
const evidenceDetail=ref<EvidenceDetail|null>(null), evidenceVisible=ref(false), evidenceLoading=ref(false)
const detailVisible=ref(false), detailLoading=ref(false), detailMode=ref<'CARD'|'CLAIM'>('CARD')
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
/** 摄入面板引用：一次摄入结束后复位上传区，让下一个文件可以立刻继续上传。 */
const dataPanel=ref<InstanceType<typeof DataCenterPanel>|null>(null)
const homePanel=ref<InstanceType<typeof CreateHomePanel>|null>(null)
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
/** 一级导航固定为 IA 的 10 个入口，二级为页面入口。 */
const navSections=NAV_SECTIONS
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
/** 只有「系统配置」在左侧保留二级列表，其余模块走默认页 + 页面顶部导航条。 */
function hasSubNav(section:NavSection){return section.key==='system'}
/** 子页面的顶部导航：给出当前模块的同级入口，避免二级菜单消失后失去跨页能力。 */
const moduleNav=computed(()=>{
  const section=activeLocation.value?.section
  if(!section||hasSubNav(section))return null
  if(active.value===defaultLeafOf(section).key)return null
  return {section,label:section.label,leaves:leavesOf(section)}
})
/** 用户与用户组管理只对系统管理员开放，避免普通账号看到无权操作的入口。 */
function leavesOf(section:NavSection){
  return section.children.filter(leaf=>!leaf.adminOnly||me.value?.canManageUsers)
}
function enterSection(section:NavSection){
  active.value=defaultLeafOf(section).key
}
function selectLeaf(leaf:NavLeaf){
  if(leaf.planned)return
  active.value=leaf.key
}
/** 角标只用于需要人工介入的入口：待审核与未解决冲突。 */
function leafBadge(leaf:NavLeaf):number|string{
  if(leaf.badge==='pendingReview')return pendingReviews.value.length||''
  if(leaf.badge==='openConflict')return conflicts.value.length||''
  return ''
}
/** 资料中心服务三个入口：数据源 / 文档 / 解析任务，用 focus 决定区块顺序。 */
const dcFocus=computed<'sources'|'documents'|'ingestion'|''>(()=>
  (activeLocation.value?.leaf?.focus as 'sources'|'documents'|'ingestion')||'')
/** 治理工作台服务三个入口：待审核 / 冲突 / 裁决，focus 来自 IA 定义。 */
const govFocus=computed<'review'|'conflict'|'judgment'|''>(()=>
  (activeLocation.value?.leaf?.focus as 'review'|'conflict'|'judgment')||'')
/**
 * 知识创建的五个环节不再是独立页面：统一落在流水线首页的对应步骤上，
 * 从流水线条、工作台治理入口或任务中心跳转，看到的都是同一个容器。
 */
const CREATE_HOME_STEPS:Record<string,string>={
  'create.home':'documents',
  'create.chunk':'create.chunk',
  'create.entity':'create.entity',
  'create.review':'create.review',
  'create.conflict':'create.conflict',
  'create.judgment':'create.judgment',
}
const createHomeKey=computed(()=>CREATE_HOME_STEPS[active.value]||'')
const navigate=(tab:string)=>{active.value=normalizeViewKey(tab)}
/** 工作台 KPI 下钻：按对象类型进入资产浏览（设计 §3.1）。 */
function openAssets(type:AssetType){assetType.value=type;
active.value='workbench.assets'}
/** 任务中心 / 创建首页点击任务：选中该次运行并进入解析任务页。 */
function openRun(runId:number){selectedRunId.value=runId;
void selectRun(runId);
active.value='create.jobs'}
/** 顶栏全局搜索：进入搜索页并直接执行一次检索，减少一次点击。 */
async function globalSearch(){
  const keyword=globalKeyword.value.trim()
  if(!keyword)return
  q.value=keyword
  active.value='search.query'
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
    ElMessage.success('ACL 已保存并写入传播 Outbox')
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
if(runs.value.length&&!selectedRunId.value)await selectRun(runs.value[0].id)}}
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
async function loadActive(){try{
if(active.value==='workbench')await Promise.all([loadOverview(),loadDashboard()]);
else if(dcFocus.value)await loadData();
else if(govFocus.value)await loadReview();
else if(active.value==='create.entity')await loadOverview();
else if(active.value==='search.query'||active.value==='search.test'||active.value==='search.trace')await loadQueryHistory();
else if(active.value==='system.acl')await loadAcl()}catch(e){err(e)}}
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
dataPanel.value?.resetUpload();
homePanel.value?.resetUpload();
selectedRunId.value=Number(d.runId||0)||null;
await Promise.all([loadData(),loadOverview()]);
if(selectedRunId.value)await selectRun(selectedRunId.value)}catch(e){err(e)}
finally{await pollLiveRun().catch(()=>undefined);
stopLive();
uploading.value=false}}
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
if((type==='CARD'||type==='CLAIM')&&code){await openDetail(type as 'CARD'|'CLAIM',code);return}
ElMessage.info('该引用暂未返回 Evidence/Object 定位标识')
}
async function loadQueryHistory(){
historyLoading.value=true
try{queryHistory.value=unwrap(await getQueryHistory(knowledgeBaseId.value),[])}catch(e){err(e)}
finally{historyLoading.value=false}}
/** 版本治理页点开详情：版本快照只带类型与对象码，详情仍走统一的详情抽屉。 */
function openDetailFromVersion(type:string,code:string){
  if((type==='CARD'||type==='CLAIM')&&code)openDetail(type,code)
}
async function openTrace(qid:string){traceVisible.value=true;traceLoading.value=true;try{
queryTrace.value=unwrap(await getTrace(qid),null)}catch(e){err(e)}finally{traceLoading.value=false}}
async function openDetail(mode:'CARD'|'CLAIM',code:string){detailMode.value=mode;detailCode.value=code;
detailVisible.value=true;detailLoading.value=true;try{
knowledgeDetail.value=unwrap(await getKnowledgeObjectDetail(knowledgeBaseId.value,mode,code),null)
}catch(e){err(e)}finally{detailLoading.value=false}}
async function runIntelligentAdjudication(){adjudicating.value=true;
try{const d=unwrap(await runAdjudication(knowledgeBaseId.value),
    {});
ElMessage.success(`裁决完成：自动批准 ${Number(d.autoApproved||0)}，人工复核 ${Number(d.requiresHumanReview||0)}`);
await Promise.all([loadReview(),loadOverview()])}catch(e){err(e)}finally{adjudicating.value=false}}
async function review(row:ReviewRow, decision:string){try{await reviewKnowledge(row,decision);
await loadReview()}catch(e){err(e)}} async function resolveConflict(row:ConflictRow,
    status:string){try{await resolveKnowledgeConflict(row.id,status);
await loadReview()}catch(e){err(e)}}
/** 打开图谱页：起点编码写入 graphCode，GraphPanel 监听后自动加载邻域。 */
function openGraph(code:string){graphCode.value=code;
active.value='graph'}
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
  await initialLoad()
}
async function doLogout(){
  try{await logout()}catch{/* 会话可能已过期，本地状态仍需清理 */}
  setToken('')
  me.value=null
}
/** 任意请求返回 401 时回到登录页，避免半登录状态下反复报错。 */
function onAuthExpired(){
  me.value=null
  authReady.value=true
}
async function initialLoad(){
  try{await loadBases();
  await Promise.all([loadOverview(),loadReview(),loadDashboard(),loadTasks()]);
  if(active.value!=='workbench'&&!govFocus.value)await loadActive();
  void loadEnvironment();
  void loadSpaceContext()}catch(e){err(e)}
}
watch(active,
    v=>{localStorage.setItem('kos.activeView',v);
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
  window.removeEventListener(AUTH_EXPIRED_EVENT,onAuthExpired)
})
onMounted(async()=>{
  window.addEventListener(AUTH_EXPIRED_EVENT,onAuthExpired)
  try{await bootstrapAuth()}catch(e){authReady.value=true}
  if(needLogin.value)return
  /** 非管理员不应停留在用户管理页：刷新前一次会话留下的 tab 可能已经无权访问。 */
  if(active.value==='system.users'&&!me.value?.canManageUsers)active.value='workbench'
  await initialLoad()
})
</script>
<style src="./app.css"></style>
