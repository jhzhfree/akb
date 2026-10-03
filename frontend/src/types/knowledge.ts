export type { KnowledgeBase, KnowledgeCard, Claim, KnowledgeDocument, KnowledgeSource, Overview, SourcePreview } from '../types-legacy'

/** 资产浏览行：不同对象类型字段不同，统一按索引访问，具体列由对象类型决定。 */
export type AssetRow = Record<string, unknown>

/** 资产浏览分页结果：total 是 ACL + 关键字过滤后的命中总数，与工作台 KPI 同源。 */
export interface AssetPage {
  /** 后端归一化后的对象类型：SOURCE / DOCUMENT / CARD / CLAIM / ENTITY / RELATION */
  type:string
  keyword:string
  items:AssetRow[]
  total:number
  page:number
  size:number
  pageCount:number
}
