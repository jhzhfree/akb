import axios, { type AxiosResponse } from 'axios'
import type { ApiEnvelope } from '../types'

type ApiResponse<T> = AxiosResponse<ApiEnvelope<T>>

/** 一个可配置的菜单项：key 与前端 navigation.ts 的 leaf.key 一致。 */
export interface RoleMenuItem {
  key:string
  /** 所需权限码；null 表示登录即可见。 */
  permission:string|null
  adminOnly:boolean
}

/** 一个角色的可见菜单状态。 */
export interface RoleMenuRole {
  code:string
  label:string
  visible:string[]
  /** 是否已被管理员显式定制（false 表示仍在用内置默认）。 */
  configured:boolean
}

export interface RoleMenuConfig {
  roles:RoleMenuRole[]
  menu:RoleMenuItem[]
  /** 权限码 → 中文名。 */
  permissions:Record<string,string>
}

/**
 * 角色可见菜单配置。
 * 与 /api/auth/me 下发的 menu 同源：这里改的是配置本身，me 下发的是当前登录人拿到的结果。
 */
export const getRoleMenu = ():Promise<ApiResponse<RoleMenuConfig>> => axios.get('/api/system/role-menu')

/** 保存一个角色的可见菜单：未列出的菜单项置为不可见。 */
export const saveRoleMenu = (role:string, visible:string[]):Promise<ApiResponse<{role:string; visible:string[]; configured:boolean}>> =>
  axios.put('/api/system/role-menu', {visible}, {params:{role}})

/** 恢复该角色的内置默认菜单。 */
export const resetRoleMenu = (role:string):Promise<ApiResponse<{role:string; visible:string[]; configured:boolean}>> =>
  axios.post('/api/system/role-menu/reset', null, {params:{role}})

/**
 * 一类默认配置的来源与条数。
 * source 有三种可能：配置目录里的文件路径、classpath:defaults/...（jar 内）、built-in（内置常量）。
 */
export interface DefaultCatalogSource { source:string; count:number }

export interface DefaultCatalogStatus {
  /** 配置目录；为 null 说明没配 KNOWLEDGE_CONFIG_DIR，默认配置只来自镜像。 */
  configDir:string|null
  scenarioTemplates:DefaultCatalogSource
  derivationRules:DefaultCatalogSource
}

export interface DefaultRestoreResult {
  backend:{
    configured:boolean
    written:string[]
    failed:string[]
    message?:string
    scenarioTemplates:DefaultCatalogSource
    derivationRules:DefaultCatalogSource
  }
  aiService:{ ok:boolean; result?:{profiles?:string[]; active?:string; source?:string}; error?:string }
  /** 两侧都恢复成功才为 true。 */
  allRestored:boolean
}

/** 默认配置当前生效的来源。 */
export const getDefaultCatalog = ():Promise<ApiResponse<DefaultCatalogStatus>> => axios.get('/api/admin/defaults')

/** 用镜像内的出厂配置覆盖配置目录，并让 AI 服务恢复检索策略。 */
export const restoreDefaultCatalog = ():Promise<ApiResponse<DefaultRestoreResult>> =>
  axios.post('/api/admin/defaults/restore')
