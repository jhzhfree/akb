import type { QueryCitation } from '../types'

/** 答案交付格式：默认（模型原文）/ markdown / 纯文本（不含证据）/ html（含引用原文）。 */
export type AnswerFormat = 'default'|'markdown'|'text'|'html'

export const ANSWER_FORMATS:Array<{ value:AnswerFormat; label:string; hint:string }> = [
  { value:'default', label:'默认', hint:'模型生成的原文，引用由界面单独展示' },
  { value:'markdown', label:'Markdown', hint:'答案正文 + Markdown 格式的引用原文章节' },
  { value:'text', label:'纯文本', hint:'去掉 Markdown 标记与证据章节，只有正文' },
  { value:'html', label:'HTML', hint:'渲染后的 HTML 片段，答案里直接带上引用原文' },
]

export interface RetrievalRequest {
  knowledgeBaseId:number
  query:string
  topK?:number
  permissionTags?:string[]
  /** 答案返回格式；不传或无法识别的取值都按 default 处理。 */
  answerFormat?:AnswerFormat
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
  /** 实际生效的格式：服务端会把无法识别的取值退回 default 并回传真实值。 */
  answerFormat?:AnswerFormat
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
