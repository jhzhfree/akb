import axios, { type AxiosResponse } from 'axios'
import type {
  ApiEnvelope, ExternalAuditRow, ExternalKeyRow, ExternalServiceOverview,
} from '../types'

type ApiResponse<T> = AxiosResponse<ApiEnvelope<T>>

/* ---- 控制台治理接口：走登录会话 ---- */

export const getExternalServices = ():Promise<ApiResponse<ExternalServiceOverview>> =>
  axios.get('/api/external/admin/services')
export const listExternalKeys = ():Promise<ApiResponse<ExternalKeyRow[]>> =>
  axios.get('/api/external/admin/keys')
export const createExternalKey = (payload:{
  name?:string; knowledgeBaseId?:number|null; scopes?:string[]; rateLimitPerMinute?:number
  expiresAt?:string|null; memoryNamespace?:string|null; memoryAgentCode?:string|null
}):Promise<ApiResponse<ExternalKeyRow>> => axios.post('/api/external/admin/keys', payload)
export const revokeExternalKey = (id:number):Promise<ApiResponse<ExternalKeyRow>> =>
  axios.post(`/api/external/admin/keys/${id}/revoke`)
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
