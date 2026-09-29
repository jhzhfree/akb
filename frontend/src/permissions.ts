
/**
 * 权限码与角色定义。
 *
 * 权限码与后端 common/Permissions.java 一一对应：后端在写接口入口按同样的码校验，
 * 前端据 /api/auth/me 下发的 permissions 裁剪导航、区块与按钮，
 * 保证「界面上看不到的操作，接口也调不通」。
 */
import type { UserRoleValue } from './types'

/** 权限码字典（与后端 Permissions 常量同名同值）。 */
export const PERM = {
  SYSTEM_VIEW: 'system:view',
  SYSTEM_USER: 'system:user',
  SYSTEM_ACL: 'system:acl',
  SYSTEM_MODEL: 'system:model',
  SYSTEM_SCHEMA: 'system:schema',
  SYSTEM_INDEX: 'system:index',
  SYSTEM_TASK: 'system:task',
  SYSTEM_AUDIT: 'system:audit',
  SYSTEM_SPACE: 'system:space',
  WORKBENCH_VIEW: 'workbench:view',
  WORKBENCH_ASSET: 'workbench:asset',
  GENERATE_IMPORT: 'generate:import',
  GENERATE_CONNECTOR: 'generate:connector',
  GENERATE_JUDGE: 'generate:judge',
  GENERATE_DERIVE: 'generate:derive',
  SEARCH_QUERY: 'search:query',
  SEARCH_GRAPH: 'search:graph',
  SEARCH_TEST: 'search:test',
  SEARCH_POLICY: 'search:policy',
  SEARCH_EVALUATE: 'search:evaluate',
  INTEGRATION_VIEW: 'integration:view',
} as const

export type PermissionCode = typeof PERM[keyof typeof PERM]

/** 未开启认证或后端未下发权限时按全权限处理，避免匿名/单机部署被权限系统挡住。 */
export function hasPermission(granted: string[] | null | undefined, code: string): boolean {
  if (granted == null) return true
  return granted.indexOf(code) >= 0
}

/** 登录后按角色进入的落地页：让每个角色第一眼看到自己负责的工作区。 */
export const ROLE_HOME: Record<UserRoleValue, string> = {
  ADMIN: 'system',
  KNOWLEDGE_ADMIN: 'workbench',
  KNOWLEDGE_OPERATOR: 'create.overview',
  KNOWLEDGE_AUDITOR: 'create.judgment',
  KNOWLEDGE_VIEWER: 'workbench',
}

/** 角色职责一句话说明，用于用户管理的角色下拉与权限说明。 */
export const ROLE_BRIEF: Record<UserRoleValue, string> = {
  ADMIN: '系统配置：用户与组织、角色权限、模型、索引、任务告警、审计、Schema、知识空间',
  KNOWLEDGE_ADMIN: '知识工作台、知识生成（含裁决与衍生）、知识检索（含策略与测评）全部功能',
  KNOWLEDGE_OPERATOR: '简化知识生成：上传文件、生成知识、查看知识对象；可检索，不能裁决、不能衍生、不能配置策略与测评',
  KNOWLEDGE_AUDITOR: '知识运营员 + 知识裁决 + 知识测评',
  KNOWLEDGE_VIEWER: '可查看知识工作台（总览 / 资产浏览 / 版本 / 质量）与知识检索，不能改动知识',
}

/**
 * 角色身份色：仅用于视觉识别（顶栏 / 侧栏 / 欢迎区），不改权限判定。
 * 已定义的 CSS 变量：--role-admin / --role-knowledge-admin / --role-operator / --role-auditor / --role-viewer。
 */
export const ROLE_ACCENT: Record<UserRoleValue, string> = {
  ADMIN: 'var(--role-admin)',
  KNOWLEDGE_ADMIN: 'var(--role-knowledge-admin)',
  KNOWLEDGE_OPERATOR: 'var(--role-operator)',
  KNOWLEDGE_AUDITOR: 'var(--role-auditor)',
  KNOWLEDGE_VIEWER: 'var(--role-viewer)',
}
