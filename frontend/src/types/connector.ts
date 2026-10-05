/** 连接器：把「知识不在本地文件里」的外部系统接进知识库（设计 §6.1）。 */

/** 第一步支持的三种来源：Jira 问题、Confluence 页面、任意 RESTful 列表接口。 */
export type ConnectorType = 'JIRA' | 'CONFLUENCE' | 'REST'

/** 认证方式：无 / Basic（Jira、Confluence Server 常用）/ Bearer（API Token、网关令牌）。 */
export type ConnectorAuthType = 'NONE' | 'BASIC' | 'BEARER'

/**
 * 类型专属配置。写成自由结构而非每种类型一个字段，是为了让新增连接器类型
 * 不必改动保存逻辑：Jira 用 jql，Confluence 用 spaceKey，REST 用路径与字段映射。
 */
export interface ConnectorConfig extends ConnectorIngestSettings {
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
  /** 跳过 SSL 证书校验：内网自签名证书或 IP 直连的站点需要开启，否则握手直接失败。 */
  insecureSsl?:boolean
}

/** 入库模式：手动（同步只刷新目录）或自动（同步后按范围与配额自动入库/重抽）。 */
export type ConnectorIngestMode = 'MANUAL' | 'AUTO'

/** 入库范围：不填表示全部；填了表示只有命中的条目才会被自动入库。 */
export interface ConnectorIngestScope {
  types?:string[]
  /** 形如 status=已完成，多条之间是「且」。 */
  attributes?:string[]
}

/** 入库设置：决定外部条目以什么身份进入知识库、以及是否自动进入。 */
export interface ConnectorIngestSettings {
  /** 入库模式，默认 MANUAL：远程系统噪声大，抽取也不便宜，默认不自动跑。 */
  ingestMode?:ConnectorIngestMode
  /** 自动入库每轮最多处理多少条。 */
  ingestQuota?:number
  /** 权威度 0–1：外部内容默认比导入文件低一档（0.7）。 */
  authorityScore?:number
  /** 权限标签，逗号分隔：入库产物带上它才能被对应的人检索到。 */
  permissionTags?:string
  ingestScope?:ConnectorIngestScope
}

/** 入库设置视图：后端按当前配置解析出来的生效值，供界面回显。 */
export interface ConnectorIngestPolicy {
  mode:ConnectorIngestMode
  auto:boolean
  quota:number
  authorityScore:number
  permissionTags?:string|null
  types:string[]
  attributes:string[]
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
  lastSyncTime?:string
  lastSyncCount?:number
  /** 定时同步的 cron，留空表示只手动同步。 */
  syncCron?:string|null
  /** 下次定时同步的到点时间。 */
  nextFireAt?:string|null
  /** 定时同步运行态：IDLE / RUNNING / FAILED。 */
  syncStatus?:string
  /** 最近一次定时同步的失败原因。 */
  lastError?:string|null
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
  /** 定时同步的 cron（分 时 日 月 周）；留空表示只手动同步。 */
  syncCron?:string
}

/** 三种连接器读出来的都是这个形状，后续转文档时只认这五个字段。 */
export interface ConnectorItem {
  externalId:string
  title:string
  /** 外部系统的类型：Jira 的问题类型、Confluence 的内容类型、REST 的 typeField。 */
  type?:string
  /** 类型专属属性（状态 / 优先级 / 经办人…），按类型动态展示与筛选。 */
  attributes?:Record<string, CatalogAttribute>
  url?:string|null
  updatedAt?:string|null
  excerpt?:string|null
}

/** 一个属性：键是外部系统字段名，标签是我们给它的中文名。 */
export interface CatalogAttribute {
  key:string
  label:string
  value:string|number|null
}

/** 已同步落库的一条外部知识：比 ConnectorItem 多了主键与同步时间。 */
export interface CatalogItem extends ConnectorItem {
  id:number
  externalId:string
  title:string
  type:string
  attributes?:Record<string, CatalogAttribute>
  syncedTime?:string|null
  /** 仅详情返回：外部系统的原始条目（归一化后的 JSON 文本）。 */
  raw?:string|null
  connectorName?:string
  connectorType?:string
}

export interface CatalogPage {
  connector?:{ id:number; name:string; type:string; baseUrl:string }
  items:CatalogItem[]
  total:number
  page:number
  size:number
  lastSyncTime?:string|null
  lastSyncCount?:number|null
}

/** 左侧筛选树：类型计数 + 选中类型下每个属性的取值计数。 */
export interface CatalogFacets {
  types:{ value:string; count:number }[]
  attributes:{
    key:string
    label:string
    values:{ value:string; count:number }[]
  }[]
  total:number
  lastSyncTime?:string|null
  lastSyncCount?:number|null
}

/** 属性筛选条件在 URL 上的形态：key=value，可重复。 */
export type CatalogFilter = string

export interface CatalogSyncResult {
  connectorId:number
  synced:number
  remoteTotal?:number|null
  storedTotal?:number|null
  /** 源端已删除、本次被移出检索的条数。 */
  archived?:number
  /** 自动入库模式下本次顺带入库的条数。 */
  autoIngested?:number
  /** 自动入库模式下本次重抽的条数。 */
  autoRefreshed?:number
  /** true 表示入库与失效传播已交给后台线程，请求返回时它们还没跑完。 */
  ingestScheduled?:boolean
  message:string
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

/**
 * 入库结果。入库后这条外部知识就是一篇普通文档：同样的切片、卡片、断言与检索链路，
 * 这里只额外带回它在知识库里的身份，便于从条目跳到文档。
 */
export interface CatalogIngestResult {
  status:'INGESTED' | 'SKIPPED' | 'STALE' | 'FAILED'
  itemId?:number
  externalId?:string
  documentId?:number|null
  sourceId?:number|null
  chunkCount?:number
  message?:string
}

/** 列表上每一条的入库状态：未入库的不出现在列表里；ARCHIVED 表示源端已删除、已移出检索。 */
export interface CatalogIngestStatus {
  itemId:number
  ingestStatus:'INGESTED' | 'STALE' | 'FAILED' | 'ARCHIVED'
  documentId?:number|null
  contentHash?:string|null
}

/** 跑一轮自动入库的结果：新入库多少、重抽多少、失败多少。 */
export interface CatalogAutoIngestResult {
  connectorId:number
  /** true 表示已提交后台，返回时入库还没跑完（进度看入库状态列）。 */
  scheduled?:boolean
  ingested:number
  refreshed:number
  failed:number
  quota:number
  message:string
}

export interface CatalogIngestSelectionResult {
  /** true 表示已提交后台，返回时入库还没跑完（进度看入库状态列）。 */
  scheduled?:boolean
  connectorId:number
  requested:number
  ingested:number
  skipped:number
  failed:number
  message:string
  items:{ itemId:number; externalId?:string; status:string; documentId?:number|null; message?:string }[]
}
