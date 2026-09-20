
/**
 * 信息架构（IA）与导航定义。
 *
 * 依据《产品详细设计说明书 V2.0》第 1 章「设计基线与总体原则」与第 2 章「全局框架」：
 *   1. 一级入口稳定：只保留「知识工作台 / 知识创建 / 知识检索 / 知识集成 / 系统配置」五个一级菜单，
 *      Chunk、Assertion、Graph、Evaluation 等一律作为二级入口或页面内部入口进入；
 *   2. “知识应用”升级为“知识集成”：把可信知识能力通过 REST / MCP / SSE / 凭证接入外部系统；
 *   3. 知识空间选择器固定在顶栏，不作为一级菜单；
 *   4. 涉及调试的页面统一采用「配置区 + 结果区 + Trace 抽屉」的交互模式。
 *
 * IA 中当前版本尚未实现的入口保留位置并标记 planned：导航不隐藏规划能力，
 * 但也不提供任何假数据页面，点击时给出明确提示。
 */
export interface NavLeaf {
  /** 视图 key，同时作为本地存储的当前页面标识 */
  key: string
  label: string
  icon: string
  /** 同一面板在不同入口下的聚焦区块，如资料中心的「资料 / 文档 / 摄入」、治理的「待审核 / 冲突 / 裁决」 */
  focus?: string
  /** IA 保留但当前版本未实现的入口 */
  planned?: boolean
  /** 需要人工介入的入口角标 */
  badge?: 'pendingReview' | 'openConflict'
  /** 调试类页面：配置区 + 结果区 + Trace 抽屉 */
  debug?: boolean
  /** 仅系统管理员可见 */
  adminOnly?: boolean
}

export interface NavSection {
  key: string
  label: string
  icon: string
  /** 是否属于知识空间上下文：是则顶部展示空间 / 环境 / 索引状态 */
  scoped: boolean
  children: NavLeaf[]
}

export const NAV_SECTIONS: NavSection[] = [
  {
    key: 'workbench', label: '知识工作台', icon: '▣', scoped: false,
    children: [
      { key: 'workbench', label: '工作台', icon: '▣' },
      { key: 'workbench.assets', label: '资产浏览', icon: '🗃' },
      { key: 'workbench.graph', label: '图谱概览', icon: '❖' },
      { key: 'workbench.version', label: '版本', icon: '⧉' },
      { key: 'workbench.quality', label: '质量', icon: '◎' },
    ],
  },
  {
    key: 'create', label: '知识创建', icon: '⚒', scoped: true,
    children: [
      { key: 'create.home', label: '创建首页', icon: '⚒' },
      { key: 'create.import', label: '文档导入', icon: '📄', focus: 'documents' },
      { key: 'create.sources', label: '数据源与连接器', icon: '⇪', focus: 'sources' },
      { key: 'create.jobs', label: '解析任务', icon: '⏳', focus: 'ingestion' },
      { key: 'create.chunk', label: 'Chunk', icon: '▦' },
      { key: 'create.entity', label: '实体归一', icon: '⬡' },
      { key: 'create.review', label: '待审核', icon: '🕘', badge: 'pendingReview', focus: 'review' },
      { key: 'create.conflict', label: '冲突', icon: '⚠', badge: 'openConflict', focus: 'conflict' },
      { key: 'create.judgment', label: '知识裁决', icon: '⚖', focus: 'judgment' },
      { key: 'create.derivation', label: '知识衍生', icon: '⑃', planned: true },
      { key: 'create.space', label: '知识空间', icon: '◈' },
    ],
  },
  {
    key: 'search', label: '知识检索', icon: '⌕', scoped: true,
    children: [
      { key: 'search.query', label: '搜索', icon: '⌕' },
      { key: 'search.test', label: '检索测试', icon: '🧪', debug: true },
      { key: 'search.profile', label: '检索策略', icon: '⌗' },
      { key: 'search.trace', label: 'Query Trace', icon: '🛰', debug: true },
      { key: 'search.benchmark', label: 'Benchmark', icon: '📈' },
    ],
  },
  {
    key: 'integration', label: '知识集成', icon: '⇄', scoped: true,
    children: [
      { key: 'integration.overview', label: '集成概览', icon: '⇄' },
      { key: 'integration.services', label: '服务与 API', icon: '🔌' },
      { key: 'integration.mcp', label: 'MCP', icon: '🧩' },
      { key: 'integration.sse', label: 'SSE', icon: '⇉' },
      { key: 'integration.credentials', label: '凭证', icon: '🔑' },
      { key: 'integration.logs', label: '调用日志', icon: '📜' },
    ],
  },
  {
    key: 'system', label: '系统配置', icon: '⚙', scoped: false,
    children: [
      { key: 'system.model', label: '模型配置', icon: '🧠' },
      { key: 'system.users', label: '用户与组织', icon: '👤', adminOnly: true },
      { key: 'system.acl', label: '权限与审计', icon: '⚿' },
      { key: 'system.schema', label: '知识 Schema', icon: '🧬', planned: true },
      { key: 'system.index', label: '存储与索引', icon: '🗲' },
      { key: 'system.tasks', label: '任务与告警', icon: '⏳', planned: true },
      { key: 'system.policy', label: '问答策略', icon: '⌗' },
      { key: 'system.lab', label: '接口联调', icon: '⌁', debug: true },
    ],
  },
]

export interface NavLocation { section: NavSection; leaf: NavLeaf }

const LOCATIONS = new Map<string, NavLocation>()
for (const section of NAV_SECTIONS) {
  for (const leaf of section.children) LOCATIONS.set(leaf.key, { section, leaf })
}

export function locate(key: string): NavLocation | undefined {
  return LOCATIONS.get(key)
}

/** 一级导航默认进入的页面：跳过尚未实现的入口。 */
export function defaultLeafOf(section: NavSection): NavLeaf {
  return section.children.find(leaf => !leaf.planned) || section.children[0]
}

/**
 * 旧版本视图 key 到新 IA 的映射，保证升级后书签/本地存储不失效。
 * V2.0 把十个一级菜单收敛为五个，旧 key 全部落到对应的二级入口。
 */
const LEGACY_TAB_ALIAS: Record<string, string> = {
  // 单级 tab（早期版本）
  overview: 'workbench',
  data: 'create.import',
  cards: 'workbench.assets',
  claims: 'workbench.assets',
  review: 'create.judgment',
  graph: 'workbench.graph',
  query: 'search.query',
  lifecycle: 'create.jobs',
  ops: 'system.index',
  acl: 'system.acl',
  queryPolicy: 'system.policy',
  external: 'integration.services',
  externalLab: 'system.lab',
  users: 'system.users',
  settings: 'system.model',
  // V1.x 二级 key
  'space.kb': 'create.space',
  'space.sources': 'create.sources',
  'space.documents': 'create.import',
  'space.tags': 'create.home',
  'production.parsing': 'create.jobs',
  'production.cleaning': 'create.home',
  'production.extraction': 'create.home',
  'production.derivation': 'create.derivation',
  'production.pipeline': 'create.jobs',
  'manage.chunk': 'create.chunk',
  'manage.card': 'workbench.assets',
  'manage.entity': 'workbench.assets',
  'manage.relation': 'workbench.assets',
  'manage.assertion': 'workbench.assets',
  'governance.review': 'create.review',
  'governance.conflict': 'create.conflict',
  'governance.judgment': 'create.judgment',
  'governance.version': 'workbench.version',
  'governance.quality': 'workbench.quality',
  'retrieval.test': 'search.test',
  'retrieval.strategy': 'search.profile',
  'retrieval.trace': 'search.trace',
  'retrieval.benchmark': 'search.benchmark',
  'application.qa': 'search.query',
  'application.agent': 'integration.overview',
  'application.api': 'integration.services',
  'application.mcp': 'integration.mcp',
  'evaluation.dataset': 'search.benchmark',
  'evaluation.run': 'search.benchmark',
  'admin.ops': 'system.index',
  'admin.acl': 'system.acl',
  'admin.policy': 'system.policy',
  'admin.external': 'integration.services',
  'admin.lab': 'system.lab',
  'admin.model': 'system.model',
  'admin.users': 'system.users',
}

export function normalizeViewKey(raw: string | null | undefined): string {
  if (!raw) return 'workbench'
  if (LOCATIONS.has(raw)) return raw
  return LEGACY_TAB_ALIAS[raw] || 'workbench'
}
