import axios, { type AxiosResponse } from 'axios'
import type {
  ApiEnvelope, AssessmentRow, Capabilities, Claim, ConflictDetail, ConflictRow, DriftResult, GraphResult,
  IndexSchema, IngestionRun, IngestionRunDetail, KnowledgeBase, KnowledgeCard,
  KnowledgeDocument, KnowledgeSource, OutboxStats, Overview, QueryHistoryItem, QueryResult, QueryTrace, ReviewRow,
  SourcePreview,
  AnswerQualification, AnswerQualificationSummary,
} from '../types'

const LONG_TIMEOUT_MS = 180000
type ApiResponse<T> = AxiosResponse<ApiEnvelope<T>>

export const getKnowledgeBases = ():Promise<ApiResponse<KnowledgeBase[]>> =>
  axios.get('/api/knowledge/bases')
/** 创建知识库：visibility 为 PUBLIC / PRIVATE / GROUP，GROUP 时须传 ownerGroupId。 */
export const createKnowledgeBase = (
  name:string, description='', visibility='PUBLIC', ownerGroupId:number|null=null,
):Promise<ApiResponse<KnowledgeBase>> =>
  axios.post('/api/knowledge/bases', { name, description, visibility, ownerGroupId })
export const updateKnowledgeBaseAccess = (
  id:number, visibility:string, ownerGroupId:number|null=null,
):Promise<ApiResponse<KnowledgeBase>> =>
  axios.post(`/api/knowledge/bases/${id}/access`, { visibility, ownerGroupId })
export const archiveKnowledgeBase = (id:number):Promise<ApiResponse<KnowledgeBase>> =>
  axios.post(`/api/knowledge/bases/${id}/archive`)
/** 清空知识库内的全部知识数据；dryRun=true 时只返回将删除的规模，供二次确认展示。 */
export const purgeKnowledgeBase = (
  kb:number, dryRun=false, purgeIndex=true,
):Promise<ApiResponse<import('../types').PurgeResult>> =>
  axios.post(
    `/api/knowledge/bases/${kb}/purge?dryRun=${dryRun}&purgeIndex=${purgeIndex}`,
    {}, { timeout:LONG_TIMEOUT_MS },
  )
export const getOverview = (kb:number):Promise<ApiResponse<Overview>> =>
  axios.get(`/api/knowledge/overview/${kb}`)
export const getSources = (kb:number):Promise<ApiResponse<KnowledgeSource[]>> =>
  axios.get(`/api/knowledge/sources/${kb}`)
export const getDocuments = (kb:number):Promise<ApiResponse<KnowledgeDocument[]>> =>
  axios.get(`/api/knowledge/documents/${kb}`)
export const getSourcePreview = (
  kb:number, sourceId:number, limit=20000,
):Promise<ApiResponse<SourcePreview>> =>
  axios.get(`/api/knowledge/sources/${kb}/${sourceId}/preview?limit=${limit}`)
/** 源文件原件下载地址（在线打开或另存）。 */
export const sourceFileUrl = (kb:number, sourceId:number):string =>
  `/api/knowledge/sources/${kb}/${sourceId}/file`
export const getCards = (kb:number):Promise<ApiResponse<KnowledgeCard[]>> =>
  axios.get(`/api/knowledge/cards/${kb}`)
export const getClaims = (kb:number):Promise<ApiResponse<Claim[]>> =>
  axios.get(`/api/knowledge/claims/${kb}`)
export const getPendingReviews = (kb:number):Promise<ApiResponse<ReviewRow[]>> =>
  axios.get(`/api/knowledge/reviews/${kb}/pending`)
export const getOpenConflicts = (kb:number):Promise<ApiResponse<ConflictRow[]>> =>
  axios.get(`/api/knowledge/conflicts/${kb}?status=OPEN`)
export const getAssessments = (kb:number, limit=100):Promise<ApiResponse<AssessmentRow[]>> =>
  axios.get(`/api/knowledge/adjudication/${kb}/assessments?limit=${limit}`)
export const getCapabilities = ():Promise<ApiResponse<Capabilities>> =>
  axios.get('/api/knowledge/capabilities')

/** 运行环境：profile / 版本 / 外部依赖形态，供顶部空间上下文条展示。 */
export const getEnvironment = ():Promise<ApiResponse<Record<string, unknown>>> =>
  axios.get('/api/knowledge/env')
export const getOutboxStats = (kb:number):Promise<ApiResponse<OutboxStats>> =>
  axios.get(`/api/knowledge/index/outbox/${kb}/stats`)
export const getIndexDrift = (kb:number):Promise<ApiResponse<DriftResult>> =>
  axios.get(`/api/knowledge/index/drift/${kb}`)
export const getIndexSchemas = (kb:number):Promise<ApiResponse<IndexSchema[]>> =>
  axios.get(`/api/knowledge/index/schema/${kb}`)
export const getIngestionRuns = (kb:number, limit=20):Promise<ApiResponse<IngestionRun[]>> =>
  axios.get(`/api/knowledge/ingestion/${kb}/runs?limit=${limit}`)
export const getIngestionRun = (runId:number):Promise<ApiResponse<IngestionRunDetail>> =>
  axios.get(`/api/knowledge/ingestion/runs/${runId}`)

/* ---- V1.0 文档状态机：失败/索引失效文档从原件重跑，Chunk 按 revision 查看 ---- */

/** 重跑文档：仅 FAILED / DIRTY 可重跑，旧 Chunk revision 保留为 SUPERSEDED（设计 §16.1）。 */
export const retryDocument = (documentId:number):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/documents/${documentId}/retry`, {}, { timeout:LONG_TIMEOUT_MS })
/** 文档 Chunk 清单与切分元数据（设计 §7.2）。 */
export const getDocumentChunks = (
  documentId:number,
):Promise<ApiResponse<import('../types').DocumentChunk[]>> =>
  axios.get(`/api/knowledge/documents/${documentId}/chunks`)

/* ---- V1.0 契约端点：纯检索 / 生成式回答 / 溯源链 ---- */

/** 纯检索：只返回排序候选，不生成答案（设计 §17.1）。 */
export const retrieveKnowledge = (
  payload:Record<string,unknown>,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post('/api/knowledge/retrieve', payload, { timeout:LONG_TIMEOUT_MS })
/** 生成式回答：答案、引用、置信度与 traceId（设计 §17.1）。 */
export const answerKnowledge = (
  payload:Record<string,unknown>,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post('/api/knowledge/answer', payload, { timeout:LONG_TIMEOUT_MS })
/** 知识对象溯源链：对象 → Evidence → Chunk → Document → Source（设计 §9.4）。 */
export const getLineage = (
  kb:number, objectCode:string, objectType='',
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.get(
    `/api/knowledge/lineage/${kb}/${encodeURIComponent(objectCode)}`
    + `${objectType ? `?objectType=${encodeURIComponent(objectType)}` : ''}`,
  )

export function uploadDocument(
  kb:number, file:File, authorityScore:number, permissionTags:string[]=[],
):Promise<ApiResponse<Record<string,unknown>>> {
  const form = new FormData()
  form.append('knowledgeBaseId', String(kb))
  form.append('authorityScore', String(authorityScore))
  form.append('permissionTags', JSON.stringify(permissionTags))
  form.append('file', file)
  return axios.post('/api/knowledge/documents/upload', form, { timeout:LONG_TIMEOUT_MS })
}

/* ---- 批量接入（多个文件一次提交，后端异步解析并生成知识） ---- */

/** 批量上传：立即返回批次号，实际解析在后台队列执行，前端轮询 batch 状态。 */
export function uploadDocuments(
  kb:number, files:File[], authorityScore:number, permissionTags:string[]=[],
):Promise<ApiResponse<import('../types').BatchIngestStatus>> {
  const form = new FormData()
  form.append('knowledgeBaseId', String(kb))
  form.append('authorityScore', String(authorityScore))
  form.append('permissionTags', JSON.stringify(permissionTags))
  files.forEach(file => form.append('files', file))
  return axios.post('/api/knowledge/documents/batch-upload', form, { timeout:LONG_TIMEOUT_MS })
}
/** 批次状态：总进度与每个文件的成功/失败明细。 */
export const getBatchStatus = (
  batchCode:string,
):Promise<ApiResponse<import('../types').BatchIngestStatus>> =>
  axios.get(`/api/knowledge/documents/batch/${encodeURIComponent(batchCode)}`)
/** 最近批次列表：回到接入页时仍能接着监视上次未结束的批量。 */
export const listBatches = (
  kb:number, limit=10,
):Promise<ApiResponse<import('../types').BatchIngestStatus[]>> =>
  axios.get(`/api/knowledge/documents/batches/${kb}?limit=${limit}`)
/** 取消批次：排队中的文件不再启动，已在解析的文件跑完即停。 */
export const cancelBatch = (batchCode:string):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/documents/batch/${encodeURIComponent(batchCode)}/cancel`)
/** 重跑批次内的失败文件，成功文件保持原样。 */
export const retryBatch = (batchCode:string):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/documents/batch/${encodeURIComponent(batchCode)}/retry`,
    {}, { timeout:LONG_TIMEOUT_MS })

export const queryKnowledge = (
  kb:number, query:string, permissionTags:string[]=[],
):Promise<ApiResponse<QueryResult>> =>
  axios.post(
    '/api/knowledge/query',
    { knowledgeBaseId:kb, query, topK:10, userId:'console-user', permissionTags },
    { timeout:LONG_TIMEOUT_MS },
  )

export const runAdjudication = (kb:number):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/adjudication/${kb}/run`, {}, { timeout:LONG_TIMEOUT_MS })

/** 人工裁决：审核人缺省由后端取登录态，备注必须能由审核人填写（设计 §4.4）。 */
export const reviewKnowledge = (
  row:ReviewRow, decision:string, comment='',
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post('/api/knowledge/adjudicate', {
    objectType:row.objectType, objectCode:row.objectCode, decision, comment,
  })

export const resolveKnowledgeConflict = (
  id:number, status:string, resolution='',
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/conflicts/${id}/resolve`, { status, resolution })

/** 冲突双方对照：值 / 条件 / 版本 / 置信度 + 各自证据，供裁决工作台并排比较。 */
export const getConflictTargets = (
  id:number,
):Promise<ApiResponse<ConflictDetail>> =>
  axios.get(`/api/knowledge/conflicts/${id}/targets`)

/** 冲突裁决：KEEP_A / KEEP_B / MERGE / REJECT_BOTH / REQUEST_EVIDENCE / IGNORE。 */
export const adjudicateConflict = (
  id:number, decision:string, comment='',
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/conflicts/${id}/adjudicate`, { decision, comment })

export const getGraph = (
  kb:number, code:string, depth=2, limit=80,
):Promise<ApiResponse<GraphResult>> =>
  axios.get(`/api/knowledge/graph/${kb}/${encodeURIComponent(code)}`, { params:{ depth, limit } })
export const getTrace = (qid:string):Promise<ApiResponse<QueryTrace>> =>
  axios.get(`/api/knowledge/trace/${encodeURIComponent(qid)}`)
export const getAnswerQualification = (qid:string):Promise<ApiResponse<AnswerQualification>> =>
  axios.get(`/api/knowledge/answer-qualification/${encodeURIComponent(qid)}`)
export const getAnswerQualificationSummary = (kb:number, hours=24):Promise<ApiResponse<AnswerQualificationSummary>> =>
  axios.get(`/api/knowledge/answer-qualifications/${kb}/summary?hours=${hours}`)
export const getQueryHistory = (kb:number, limit=30):Promise<ApiResponse<QueryHistoryItem[]>> =>
  axios.get(`/api/knowledge/traces/${kb}?limit=${limit}`)

/* ---- V1.0 实体与别名词典：查看归一结果并人工纠正 ---- */

export const getEntities = (kb:number):Promise<ApiResponse<import('../types').EntityRow[]>> =>
  axios.get(`/api/knowledge/entities/${kb}`)
export const getEntityDetail = (
  kb:number, entityCode:string,
):Promise<ApiResponse<import('../types').EntityDetail>> =>
  axios.get(`/api/knowledge/entities/${kb}/${encodeURIComponent(entityCode)}`)
export const addEntityAlias = (
  kb:number, entityCode:string, alias:string, aliasType='MANUAL',
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/entities/${kb}/${encodeURIComponent(entityCode)}/aliases`, {
    alias, aliasType, confidence:1.0,
  })
export const removeEntityAlias = (
  kb:number, entityCode:string, alias:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/entities/${kb}/${encodeURIComponent(entityCode)}/aliases/remove`, { alias })
export const mergeEntity = (
  kb:number, from:string, to:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/entities/${kb}/merge`, { from, to })
export const updateEntityStatus = (
  kb:number, entityCode:string, status:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/entities/${kb}/${encodeURIComponent(entityCode)}/status`, { status })

/* ---- V1.0 生命周期治理：到期失效、版本替代、来源影响分析 ---- */

export const getLifecycleSummary = (
  kb:number, withinDays=30,
):Promise<ApiResponse<import('../types').LifecycleSummary>> =>
  axios.get(`/api/knowledge/lifecycle/${kb}/summary?withinDays=${withinDays}`)
export const getExpiringClaims = (
  kb:number, withinDays=30,
):Promise<ApiResponse<import('../types').ExpiringClaim[]>> =>
  axios.get(`/api/knowledge/lifecycle/${kb}/expiring?withinDays=${withinDays}`)
export const expireDueKnowledge = (
  kb:number, dryRun=false,
):Promise<ApiResponse<import('../types').ExpireResult>> =>
  axios.post(`/api/knowledge/lifecycle/${kb}/expire?dryRun=${dryRun}`, {}, { timeout:LONG_TIMEOUT_MS })
export const supersedeKnowledge = (
  kb:number, objectType:'CARD'|'CLAIM', oldObjectCode:string, newObjectCode:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/lifecycle/${kb}/supersede`, { objectType, oldObjectCode, newObjectCode })
export const getSourceImpact = (
  sourceId:number,
):Promise<ApiResponse<import('../types').SourceImpact>> =>
  axios.get(`/api/knowledge/lifecycle/sources/${sourceId}/impact`)

/* ---- V1.0 索引运维：Outbox 事件重放/跳过与蓝绿发布 ---- */

export const getOutboxEvents = (
  kb:number, status='', page=1, size=20,
):Promise<ApiResponse<import('../types').OutboxEventPage>> =>
  axios.get(
    `/api/knowledge/index/outbox/${kb}/page?page=${page}&size=${size}`
    + `${status ? `&status=${encodeURIComponent(status)}` : ''}`,
  )
export const replayOutboxEvent = (eventId:string):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/index/outbox/events/${encodeURIComponent(eventId)}/replay`)
export const skipOutboxEvent = (eventId:string):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/index/outbox/events/${encodeURIComponent(eventId)}/skip`)
export const rebuildIndexSchema = (
  kb:number, schemaVersion:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(
    `/api/knowledge/index/schema/${kb}/rebuild?schemaVersion=${encodeURIComponent(schemaVersion)}`,
    {}, { timeout:LONG_TIMEOUT_MS },
  )
export const activateIndexSchema = (
  kb:number, releaseId:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/index/schema/${kb}/activate/${encodeURIComponent(releaseId)}`)
export const rollbackIndexSchema = (
  kb:number, releaseId:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/index/schema/${kb}/rollback/${encodeURIComponent(releaseId)}`)

export function unwrap<T>(response:ApiResponse<T>, fallback:T):T {
  if (!response?.data?.success) throw new Error(response?.data?.message || '请求失败')
  return response.data.data ?? fallback
}

/** Chunk 也可寻址：详情接口按类型分派到知识对象或内容单元。 */
export const getKnowledgeObjectDetail = (
  kb:number, type:'CARD'|'CLAIM'|'CHUNK', code:string,
):Promise<ApiResponse<import('../types').KnowledgeObjectDetail>> =>
  axios.get(`/api/knowledge/objects/${kb}/${type}/${encodeURIComponent(code)}`)

export const getEvidenceDetail = (
  kb:number, evidenceCode:string,
):Promise<ApiResponse<import('../types').EvidenceDetail>> =>
  axios.get(`/api/knowledge/evidence/${kb}/${encodeURIComponent(evidenceCode)}`)

export const getContentUnitDetail = (
  kb:number, contentUnitId:number,
):Promise<ApiResponse<import('../types').EvidenceDetail>> =>
  axios.get(`/api/knowledge/content-unit/${kb}/${contentUnitId}`)
export const getAclSubjects = ():Promise<ApiResponse<import('../types').AclSubject[]>> =>
  axios.get('/api/knowledge/acl/subjects')
export const getAclRoles = ():Promise<ApiResponse<import('../types').AclRole[]>> =>
  axios.get('/api/knowledge/acl/roles')
export const getAclAudits = (kb:number):Promise<ApiResponse<import('../types').AclAudit[]>> =>
  axios.get(`/api/knowledge/acl/audits/${kb}?limit=100`)
export const getAclChanges = (kb:number):Promise<ApiResponse<import('../types').AclChange[]>> =>
  axios.get(`/api/knowledge/acl/changes/${kb}?limit=100`)
export const changeResourceAcl = (
  kb:number, resourceType:string, resourceCode:string, permissionTags:string[], reason:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/acl/resources/${kb}`, {resourceType,resourceCode,permissionTags,reason})

export const getModelSettings = ():Promise<ApiResponse<import('../types').ModelSettings>> =>
  axios.get('/api/knowledge/settings/model')
export const saveModelSettings = (
  payload:Partial<import('../types').ModelSettings>,
):Promise<ApiResponse<import('../types').ModelSettings>> =>
  axios.post('/api/knowledge/settings/model', payload)
export const testModelSettings = (
  kind:'llm'|'embedding',
):Promise<ApiResponse<import('../types').ModelTestResult>> =>
  axios.post('/api/knowledge/settings/model/test', { kind }, { timeout:LONG_TIMEOUT_MS })

/** 读取当前生效的问答策略（路由/改写/召回/融合/重排/答案）。 */
export const getQueryPolicy = ():Promise<ApiResponse<import('../types').QueryPolicy>> =>
  axios.get('/api/knowledge/settings/query-policy')
/** 保存问答策略；传入的嵌套字段按增量合并，下一次问答起生效。 */
export const saveQueryPolicy = (
  payload:Partial<import('../types').QueryPolicy>,
):Promise<ApiResponse<import('../types').QueryPolicy>> =>
  axios.post('/api/knowledge/settings/query-policy', payload)
/** 恢复内置默认策略。 */
export const resetQueryPolicy = ():Promise<ApiResponse<import('../types').QueryPolicy>> =>
  axios.post('/api/knowledge/settings/query-policy/reset')
/** 读取可配置通道说明与默认值，供配置面板渲染。 */
export const getQueryPolicySchema = ():Promise<ApiResponse<import('../types').QueryPolicySchema>> =>
  axios.get('/api/knowledge/settings/query-policy/schema')
/** 列出已保存的检索策略与当前生效策略。 */
export const listQueryPolicyProfiles = (
):Promise<ApiResponse<import('../types').QueryPolicyProfiles>> =>
  axios.get('/api/knowledge/settings/query-policy/profiles')
/** 新增检索策略：默认克隆当前策略，也可用 base 指定来源。 */
export const createQueryPolicyProfile = (
  payload:{name:string; label?:string; base?:string; policy?:Record<string,unknown>},
):Promise<ApiResponse<import('../types').QueryPolicyProfiles>> =>
  axios.post('/api/knowledge/settings/query-policy/profiles', payload)
/** 修改指定检索策略（增量合并）。 */
export const updateQueryPolicyProfile = (
  name:string, policy:Record<string,unknown>,
):Promise<ApiResponse<import('../types').QueryPolicyProfiles>> =>
  axios.put(`/api/knowledge/settings/query-policy/profiles/${encodeURIComponent(name)}`, { policy })
/** 重命名检索策略或修改展示名。 */
export const renameQueryPolicyProfile = (
  name:string, payload:{label?:string; newName?:string},
):Promise<ApiResponse<import('../types').QueryPolicyProfiles>> =>
  axios.post(`/api/knowledge/settings/query-policy/profiles/${encodeURIComponent(name)}/rename`,
    payload)
/** 切换生效策略：下一次问答起使用新策略。 */
export const activateQueryPolicyProfile = (
  name:string,
):Promise<ApiResponse<import('../types').QueryPolicyProfiles>> =>
  axios.post(`/api/knowledge/settings/query-policy/profiles/${encodeURIComponent(name)}/activate`)
/** 删除检索策略：正在使用或最后一个策略不允许删除。 */
export const deleteQueryPolicyProfile = (
  name:string,
):Promise<ApiResponse<import('../types').QueryPolicyProfiles>> =>
  axios.delete(`/api/knowledge/settings/query-policy/profiles/${encodeURIComponent(name)}`)

/* ---- 分块策略（设计 §4.1） ---- */

/** 读取知识库分块策略与可选算法。 */
export const getChunkPolicy = (
  kb:number,
):Promise<ApiResponse<import('../types').ChunkPolicyState>> =>
  axios.get('/api/knowledge/settings/chunk-policy', { params: { kb } })
/** 保存分块策略：下一次接入生效，已有文档需重新接入才能按新策略重切。 */
export const saveChunkPolicy = (
  kb:number, policy:import('../types').ChunkPolicy,
):Promise<ApiResponse<import('../types').ChunkPolicyState>> =>
  axios.put('/api/knowledge/settings/chunk-policy', policy, { params: { kb } })
/** 恢复内置默认分块策略。 */
export const resetChunkPolicy = (
  kb:number,
):Promise<ApiResponse<import('../types').ChunkPolicyState>> =>
  axios.post('/api/knowledge/settings/chunk-policy/reset', null, { params: { kb } })

/* ---- 评测中心（设计 §14） ---- */

/** 运行需要回放整个数据集：逐用例调用检索链，超时按最长等待处理。 */
const EVAL_TIMEOUT_MS = 600000
type EvalRow = Record<string, unknown>

export const listEvaluationDatasets = (kb:number):Promise<ApiResponse<EvalRow[]>> =>
  axios.get(`/api/knowledge/evaluations/datasets/${kb}`)
export const createEvaluationDataset = (
  kb:number, payload:{ name:string; description?:string; version?:string },
):Promise<ApiResponse<EvalRow>> => axios.post(`/api/knowledge/evaluations/datasets/${kb}`, payload)
export const updateEvaluationDataset = (
  code:string, payload:{ name?:string; description?:string; status?:string },
):Promise<ApiResponse<number>> => axios.put(`/api/knowledge/evaluations/datasets/${code}`, payload)
export const listEvaluationCases = (code:string):Promise<ApiResponse<EvalRow[]>> =>
  axios.get(`/api/knowledge/evaluations/datasets/${code}/cases`)
export const createEvaluationCase = (
  code:string, payload:{
    question:string; expectedObjectCodes:string[]; expectedEvidenceCodes?:string[]
    expectedAnswer?:string; tags?:string[]; difficulty?:string;
  },
):Promise<ApiResponse<EvalRow>> => axios.post(`/api/knowledge/evaluations/datasets/${code}/cases`, payload)
export const deleteEvaluationCase = (id:number):Promise<ApiResponse<number>> =>
  axios.delete(`/api/knowledge/evaluations/cases/${id}`)
export const runEvaluation = (
  kb:number, payload:{
    datasetCode:string; mode?:'RETRIEVAL'|'FULL'; topK?:number
    baselineRunCode?:string; permissionTags?:string[]
  },
):Promise<ApiResponse<EvalRow>> =>
  axios.post(`/api/knowledge/evaluations/runs/${kb}`, payload, { timeout:EVAL_TIMEOUT_MS })
export const listEvaluationRuns = (
  kb:number, datasetId?:number|null, limit=20,
):Promise<ApiResponse<EvalRow[]>> =>
  axios.get(`/api/knowledge/evaluations/runs/${kb}`, { params:{ datasetId:datasetId ?? undefined, limit } })
export const getEvaluationRun = (kb:number, runCode:string):Promise<ApiResponse<EvalRow>> =>
  axios.get(`/api/knowledge/evaluations/runs/${kb}/${runCode}`)
export const getEvaluationGaps = (kb:number, runCode:string):Promise<ApiResponse<EvalRow>> =>
  axios.get(`/api/knowledge/evaluations/gaps/${kb}/${runCode}`)

/* ---- 知识工作台聚合（详细设计 §3 / §11） ---- */

/** 工作台 KPI：来源 / 文档 / 卡片 / 断言 / 实体 / 关系与健康度。 */
export const getDashboardSummary = (
  kb:number,
):Promise<ApiResponse<import('../types').DashboardSummary>> =>
  axios.get(`/api/knowledge/dashboard/summary/${kb}`)
/** 知识生命周期分阶段统计。 */
export const getDashboardLifecycle = (
  kb:number,
):Promise<ApiResponse<import('../types').DashboardLifecycle>> =>
  axios.get(`/api/knowledge/dashboard/lifecycle/${kb}`)
/** 治理待办：待审核 / 冲突 / 过期 / 无证据 / 低置信 / 衍生失效。 */
export const getDashboardGovernance = (
  kb:number, lowConfidence?:number,
):Promise<ApiResponse<import('../types').DashboardGovernance>> =>
  axios.get(`/api/knowledge/dashboard/governance/${kb}`, { params:{ lowConfidence:lowConfidence ?? undefined } })

/* ---- 知识集成（详细设计 §6） ---- */

export const getIntegrationOverview = (
  hours=24,
):Promise<ApiResponse<import('../types').IntegrationOverview>> =>
  axios.get('/api/integration/overview', { params:{ hours } })
/** 调用日志：traceId 可回到 检索链路追踪，形成外部调用 → 检索 → 证据的追溯链。 */
export const getIntegrationCallLogs = (
  params:{ hours?:number; protocol?:string; status?:string; limit?:number } = {},
):Promise<ApiResponse<import('../types').IntegrationCallRow[]>> =>
  axios.get('/api/integration/call-logs', { params })
/** 单次调用的全部属性（调用方、入参、返回）：给调用日志的 Trace 视图用。 */
export const getIntegrationCallDetail = (
  traceId:string,
):Promise<ApiResponse<import('../types').IntegrationCallDetail|null>> =>
  axios.get(`/api/integration/call-logs/${encodeURIComponent(traceId)}`)

/* ---- 治理洞察：版本治理与知识质量（设计 §5.1 / §9.4） ---- */

export const getVersionSnapshots = (
  kb:number, params:{ objectType?:string; changeType?:string; keyword?:string; limit?:number; offset?:number } = {},
):Promise<ApiResponse<import('../types').VersionPage>> =>
  axios.get(`/api/knowledge/governance/versions/${kb}`, { params })
export const getVersionEvidence = (
  kb:number, objectType:string, objectCode:string,
):Promise<ApiResponse<import('../types').VersionEvidence>> =>
  axios.get(`/api/knowledge/governance/versions/${kb}/${objectType}/${objectCode}/evidence`)
export const getQualityOverview = (
  kb:number, withinDays=30,
):Promise<ApiResponse<import('../types').QualityOverview>> =>
  axios.get(`/api/knowledge/governance/quality/${kb}`, { params:{ withinDays } })

/* ---- 任务中心与告警（设计 §7：任务 / DLQ / 告警规则） ---- */

export const getTaskOverview = (
  params:{ kb?:number; status?:string; taskType?:string; triggerType?:string; limit?:number } = {},
):Promise<ApiResponse<import('../types').TaskOverview>> =>
  axios.get('/api/knowledge/tasks', { params })
export const getTaskStats = (kb?:number):Promise<ApiResponse<import('../types').TaskStatsPair>> =>
  axios.get('/api/knowledge/tasks/stats', { params:{ kb } })
export const getTaskDictionaries = ():Promise<ApiResponse<import('../types').TaskDictionaries>> =>
  axios.get('/api/knowledge/tasks/types')
export const getTaskDetail = (taskCode:string):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.get(`/api/knowledge/tasks/${taskCode}`)
/** 重试：失败与死信任务重新入队，接入类任务同时重新执行。 */
export const retryTask = (taskCode:string, kb?:number):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.post(`/api/knowledge/tasks/${taskCode}/retry`, null, { params:{ kb } })
export const cancelTask = (taskCode:string):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.post(`/api/knowledge/tasks/${taskCode}/cancel`)
export const getAlerts = (
  params:{ kb?:number; status?:string; severity?:string; limit?:number } = {},
):Promise<ApiResponse<import('../types').AlertList>> =>
  axios.get('/api/knowledge/tasks/alerts', { params })
/** 立即评估告警规则，不等待下一次调度。 */
export const evaluateAlerts = (kb?:number):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.post('/api/knowledge/tasks/alerts/evaluate', null, { params:{ kb } })
export const ackAlert = (alertCode:string, note?:string):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.post(`/api/knowledge/tasks/alerts/${alertCode}/ack`, { note })
export const resolveAlert = (alertCode:string, note?:string):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.post(`/api/knowledge/tasks/alerts/${alertCode}/resolve`, { note })
export const reopenAlert = (alertCode:string, note?:string):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.post(`/api/knowledge/tasks/alerts/${alertCode}/reopen`, { note })
export const createAlertRule = (
  kb:number|undefined, payload:Record<string, unknown>,
):Promise<ApiResponse<import('../types').AlertRule>> =>
  axios.post('/api/knowledge/tasks/alerts/rules', payload, { params:{ kb } })
export const updateAlertRule = (
  ruleCode:string, payload:Record<string, unknown>,
):Promise<ApiResponse<import('../types').AlertRule>> =>
  axios.put(`/api/knowledge/tasks/alerts/rules/${ruleCode}`, payload)
export const deleteAlertRule = (ruleCode:string):Promise<ApiResponse<string>> =>
  axios.delete(`/api/knowledge/tasks/alerts/rules/${ruleCode}`)

/* ---- 知识衍生：手动衍生与计划衍生（设计 §4.5 / PRD §5.5） ---- */

export const getDerivationOverview = (
  params:{ kb?:number; status?:string; ruleCode?:string; limit?:number } = {},
):Promise<ApiResponse<import('../types').DerivationOverview>> =>
  axios.get('/api/knowledge/derivations', { params })
export const createDerivationRule = (
  kb:number|undefined, payload:Record<string, unknown>,
):Promise<ApiResponse<import('../types').DerivationRule>> =>
  axios.post('/api/knowledge/derivations/rules', payload, { params:{ kb } })
export const updateDerivationRule = (
  ruleCode:string, payload:Record<string, unknown>,
):Promise<ApiResponse<import('../types').DerivationRule>> =>
  axios.put(`/api/knowledge/derivations/rules/${ruleCode}`, payload)
export const deleteDerivationRule = (ruleCode:string):Promise<ApiResponse<string>> =>
  axios.delete(`/api/knowledge/derivations/rules/${ruleCode}`)
/** 手动衍生：立即执行一次规则。 */
export const runDerivationRule = (
  ruleCode:string, kb?:number,
):Promise<ApiResponse<import('../types').DerivationRunResult>> =>
  axios.post(`/api/knowledge/derivations/rules/${ruleCode}/run`, null, { params:{ kb } })
/** 计划衍生：给定 cron 改为计划执行，cron 为空改回手动。 */
export const planDerivationRule = (
  ruleCode:string, cronExpr:string,
):Promise<ApiResponse<import('../types').DerivationRule>> =>
  axios.post(`/api/knowledge/derivations/rules/${ruleCode}/plan`, { cronExpr })
export const getDerivedCandidates = (
  params:{ kb?:number; status?:string; ruleCode?:string; limit?:number } = {},
):Promise<ApiResponse<import('../types').DerivedCandidate[]>> =>
  axios.get('/api/knowledge/derivations/candidates', { params })
export const getDerivedCandidate = (
  candidateCode:string,
):Promise<ApiResponse<import('../types').DerivedCandidateDetail>> =>
  axios.get(`/api/knowledge/derivations/candidates/${candidateCode}`)
export const reviewDerivedCandidate = (
  candidateCode:string, decision:'APPROVED'|'REJECTED'|string, reason?:string,
):Promise<ApiResponse<import('../types').DerivedCandidateDetail>> =>
  axios.post(`/api/knowledge/derivations/candidates/${candidateCode}/review`, { decision, reason })
/** 基础断言变更后标记衍生候选失效。 */
export const markDerivedStale = (
  kb:number|undefined, assertionCodes:string[],
):Promise<ApiResponse<Record<string, unknown>>> =>
  axios.post('/api/knowledge/derivations/stale', { assertionCodes }, { params:{ kb } })
export const getDerivationRuns = (
  params:{ kb?:number; ruleCode?:string; limit?:number } = {},
):Promise<ApiResponse<import('../types').DerivationRun[]>> =>
  axios.get('/api/knowledge/derivations/runs', { params })

/* ---- 知识 Schema：类型字典、校验与发布（设计 §7） ---- */

export const getSchemaOverview = (
  params:{ kb?:number; kind?:string } = {},
):Promise<ApiResponse<import('../types').SchemaOverview>> =>
  axios.get('/api/knowledge/schema', { params })
export const getSchemaTypes = (
  kb?:number, kind?:string,
):Promise<ApiResponse<import('../types').SchemaType[]>> =>
  axios.get('/api/knowledge/schema/types', { params:{ kb, kind } })
export const upsertSchemaType = (
  kb:number|undefined, payload:Record<string, unknown>,
):Promise<ApiResponse<import('../types').SchemaType>> =>
  axios.post('/api/knowledge/schema/types', payload, { params:{ kb } })
export const deprecateSchemaType = (
  kb:number|undefined, kind:string, typeCode:string,
):Promise<ApiResponse<import('../types').SchemaType>> =>
  axios.post('/api/knowledge/schema/types/deprecate', { kind, typeCode }, { params:{ kb } })
export const deleteSchemaType = (
  kb:number|undefined, kind:string, typeCode:string,
):Promise<ApiResponse<string>> =>
  axios.delete('/api/knowledge/schema/types', { params:{ kb, kind, typeCode } })
export const validateSchema = (
  kb?:number,
):Promise<ApiResponse<import('../types').SchemaValidation>> =>
  axios.get('/api/knowledge/schema/validate', { params:{ kb } })
export const publishSchema = (kb?:number):Promise<ApiResponse<import('../types').SchemaRelease>> =>
  axios.post('/api/knowledge/schema/publish', null, { params:{ kb } })
export const getSchemaReleases = (
  kb?:number,
):Promise<ApiResponse<import('../types').SchemaRelease[]>> =>
  axios.get('/api/knowledge/schema/releases', { params:{ kb } })
