
/**
 * 信息架构（IA）与导航定义。
 *
 * 依据《产品详细设计说明书 V2.1》第 1 章「设计基线与总体原则」与第 2 章「全局框架」：
 *   1. 一级入口稳定：只保留「知识概览 / 知识生成 / 知识检索 / 接口服务 / 系统配置」五个一级菜单；
 *   2. 左侧导航不设置任何二级菜单：Chunk、Assertion、Graph、Evaluation、凭证等一律进入主页面内部，
 *      通过 Tab、任务卡、快捷操作、Drawer 或详情页承载；
 *   3. “知识创建”更名为“知识生成”：强调从原始数据到可信知识资产的流水线，而不是简单 CRUD；
 *   4. “知识图谱”整体归入“知识检索”：作为实体关系探索、Graph Recall 与路径分析能力；
 *   5. “知识集成”更名为“接口服务”：对外提供的是 API / MCP / SSE 接口，而不是内部的数据集成；
 *   6. 知识空间选择器固定在顶栏，不作为一级菜单。
 *
 * 左侧菜单按业务域分组（原型 knowledge-base-prototype 的 dashboard 侧栏）：
 *   工作台：知识概览
 *   知识库：知识生成、知识检索
 *   系统集成：接口服务
 * 系统配置属于低频平台配置，不进分组，收在侧栏底部的设置图标里。
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
  /**
   * 仅管理员视图可见的入口（角色维度，区别于权限码）：
   * 系统管理员与知识管理员看到模块内完整 Tab，其余角色只看到精简版。
   * 例如知识生成的「生成概览 / 连接器 / 生成任务」属于生产流水线运维入口，
   * 普通成员只需要「文档导入 / 知识裁决 / 知识衍生」。
   */
  adminOnly?: boolean
}

/** 管理员角色：看到模块内的完整 Tab；其余角色进入精简视图。 */
const ADMIN_ROLES = new Set<string>(['ADMIN', 'KNOWLEDGE_ADMIN'])

/** 未拿到角色（未开启认证 / me 未就绪）时按管理员视图渲染，与 hasPermission 的空权限全放行保持一致。 */
export function isAdminView(role?: string | null): boolean {
  return !role || ADMIN_ROLES.has(role)
}

/** 左侧菜单的业务域分组：工作台 / 知识库 / 系统集成。 */
export type NavGroupKey = 'workbench' | 'knowledge' | 'integration'

export interface NavGroup {
  key: NavGroupKey
  label: string
  sections: NavSection[]
}

/** 分组顺序即侧栏自上而下的展示顺序。 */
export const NAV_GROUP_ORDER: {key:NavGroupKey; label:string}[] = [
  {key:'workbench', label:'工作台'},
  {key:'knowledge', label:'知识库'},
  {key:'integration', label:'系统集成'},
]

export interface NavSection {
  key: string
  label: string
  /** 图标名：由 components/NavIcon.vue 渲染成原型风格的线性 SVG 图标 */
  icon: string
  /** 所属业务域分组；缺省（如系统配置）表示不进左侧分组，只在底部工具区出现 */
  group?: NavGroupKey
  /** 是否属于知识空间上下文：是则顶部展示空间 / 环境 / 索引状态 */
  scoped: boolean
  /** 一级菜单描述：顶栏与一级入口的辅助说明 */
  desc: string
  /** 页面内 Tab：左侧不展示，进入模块后在页面顶部横向切换 */
  tabs: NavLeaf[]
}

export const NAV_SECTIONS: NavSection[] = [
  {
    key: 'workbench', label: '知识概览', icon: 'grid', group: 'workbench', scoped: false,
    desc: '一个页面掌握企业知识资产、生命周期、治理状态与待办',
    tabs: [
      /** 首页：登录后的主页面（原型 dashboard）——数据总览、最近访问的知识库，顶部不再放欢迎区。 */
      { key: 'home', label: '首页', icon: 'home', permission: PERM.WORKBENCH_VIEW },
      { key: 'workbench', label: '工作台', icon: 'grid', permission: PERM.WORKBENCH_VIEW },
      { key: 'workbench.assets', label: '资产浏览', icon: 'layers', permission: PERM.WORKBENCH_ASSET },
      { key: 'workbench.version', label: '版本', icon: 'history', permission: PERM.WORKBENCH_VIEW },
      { key: 'workbench.quality', label: '质量', icon: 'shield', permission: PERM.WORKBENCH_VIEW },
    ],
  },
  {
    key: 'create', label: '知识生成', icon: 'sparkles', group: 'knowledge', scoped: true,
    desc: '文档、连接器、生成任务、裁决与衍生集中在一个知识生成工作区',
    /**
     * 知识生成按角色分两套视图（Tab 顺序对管理员保持不变）：
     *   管理员：生成概览 / 文档导入 / 连接器 / 生成任务 / 知识裁决 / 知识衍生
     *   普通成员：文档导入（默认进入）/ 知识裁决 / 知识衍生
     * 概览、连接器与生成任务属于生产流水线的运维入口，对普通成员隐藏；
     * 过滤后剩下的第一个可见 Tab 正是「文档导入」，即普通成员的默认落点。
     */
    tabs: [
      { key: 'create.overview', label: '生成概览', icon: 'sparkles', adminOnly: true, permission: PERM.GENERATE_IMPORT },
      { key: 'create.import', label: '文档导入', icon: 'filePlus', focus: 'documents', permission: PERM.GENERATE_IMPORT },
      { key: 'create.connector', label: '连接器', icon: 'link', adminOnly: true, focus: 'sources', permission: PERM.GENERATE_CONNECTOR },
      /** 连接器的知识目录：按外部系统的类型与属性浏览同步下来的条目。 */
      { key: 'create.connectorCatalog', label: '外部知识', icon: 'layers', adminOnly: true, permission: PERM.GENERATE_CONNECTOR },
      { key: 'create.jobs', label: '生成任务', icon: 'clock', adminOnly: true, focus: 'ingestion', permission: PERM.GENERATE_IMPORT },
      { key: 'create.judgment', label: '知识裁决', icon: 'scale', badge: 'openConflict', focus: 'judgment', permission: PERM.GENERATE_JUDGE },
      { key: 'create.derive', label: '知识衍生', icon: 'branch', permission: PERM.GENERATE_DERIVE },
    ],
  },
  {
    key: 'search', label: '知识检索', icon: 'search', group: 'knowledge', scoped: true,
    desc: '统一完成知识搜索、知识图谱探索、检索调试、Trace 与质量评测',
    tabs: [
      { key: 'search.query', label: '搜索', icon: 'search', permission: PERM.SEARCH_QUERY },
      { key: 'search.test', label: '检索测试', icon: 'beaker', debug: true, permission: PERM.SEARCH_TEST },
      { key: 'search.profile', label: '检索策略', icon: 'sliders', permission: PERM.SEARCH_POLICY },
      /**
       * 检索链路追踪与检索评测属于检索质量的调试 / 测评入口，只在管理视图出现：
       * 用户视图下的知识检索只保留「搜索 / 检索测试 / 检索策略 / 知识图谱」。
       */
      { key: 'search.trace', label: '检索链路追踪', icon: 'rss', debug: true, adminOnly: true, permission: PERM.SEARCH_TEST },
      { key: 'search.graph', label: '知识图谱', icon: 'network', permission: PERM.SEARCH_GRAPH },
      { key: 'search.benchmark', label: '检索评测', icon: 'chart', adminOnly: true, permission: PERM.SEARCH_EVALUATE },
    ],
  },
  {
    /** V2.2：原「知识集成」更名为「接口服务」——对外交付的是 API / MCP / SSE 接口能力。 */
    key: 'integration', label: '接口服务', icon: 'plug', group: 'integration', scoped: true,
    desc: '一个页面完成接口服务发布、协议配置、凭证管理和调用追踪',
    /**
     * 用户视图下接口服务只保留「服务」：调用方只需要知道有哪些服务、怎么接，
     * 概览、协议配置（API / MCP / SSE）、凭证与调用日志属于发布与运维侧，只对管理视图开放。
     * 「服务」是过滤后剩下的第一个可见 Tab，即用户视图的默认落点。
     */
    tabs: [
      { key: 'integration.overview', label: '接口概览', icon: 'plug', adminOnly: true, permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.services', label: '服务', icon: 'server', permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.api', label: 'API', icon: 'code', debug: true, adminOnly: true, permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.mcp', label: 'MCP', icon: 'blocks', adminOnly: true, permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.sse', label: 'SSE', icon: 'broadcast', adminOnly: true, permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.credentials', label: '凭证', icon: 'key', adminOnly: true, permission: PERM.INTEGRATION_VIEW },
      { key: 'integration.logs', label: '调用日志', icon: 'doc', adminOnly: true, permission: PERM.INTEGRATION_VIEW },
    ],
  },
  {
    key: 'system', label: '系统配置', icon: 'settings', scoped: false,
    desc: '低频平台配置集中管理，不占用业务导航',
    tabs: [
      { key: 'system', label: '配置总览', icon: 'settings', permission: PERM.SYSTEM_VIEW },
      { key: 'system.model', label: '模型配置', icon: 'cpu', permission: PERM.SYSTEM_MODEL },
      { key: 'system.users', label: '用户与组织', icon: 'users', adminOnly: true, permission: PERM.SYSTEM_USER },
      { key: 'system.acl', label: '角色与权限', icon: 'lock', permission: PERM.SYSTEM_ACL },
      { key: 'system.schema', label: '知识 Schema', icon: 'tag', permission: PERM.SYSTEM_SCHEMA },
      { key: 'system.index', label: '存储与索引', icon: 'database', permission: PERM.SYSTEM_INDEX },
      { key: 'system.tasks', label: '任务与告警', icon: 'checks', permission: PERM.SYSTEM_TASK },
      { key: 'system.audit', label: '审计日志', icon: 'doc', permission: PERM.SYSTEM_AUDIT },
      { key: 'system.space', label: '知识空间', icon: 'folder', permission: PERM.SYSTEM_SPACE },
    ],
  },
]

/** 权限判定的最小契约：外壳按登录人权限实现，页面按同一个函数裁剪入口。 */
export type PermissionCheck = (code: string) => boolean

/**
 * 模块页内可见的 Tab：先按角色裁剪管理员专属入口，再按登录人权限过滤。
 * 角色缺省（未开启认证 / me 未就绪）时按管理员视图渲染，与 hasPermission 的空权限全放行一致。
 */
export function visibleLeaves(section: NavSection, can?: PermissionCheck, role?: string | null): NavLeaf[] {
  const admin = isAdminView(role)
  return section.tabs.filter(leaf => {
    if (leaf.adminOnly && !admin) return false
    if (can && leaf.permission && !can(leaf.permission)) return false
    return true
  })
}

/** 一级入口可见：该模块下至少要有一个有权访问的 Tab。 */
export function visibleSections(can?: PermissionCheck, role?: string | null): NavSection[] {
  return NAV_SECTIONS.filter(section => visibleLeaves(section, can, role).length > 0)
}

/**
 * 左侧菜单：按业务域分组后的可见入口（工作台 / 知识库 / 系统集成）。
 * 无 group 的模块（系统配置）不进分组；整组都无权访问时整组不展示。
 */
export function visibleGroups(can?: PermissionCheck, role?: string | null): NavGroup[] {
  const sections = visibleSections(can, role)
  return NAV_GROUP_ORDER
    .map(group => ({key:group.key, label:group.label, sections:sections.filter(s=>s.group===group.key)}))
    .filter(group => group.sections.length > 0)
}

export interface NavLocation { section: NavSection; leaf: NavLeaf }

const LOCATIONS = new Map<string, NavLocation>()
for (const section of NAV_SECTIONS) {
  for (const leaf of section.tabs) LOCATIONS.set(leaf.key, { section, leaf })
}

export function locate(key: string): NavLocation | undefined {
  return LOCATIONS.get(key)
}

/**
 * 一级导航默认进入的页面：跳过规划中、管理员专属与无权访问的入口。
 * 普通成员进入知识生成时，这里落到「文档导入」。
 */
export function defaultLeafOf(section: NavSection, can?: PermissionCheck, role?: string | null): NavLeaf {
  const leaves = visibleLeaves(section, can, role)
  return leaves.find(leaf => !leaf.planned) || leaves[0] || section.tabs[0]
}

/** 按角色与权限取模块内的 Tab，供模块页面复用外壳的权限判定。 */
export function leavesOfSection(key: string, can?: PermissionCheck, role?: string | null): NavLeaf[] {
  const section = NAV_SECTIONS.find(item => item.key === key)
  return section ? visibleLeaves(section, can, role) : []
}

/**
 * 旧版本视图 key 到当前 IA 的映射，保证升级后书签/本地存储不失效。
 * V2.2 的三处基线变更均在此兼容：知识创建 → 知识生成、知识图谱 → 知识检索、
 * 接口联调 → 接口服务 API，系统配置的旧二级入口收敛为配置卡片。
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
