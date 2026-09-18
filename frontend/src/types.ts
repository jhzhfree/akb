export type KnowledgeStatus =
  | 'DRAFT' | 'APPROVED' | 'EFFECTIVE' | 'UNCERTAIN' | 'CONFLICTED'
  | 'REJECTED' | 'EXPIRED' | 'SUPERSEDED' | string

export interface ApiEnvelope<T> { success:boolean; code?:string; message?:string; data?:T }
export interface KnowledgeBase {
  id:number; baseCode:string; name:string; description?:string; status:string; createdTime?:string
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
}

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
  id?:number; objectType:string; objectCode:string; status?:string; [key:string]:unknown
}
export interface ConflictRow { id:number; status:string; [key:string]:unknown }
export interface AssessmentRow {
  id?:number; objectType?:string; objectCode?:string; decision?:string; [key:string]:unknown
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
export interface QueryResult {
  queryId?:string; traceId?:string; question?:string; answer?:string
  questionType?:string; questionTypeLabel?:string; rewrittenQuery?:string; routerNote?:string
  channels?:string[]; strategies?:string[]; contextChars?:number; durationMs?:number
  reranked?:boolean; confidence?:number; claims?:QuerySupportClaim[]; citations?:QueryCitation[]
  model?:string; answerQualification?:{gateStatus?:string;[key:string]:unknown}
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
}
export const stageLabel = (stage?:string):string =>
  STAGE_LABELS[String(stage || '').toUpperCase()] || String(stage || '')

/**
 * 界面枚举值统一展示为「中文（英文原值）」：中文保证可读，括号内的原值保证
 * 能与接口返回、日志和 SQL 里的枚举直接对齐。卡片、断言、实体共用同一套叫法，
 * 跨模块对照时语义一致；未收录的值按原值透传，新增枚举不会退化成空白。
 */
const STATUS_LABELS:Record<string,string> = {
  DRAFT:'草稿', PENDING:'处理中', PENDING_REVIEW:'待评审', UNCERTAIN:'待确认',
  APPROVED:'已审批', EFFECTIVE:'已生效', CONFLICTED:'存在冲突', REJECTED:'已驳回',
  SUPERSEDED:'已替代', EXPIRED:'已过期', INVALID:'已作废',
  ACTIVE:'有效', MERGED:'已合并', ARCHIVED:'已归档', RESOLVED:'已处理', UNKNOWN:'未知',
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
  const label = labels[raw.toUpperCase()]
  return label ? `${label}（${raw}）` : raw
}

export const statusLabel = (status?:unknown):string => labeled(STATUS_LABELS, status)
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
  [key:string]:unknown
}
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
export interface FusionPolicy {
  rrfK:number; qualitySignalsEnabled:boolean
  qualityWeights:{ authority:number; freshness:number; confidence:number; evidence:number }
}
export interface RerankPolicy { enabled:boolean; topN:number }
export interface AnswerPolicy {
  contextMaxChars:number; evidenceGateEnabled:boolean; faithfulnessMin:number; entailmentMin:number
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
