<template>
<div class="kos-shell">
<header class="kos-topbar">
  <div class="kos-brand"><span class="kos-logo">◈</span>AI 原生企业知识库平台</div>
  <span class="kos-pill">V1.0</span>
  <div class="kos-grow"></div>
  <button class="kos-kbswitch" @click="showBases=true">
    <span>知识库：<b>{{activeBase?.name||'选择知识库'}}</b></span><i>▾</i>
  </button>
  <div class="kos-status"><i class="kos-dot" :class="{ok:capabilities}"></i>{{capabilities?'服务已连接':'服务状态未知'}}</div>
  <div class="kos-avatar">{{avatarText}}</div>
</header>
<div class="kos-body">
<aside class="kos-side">
  <template v-for="group in navGroups" :key="group.title">
    <div class="kos-nav-title">{{group.title}}</div>
    <button v-for="item in group.items" :key="item.key" class="kos-nav-item" :class="{active:active===item.key}" @click="active=item.key">
      <span class="kos-nav-ic">{{item.icon}}</span>
      <span class="kos-nav-tx">{{item.label}}</span>
      <em v-if="item.badge" class="kos-nav-dot">{{item.badge}}</em>
    </button>
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
  <OverviewPanel v-if="active==='overview'" :knowledge-base-id="knowledgeBaseId" :overview="overview" :capabilities="capabilities" @navigate="navigate"/>
  <DataCenterPanel
    v-else-if="active==='data'"
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
    @select-run="selectRun"/>
  <ReviewPanel
    v-else-if="active==='review'"
    :pending-reviews="pendingReviews"
    :conflicts="conflicts"
    :assessments="assessments"
    :adjudicating="adjudicating"
    @adjudicate="runIntelligentAdjudication"
    @review="review"
    @resolve="resolveConflict"/>
  <QueryPanel v-else-if="active==='query'" v-model:query="q" :result="result" :asking="asking"
    :history="queryHistory" @ask="ask" @open-trace="openTrace" @refresh-history="loadQueryHistory" @open-citation="openCitation"/>
  <KnowledgeListPanel v-else-if="active==='cards'" mode="cards" :items="cards" :loading="listLoading"
    :error="listError" @open-graph="openGraph" @open-detail="openDetail" @retry="loadActive"/>
  <template v-else-if="active==='claims'">
    <KnowledgeListPanel mode="claims" :items="claims" :loading="listLoading" :error="listError"
      @open-graph="openGraph" @open-detail="openDetail" @retry="loadActive"/>
    <EntityGovernancePanel :knowledge-base-id="knowledgeBaseId" @refresh="loadOverview" @open-graph="openGraph"/>
  </template>
  <GraphPanel v-else-if="active==='graph'" :knowledge-base-id="knowledgeBaseId" v-model:code="graphCode"/>
  <LifecyclePanel v-else-if="active==='lifecycle'" :knowledge-base-id="knowledgeBaseId"
    @refresh="loadOverview" @open-detail="openDetail"/>
  <OpsPanel v-else-if="active==='ops'" :knowledge-base-id="knowledgeBaseId"
    :base-name="activeBase?.name||''" @refresh="loadOverview"/>
  <AclAdministrationPanel v-else-if="active==='acl'" :subjects="aclSubjects" :roles="aclRoles" :changes="aclChanges" :audits="aclAudits" @refresh="loadAcl" @change="changeAcl"/>
  <QueryPolicyPanel v-else-if="active==='queryPolicy'"/>
  <ModelSettingsPanel v-else/>
</main>
</div>
<KnowledgeBaseDialog :visible="showBases" :bases="bases" :active-id="knowledgeBaseId" @close="showBases=false" @select="selectBase" @create="createBase"/>
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
  KnowledgeObjectDetail,
} from './types'
import OverviewPanel from'./components/OverviewPanel.vue';
import DataCenterPanel from'./components/DataCenterPanel.vue';
import ReviewPanel from'./components/ReviewPanel.vue';
import QueryPanel from'./components/QueryPanel.vue';
import KnowledgeListPanel from'./components/KnowledgeListPanel.vue';
import EntityGovernancePanel from './components/EntityGovernancePanel.vue'
import LifecyclePanel from './components/LifecyclePanel.vue'
import GraphPanel from'./components/GraphPanel.vue';
import OpsPanel from'./components/OpsPanel.vue';
import AclAdministrationPanel from './components/AclAdministrationPanel.vue'
import KnowledgeBaseDialog from './components/KnowledgeBaseDialog.vue'
import KnowledgeDetailWorkspace from './components/KnowledgeDetailWorkspace.vue'
import QueryTracePanel from './components/QueryTracePanel.vue'
import EvidenceViewer from './components/EvidenceViewer.vue'
import ModelSettingsPanel from './components/ModelSettingsPanel.vue'
import QueryPolicyPanel from './components/QueryPolicyPanel.vue'
import {
  createKnowledgeBase, getKnowledgeBases, getOverview, getSources, getDocuments, getCards,
  getClaims, getPendingReviews, getOpenConflicts, getCapabilities, getAssessments,
  getIngestionRuns, getIngestionRun,
  uploadDocument, queryKnowledge, runAdjudication, reviewKnowledge,
  resolveKnowledgeConflict, getAclSubjects, getAclRoles, getAclAudits, getAclChanges,
  changeResourceAcl, getKnowledgeObjectDetail, getEvidenceDetail,
  getContentUnitDetail, getQueryHistory, getTrace, unwrap,
  getSourcePreview,
} from './api/knowledge'
type Tab='overview'|'data'|'cards'|'claims'|'review'|'graph'|'query'|'lifecycle'|'ops'|'acl'|'queryPolicy'|'settings'
const active=ref<Tab>((localStorage.getItem('llmkb.activeTab')as Tab)||'overview')
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
/** 卡片/断言列表的加载与错误状态，交由面板统一样式呈现。 */
const listLoading=ref(false), listError=ref('')
/** 最近一次操作失败信息：错误需要在页面上停留可见，而不是一闪而过的 toast。 */
const failure=ref('')
const activeBase=computed(()=>bases.value.find(v=>v.id===knowledgeBaseId.value))
const avatarText=computed(()=>String(activeBase.value?.name||'知识库').trim().slice(0,1)||'K')
interface NavItem{key:Tab;label:string;icon:string;badge?:number|string}
/** 导航按原型分为「知识工作台」与「运营与治理」两组，角标只用于需要人工介入的入口。 */
const navGroups=computed(()=>[
  {title:'知识工作台',items:[
    {key:'overview'as Tab,label:'概览',icon:'▣'},
    {key:'data'as Tab,label:'资料与摄入',icon:'⇪'},
    {key:'cards'as Tab,label:'知识卡片',icon:'▤'},
    {key:'claims'as Tab,label:'断言与实体',icon:'≡'},
    {key:'review'as Tab,label:'知识裁决',icon:'✓',badge:pendingReviews.value.length||''},
    {key:'graph'as Tab,label:'知识图谱',icon:'❖'},
    {key:'query'as Tab,label:'智能问答',icon:'⌕'},
  ] as NavItem[]},
  {title:'运营与治理',items:[
    {key:'lifecycle'as Tab,label:'生命周期',icon:'◷'},
    {key:'ops'as Tab,label:'索引与发布',icon:'⚙'},
    {key:'acl'as Tab,label:'权限与审计',icon:'⚿'},
    {key:'queryPolicy'as Tab,label:'问答策略',icon:'⌗'},
    {key:'settings'as Tab,label:'模型设置',icon:'🧠'},
  ] as NavItem[]},
])
const navigate=(tab:string)=>{active.value=tab as Tab}
function pick(f:UploadFile){file.value=f.raw||null} function remove(){file.value=null}
function err(e:unknown){const x=e as{response?:{data?:{message?:string;traceId?:string}};
message?:string};
const msg=x.response?.data?.message||x.message||'操作失败';
const tid=x.response?.data?.traceId||'';
failure.value=tid?`${msg}\ntraceId: ${tid}`:msg;
ElMessage.error(msg)}
async function loadBases(){bases.value=unwrap(await getKnowledgeBases(),[]);
if(!bases.value.some(v=>v.id===knowledgeBaseId.value)&&bases.value.length)knowledgeBaseId.value=bases.value[0].id}
async function createBase(name:string,
    description:string){try{const base=unwrap(await createKnowledgeBase(name,description),null as unknown as KnowledgeBase);
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
catch(e){previewError.value=(e as{message?:string}).message||'源文件预览加载失败'}
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
async function loadList(){
  listLoading.value=true;listError.value=''
  try{
    if(active.value==='cards')cards.value=unwrap(await getCards(knowledgeBaseId.value),[])
    else if(active.value==='claims')claims.value=unwrap(await getClaims(knowledgeBaseId.value),[])
  }catch(e){listError.value=(e as{message?:string}).message||'加载失败'}
  finally{listLoading.value=false}
}
async function loadActive(){try{if(active.value==='overview')await loadOverview();
else if(active.value==='data')await loadData();
else if(active.value==='review')await loadReview();
else if(active.value==='cards'||active.value==='claims')await loadList();
else if(active.value==='query')await loadQueryHistory();
else if(active.value==='acl')await loadAcl()}catch(e){err(e)}}
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
async function loadQueryHistory(){try{queryHistory.value=unwrap(await getQueryHistory(knowledgeBaseId.value),[])}catch(e){err(e)}}
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
watch(active,
    v=>{localStorage.setItem('llmkb.activeTab',v);
void loadActive()});
watch(knowledgeBaseId,
    v=>{localStorage.setItem('llmkb.kbId',String(v));
result.value=null;
graphCode.value='';
runDetail.value=null;
selectedRunId.value=null;
resetPreview();
stopLive();
void loadActive()})
onUnmounted(stopLive)
onMounted(async()=>{try{await loadBases();
await Promise.all([loadOverview(),loadReview()]);
if(active.value!=='overview'&&active.value!=='review')await loadActive()}catch(e){err(e)}})
</script>
<style src="./app.css"></style>
