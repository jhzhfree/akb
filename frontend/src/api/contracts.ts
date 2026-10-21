import type { QueryCitation } from '../types'

/** 答案交付格式：默认（模型原文）/ markdown / 纯文本（不含证据）/ html（含引用原文）。 */
export type AnswerFormat = 'default'|'markdown'|'text'|'html'

export const ANSWER_FORMATS:Array<{ value:AnswerFormat; label:string; hint:string }> = [
  { value:'default', label:'默认', hint:'模型生成的原文，引用由界面单独展示' },
  { value:'markdown', label:'Markdown', hint:'答案正文 + Markdown 格式的引用原文章节' },
  { value:'text', label:'纯文本', hint:'去掉 Markdown 标记与证据章节，只有正文' },
  { value:'html', label:'HTML', hint:'渲染后的 HTML 片段，答案里直接带上引用原文' },
]

/** 返回详略：极简（只给答案主体）/ 基础（补回检索结果）/ 完整（当前全部字段）。 */
export type ResponseMode = 'minimal'|'basic'|'full'

export const RESPONSE_MODES:Array<{ value:ResponseMode; label:string; hint:string }> = [
  { value:'minimal', label:'极简', hint:'query 只给答案与数量口径；search 只给证据' },
  { value:'basic', label:'基础', hint:'在极简之上补回 candidates，query 另给 selectedContext' },
  { value:'full', label:'完整', hint:'当前全部字段，默认值——不传即此项' },
]

export interface RetrievalRequest {
  knowledgeBaseId:number
  query:string
  topK?:number
  permissionTags?:string[]
  /** 答案返回格式；不传或无法识别的取值都按 default 处理。 */
  answerFormat?:AnswerFormat
  /** 返回详略；不传或无法识别的取值都按 full 处理（即当前全部字段）。 */
  responseMode?:ResponseMode
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
  /** 检索链路字段随版本增减，这里只声明常用的几个；其余按原样透传。 */
  [key:string]:unknown
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
  /** 重跑生成的切片数量与 revision：新 Chunk 用新 revision，旧的转 SUPERSEDED，已有引用不断。 */
  chunkCount?:number
  chunkRevision?:number
}
export interface AdjudicationRunResult {
  processed?:number
  accepted?:number
  rejected?:number
  pendingReview?:number
  [key:string]:unknown
}

/** 知识库重新生成的结果：逐文档的成败与本次产出的卡片/断言数。 */
export interface RegenerationResult {
  knowledgeBaseId?:number
  documentCount?:number
  processed?:number
  skipped?:number
  failed?:number
  cardCount?:number
  claimCount?:number
  documents?:Array<{
    documentId?:number
    title?:string
    status?:string
    message?:string
    cardCount?:number
    claimCount?:number
  }>
  [key:string]:unknown
}
