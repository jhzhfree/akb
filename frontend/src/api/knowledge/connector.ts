import axios, { type AxiosResponse } from 'axios'
import type {
  ApiEnvelope, CatalogAutoIngestResult, CatalogFacets, CatalogIngestResult, CatalogIngestSelectionResult,
  CatalogIngestStatus, CatalogItem, CatalogPage, CatalogSyncResult, ConnectorIngestPolicy, ConnectorItemPage,
  ConnectorPayload, ConnectorSource, ConnectorTestResult,
} from '../../types'

type ApiResponse<T> = AxiosResponse<ApiEnvelope<T>>

/**
 * 外部系统不在我们这边，慢或超时都正常，因此比内部接口给更长的等待时间；
 * 仍然远小于「导入」那种分钟级任务，所以不复用 LONG_TIMEOUT_MS。
 */
const EXTERNAL_TIMEOUT_MS = 45000

export const listConnectors = (kb:number):Promise<ApiResponse<ConnectorSource[]>> =>
  axios.get(`/api/knowledge/connectors/${kb}`)

export const createConnector = (payload:ConnectorPayload):Promise<ApiResponse<ConnectorSource>> =>
  axios.post('/api/knowledge/connectors', payload, { timeout:EXTERNAL_TIMEOUT_MS })

export const updateConnector = (id:number, payload:ConnectorPayload):Promise<ApiResponse<ConnectorSource>> =>
  axios.put(`/api/knowledge/connectors/${id}`, payload, { timeout:EXTERNAL_TIMEOUT_MS })

export const deleteConnector = (id:number):Promise<ApiResponse<{ deleted:boolean }>> =>
  axios.delete(`/api/knowledge/connectors/${id}`)

export const testConnector = (id:number):Promise<ApiResponse<ConnectorTestResult>> =>
  axios.post(`/api/knowledge/connectors/${id}/test`, {}, { timeout:EXTERNAL_TIMEOUT_MS })

/** 未保存就试读：填表单时先确认 JQL / 字段映射对不对，再决定是否保存。 */
export const previewConnector = (payload:ConnectorPayload):Promise<ApiResponse<ConnectorItemPage>> =>
  axios.post('/api/knowledge/connectors/preview', payload, { timeout:EXTERNAL_TIMEOUT_MS })

export const connectorItems = (id:number, limit = 20, offset = 0):Promise<ApiResponse<ConnectorItemPage>> =>
  axios.get(`/api/knowledge/connectors/${id}/items?limit=${limit}&offset=${offset}`, { timeout:EXTERNAL_TIMEOUT_MS })

/** 同步是「替用户跑一遍外部系统」，慢是正常的，因此比普通读取给更长的等待时间。 */
const SYNC_TIMEOUT_MS = 120000

export const syncConnectorCatalog = (id:number):Promise<ApiResponse<CatalogSyncResult>> =>
  axios.post(`/api/knowledge/connectors/${id}/sync`, {}, { timeout:SYNC_TIMEOUT_MS })

/**
 * attr 形如 status=进行中，可重复；type 为空表示全部类型。
 *
 * 查询串自己拼：axios 默认把数组序列化成 attr[]=…，后端 @RequestParam 收不到，
 * 表现就是「只有类型生效，其他属性点了没反应」。
 */
export const connectorCatalog = (
  id:number,
  params:{ type?:string; keyword?:string; attr?:string[]; page?:number; size?:number } = {},
):Promise<ApiResponse<CatalogPage>> => {
  const { attr, ...rest } = params
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(rest)) {
    if (value !== undefined && value !== null && value !== '') search.append(key, String(value))
  }
  for (const item of attr || []) search.append('attr', item)
  const query = search.toString()
  return axios.get(`/api/knowledge/connectors/${id}/catalog${query ? `?${query}` : ''}`)
}

export const connectorCatalogFacets = (id:number, type?:string):Promise<ApiResponse<CatalogFacets>> =>
  axios.get(`/api/knowledge/connectors/${id}/catalog/facets`, { params:{ type } })

export const connectorCatalogItem = (itemId:number):Promise<ApiResponse<CatalogItem>> =>
  axios.get(`/api/knowledge/connectors/catalog/items/${itemId}`)

/** 入库要走抽取与索引，比预览慢得多，因此给最长的等待时间。 */
const INGEST_TIMEOUT_MS = 180000

export const ingestConnectorItem = (id:number, itemId:number, force = false):Promise<ApiResponse<CatalogIngestResult>> =>
  axios.post(`/api/knowledge/connectors/${id}/items/${itemId}/ingest?force=${force}`, {}, { timeout:INGEST_TIMEOUT_MS })

export const ingestConnectorSelection = (
  id:number,
  payload:{ type?:string; keyword?:string; attr?:string[]; limit?:number },
):Promise<ApiResponse<CatalogIngestSelectionResult>> =>
  axios.post(`/api/knowledge/connectors/${id}/ingest`, payload, { timeout:INGEST_TIMEOUT_MS })

export const connectorIngestStatus = (id:number):Promise<ApiResponse<CatalogIngestStatus[]>> =>
  axios.get(`/api/knowledge/connectors/${id}/ingest/status`)

export const connectorIngestPolicy = (id:number):Promise<ApiResponse<ConnectorIngestPolicy>> =>
  axios.get(`/api/knowledge/connectors/${id}/ingest/policy`)

/** 自动入库一轮可能连抽几十条，等待时间与批量入库一致。 */
export const ingestConnectorAuto = (id:number):Promise<ApiResponse<CatalogAutoIngestResult>> =>
  axios.post(`/api/knowledge/connectors/${id}/ingest/auto`, {}, { timeout:INGEST_TIMEOUT_MS })
