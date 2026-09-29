import axios, { type AxiosResponse } from 'axios'
import type {
  ApiEnvelope, ConnectorItemPage, ConnectorPayload, ConnectorSource, ConnectorTestResult,
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
