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
  expiresAt?:string|null
}):Promise<ApiResponse<ExternalKeyRow>> => axios.post('/api/external/admin/keys', payload)
export const revokeExternalKey = (id:number):Promise<ApiResponse<ExternalKeyRow>> =>
  axios.post(`/api/external/admin/keys/${id}/revoke`)
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

/* ---- MCP Server 管理：工具清单与通道调用审计（管理员） ---- */

export const getMcpManifest = ():Promise<ApiResponse<import('../types').McpManifest>> =>
  axios.get('/api/external/admin/mcp/manifest')
export const listMcpCalls = (limit=50):Promise<ApiResponse<import('../types').McpCallAudit[]>> =>
  axios.get('/api/external/admin/mcp/calls', { params:{ limit } })
