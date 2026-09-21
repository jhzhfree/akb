
/**
 * 信息架构（IA）与导航定义。
 *
 * 依据《产品详细设计说明书 V2.1》第 1 章「设计基线与总体原则」与第 2 章「全局框架」：
 *   1. 一级入口稳定：只保留「知识工作台 / 知识生成 / 知识检索 / 知识集成 / 系统配置」五个一级菜单；
 *   2. 左侧导航不设置任何二级菜单：Chunk、Assertion、Graph、Evaluation、凭证等一律进入主页面内部，
 *      通过 Tab、任务卡、快捷操作、Drawer 或详情页承载；
 *   3. “知识创建”更名为“知识生成”：强调从原始数据到可信知识资产的流水线，而不是简单 CRUD；
 *   4. “知识图谱”整体归入“知识检索”：作为实体关系探索、Graph Recall 与路径分析能力；
 *   5. 知识空间选择器固定在顶栏，不作为一级菜单。
 *
 * 因此这里的 tabs 不是左侧菜单，而是进入一级模块后页面顶部的横向 Tab；
 * IA 中当前版本尚未实现的入口保留位置并标记 planned：不隐藏规划能力，
 * 但也不提供任何假数据页面，点击时给出明确提示。
 */
import { PERM } from './permissions'

export interface NavLeaf {
  /** 视图 key，同时作为本地存储的当前页面标识 */
  key: string
  label: string
  icon: string
  /** 同一面板在不同入口下的聚焦区块，如知识生成的「文档 / 数据源 / 任务」、治理的「待审核 / 冲突 / 裁决」 */
  focus?: string
  /** IA 保留但当前版本未实现的入口 */
  planned?: boolean
  /** 需要人工介入的入口角标 */
  badge?: 'pendingReview' | 'openConflict'
  /** 调试类页面：配置区 + 结果区 + Trace 抽屉 */
  debug?: boolean
  /** 访问该入口所需的权限码，缺省表示登录即可见 */
  permission?: string
  /** 仅系统管理员可见（等价于 system:user 权限，保留以兼容旧判断） */
  adminOnly?: boolean
}

export interface NavSection {
  key: string
  label: string
  icon: string
  /** 是否属于知识空间上下文：是则顶部展示空间 / 环境 / 索引状态 */
  scoped: boolean
  /** 一级菜单描述：顶栏与一级入口的辅助说明 */
  desc: string
  /** 页面内 Tab：左侧不展示，进入模块后在页面顶部横向切换 */
  tabs: NavLeaf[]
}

export const NAV_SECTIONS: NavSection[] = [
  {
    key: 'workbench', label: '知识工作台', icon: '▣', scoped: false,
    desc: '一个页面掌握企业知识资产、生命周期、治理状态与待办',
    tabs: [
      { key: 'workbench', label: '工作台', icon: '▣', permission: PERM.WORKBENCH_VIEW },
      { key: 'workbench.assets', label: '资产浏览', icon: '🗃', permission: PERM.WORKBENCH_ASSET },
      { key: 'workbench.version', label: '版本', icon: '⧉', permission: PERM.WORKBENCH_VIEW },
      { key: 'workbench.quality', label: '质量', icon: '◎', permission: PERM.WORKBENCH_VIEW },
    ],
  },
  {
    key: 'create', label: '知识生成', icon: '⚒', scoped: true,
    desc: '文档、连接器、生成任务、裁决与衍生集中在一个知识生成工作区',
    tabs: [
      { key: 'create.overview', label: '生成概览', icon: '⚒', permission: PERM.GENERATE_IMPORT },
      { key: 'create.import', label: '文档导入', icon: '📄', focus: 'documents', permission: PERM.GENERATE_IMPORT },
      { key: 'create.connector', label: '连接器', icon: '⇪', focus: 'sources', permission: PERM.GENERATE_CONNECTOR },
      { key: 'create.jobs', label: '生成任务', icon: '⏳', focus: 'ingestion', permission: PERM.GENERATE_IMPORT },
      { key: 'create.judgment', label: '知识裁决', icon: '⚖', badge: 'openConflict', focus: 'judgment', permission: PERM.GENERATE_JUDGE },
      { key: 'create.derive', label: '知识衍生', icon: '⑃', permission: PERM.GENERATE_DERIVE },
    ],
  },
  {
    key: 'search', label: '知识检索', icon: '⌕', scoped: true,
    desc: '统一完成知识搜索、知识图谱探索、检索调试、Trace 与质量评测',
    tabs: [
      { key: 'search.query', label: '搜索', icon: '⌕', permission: PERM.SEARCH_QUERY },
      { key: 'search.test', label: '检索测试', icon: '🧪', debug: true, permission: PERM.SEARCH_TEST },
      { key: 'search.profile', label: '检索策略', icon: '⌗', permission: PERM.SEARCH_POLICY },
      { key: 'search.trace', label: '检索链路追踪', icon: '🛰', debug: true, permission: PERM.SEARCH_TEST },
      { key: 'search.graph', label: '知识图谱', icon: '❖', permission: PERM.SEARCH_GRAPH },
      { key: 'search.benchmark', label: '检索评测', icon: '📈', permission: PERM.SEARCH_EVALUATE },
    ],
  },
  {
    key: 'integration', label: '知识集成', icon: '⇄', scoped: true,
    desc: '一个页面完成知识服务发布、协议配置、凭证管理和调用追踪',
    tabs: [
      { key: 'integration.overview', label: '集成概览', icon: '⇄', permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.services', label: '服务', icon: '🔌', permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.api', label: 'API', icon: '⌁', debug: true, permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.mcp', label: 'MCP', icon: '🧩', permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.sse', label: 'SSE', icon: '⇉', permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.credentials', label: '凭证', icon: '🔑', permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.logs', label: '调用日志', icon: '📜', permission: PERM.INTEGRATION_VIEW },
    ],
  },
  {
    key: 'system', label: '系统配置', icon: '⚙', scoped: false,
    desc: '低频平台配置集中管理，不占用业务导航',
    tabs: [
      { key: 'system', label: '配置总览', icon: '⚙', permission: PERM.SYSTEM_VIEW },
      { key: 'system.model', label: '模型配置', icon: '🧠', permission: PERM.SYSTEM_MODEL },
      { key: 'system.users', label: '用户与组织', icon: '👤', adminOnly: true, permission: PERM.SYSTEM_USER },
      { key: 'system.acl', label: '角色与权限', icon: '⚿', permission: PERM.SYSTEM_ACL },
      { key: 'system.schema', label: '知识 Schema', icon: '🧬', permission: PERM.SYSTEM_SCHEMA },
      { key: 'system.index', label: '存储与索引', icon: '🗲', permission: PERM.SYSTEM_INDEX },
      { key: 'system.tasks', label: '任务与告警', icon: '⏳', permission: PERM.SYSTEM_TASK },
      { key: 'system.audit', label: '审计日志', icon: '📜', permission: PERM.SYSTEM_AUDIT },
      { key: 'system.space', label: '知识空间', icon: '◈', permission: PERM.SYSTEM_SPACE },
    ],
  },
]

/** 权限判定的最小契约：外壳按登录人权限实现，页面按同一个函数裁剪入口。 */
export type PermissionCheck = (code: string) => boolean

/** 模块页内可见的 Tab：按登录人权限过滤，规划中入口另行保留提示。 */
export function visibleLeaves(section: NavSection, can?: PermissionCheck): NavLeaf[] {
  if (!can) return section.tabs
  return section.tabs.filter(leaf => !leaf.permission || can(leaf.permission))
}

/** 一级入口可见：该模块下至少要有一个有权访问的 Tab。 */
export function visibleSections(can?: PermissionCheck): NavSection[] {
  if (!can) return NAV_SECTIONS
  return NAV_SECTIONS.filter(section => visibleLeaves(section, can).length > 0)
}

export interface NavLocation { section: NavSection; leaf: NavLeaf }

const LOCATIONS = new Map<string, NavLocation>()
for (const section of NAV_SECTIONS) {
  for (const leaf of section.tabs) LOCATIONS.set(leaf.key, { section, leaf })
}

export function locate(key: string): NavLocation | undefined {
  return LOCATIONS.get(key)
}

/** 一级导航默认进入的页面：跳过规划中与无权访问的入口。 */
export function defaultLeafOf(section: NavSection, can?: PermissionCheck): NavLeaf {
  const leaves = visibleLeaves(section, can)
  return leaves.find(leaf => !leaf.planned) || leaves[0] || section.tabs[0]
}

/** 按权限码取模块内的 Tab，供模块页面复用外壳的权限判定。 */
export function leavesOfSection(key: string, can?: PermissionCheck): NavLeaf[] {
  const section = NAV_SECTIONS.find(item => item.key === key)
  return section ? visibleLeaves(section, can) : []
}

/**
 * 旧版本视图 key 到当前 IA 的映射，保证升级后书签/本地存储不失效。
 * V2.2 的三处基线变更均在此兼容：知识创建 → 知识生成、知识图谱 → 知识检索、
 * 接口联调 → 知识集成 API，系统配置的旧二级入口收敛为配置卡片。
 */
const LEGACY_TAB_ALIAS: Record<string, string> = {
  // 单级 tab（早期版本）
  overview: 'workbench',
  data: 'create.import',
  cards: 'workbench.assets',
  claims: 'workbench.assets',
  review: 'create.judgment',
  graph: 'search.graph',
  query: 'search.query',
  lifecycle: 'create.jobs',
  ops: 'system.index',
  acl: 'system.acl',
  queryPolicy: 'search.profile',
  external: 'integration.services',
  externalLab: 'integration.api',
  users: 'system.users',
  settings: 'system.model',
  // V1.x 二级 key
  'space.kb': 'system.space',
  'space.sources': 'create.connector',
  'space.documents': 'create.import',
  'space.tags': 'create.overview',
  'production.parsing': 'create.jobs',
  'production.cleaning': 'create.overview',
  'production.extraction': 'create.overview',
  'production.derivation': 'create.derive',
  'production.pipeline': 'create.jobs',
  'manage.chunk': 'create.overview',
  'manage.card': 'workbench.assets',
  'manage.entity': 'create.overview',
  'manage.relation': 'workbench.assets',
  'manage.assertion': 'workbench.assets',
  'governance.review': 'create.judgment',
  'governance.conflict': 'create.judgment',
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
  'admin.policy': 'search.profile',
  'admin.external': 'integration.services',
  'admin.lab': 'integration.api',
  'admin.model': 'system.model',
  'admin.users': 'system.users',
  // V2.0 / V2.1 二级 key
  'create.home': 'create.overview',
  'create.sources': 'create.connector',
  'create.chunk': 'create.overview',
  'create.entity': 'create.overview',
  'create.review': 'create.judgment',
  'create.conflict': 'create.judgment',
  'create.derivation': 'create.derive',
  'create.space': 'system.space',
  'workbench.graph': 'search.graph',
  'integration.api': 'integration.api',
  'system.lab': 'integration.api',
  'system.policy': 'search.profile',
}

export function normalizeViewKey(raw: string | null | undefined): string {
  if (!raw) return 'workbench'
  if (LOCATIONS.has(raw)) return raw
  return LEGACY_TAB_ALIAS[raw] || 'workbench'
}
