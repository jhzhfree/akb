/** 连接器：把「知识不在本地文件里」的外部系统接进知识库（设计 §6.1）。 */

/** 第一步支持的三种来源：Jira 问题、Confluence 页面、任意 RESTful 列表接口。 */
export type ConnectorType = 'JIRA' | 'CONFLUENCE' | 'REST'

/** 认证方式：无 / Basic（Jira、Confluence Server 常用）/ Bearer（API Token、网关令牌）。 */
export type ConnectorAuthType = 'NONE' | 'BASIC' | 'BEARER'

/**
 * 类型专属配置。写成自由结构而非每种类型一个字段，是为了让新增连接器类型
 * 不必改动保存逻辑：Jira 用 jql，Confluence 用 spaceKey，REST 用路径与字段映射。
 */
export interface ConnectorConfig {
  jql?:string
  spaceKey?:string
  path?:string
  /** 列表数组在响应里的点分路径，如 data.list；留空时按常见键自动识别。 */
  itemsPath?:string
  /** 字段映射：把外部字段名接到统一条目结构上。 */
  fields?:Record<string, string>
  pageMode?:'OFFSET' | 'PAGE'
  offsetParam?:string
  limitParam?:string
  pageParam?:string
  sizeParam?:string
  headers?:Record<string, string>
}

export interface ConnectorSource {
  id:number
  connectorCode:string
  knowledgeBaseId:number
  name:string
  type:ConnectorType
  baseUrl:string
  authType:ConnectorAuthType
  username?:string
  /** 后端永不返回密钥本身，只告诉前端「是否已经保存过」。 */
  secretConfigured?:boolean
  config?:ConnectorConfig
  status:string
  lastTestStatus?:string
  lastTestMessage?:string
  lastTestTime?:string
  createdBy?:string
  createdTime?:string
}

export interface ConnectorPayload {
  knowledgeBaseId:number
  name:string
  type:ConnectorType
  baseUrl:string
  authType:ConnectorAuthType
  username?:string
  /** 留空表示沿用已保存的凭据（编辑时不要回传占位值）。 */
  secret?:string
  config?:ConnectorConfig
}

/** 三种连接器读出来的都是这个形状，后续转文档时只认这五个字段。 */
export interface ConnectorItem {
  externalId:string
  title:string
  url?:string|null
  updatedAt?:string|null
  excerpt?:string|null
}

export interface ConnectorItemPage {
  connector?:{ id:number; name:string; type:string; baseUrl:string; lastTestStatus?:string; lastTestMessage?:string }
  items:ConnectorItem[]
  /** 远端自报总数；Confluence 不返回总数时为 null。 */
  total?:number|null
  limit:number
  offset:number
}

export interface ConnectorTestResult {
  ok:boolean
  message:string
  itemCount?:number
}
