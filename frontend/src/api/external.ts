import axios, { type AxiosResponse } from 'axios'
import type {
  ApiEnvelope, ExternalAuditRow, ExternalKeyRow, ExternalServiceOverview,
} from '../types'

type ApiResponse<T> = AxiosResponse<ApiEnvelope<T>>

/* ---- 控制台治理接口：走登录会话 ---- */

export const getExternalServices = ():Promise<ApiResponse<ExternalServiceOverview>> =>
  axios.get('/api/external/admin/services')
export interface ExternalKeyPage {
  items:ExternalKeyRow[]
  total:number
  limit:number
  offset:number
  /** 已过期或即将到期的数量：由服务端算，分页后前端数不准。 */
  expiringCount:number
}
export const listExternalKeys = (params?:{ limit?:number; offset?:number }):Promise<ApiResponse<ExternalKeyPage>> =>
  axios.get('/api/external/admin/keys', { params })
/** 只删已吊销或已失效的凭证：生效中的必须先吊销，避免「删一下」悄悄断掉线上调用。 */
export const deleteExternalKey = (id:number):Promise<ApiResponse<boolean>> =>
  axios.delete(`/api/external/admin/keys/${id}`)
/** 主体详情要看该主体的全部凭证，不受列表分页影响。 */
export const listPrincipalKeys = (principalId:number):Promise<ApiResponse<ExternalKeyRow[]>> =>
  axios.get(`/api/external/admin/principals/${principalId}/keys`)
export interface ExternalPrincipalSummary {
  id:number; principalCode:string; name:string
  /** USER / AGENT / APPLICATION：决定它能不能登录控制台、能不能走委托身份。 */
  principalType?:string|null
  status:string; keyCount:number; activeGrantCount:number; createdTime:string
  ownerUserId?:number|null
}
export const listExternalPrincipals = ():Promise<ApiResponse<ExternalPrincipalSummary[]>> =>
  axios.get('/api/external/admin/principals')
export const assignExternalPrincipal = (keyId:number, body:{principalId?:number|null;name?:string|null;reason:string}):Promise<ApiResponse<ExternalKeyRow>> =>
  axios.post(`/api/external/admin/keys/${keyId}/principal`, body)
export const createExternalKey = (payload:{
  name?:string; knowledgeBaseId?:number|null; scopes?:string[]; rateLimitPerMinute?:number
  expiresAt?:string|null; memoryNamespace?:string|null; memoryAgentCode?:string|null
}):Promise<ApiResponse<ExternalKeyRow>> => axios.post('/api/external/admin/keys', payload)
export interface KnowledgeBaseGrant {
  knowledgeBaseId:number
}
export const getKnowledgeBaseGrants = (id:number):Promise<ApiResponse<KnowledgeBaseGrant[]>> =>
  axios.get(`/api/external/admin/keys/${id}/knowledge-base-grants`)
export const replaceKnowledgeBaseGrants = (id:number, knowledgeBaseIds:number[]):Promise<ApiResponse<KnowledgeBaseGrant[]>> =>
  axios.put(`/api/external/admin/keys/${id}/knowledge-base-grants`, knowledgeBaseIds)
export interface KnowledgeBaseGrantConfig {
  knowledgeBaseId:number; expiresAt:string|null; reason:string|null; status:string; createdTime:string; permissionTags:string[]|null
}
export const getKnowledgeBaseGrantConfig = (id:number):Promise<ApiResponse<KnowledgeBaseGrantConfig[]>> =>
  axios.get(`/api/external/admin/keys/${id}/knowledge-base-grants/config`)
export const replaceKnowledgeBaseGrantConfig = (id:number, grants:Array<{
  knowledgeBaseId:number; expiresAt:string|null; reason:string|null; permissionTags:string[]|null
}>):Promise<ApiResponse<KnowledgeBaseGrantConfig[]>> =>
  axios.put(`/api/external/admin/keys/${id}/knowledge-base-grants/config`, grants)
export const revokeExternalKey = (id:number):Promise<ApiResponse<ExternalKeyRow>> =>
  axios.post(`/api/external/admin/keys/${id}/revoke`)

/* ---- 主体：身份、授权与生命周期 ---- */
/**
 * 主体是授权的最终载体：知识库授权、记忆身份、外部身份绑定都挂在主体上，
 * 凭证只是主体的一种凭据——轮换或新增凭证都不改变这些。
 */
export const createExternalPrincipal = (payload:{
  principalCode:string; name:string; principalType:'USER'|'AGENT'|'APPLICATION'
  providerInstanceId?:string|null; externalSubjectId?:string|null
  memoryNamespace?:string|null; memoryAgentCode?:string|null
}):Promise<ApiResponse<ExternalPrincipalSummary>> => axios.post('/api/external/admin/principals', payload)
/** 停用主体即让其下全部凭证与记忆调用立即失效，不必逐个吊销。 */
export const updateExternalPrincipal = (id:number, payload:{
  name?:string|null; status?:'ACTIVE'|'DISABLED'|null; ownerUserId?:number|null
}):Promise<ApiResponse<ExternalPrincipalSummary>> => axios.put(`/api/external/admin/principals/${id}`, payload)
export const assignPrincipalOwnerUser = (id:number, userId:number):Promise<ApiResponse<ExternalPrincipalSummary>> =>
  axios.put(`/api/external/admin/principals/${id}/owner-user`, { userId })
export const createPrincipalKey = (principalId:number, payload:{
  name?:string; knowledgeBaseId?:number|null; scopes?:string[]; rateLimitPerMinute?:number; expiresAt?:string|null
}):Promise<ApiResponse<ExternalKeyRow>> =>
  axios.post(`/api/external/admin/principals/${principalId}/keys`, payload)

export interface PrincipalMemoryProfile {
  principalId:number; principalCode:string; principalType:string
  configured:boolean; namespace:string|null; agentCode:string|null; status:string|null
}
export const getPrincipalMemoryProfile = (id:number):Promise<ApiResponse<PrincipalMemoryProfile>> =>
  axios.get(`/api/external/admin/principals/${id}/memory-profile`)
export const savePrincipalMemoryProfile = (id:number, body:{ namespace:string; agentCode:string }):Promise<ApiResponse<PrincipalMemoryProfile>> =>
  axios.put(`/api/external/admin/principals/${id}/memory-profile`, body)
export const getPrincipalMemoryGrants = (id:number):Promise<ApiResponse<MemoryScopeGrant[]>> =>
  axios.get(`/api/external/admin/principals/${id}/memory-grants`)
export const replacePrincipalMemoryGrants = (id:number, grants:MemoryScopeGrant[]):Promise<ApiResponse<MemoryScopeGrant[]>> =>
  axios.put(`/api/external/admin/principals/${id}/memory-grants`, grants)

/* ---- Octop 身份绑定：委托身份的前提 ---- */
export interface OctopBinding {
  id:number; provider:string; providerInstanceId:string; externalUserId:string
  principalId:number|null; principalCode:string|null; status:string; reason:string|null; createdTime:string
}
export const listOctopBindings = ():Promise<ApiResponse<OctopBinding[]>> =>
  axios.get('/api/external/admin/identity-bindings/octop')

/* ---- 用户维度：个人令牌与负责主体（系统用户管理用） ---- */
export const listUserPersonalAccessTokens = (userId:number):Promise<ApiResponse<ExternalKeyRow[]>> =>
  axios.get(`/api/external/admin/users/${userId}/personal-access-tokens`)
export const revokeUserPersonalAccessToken = (userId:number, tokenId:number):Promise<ApiResponse<boolean>> =>
  axios.post(`/api/external/admin/users/${userId}/personal-access-tokens/${tokenId}/revoke`)
export const bindOctopIdentity = (body:{
  providerInstanceId:string; externalUserId:string; principalId:number; reason?:string|null
}):Promise<ApiResponse<OctopBinding>> => axios.post('/api/external/admin/identity-bindings/octop', body)
export const approveOctopBinding = (id:number, reason?:string|null):Promise<ApiResponse<OctopBinding>> =>
  axios.post(`/api/external/admin/identity-bindings/octop/${id}/approve`, { reason: reason ?? null })
export const suspendOctopBinding = (id:number, reason?:string|null):Promise<ApiResponse<OctopBinding>> =>
  axios.post(`/api/external/admin/identity-bindings/octop/${id}/suspend`, { reason: reason ?? null })
export const resumeOctopBinding = (id:number, reason?:string|null):Promise<ApiResponse<OctopBinding>> =>
  axios.post(`/api/external/admin/identity-bindings/octop/${id}/resume`, { reason: reason ?? null })
export const deleteOctopBinding = (id:number, reason?:string|null):Promise<ApiResponse<boolean>> =>
  axios.delete(`/api/external/admin/identity-bindings/octop/${id}`, { data:{ reason: reason ?? null } })
/**
 * 轮换：换发新明文并立即撤销旧凭证，主体、授权与记忆身份都保持不变。
 * 这是换密钥的正确姿势——「吊销 + 新建」会丢掉主体绑定，记忆身份会漂到新的 key:<id>。
 * 明文只在这次响应里出现一次；不传 expiresAt 表示沿用原到期时间。
 */
export const rotateExternalKey = (id:number, expiresAt?:string|null):Promise<ApiResponse<ExternalKeyRow>> =>
  axios.post(`/api/external/admin/keys/${id}/rotate`, { expiresAt: expiresAt ?? null })
export const updateExternalMemoryPolicy = (id:number, policy:{readEnabled:boolean;writeEnabled:boolean}):Promise<ApiResponse<ExternalKeyRow>> =>
  axios.put(`/api/external/admin/keys/${id}/memory-policy`, policy)
export interface MemoryScopeGrant {
  scopeType:'TEAM'|'PROJECT'|'TASK'|'WORKFLOW'|'SESSION'; scopeCode:string; canRead:boolean; canWrite:boolean
}
export const getMemoryScopeGrants = (id:number):Promise<ApiResponse<MemoryScopeGrant[]>> =>
  axios.get(`/api/external/admin/keys/${id}/memory-access`)
export const replaceMemoryScopeGrants = (id:number, grants:MemoryScopeGrant[]):Promise<ApiResponse<MemoryScopeGrant[]>> =>
  axios.put(`/api/external/admin/keys/${id}/memory-access`, grants)
export interface ManagedMemoryItem {
  memoryCode:string; agentCode:string; scopeType:string; scopeCode:string; memoryLevel:string
  content:string; sourceType:string|null; sourceRef:string|null; importance:number; confidence:number
  expiresAt:string|null; status:'ACTIVE'|'SUSPENDED'; createdTime:string; updatedTime:string
}
export const listManagedMemories = (id:number, params?:{
  status?:string; scopeType?:string; scopeCode?:string; limit?:number; offset?:number
}):Promise<ApiResponse<{ namespace:string; limit:number; offset:number; items:ManagedMemoryItem[] }>> =>
  axios.get(`/api/external/admin/keys/${id}/memories`, { params })
export const setManagedMemoryStatus = (id:number, code:string, status:'ACTIVE'|'SUSPENDED', reason:string):Promise<ApiResponse<unknown>> =>
  axios.put(`/api/external/admin/keys/${id}/memories/${encodeURIComponent(code)}/status`, { reason }, { params:{ status } })
export const forgetManagedMemory = (id:number, code:string, reason:string):Promise<ApiResponse<unknown>> =>
  axios.post(`/api/external/admin/keys/${id}/memories/${encodeURIComponent(code)}/forget`, { reason })
export interface MemoryGovernanceEvent {
  memoryCode:string; agentCode:string; scopeType:string; scopeCode:string; operation:string
  oldStatus:string|null; newStatus:string|null; reason:string; operatorId:number; createdTime:string
}
export const listMemoryGovernanceEvents = (id:number):Promise<ApiResponse<{ limit:number; offset:number; items:MemoryGovernanceEvent[] }>> =>
  axios.get(`/api/external/admin/keys/${id}/memory-events`, { params:{ limit:200 } })
export const listExternalAudits = (
  keyId?:number|null, limit = 50,
):Promise<ApiResponse<ExternalAuditRow[]>> =>
  axios.get('/api/external/admin/audits', { params:{ keyId: keyId ?? undefined, limit } })

/**
 * 连通性自检：直接用明文密钥调用对外端点，验证凭证、范围与限流是否按预期生效。
 * 自检请求不带会话 token，与真实外部调用方完全一致。
 */
export const probeExternal = (secret:string, path = '/api/v1/external/bases'):Promise<AxiosResponse<unknown>> =>
  axios.get(path, { headers:{ 'X-API-Key': secret } })

/* ---- 网站嵌入组件：用 API Key 换取嵌入令牌 ---- */

export interface EmbedTokenResult {
  token: string
  /** 长期令牌为 null */
  expiresAt: number | null
  /** 长期令牌为 null */
  expiresInSeconds: number | null
  permanent?: boolean
  knowledgeBaseId: number | null
  origin: string
  /** 令牌唯一 ID：吊销与调用日志都按它定位 */
  jti?: string
  /** jti 的掩码形式，用于界面展示 */
  displayId?: string
}

/**
 * 换令牌走对外端点而非控制台接口：这样签发过程与真实调用方完全一致，
 * 同时校验该 Key 是否授权了当前站点（allowedOrigins）。
 *
 * @param ttlSeconds 有效期秒数；不传沿用部署默认，传 -1（或任意 <= 0）签发长期有效令牌。
 */
export const issueEmbedToken = (
  secret: string, knowledgeBaseId?: number | null, origin?: string, ttlSeconds?: number | null,
): Promise<ApiResponse<EmbedTokenResult>> =>
  axios.post('/api/v1/external/embed/token', null, {
    params: {
      knowledgeBaseId: knowledgeBaseId ?? undefined,
      origin: origin ?? undefined,
      expiresInSeconds: ttlSeconds ?? undefined,
    },
    headers: { 'X-API-Key': secret },
  })

/* ---- 嵌入令牌生命周期：吊销、恢复与调用日志（管理员） ---- */

/** 窗口内出现过的令牌：调用量、来源站点与是否已吊销 */
export interface EmbedTokenSummary {
  jti: string
  displayId: string
  keyId: number | null
  keyCode: string | null
  knowledgeBaseId: number | null
  origin: string | null
  calls: number
  errors: number
  firstCallTime: string | null
  lastCallTime: string | null
  revoked: boolean
}

/** 一条调用日志：能定位到具体是哪个令牌、哪个站点发起的 */
export interface EmbedTokenCall {
  id: number
  jti: string | null
  displayId: string
  keyId: number | null
  keyCode: string | null
  callerName: string | null
  /** 调用方 IP：与凭证并列，用来区分同一枚 Key 的不同来源。 */
  callerIp: string | null
  origin: string | null
  channel: string
  method: string
  knowledgeBaseId: number | null
  statusCode: number | null
  durationMs: number | null
  message: string | null
  requestParams: string | null
  traceId: string | null
  createdTime: string | null
}

export interface EmbedTokenRevocationItem {
  id: number
  jti: string
  keyId: number | null
  keyCode: string | null
  knowledgeBaseId: number | null
  origin: string | null
  reason: string | null
  operatorId: number | null
  revokedTime: string | null
}

export interface EmbedTokenLifecycle {
  windowHours: number
  tokens: EmbedTokenSummary[]
  revocations: EmbedTokenRevocationItem[]
}

export const fetchEmbedTokenLifecycle = (hours = 168, limit = 50): Promise<ApiResponse<EmbedTokenLifecycle>> =>
  axios.get('/api/external/admin/embed/tokens', { params: { hours, limit } })

export const fetchEmbedTokenCalls = (
  jti?: string | null, keyId?: number | null, limit = 50,
): Promise<ApiResponse<EmbedTokenCall[]>> =>
  axios.get('/api/external/admin/embed/tokens/calls', {
    params: { jti: jti || undefined, keyId: keyId ?? undefined, limit },
  })

/**
 * 吊销单个令牌：只影响这一个令牌，同一 API Key 签发的其他令牌继续可用。
 */
export const revokeEmbedToken = (body: {
  jti: string
  keyId?: number | null
  keyCode?: string | null
  knowledgeBaseId?: number | null
  origin?: string | null
  reason?: string | null
}): Promise<ApiResponse<EmbedTokenRevocationItem>> =>
  axios.post('/api/external/admin/embed/tokens/revoke', body)

export const restoreEmbedToken = (jti: string): Promise<ApiResponse<{ jti: string; restored: boolean }>> =>
  axios.post(`/api/external/admin/embed/tokens/${encodeURIComponent(jti)}/restore`)

/* ---- 平台事件总线：历史回放与订阅观测（管理员，登录会话） ---- */

/** 一条平台事件：与 SSE 下发的信封同构，便于「历史里看到的」和「订阅收到的」对照 */
export interface PlatformEventItem {
  id: number
  eventId: string
  type: string
  time: string
  knowledgeBaseId: number | null
  source: string
  actor: { type?: string; id?: string; name?: string } | null
  targetType: string | null
  targetCode: string | null
  summary: string | null
  traceId: string | null
  taskId: string | null
  detail: Record<string, unknown> | null
}

export interface PlatformEventRetention {
  enabled: boolean
  retentionDays: number
  batchSize: number
  purgedTotal: number
  lastRun: string | null
  lastPurged: number
}

export interface PlatformSubscriptionStats {
  active: number
  maxPerPrincipal: number
  items: { principal: string; streams: number }[]
}

export interface PlatformEventHistory {
  items: PlatformEventItem[]
  count: number
  /** 还有更旧的：用 nextUntilId 翻页，不用 offset，避免新事件插队导致重页/漏页 */
  hasMore: boolean
  nextUntilId: number | null
  oldestId: number | null
  activeSubscriptions: number
  subscriptions: PlatformSubscriptionStats | null
  retention: PlatformEventRetention | null
}

export const fetchPlatformEventTypes = (): Promise<ApiResponse<string[]>> =>
  axios.get('/api/internal/platform/events/types')

export const fetchPlatformEventHistory = (params: {
  knowledgeBaseId?: number | null
  types?: string[]
  sinceId?: number | null
  untilId?: number | null
  keyword?: string
  limit?: number
}): Promise<ApiResponse<PlatformEventHistory>> =>
  axios.get('/api/internal/platform/events', {
    params: {
      knowledgeBaseId: params.knowledgeBaseId ?? undefined,
      types: params.types?.length ? params.types.join(',') : undefined,
      sinceId: params.sinceId ?? undefined,
      untilId: params.untilId ?? undefined,
      keyword: params.keyword || undefined,
      limit: params.limit ?? 100,
    },
  })

export const fetchPlatformSubscriptions = (): Promise<ApiResponse<PlatformSubscriptionStats>> =>
  axios.get('/api/internal/platform/events/subscriptions')

/* ---- MCP Server 管理：工具清单与通道调用审计（管理员） ---- */

export const getMcpManifest = ():Promise<ApiResponse<import('../types').McpManifest>> =>
  axios.get('/api/external/admin/mcp/manifest')
export const listMcpCalls = (limit=50):Promise<ApiResponse<import('../types').McpCallAudit[]>> =>
  axios.get('/api/external/admin/mcp/calls', { params:{ limit } })
