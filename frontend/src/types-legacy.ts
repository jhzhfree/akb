export type KnowledgeStatus =
  | 'DRAFT' | 'APPROVED' | 'EFFECTIVE' | 'UNCERTAIN' | 'CONFLICTED'
  | 'REJECTED' | 'EXPIRED' | 'SUPERSEDED' | string

export interface ApiEnvelope<T> { success:boolean; code?:string; message?:string; data?:T }
/** 空间上下文条上的索引状态：漂移、待投递与死信来自 Outbox / 漂移检测。 */
export interface IndexState {
  drift:number
  pending:number
  dead:number
  loading:boolean
}

export interface KnowledgeBase {
  id:number; baseCode:string; name:string; description?:string; status:string; createdTime?:string
  /** 创建人：私有与用户组可见的知识库以此判定归属。 */
  createdBy?:number|null; createdByName?:string|null
  /** 可见范围：PUBLIC 公开 / PRIVATE 私有 / GROUP 用户组。 */
  visibility?:'PUBLIC'|'PRIVATE'|'GROUP'|string
  ownerGroupId?:number|null; ownerGroupName?:string|null
}
export interface Overview {
  knowledgeBaseId:number; sourceCount:number; documentCount:number; chunkCount:number
  cardCount:number; effectiveCardCount:number; claimCount:number; pendingReviewCount:number
  openConflictCount:number; assessmentCount:number
}
/** 知识库清空：dryRun 预览与执行结果共用同一结构。 */
export interface PurgeResult {
  knowledgeBaseId:number; dryRun?:boolean
  /** 各对象类型的删除规模，total 为合计。 */
  counts?:Record<string, number>; deleted?:Record<string, number>
  removedSourceFiles?:number
  /** 检索视图（Elasticsearch / Milvus / 图视图）清理结果，失败时带 error 字段。 */
  retrievalViews?:Record<string, unknown>
}

export interface KnowledgeSource {
  id:number; sourceCode:string; knowledgeBaseId:number; name:string; sourceType:string
  sourceUri:string; authorityScore:number; status:string; createdTime?:string
}
export interface KnowledgeDocument {
  id:number; documentCode:string; title:string; mimeType?:string; parseStatus:string
  chunkCount:number; createdTime?:string
  sourceId?:number; sourceName?:string
  /** 文档状态机（设计 §16.1）：UPLOADED→PROCESSING→READY/FAILED→RETRYING→PROCESSING。 */
  status?:string; currentStage?:string; retryCount?:number; errorMessage?:string|null
}
/** 可重跑的状态：其余状态重跑会破坏已生效的知识对象，后端同样拒绝。 */
export const RETRYABLE_DOCUMENT_STATUS = ['FAILED', 'DIRTY'] as const
export const isRetryableDocument = (doc:KnowledgeDocument):boolean =>
  RETRYABLE_DOCUMENT_STATUS.includes(String(doc.status || '').toUpperCase() as 'FAILED')

/** 文档 Chunk 元数据（设计 §7.2）：切分策略、页码/字符区间、token 数、revision。 */
export interface DocumentChunk {
  id:number; unitCode:string; chunkType?:string; headingPath?:string|null
  pageNo?:number|null; pageEnd?:number|null
  charStart?:number|null; charEnd?:number|null
  tokenCount?:number|null; status?:string
  sourceVersion?:number; revision?:number; parentId?:number|null
  excerpt?:string; contentLength?:number; createdTime?:string
  /** 详情视图带回全文与文档归属，便于核对引用落点。 */
  content?:string; documentCode?:string; documentTitle?:string
  evidenceCount?:number; seq?:number; /** 上下文块的相对位置：PREV / NEXT */
  pos?:'PREV'|'NEXT'
}
/** 分块策略：知识库自己决定怎么切，接入时下发给 AI 服务（设计 §4.1）。 */
export interface ChunkPolicy {
  strategy?:string; chunkSize?:number; chunkOverlap?:number; minChunkSize?:number
  measure?:string; keepStructure?:boolean; respectSentence?:boolean; parentChild?:boolean
}
export interface ChunkPolicySchema {
  default?:ChunkPolicy
  strategies?:{name:string;label:string;description?:string}[]
  measures?:{name:string;label:string}[]
  ranges?:Record<string,{min:number;max:number}>
}
export interface ChunkPolicyState {
  policy:ChunkPolicy; customized?:boolean; updatedBy?:string; updatedTime?:string
  schema?:ChunkPolicySchema
}
/** 检索策略档案：多套策略并存，切换即刻生效（设计 §14）。 */
export interface QueryPolicyProfile {
  name:string; label?:string; updatedAt?:string; active?:boolean
  summary?:{ channels?:string[]; routingEnabled?:boolean; rerankEnabled?:boolean
    rrfK?:number; recallMultiplier?:number }
}
export interface QueryPolicyProfiles { active:string; profiles:QueryPolicyProfile[] }

/** 源文件预览：文本格式展示原文，二进制格式回退到解析文本。 */
export interface SourcePreviewMeta {
  id?:number; name?:string; sourceType?:string; sourceUri?:string
  authorityScore?:number; status?:string; createdTime?:string
}
export interface SourcePreviewDocument {
  id?:number; documentCode?:string; title?:string; mimeType?:string
  parseStatus?:string; chunkCount?:number
}
export interface SourcePreview {
  available:boolean; reason?:string
  knowledgeBaseId?:number; sourceId?:number
  /** 原件是否已落盘；false 时只能看到解析文本。 */
  stored?:boolean; parsedAvailable?:boolean
  previewType?:'TEXT'|'BINARY'|'MISSING'|string
  mode?:'SOURCE'|'PARSED'|'NONE'|string
  content?:string|null; truncated?:boolean; totalChars?:number; limit?:number
  sizeBytes?:number; fileUrl?:string
  source?:SourcePreviewMeta; document?:SourcePreviewDocument
}
export interface KnowledgeCard {
  id?:number; knowledgeCode:string; title:string; summary?:string; status:KnowledgeStatus
  confidence?:number; [key:string]:unknown
}
export interface Claim {
  id?:number; claimCode:string; subject?:string; predicate?:string; objectValue?:string
  status:KnowledgeStatus; confidence?:number
  /** 极性（POSITIVE / NEGATIVE）。缺省表示抽取未给出，按正向处理。 */
  polarity?:string
  /** 来源自述确定性，与模型计算的 confidence 分离，两者不互相覆盖。 */
  certainty?:number
  /** 适用条件与限定词，决定两条断言是否在描述同一命题。 */
  conditionJson?:string; qualifierJson?:string
  /** 有效期窗口。为空表示未知而非永久。 */
  validFrom?:string; validTo?:string
  version?:number
  [key:string]:unknown
}

/** 实体的表面形式（别名），归一阶段的产物。 */
export interface EntityAlias {
  alias:string; aliasType?:string; confidence?:number; createdTime?:string
}

/** 知识图谱中的规范实体及其使用量，用于人工复核归一结果。 */
export interface EntityRow {
  entityCode:string; entityType?:string; canonicalName?:string; description?:string
  confidence?:number; status?:string; aliasCount?:number; claimCount?:number; cardCount?:number
}
export interface EntityDetail extends EntityRow { aliases?:EntityAlias[] }

/** 知识生命周期看板指标（GET /lifecycle/{kb}/summary）。 */
export interface LifecycleSummary {
  knowledgeBaseId:number; withinDays:number
  claimTotal:number; claimEffective:number; claimExpired:number; claimSuperseded:number; claimRejected:number
  cardTotal:number; cardSuperseded:number
  effectiveTotal:number; decayedTotal:number; dueNowCount:number; expiringSoonCount:number
  expiredRate:number
  claimStatusCounts?:Record<string,number>; cardStatusCounts?:Record<string,number>
  [key:string]:unknown
}

/** 即将过期的断言，用于复核续期。 */
export interface ExpiringClaim {
  claimCode:string; subject?:string; predicate?:string; objectValue?:string; status?:string
  validFrom?:string; validTo?:string; version?:number; confidence?:number
}

/** 到期失效扫描结果；dryRun 时 candidateClaimCodes 为预览清单。 */
export interface ExpireResult {
  knowledgeBaseId?:number; dryRun?:boolean; scanned?:number; expired?:number
  expiredClaimCodes?:string[]; candidateClaimCodes?:string[]
  [key:string]:unknown
}

/** 来源撤销影响面分析结果。 */
export interface SourceImpact {
  sourceId?:number; affectedCount?:number; affectedByType?:Record<string,string[]>; degraded?:boolean
  [key:string]:unknown
}

/** 索引 Outbox 事件，重放与跳过操作的载体。 */
export interface OutboxEvent {
  id?:number; eventId:string; knowledgeBaseId?:number; aggregateType?:string; aggregateKey?:string
  eventType?:string; objectVersion?:number; status?:string; attemptCount?:number; maxAttempts?:number
  lastError?:string;   nextAttemptAt?:string; createdTime?:string; processedTime?:string
  [key:string]:unknown
}
export interface OutboxEventPage {
  items:OutboxEvent[]; total:number; page:number; size:number; totalPages:number
}
export interface ReviewRow {
  id?:number; objectType:string; objectCode:string; displayTitle?:string; status?:string
  confidence?:number; conflictCount?:number; [key:string]:unknown
}
/** 冲突双方摘要：列表直接展示 A/B 标题与状态，避免只看到对象编码。 */
export interface ConflictRow {
  id:number; status:string; conflictType?:string; conflictScore?:number; resolution?:string
  objectACode?:string; objectBCode?:string; objectAType?:string; objectBType?:string
  objectATitle?:string; objectBTitle?:string; objectAContent?:string; objectBContent?:string
  objectAStatus?:string; objectBStatus?:string; objectAConfidence?:number; objectBConfidence?:number
  objectAVersion?:number; objectBVersion?:number; createdTime?:string; [key:string]:unknown
}
export interface AssessmentRow {
  id?:number; objectType?:string; objectCode?:string; decision?:string
  knowledgeConfidence?:number; riskLevel?:string; requiresHumanReview?:boolean
  policyVersion?:string; reasonText?:string; createdTime?:string; [key:string]:unknown
}
/** 裁决工作台右侧 Evidence：原文 / 来源 / 页码 / 权威度。 */
export interface AdjudicationEvidence {
  evidenceCode?:string; quoteText?:string; authorityScore?:number; freshnessScore?:number
  sourceName?:string; documentTitle?:string; documentCode?:string
  pageNo?:number; headingPath?:string; contentUnitCode?:string; [key:string]:unknown
}
/** 冲突的一方：值 / 条件 / 版本 / 置信度 + 支撑证据。 */
export interface AdjudicationSide {
  objectType?:string; objectCode?:string; content?:string; conditionJson?:string
  confidence?:number; status?:string; versionNo?:number
  evidenceCount?:number; evidence?:AdjudicationEvidence[]; [key:string]:unknown
}
export interface ConflictDetail {
  id?:number; conflictType?:string; conflictScore?:number; status?:string
  objectACode?:string; objectBCode?:string; sides?:AdjudicationSide[]; [key:string]:unknown
}
export interface QueryCitation {
  objectId?:string; objectType?:'CARD'|'CLAIM'|string; objectCode?:string; evidenceCode?:string
  contentUnitCode?:string; contentUnitId?:number; title?:string; sourceName?:string; pageNo?:number; score?:number
  index?:number; channel?:string; source?:string
  [key:string]:unknown
}
/** 回答卡片展示的支撑断言（来自断言召回通道的已裁决断言）。 */
export interface QuerySupportClaim {
  claimId?:string; subject?:string; predicate?:string; object?:string
  conditions?:string[]; confidence?:number; status?:string
}
/** 一条图谱路径：多跳推导走过的关系序列，回答里每一句都能落回这里。 */
export interface QueryGraphPathHop {
  sourceName?:string; relationType?:string; targetName?:string
  sourceCode?:string; targetCode?:string
}
/** 结论来源：这句话是查到的、沿图谱走出来的，还是规则推出来的。 */
export interface QueryConclusionSource {
  type?:string; label?:string
  graphPaths?:QueryGraphPathHop[][]
  evidenceKinds?:string[]
}
export interface QueryResult {
  queryId?:string; traceId?:string; question?:string; answer?:string
  questionType?:string; questionTypeLabel?:string; rewrittenQuery?:string; routerNote?:string
  channels?:string[]; strategies?:string[]; contextChars?:number; durationMs?:number
  reranked?:boolean; confidence?:number; claims?:QuerySupportClaim[]; citations?:QueryCitation[]
  model?:string; answerQualification?:{gateStatus?:string;[key:string]:unknown}
  conclusionSource?:QueryConclusionSource
  /** 答案交付格式：服务端实际生效的格式（default / markdown / text / html）。 */
  answerFormat?:string
  [key:string]:unknown
}
export interface GraphNode {
  code:string; type?:string; title?:string; status?:string; objectType?:string
  confidence?:number|null; distance?:number; degree?:number; [key:string]:unknown
}
export interface GraphEdge {
  source?:string; sourceType?:string; target?:string; targetType?:string
  type?:string; semanticType?:string; confidence?:number|null; [key:string]:unknown
}
export interface GraphResult {
  provider?:string; source?:string; available?:boolean; reason?:string; depth?:number
  truncated?:boolean; seed?:Record<string,unknown>
  nodes?:GraphNode[]; edges?:GraphEdge[]; [key:string]:unknown
}
/** 工作台图谱概览：知识库级关系视图，带全图规模，便于说明「展示的是最关联的一部分」。 */
export interface GraphOverview extends GraphResult {
  totalNodes?:number; totalEdges?:number; limit?:number
}
export interface IngestionRun {
  id:number; runCode:string; knowledgeBaseId:number; sourceName:string; status:string
  currentStage:string; chunkCount:number; cardCount:number; claimCount:number
  errorMessage?:string; startedTime?:string; finishedTime?:string
}
export interface IngestionEvent {
  id:number; runId:number; eventType:string; stage:string; status:string; message?:string
  detailJson?:string; createdTime?:string
}
export interface IngestionRunDetail extends IngestionRun { events:IngestionEvent[] }
/** 摄入阶段的中文化标签：运行日志与上传期间的实时提示共用同一套叫法。 */
const STAGE_LABELS:Record<string,string> = {
  SOURCE_RECEIVED:'接收资料', SOURCE:'接收资料',
  PARSE:'文档解析', PERSIST:'权威数据持久化',
  INDEX:'索引入队', STANDARD:'标准化入队', EXTRACT:'知识抽取',
  COMPLETED:'已完成', FAILED:'失败',
  /** 文档状态机新增阶段：重跑需要重新切分，旧 revision 保留为 SUPERSEDED。 */
  CHUNK:'内容切分', RETRY:'重跑', RETRYING:'重跑中', READY:'已就绪',
  PROCESSING:'处理中', REINDEXING:'重建索引', UPLOADED:'已上传', DIRTY:'索引失效',
}
export const stageLabel = (stage?:string):string =>
  STAGE_LABELS[String(stage || '').toUpperCase()] || String(stage || '')

/** 文档状态（设计 §16.1）：与后端 t_document.status 取值一一对应。 */
const DOCUMENT_STATUS_LABELS:Record<string,string> = {
  UPLOADED:'已上传', PROCESSING:'处理中', READY:'已就绪', FAILED:'失败',
  RETRYING:'重跑中', DIRTY:'索引失效', REINDEXING:'重建索引', ARCHIVED:'已归档',
}
export const documentStatusLabel = (status?:string):string =>
  DOCUMENT_STATUS_LABELS[String(status || '').toUpperCase()] || String(status || '—')
/** 状态到 Element Tag 语义色的映射，失败与失效需要一眼可见。 */
export const documentStatusTag = (status?:string):'success'|'danger'|'warning'|'info'|'primary' => {
  switch (String(status || '').toUpperCase()) {
    case 'READY': return 'success'
    case 'FAILED': return 'danger'
    case 'DIRTY': case 'RETRYING': return 'warning'
    case 'PROCESSING': case 'REINDEXING': case 'UPLOADED': return 'primary'
    default: return 'info'
  }
}

/**
 * 界面枚举值统一展示为中文：卡片、断言、实体共用同一套叫法，跨模块对照时语义一致；
 * 英文取值只出现在接口报文、日志与 SQL 里，界面不再混排，避免同一状态出现两种写法。
 * 未收录的值按原值透传，新增枚举不会退化成空白。
 */
const STATUS_LABELS:Record<string,string> = {
  DRAFT:'草稿', PENDING:'处理中', PENDING_REVIEW:'待评审', UNCERTAIN:'待确认',
  APPROVED:'已审批', EFFECTIVE:'已生效', CONFLICTED:'存在冲突', REJECTED:'已驳回',
  SUPPORTED:'证据支持', PARTIALLY_SUPPORTED:'部分支持',
  SUPERSEDED:'已替代', EXPIRED:'已过期', INVALID:'已作废',
  ACTIVE:'有效', MERGED:'已合并', ARCHIVED:'已归档', RESOLVED:'已处理', UNKNOWN:'未知',
  DISABLED:'已停用', DEPRECATED:'已弃用', INACTIVE:'未启用', RUNNING:'执行中',
  SUCCESS:'成功', FAILED:'失败', PUBLISHED:'已发布', PROCESSED:'已处理', DEAD:'已进入死信',
  REVOKED:'已吊销', RETRYING:'重试中', ENABLED:'已启用',
}
/** 知识卡片类型：取值来自 ai-service 抽取词表 FACT|PROCEDURE|RULE|PROBLEM_SOLUTION|CONCEPT。 */
const CARD_TYPE_LABELS:Record<string,string> = {
  FACT:'事实', PROCEDURE:'流程', RULE:'规则', PROBLEM_SOLUTION:'问题处置',
  CONCEPT:'概念', TERM:'术语',
}
/** 断言极性：negation 冲突判定依赖这两个取值，前端只做展示。 */
const POLARITY_LABELS:Record<string,string> = { POSITIVE:'正向', NEGATIVE:'负向' }
/** 实体类型：由抽取阶段给出，缺省为 TERM。 */
const ENTITY_TYPE_LABELS:Record<string,string> = {
  TERM:'术语', PERSON:'人物', ORG:'组织', ORGANIZATION:'组织', DEPARTMENT:'部门', ROLE:'角色',
  PRODUCT:'产品', SYSTEM:'系统', LOCATION:'地点', EVENT:'事件', DATE:'时间', METRIC:'指标',
  DOCUMENT:'文档', CONCEPT:'概念',
}
/** 知识对象类型：graph / review / lifecycle 的影响面都按这套取值分组。 */
const OBJECT_TYPE_LABELS:Record<string,string> = {
  CARD:'知识卡片', CLAIM:'断言', EVIDENCE:'证据', ENTITY:'实体', SOURCE:'来源',
}

function labeled(labels:Record<string,string>, value?:unknown):string {
  const raw = String(value ?? '').trim()
  if (!raw) return '—'
  return labels[raw.toUpperCase()] || raw
}

export const statusLabel = (status?:unknown):string => labeled(STATUS_LABELS, status)
/** 索引快照完整性：取值来自后端校验结果 VERIFIED / COUNT_MISMATCH / SOURCE_CHANGED。 */
export const integrityLabel = (state?:unknown):string =>
  labeled({ VERIFIED:'完全一致', COUNT_MISMATCH:'数量不一致', SOURCE_CHANGED:'来源已变更' }, state)

/** 平台角色：决定可见的一级模块、页内 Tab 与可执行的操作。 */
const USER_ROLE_LABELS:Record<string,string> = {
  ADMIN:'系统管理员', KNOWLEDGE_ADMIN:'知识系统管理员',
  KNOWLEDGE_OPERATOR:'知识运营员', KNOWLEDGE_AUDITOR:'知识审核员', KNOWLEDGE_VIEWER:'知识浏览员',
  EDITOR:'知识运营员', VIEWER:'知识浏览员',
}
/** 知识库可见范围。 */
const VISIBILITY_LABELS:Record<string,string> = {
  PUBLIC:'公开', PRIVATE:'私有', GROUP:'用户组',
}
export const userRoleLabel = (role?:unknown):string => labeled(USER_ROLE_LABELS, role)
export const visibilityLabel = (visibility?:unknown):string => labeled(VISIBILITY_LABELS, visibility)
export const cardTypeLabel = (type?:unknown):string => labeled(CARD_TYPE_LABELS, type)
export const polarityLabel = (polarity?:unknown):string => labeled(POLARITY_LABELS, polarity)
export const entityTypeLabel = (type?:unknown):string => labeled(ENTITY_TYPE_LABELS, type)
export const objectTypeLabel = (type?:unknown):string => labeled(OBJECT_TYPE_LABELS, type)

/** 生命周期状态对应的标签配色，卡片 / 断言 / 实体共用同一视觉语义。 */
export const statusTagType = (status?:unknown):'success'|'warning'|'danger'|'info' => {
  switch (String(status ?? '').toUpperCase()) {
    case 'APPROVED': case 'EFFECTIVE': case 'ACTIVE': case 'RESOLVED': return 'success'
    case 'DRAFT': case 'UNCERTAIN': case 'PENDING': case 'PENDING_REVIEW': return 'warning'
    case 'CONFLICTED': case 'REJECTED': case 'INVALID': return 'danger'
    default: return 'info'
  }
}
export interface IndexSchema {
  id?:number; releaseId?:string; schemaVersion?:string; status?:string; integrityState?:string
  expectedCount?:number; elasticsearchCount?:number; milvusCount?:number; [key:string]:unknown
}
export interface OutboxStats { pending?:number; retry?:number; dead?:number; [key:string]:unknown }
export interface DriftResult { [key:string]:unknown }
export interface Capabilities {
  retrieval?:Record<string,unknown>; reranker?:Record<string,unknown>; [key:string]:unknown
}

export interface EvidenceRow {
  evidenceCode:string; quoteText?:string; authorityScore?:number; freshnessScore?:number
  sourceName?:string; locationJson?:string; contentUnitCode?:string; headingPath?:string
  pageNo?:number; documentCode?:string; documentTitle?:string
}
export interface KnowledgeVersion {
  id:number; objectType:string; objectCode:string; versionNo:number; title?:string; summary?:string
  content?:string; status?:string; confidence?:number; changeType?:string; changedBy?:string
  supersedesVersionNo?:number; createdTime?:string
}
export interface ReviewHistory { id:number; decision:string; reviewer?:string; comment?:string; createdTime?:string }
export interface KnowledgeObjectDetail {
  object:Record<string,unknown>; evidence:EvidenceRow[]; conflicts:ConflictRow[];
  reviews:ReviewHistory[]; assessments:AssessmentRow[]; graph:GraphResult; versions:KnowledgeVersion[]
  /** Chunk 详情才有：同文档上下相邻块，用于把命中的片段放回上下文中读。 */
  neighbors?:DocumentChunk[]
}

export interface QueryHistoryItem {
  queryId:string; originalQuery:string; answer?:string; modelName?:string
  latencyMs?:number; errorCode?:string; createdTime?:string
}
export interface QueryTrace extends QueryHistoryItem {
  traceId?:string; rewrittenQueriesJson?:unknown; routerDecisionJson?:unknown
  retrievalJson?:unknown; fusionJson?:unknown; rerankJson?:unknown
  selectedContextJson?:unknown; citationsJson?:unknown; tokenUsageJson?:unknown; traceJson?:unknown
  answerQualification?:AnswerQualification
  /** 9 段流水线时间轴（设计 §12.5），后端已解析为数组；缺失时回退解析 traceJson。 */
  stages?:PipelineStage[]; pipelineJson?:unknown
}

export interface AnswerClaimAssessment {
  claimId:string; statement:string; decision:string; confidence?:number; entailmentScore?:number
  evidenceIds?:unknown; evidenceIdsJson?:unknown; evidenceStatusesJson?:unknown; includedInFinalAnswer?:boolean
}
export interface AnswerQualification {
  queryId?:string; knowledgeBaseId?:number; policyVersion?:string; gateStatus?:string
  evidenceGateStatus?:string; structuredGateStatus?:string; claimCount?:number
  originalAnswerHash?:string; deliveredAnswerHash?:string; claims?:AnswerClaimAssessment[]
}
export interface AnswerQualificationSummary {
  knowledgeBaseId:number; windowHours:number; summary:Record<string,number|string>
  policies:Array<Record<string,number|string>>
}

export interface EvidenceDetail extends EvidenceRow {
  evidenceStatus?:string; sourceCode?:string; sourceType?:string; sourceUri?:string
  mimeType?:string; parseStatus?:string; content?:string; contentHash?:string
}

export interface TraceCandidate {
  id:string; title?:string; channel?:string; score?:number; rerankScore?:number
  objectType?:string; contentUnitId?:number; sourceName?:string; pageNo?:number
  fusionRank?:number; rerankRank?:number; rankDelta?:number; channelRanks?:Record<string,number>
  /** 分数构成（设计 §12.4）：候选为什么被召回、各阶段如何改分。 */
  scoreBreakdown?:ScoreBreakdown
  [key:string]:unknown
}
/** 单通道命中明细：原始分与融合贡献分离，避免融合后无法解释来源。 */
export interface ScoreChannelHit {
  channel:string; label?:string; rank?:number|null; rawScore?:number|null
  weight?:number; rrfContribution?:number|null
}
/** 候选分数构成：RRF → 质量加权 → 重排 → 最终分，外加命中与过滤解释。 */
export interface ScoreBreakdown {
  rrfScore?:number; rrfK?:number; channelWeights?:Record<string,number>
  channels?:ScoreChannelHit[]; qualityBoost?:number; qualitySignals?:Record<string,number>
  preRerankScore?:number; rerankScore?:number; finalScore?:number
  fusionRank?:number; rerankRank?:number; rankDelta?:number
  whyMatched?:string[]; filteredBy?:string[]
  [key:string]:unknown
}
/** Trace 时间轴的一段（设计 §12.5）：输入输出数量、耗时、模型/索引版本与错误。 */
export interface PipelineStage {
  key:string; label?:string; latencyMs?:number|null
  inputCount?:number|null; outputCount?:number|null
  detail?:Record<string,unknown>; errors?:unknown
}
/** 被过滤原因的展示文案：与 ai-service 的 filteredBy 取值对应。 */
const FILTER_REASON_LABELS:Record<string,string> = {
  RERANK_DEPTH_CUTOFF:'未进入重排深度', TOPK_CUTOFF:'未进入 TopK',
  ACL_DENIED:'权限不足', STATUS_FILTERED:'状态不可见', DEDUP:'去重合并',
}
export const filterReasonLabel = (reason?:string):string =>
  FILTER_REASON_LABELS[String(reason || '').toUpperCase()] || String(reason || '')
export interface AclSubject { subjectCode:string; displayName?:string; departmentCode?:string; status:string }
export interface AclRole { roleCode:string; roleName?:string; status:string }
export interface AclAudit {
  id:number; subjectCode?:string; resourceType:string; resourceCode?:string
  actionName:string; decision:string; reason?:string; createdTime?:string
}
export interface AclChange {
  id:number; changeCode:string; resourceType:string; resourceCode:string
  propagationState:string; changedBy?:string; createdTime?:string
  propagationResult?:unknown; lastError?:string; completedTime?:string
}

/** 模型配置段落：字段与 ai-service 的持久化 JSON 保持一致（下划线命名）。 */
export interface ModelSection {
  provider:string; base_url:string; api_key:string; model:string; api_key_set:boolean
  temperature?:number; max_tokens?:number; timeout?:number; enabled?:boolean; dim?:number
  [key:string]:unknown
}
export interface ModelSettings {
  llm:ModelSection; embedding:ModelSection
  source?:'file'|'env'; settingsFile?:string
}
export interface ModelTestResult {
  ok?:boolean; provider?:string; model?:string; latencyMs?:number
  dimension?:number; sample?:string; [key:string]:unknown
}
/**
 * 检索索引开关：Elasticsearch 全文与 Milvus 向量。
 * 关闭即由本地索引承接，保存后即时生效并持久化；掩码凭据回传时表示"不修改"。
 */
export interface RetrievalSettings {
  mode:'local'|'auto'|'external'|'dual'
  externalRequired:boolean
  elasticsearch:{enabled:boolean; url:string; index:string; readAlias:string; username:string
    password:string; verifySsl:boolean; analyzer:string; passwordSet?:boolean; usernameSet?:boolean}
  milvus:{enabled:boolean; url:string; collection:string; readAlias:string; token:string
    tokenSet?:boolean}
  /** 各引擎实际可用性：与"是否启用"是两件事，界面要分开呈现。 */
  providers?:Record<string,{provider?:string; available?:boolean; detail?:string
    url?:string; [key:string]:unknown}>
  source?:'file'|'env'; settingsFile?:string
}

/** 问答策略：与 ai-service 的 query_policy 保持一致（驼峰命名）。 */
export interface RouteRule {
  questionType:string; enabled:boolean; keywords:string[]; strategies:string[]
  priority:number; reason?:string
}
export interface RewritePolicy {
  enabled:boolean; llmEnabled:boolean; maxVariants:number; maxRecallRounds:number
  synonyms:Record<string, string[]>
}
export interface ChannelPolicy { enabled:boolean; weight:number; scale:number }
export interface RecallPolicy {
  channels:Record<string, ChannelPolicy>; recallMultiplier:number; minRecallCandidates:number
}
export interface ChunkBoostPolicy { enabled:boolean; base:number; headingHit:number; coverage:number }
export interface FusionPolicy {
  rrfK:number; qualitySignalsEnabled:boolean
  qualityWeights:{ authority:number; freshness:number; confidence:number; evidence:number }
  chunkBoost:ChunkBoostPolicy
}
export interface RerankPolicy { enabled:boolean; topN:number }
export interface AnswerPolicy {
  contextMaxChars:number; minChunkEvidence:number; evidenceGateEnabled:boolean; faithfulnessMin:number; entailmentMin:number
  policyVersion:string; noAnswerText:string
}
export interface QueryPolicy {
  version:string
  route:{ enabled:boolean; rules:RouteRule[]; defaultStrategies:string[] }
  rewrite:RewritePolicy; recall:RecallPolicy; fusion:FusionPolicy
  rerank:RerankPolicy; answer:AnswerPolicy
  source?:'file'|'default'; policyFile?:string; policyVersionDefault?:string
}
export interface QueryPolicySchema {
  channels:{ name:string; label:string; description:string }[]
  defaults:QueryPolicy; policyFile:string; policyVersionDefault:string
}

/* ---- 用户、用户组与登录会话 ---- */

export type UserRoleValue =
  |'ADMIN'|'KNOWLEDGE_ADMIN'|'KNOWLEDGE_OPERATOR'|'KNOWLEDGE_AUDITOR'|'KNOWLEDGE_VIEWER'
export interface UserAccount {
  id:number; userCode:string; username:string; displayName:string; email:string
  role:UserRoleValue|string; roleLabel?:string; status:string; lastLoginTime?:string|null
  groupIds:number[]; groupNames:string[]
}
export interface UserGroupRow {
  id:number; groupCode:string; name:string; description:string; status:string
  memberCount:number; memberIds:number[]
}
export interface AuthSessionResult { token:string; expiresAt:string; user:UserAccount }
/** 后端是否开启认证、是否允许匿名只读、以及是否已完成初始管理员创建。 */
export interface AuthStatus { enabled:boolean; allowAnonymous:boolean; initialized:boolean }
export interface AuthMe extends AuthStatus {
  user:UserAccount|null; canManageUsers:boolean; canWriteKnowledge:boolean
  /** 角色自带的权限码：前端据此裁剪菜单与操作，后端按同一套码校验接口。 */
  permissions:string[]
  /** 角色中文名，避免前端再维护一份映射。 */
  roleLabel:string
  /** 该角色登录后默认进入的视图 key。 */
  homeView:string
}
/** 角色字典：角色码、中文名、职责说明与自带权限码。 */
export interface RoleCatalogItem {
  code:string; label:string; homeView:string; description:string; permissions:string[]
}
export interface RoleCatalog { roles:RoleCatalogItem[]; permissions:Record<string,string> }

/* ---- 对外接口服务：RESTful / MCP Server / SSE ---- */

export interface ExternalEndpoint { method:string; path:string; summary:string }
export interface ExternalRestChannel {
  enabled:boolean; basePath:string; auth:string; endpoints:ExternalEndpoint[]
}
export interface ExternalMcpChannel {
  enabled:boolean; endpoint:string; protocolVersion:string; methods:string[]; tools:string[]
}
export interface ExternalSseChannel {
  enabled:boolean; heartbeatSeconds:number; maxMinutes:number; endpoints:ExternalEndpoint[]
}
export interface ExternalServiceOverview {
  rest:ExternalRestChannel; mcp:ExternalMcpChannel; sse:ExternalSseChannel
  activeKeys:number; docs:{ openapi:string; swaggerUi:string }
}
export interface ExternalKeyRow {
  id:number; keyCode:string; name:string; keyPrefix:string
  knowledgeBaseId:number|null; knowledgeBaseName:string|null
  scopes:string; rateLimitPerMinute:number; status:string
  expiresAt:string|null; lastUsedAt:string|null; createdByName:string|null; createdTime:string
  /** 明文仅创建响应携带一次。 */
  secret?:string
}
export interface ExternalAuditRow {
  id:number; keyId:number|null; keyCode:string|null; channel:string; method:string
  knowledgeBaseId:number|null; statusCode:number; durationMs:number
  traceId:string|null; message:string|null; createdTime:string
  callerName?:string|null; requestParams?:string|null; responseData?:string|null
}

/* ---- 评测中心（设计 §14）：数据集 / 用例 / 运行 / 知识缺口 ---- */

export interface EvaluationDataset {
  id:number; datasetCode:string; knowledgeBaseId:number; name:string; description?:string
  version?:string; status:string; createdBy?:string; createdTime?:string; caseCount?:number
}
export interface EvaluationCase {
  id:number; caseCode:string; datasetId:number; question:string
  /** 期望命中的知识标识 JSON 字符串：内容单元 id / 文档名 / 证据码皆可。 */
  expectedObjectCodesJson?:string; expectedEvidenceCodesJson?:string; expectedAnswer?:string
  tagsJson?:string; difficulty?:string; version?:string; status?:string; createdTime?:string
}
/** 一次评测运行的聚合指标；delta 与 verdict 只在指定基线时才有值。 */
export interface EvaluationRun {
  id:number; runCode:string; datasetId:number; knowledgeBaseId:number; status:string
  mode:string; topK:number; policyVersion?:string; baselineRunCode?:string|null
  caseCount:number; hitAt1Rate?:number|null; hitAt3Rate?:number|null; hitAt5Rate?:number|null
  recallAt5?:number|null; mrr?:number|null; ndcgAt10?:number|null
  latencyP50Ms?:number|null; latencyP95Ms?:number|null
  citationAccuracy?:number|null; faithfulness?:number|null
  regressionVerdict?:string|null; baselineDeltaJson?:string|null; gapsJson?:string|null
  errorMessage?:string|null; startedTime?:string; finishedTime?:string
  datasetName?:string; datasetCode?:string
  results?:EvaluationResult[]; baseline?:EvaluationRun
}
/** 逐用例证据：没有它，平均值无法回答"哪道题坏了"。 */
export interface EvaluationResult {
  id:number; runId:number; caseId:number; caseCode:string; queryId?:string; status:string
  expectedCodesJson?:string; retrievedCodesJson?:string
  hitAt1?:number; hitAt3?:number; hitAt5?:number
  reciprocalRank?:number; recallAt5?:number; ndcgAt10?:number; latencyMs?:number
  citationTotal?:number; citationHit?:number; citationAccuracy?:number; faithfulness?:number
  errorMessage?:string; createdTime?:string
}
export interface EvaluationGap {
  runCode:string; knowledgeBaseId:number; caseCount:number; gapCount:number
  gaps:{ caseCode:string; queryId?:string; expectedCodes?:string; retrievedCodes?:string }[]
}

/* ---- 治理洞察（设计 §5.1 知识质量 / §9.4 Lineage + 版本溯源） ---- */

/** 版本快照列表项：正文只回前 400 字符，差异比对用得上，列表渲染不至于拖垮页面。 */
export interface VersionSnapshotRow {
  id:number; objectType:string; objectCode:string; versionNo:number
  title?:string; summary?:string; excerpt?:string; status?:string; confidence?:number
  changeType?:string; changedBy?:string; supersedesVersionNo?:number|null; createdTime?:string
}
export interface VersionGroupRow { objectType:string; changeType:string; total:number; latestTime?:string }
export interface VersionPage {
  total:number; rows:VersionSnapshotRow[]; groups:VersionGroupRow[]
}
/** 证据原文定位链：source → document → page → heading → contentUnit（设计：不另建第二份原文）。 */
export interface VersionEvidenceRow {
  evidenceCode:string; quoteText?:string; authorityScore?:number; freshnessScore?:number
  evidenceStatus?:string; sourceName?:string; sourceType?:string
  documentTitle?:string; documentCode?:string; contentUnitCode?:string
  headingPath?:string; pageNo?:number|null; pageEnd?:number|null
}
export interface VersionEvidence {
  objectType:string; objectCode:string; items:VersionEvidenceRow[]
}
/** 质量概览：每一项都来自库里已有的数据，没有为看板凭空造的指标。 */
export interface QualityOverview {
  trustedCoverage:{ approved:number; total:number; ratio:number; byStatus:Record<string, number> }
  citation:{
    answerCount:number; claimCount:number; supportedClaimCount:number; citationCount:number
    accuracy:number; faithfulness:number; completeness:number; latestTime?:string
  }
  /** 最近一次成功的评测运行；没有跑过评测就是 null，不能用 0 假装测过。 */
  retrieval:{
    runCode:string; mode:string; status:string; caseCount:number
    hitAt1Rate?:number|null; hitAt5Rate?:number|null; recallAt5?:number|null; mrr?:number|null
    ndcgAt10?:number|null; latencyP50Ms?:number|null; latencyP95Ms?:number|null
    regressionVerdict?:string|null; finishedTime?:string; datasetName?:string
  } | null
  freshness:{
    total:number; freshCount:number; staleCount:number; expiredCount:number
    ratio:number; latestUpdate?:string
  }
  health:{
    pendingReview:number; pendingCardReview:number; openConflict:number
    failedIngestion:number; uncertainClaim:number; conflictedClaim:number
  }
  statusBreakdown:{ objectType:string; status:string; total:number }[]
  withinDays:number
}

/* ---- MCP 服务管理与调用审计 ---- */

/** 单个 MCP 工具的入参定义：智能体只能按 schema 调用，页面把它原样展示出来。 */
export interface McpToolProperty { type:string; description:string }
export interface McpTool {
  name:string; description:string
  inputSchema:{ type:string; properties:Record<string, McpToolProperty>; required:string[] }
}
export interface McpManifest {
  protocolVersion:string; serverName:string; serverVersion:string
  endpoint:string; enabled:boolean; auth:string
  tools:McpTool[]
  initialize:Record<string, unknown>
}
export interface McpCallAudit {
  id:number; keyId:number|null; keyCode:string|null; channel:string; method:string
  knowledgeBaseId:number|null; statusCode:number; durationMs:number
  traceId:string|null; message:string|null; createdTime:string
}

/* ---- 知识工作台聚合（详细设计 §3 / §11） ---- */

/** 最近更新的知识对象：工作台「最近更新」列表。 */
export interface RecentObject {
  objectType:'CARD'|'CLAIM'|string; objectCode:string; displayTitle:string
  status:string; confidence?:number|null; updatedTime?:string
}
/** KPI 卡：来源 / 文档 / 卡片 / 断言 / 实体 / 关系，均可点击进入资产浏览。 */
export interface DashboardSummary {
  knowledgeBaseId:number
  sourceCount:number; documentCount:number; chunkCount:number
  cardCount:number; claimCount:number; entityCount:number; relationCount:number
  /** 知识健康度：按治理待办的加权扣分模型，满分 100。 */
  healthScore:number
  pendingReviewCount:number; openConflictCount:number
  recent:RecentObject[]
}
/** 生命周期单阶段：接入 → 解析 → 抽取 → 衍生 → 裁决 → 发布。 */
export interface LifecycleStage {
  code:'INGEST'|'PARSE'|'EXTRACT'|'DERIVE'|'JUDGE'|'PUBLISH'|string
  label:string; count:number; abnormal:number
  /** 点击目标：IA 二级入口 key。 */
  target:string
}
export interface DashboardLifecycle { knowledgeBaseId:number; stages:LifecycleStage[] }
/** 治理卡片条目：只展示需要行动的数字，level 决定配色。 */
export interface GovernanceItem {
  key:string; label:string; count:number
  level:'ok'|'warn'|'block'; target:string
}
export interface DashboardGovernance {
  knowledgeBaseId:number; healthScore:number
  pendingReview:number; conflict:number; expired:number
  noEvidence:number; lowConfidence:number; staleDerived:number
  items:GovernanceItem[]
}

/* ---- 知识集成（详细设计 §6） ---- */

export interface IntegrationService {
  code:string; name:string; purpose:string; endpoint:string
  /** 集成服务状态：DRAFT → TESTING → PUBLISHED → DISABLED → ARCHIVED。 */
  status:'DRAFT'|'TESTING'|'PUBLISHED'|'DISABLED'|'ARCHIVED'|string
}
export interface ProtocolStat { protocol:string; calls:number; successCount:number; errorCount:number }
export interface IntegrationHealthRow { name:string; status:string; errorRate:number; p95Ms:number }
export interface IntegrationCallRow {
  time?:string; client?:string|null; protocol?:string; method?:string
  status?:number|null; latencyMs?:number|null; traceId?:string|null; message?:string|null
  /** 调用方名称：凭证自命名的接入方，便于人工辨认。 */
  caller?:string|null
  /** 脱敏后的传入参数 JSON 文本。 */
  requestParams?:string|null
  /** 脱敏后的返回数据 JSON 文本；流式通道不采集。 */
  responseData?:string|null
}
/** 单次调用的全部属性：调用日志点「查看 Trace」后展示的完整明细。 */
export interface IntegrationCallDetail extends IntegrationCallRow {
  id?:number; keyId?:number|null; knowledgeBaseId?:number|null
}
export interface IntegrationOverview {
  windowHours:number
  serviceCount:number; credentialCount:number; activeCredentialCount:number
  calls:number; successCount:number; errorCount:number; successRate:number
  p95Ms:number; avgDurationMs:number; maxDurationMs:number
  services:IntegrationService[]
  protocolDistribution:ProtocolStat[]
  health:IntegrationHealthRow[]
  recentCalls:IntegrationCallRow[]
}

/* ---- 任务中心与告警（设计 §7） ---- */

/** 任务状态：排队 → 运行中 → 成功 / 失败 / 已取消 / 死信。 */
export type TaskStatus = 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELED'|'DEAD'|string
export interface TaskRun {
  taskCode:string; knowledgeBaseId?:number; taskType?:string; taskName?:string
  targetType?:string; targetCode?:string; status?:TaskStatus; stage?:string; progress?:number
  triggerType?:'MANUAL'|'SCHEDULED'|'EVENT'|'RETRY'|string
  triggeredBy?:string; retryCount?:number; maxAttempts?:number
  errorMessage?:string; payloadJson?:string|null; resultJson?:string|null
  startedTime?:string|null; finishedTime?:string|null; createdTime?:string|null
}
export interface TaskStats { total?:number; queued?:number; running?:number; succeeded?:number
  failed?:number; dead?:number; canceled?:number; failure?:number
  deadLetter?:number; documentFailed?:number }
export interface AlertStats { open?:number; acked?:number; critical?:number; unresolved?:number }
export interface AlertRule {
  ruleCode:string; knowledgeBaseId?:number|null; name?:string; metric?:string; metricLabel?:string
  severity?:'INFO'|'WARNING'|'CRITICAL'|string; threshold?:number; windowMinutes?:number
  enabled?:boolean; channels?:string
}
export interface AlertEvent {
  alertCode:string; ruleCode?:string; knowledgeBaseId?:number|null
  severity?:'INFO'|'WARNING'|'CRITICAL'|string
  status?:'OPEN'|'ACKED'|'RESOLVED'|string
  title?:string; detailJson?:string|null; metricValue?:number; occurCount?:number
  firstSeen?:string|null; lastSeen?:string|null; ackedBy?:string|null; createdTime?:string|null
}
export interface TaskOverview {
  tasks:TaskRun[]; stats:TaskStats; taskTypes:string[]
  alerts:AlertEvent[]; alertStats:AlertStats; rules:AlertRule[]
  /** 任务与告警各自按当前筛选条件的命中总数，供分页条渲染（不传时按一页处理）。 */
  taskTotal?:number; alertTotal?:number
}
export interface TaskStatsPair { tasks:TaskStats; alerts:AlertStats }
export interface TaskDictionaries {
  taskTypes:string[]
  alert:{ metrics:string[]; metricLabels:Record<string,string>; severities:string[]; statuses:string[] }
}
export interface AlertList { events:AlertEvent[]; stats:AlertStats; total?:number }

/** ACL 流水（访问审计 / 权限变更）的分页结果：只增不减的日志，必须能翻到任意一页。 */
export interface AclLogPage<T> { items:T[]; total:number; page:number; size:number; pageCount:number }

/* ---- 知识衍生（设计 §4.5） ---- */

export interface DerivationRule {
  ruleCode:string; knowledgeBaseId?:number; name?:string
  derivationType?:'GRAPH_PATH'|'TEMPLATE'|'AI'|string
  /** 预设编码：由常用规则库一键启用而来，可用于恢复默认定义。 */
  presetCode?:string|null
  enabled?:boolean; scheduleType?:'MANUAL'|'SCHEDULED'|string
  cronExpr?:string|null; nextFireAt?:string|null; lastFireAt?:string|null
  scheduleText?:string; definitionJson?:string|null; algorithmVersion?:string
  status?:string; lastError?:string|null; candidateCount?:number; staleCount?:number
}
/** 定义字段的可修改边界：控制台按它渲染表单，服务端按它校验，两者同一套取值。 */
export interface DerivationFieldBound {
  field:string; label:string
  kind:'ENUM'|'NUMBER'|'STRING'
  default:string|number
  hint?:string
  min?:number; max?:number; step?:number
  minLength?:number; maxLength?:number
  options?:string[]; placeholders?:string[]
}
export interface DerivationPreset {
  presetCode:string; name:string
  derivationType:'GRAPH_PATH'|'TEMPLATE'|'AI'|string
  description?:string; scene?:string
  scheduleType?:string
  definition?:Record<string, unknown>
  bounds?:Record<string, DerivationFieldBound>
  /** 该知识库是否已启用此预设；已启用时带上规则编码。 */
  installed?:boolean; ruleCode?:string|null
}
export interface DerivedCandidate {
  candidateCode:string; knowledgeBaseId?:number; ruleCode?:string
  derivationType?:string; algorithmVersion?:string; title?:string; confidence?:number
  status?:'DRAFT'|'PENDING'|'APPROVED'|'REJECTED'|'STALE'|string
  baseCount?:number; staleReason?:string|null
  createdTime?:string|null; updatedTime?:string|null
}
export interface DerivedCandidateDetail extends DerivedCandidate {
  content?:string; baseAssertionCodes?:string[]; graphPath?:unknown
  evidenceJson?:string|null; derivedFromJson?:string|null
  permissionTags?:string[]|null; authorityScore?:number
}
export interface DerivationRun {
  runCode:string; knowledgeBaseId?:number; ruleCode?:string
  triggerType?:'MANUAL'|'SCHEDULED'|string; status?:string
  scannedCount?:number; candidateCount?:number; staleCount?:number
  errorMessage?:string|null; startedTime?:string|null; finishedTime?:string|null
}
export interface DerivationRunResult {
  runCode:string; ruleCode?:string; triggerType?:string
  scanned?:number; candidates?:number; errorMessage?:string
}
export interface DerivationOverview {
  rules:DerivationRule[]; candidates:DerivedCandidate[]; runs:DerivationRun[]
  stats:Record<string,number>
  schema:{
    derivationTypes:string[]; schedules:string[]; statuses:string[]
    algorithmVersion:string; cronHint:string
    definitionDefaults:Record<string,Record<string,unknown>>
    definitionBounds?:Record<string,Record<string,DerivationFieldBound>>
    relationTypes?:string[]
    editPolicy?:Record<string,string>
    presets?:DerivationPreset[]
  }
}

/* ---- 知识 Schema（设计 §7） ---- */

export interface SchemaType {
  knowledgeBaseId?:number; kind?:'KNOWLEDGE_TYPE'|'ENTITY_TYPE'|'RELATION_TYPE'|string
  typeCode:string; label?:string; description?:string
  propertiesJson?:string|null; status?:'ACTIVE'|'DEPRECATED'|string
  builtIn?:number; version?:number; updatedBy?:string|null; updatedTime?:string|null
}
export interface SchemaRelease {
  releaseCode:string; knowledgeBaseId?:number; version?:string
  state?:'DRAFT'|'PUBLISHED'|'ARCHIVED'|string
  definitionJson?:string|null; validationJson?:string|null; checksum?:string
  publishedBy?:string|null; publishedTime?:string|null; createdTime?:string|null
  published?:boolean; delivery?:{ delivered?:boolean; error?:string|null }
}
export interface SchemaValidation {
  valid:boolean; errors:string[]; warnings:string[]
  violations:{ kind?:string; typeCode?:string; useCount?:number }[]
  violationCount?:number; typeCount?:number; validatedAt?:string
}
export interface SchemaOverview {
  types:SchemaType[]; published:SchemaRelease; releases:SchemaRelease[]
  validation:SchemaValidation
  meta:{ kinds:string[]; propertyTypes:string[]; statuses:string[]; codePattern:string; states:string[] }
}

/* ---- 批量接入（一次提交多个文件，后端异步执行） ---- */

/** 批次内单个文件的处理结果：成功/失败逐文件独立，一个文件失败不影响其它文件。 */
export interface BatchIngestItem {
  index:number; filename:string; size:number
  status:'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELED'|string; error:string|null; attempts:number
  documentId:number|null; runId:number|null; runCode:string|null
  chunkCount:number; cardCount:number; claimCount:number
  startedAt:number|null; finishedAt:number|null
}
/** 批次整体视图：总进度 + 成功/失败计数 + 每个文件的明细，用于轮询展示。 */
export interface BatchIngestStatus {
  batchCode:string; taskCode:string|null; knowledgeBaseId:number
  status:'RUNNING'|'SUCCEEDED'|'PARTIAL'|'FAILED'|'CANCELED'|string
  fileCount:number; finishedCount:number; progress:number
  succeededCount:number; failedCount:number; canceledCount:number
  createdAt:number; finishedAt:number|null
  items:BatchIngestItem[]
}
