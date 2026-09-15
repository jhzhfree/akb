import axios from 'axios'

const KB_ID = 1
const LONG_TIMEOUT_MS = 180000

export function getDocuments() {
  return axios.get(`/api/knowledge/documents/${KB_ID}`)
}

export function getCards() {
  return axios.get(`/api/knowledge/cards/${KB_ID}`)
}

export function getClaims() {
  return axios.get(`/api/knowledge/claims/${KB_ID}`)
}

export function getPendingReviews() {
  return axios.get(`/api/knowledge/reviews/${KB_ID}/pending`)
}

export function getOpenConflicts() {
  return axios.get(`/api/knowledge/conflicts/${KB_ID}?status=OPEN`)
}

export function getCapabilities() {
  return axios.get('/api/knowledge/capabilities')
}

export function getAssessments(limit = 100) {
  return axios.get(`/api/knowledge/adjudication/${KB_ID}/assessments?limit=${limit}`)
}

export function uploadDocument(file, authorityScore) {
  const form = new FormData()
  form.append('knowledgeBaseId', String(KB_ID))
  form.append('authorityScore', String(authorityScore))
  form.append('file', file)
  return axios.post('/api/knowledge/documents/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: LONG_TIMEOUT_MS,
  })
}

export function queryKnowledge(query) {
  return axios.post(
    '/api/knowledge/query',
    {
      knowledgeBaseId: KB_ID,
      query,
      topK: 10,
      userId: 'demo',
      permissionTags: [],
    },
    { timeout: LONG_TIMEOUT_MS },
  )
}

export function runAdjudication() {
  return axios.post(`/api/knowledge/adjudication/${KB_ID}/run`, {}, { timeout: LONG_TIMEOUT_MS })
}

export function reviewKnowledge(row, decision) {
  return axios.post('/api/knowledge/adjudicate', {
    objectType: row.objectType,
    objectCode: row.objectCode,
    decision,
    reviewer: 'admin',
    comment: 'V1.1 裁决工作台',
  })
}

export function resolveKnowledgeConflict(id, status) {
  return axios.post(`/api/knowledge/conflicts/${id}/resolve`, { status })
}

export function getGraph(code) {
  return axios.get(`/api/knowledge/graph/${KB_ID}/${encodeURIComponent(code)}`)
}

export function unwrap(response, fallback = null) {
  if (!response?.data?.success) {
    throw new Error(response?.data?.message || '请求失败')
  }
  return response.data.data ?? fallback
}
