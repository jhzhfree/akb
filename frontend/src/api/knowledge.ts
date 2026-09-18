import axios, { type AxiosResponse } from 'axios'
import type {
  ApiEnvelope, AssessmentRow, Capabilities, Claim, ConflictRow, DriftResult, GraphResult,
  IndexSchema, IngestionRun, IngestionRunDetail, KnowledgeBase, KnowledgeCard,
  KnowledgeDocument, KnowledgeSource, OutboxStats, Overview, QueryHistoryItem, QueryResult, QueryTrace, ReviewRow,
  SourcePreview,
  AnswerQualification, AnswerQualificationSummary,
} from '../types'

const LONG_TIMEOUT_MS = 180000
type ApiResponse<T> = AxiosResponse<ApiEnvelope<T>>

export const getKnowledgeBases = ():Promise<ApiResponse<KnowledgeBase[]>> =>
  axios.get('/api/knowledge/bases')
export const createKnowledgeBase = (name:string, description=''):Promise<ApiResponse<KnowledgeBase>> =>
  axios.post('/api/knowledge/bases', { name, description })
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

export const reviewKnowledge = (
  row:ReviewRow, decision:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post('/api/knowledge/adjudicate', {
    objectType:row.objectType, objectCode:row.objectCode, decision,
    reviewer:'admin', comment:'知识治理工作台人工裁决',
  })

export const resolveKnowledgeConflict = (
  id:number, status:string,
):Promise<ApiResponse<Record<string,unknown>>> =>
  axios.post(`/api/knowledge/conflicts/${id}/resolve`, { status })

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

export const getKnowledgeObjectDetail = (
  kb:number, type:'CARD'|'CLAIM', code:string,
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
