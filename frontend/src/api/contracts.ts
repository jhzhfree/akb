import type { QueryCitation } from '../types'

export interface RetrievalRequest {
  knowledgeBaseId:number
  query:string
  topK?:number
  permissionTags?:string[]
  [key:string]:unknown
}
export interface RetrievalResult {
  queryId?:string
  rewrittenQueries?:string[]
  router?:Record<string,unknown>
  recall?:unknown[]
  candidates?:unknown[]
  reranker?:unknown
  fusion?:unknown
  trace?:Record<string,unknown>
}
export interface AnswerResult {
  queryId?:string
  answer?:string
  citations?:QueryCitation[]
  confidence?:number
  model?:string
  trace?:Record<string,unknown>
}
export interface DocumentRetryResult {
  documentId?:number
  runId?:number
  status?:string
  stage?:string
  message?:string
}
export interface AdjudicationRunResult {
  processed?:number
  accepted?:number
  rejected?:number
  pendingReview?:number
  [key:string]:unknown
}
