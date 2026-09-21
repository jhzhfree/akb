import axios, { type AxiosResponse } from 'axios'
import type {
  ApiEnvelope, AuthMe, AuthSessionResult, AuthStatus, RoleCatalog, UserAccount, UserGroupRow,
  UserRoleValue,
} from '../types'

type ApiResponse<T> = AxiosResponse<ApiEnvelope<T>>

const TOKEN_KEY = 'llmkb.authToken'
/** 会话失效事件：App 监听后回到登录页，避免每个调用点各自处理 401。 */
export const AUTH_EXPIRED_EVENT = 'kos-auth-expired'

export function getToken():string {
  return localStorage.getItem(TOKEN_KEY) || ''
}
export function setToken(token:string):void {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

axios.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers = { ...(config.headers || {}), Authorization: `Bearer ${token}` } as never
  }
  return config
})
axios.interceptors.response.use(
  response => response,
  error => {
    if (error?.response?.status === 401) {
      setToken('')
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
    }
    return Promise.reject(error)
  },
)

/* ---- 登录与会话 ---- */

export const login = (
  username:string, password:string,
):Promise<ApiResponse<AuthSessionResult>> =>
  axios.post('/api/auth/login', { username, password })
export const logout = ():Promise<ApiResponse<boolean>> => axios.post('/api/auth/logout')
export const getAuthStatus = ():Promise<ApiResponse<AuthStatus>> => axios.get('/api/auth/status')
export const getMe = ():Promise<ApiResponse<AuthMe>> => axios.get('/api/auth/me')
/** 角色字典：角色码、中文名、职责说明与自带权限码，供用户管理与权限说明使用。 */
export const getRoleCatalog = ():Promise<ApiResponse<RoleCatalog>> => axios.get('/api/auth/roles')
export const changeOwnPassword = (
  oldPassword:string, newPassword:string,
):Promise<ApiResponse<boolean>> =>
  axios.post('/api/auth/password', { oldPassword, newPassword })

/* ---- 用户管理（系统管理员） ---- */

export const listUsers = ():Promise<ApiResponse<UserAccount[]>> => axios.get('/api/users')
export const createUser = (payload:{
  username:string; password:string; displayName?:string; email?:string
  role?:UserRoleValue|string; groupIds?:number[]
}):Promise<ApiResponse<UserAccount>> => axios.post('/api/users', payload)
export const updateUser = (id:number, payload:{
  displayName?:string; email?:string; role?:UserRoleValue|string; groupIds?:number[]
}):Promise<ApiResponse<UserAccount>> => axios.put(`/api/users/${id}`, payload)
export const setUserStatus = (id:number, status:'ACTIVE'|'DISABLED'):Promise<ApiResponse<boolean>> =>
  axios.post(`/api/users/${id}/status?status=${status}`)
export const resetUserPassword = (id:number, password:string):Promise<ApiResponse<boolean>> =>
  axios.post(`/api/users/${id}/password`, { password })

/* ---- 用户组管理 ---- */

export const listGroups = ():Promise<ApiResponse<UserGroupRow[]>> => axios.get('/api/groups')
export const createGroup = (
  name:string, description='',
):Promise<ApiResponse<UserGroupRow>> => axios.post('/api/groups', { name, description })
export const updateGroup = (
  id:number, name:string, description?:string,
):Promise<ApiResponse<UserGroupRow>> =>
  axios.put(`/api/groups/${id}`, { name, description })
export const setGroupStatus = (
  id:number, status:'ACTIVE'|'ARCHIVED',
):Promise<ApiResponse<boolean>> => axios.post(`/api/groups/${id}/status?status=${status}`)
/** 全量替换成员：前端把多选结果整体提交，避免逐条增删产生中间态。 */
export const setGroupMembers = (
  id:number, memberIds:number[],
):Promise<ApiResponse<UserGroupRow>> => axios.post(`/api/groups/${id}/members`, { memberIds })
export const removeGroupMember = (id:number, userId:number):Promise<ApiResponse<UserGroupRow>> =>
  axios.delete(`/api/groups/${id}/members/${userId}`)
