<template>
  <!-- 设计 §7：系统配置 = 低频平台配置，卡片宫格进入，模块间用 Tab 横向切换，不占业务导航。 -->
  <ModuleTabs
    label="系统配置" :leaves="leaves" :active="active"
    @select="$emit('select',$event)" />

  <div v-if="active==='system'" class="config-grid mt-4">
    <button v-for="module in modules" :key="module.key" class="config-card" :disabled="module.planned"
      @click="$emit('select',module.key)">
      <b>{{ module.label }}</b>
      <span>{{ module.desc }}</span>
      <em v-if="module.planned">规划中</em>
    </button>
  </div>

  <ModelSettingsPanel v-else-if="active==='system.model'" />

  <UserAdminPanel v-else-if="active==='system.users'" />

  <AclAdministrationPanel
    v-else-if="active==='system.acl'"
    :subjects="aclSubjects" :roles="aclRoles" :changes="aclChanges" :audits="aclAudits"
    :change-total="changeTotal" :change-page="changePage" :change-size="changeSize"
    :audit-total="auditTotal" :audit-page="auditPage" :audit-size="auditSize"
    @change="$emit('acl-change',$event)" @log-page="$emit('acl-log-page',$event)" />

  <SchemaPanel v-else-if="active==='system.schema'" :knowledge-base-id="knowledgeBaseId" />

  <OpsPanel v-else-if="active==='system.index'" :knowledge-base-id="knowledgeBaseId" :base-name="baseName"
    @refresh="$emit('refresh')" />

  <TaskAlertPanel v-else-if="active==='system.tasks'" :knowledge-base-id="knowledgeBaseId" />

  <!-- 审计日志与权限同源：同一治理面板的审计区块，不另造一份日志来源。 -->
  <AclAdministrationPanel
    v-else-if="active==='system.audit'"
    :subjects="aclSubjects" :roles="aclRoles" :changes="aclChanges" :audits="aclAudits"
    :change-total="changeTotal" :change-page="changePage" :change-size="changeSize"
    :audit-total="auditTotal" :audit-page="auditPage" :audit-size="auditSize"
    @change="$emit('acl-change',$event)" @log-page="$emit('acl-log-page',$event)" />

  <SpacePanel
    v-else-if="active==='system.space'"
    :bases="bases" :overview="overview" :active-id="knowledgeBaseId"
    @select="$emit('select-base',$event)" @create="$emit('create-base')" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { AclAudit, AclChange, AclRole, AclSubject, KnowledgeBase, Overview } from '../types'
import { useShell } from '../shell'
import ModuleTabs from './ModuleTabs.vue'
import ModelSettingsPanel from './ModelSettingsPanel.vue'
import UserAdminPanel from './UserAdminPanel.vue'
import AclAdministrationPanel from './AclAdministrationPanel.vue'
import OpsPanel from './OpsPanel.vue'
import SpacePanel from './SpacePanel.vue'
import SchemaPanel from './SchemaPanel.vue'
import TaskAlertPanel from './TaskAlertPanel.vue'

const props = defineProps<{
  active: string
  knowledgeBaseId: number
  baseName: string
  bases: KnowledgeBase[]
  overview: Overview
  aclSubjects: AclSubject[]
  aclRoles: AclRole[]
  aclChanges: AclChange[]
  aclAudits: AclAudit[]
  /** 审计与变更的分页状态：列表只装当前页，总数来自后端计数。 */
  changeTotal?: number
  changePage?: number
  changeSize?: number
  auditTotal?: number
  auditPage?: number
  auditSize?: number
  /** 用户与组织仅管理员可见，非管理员不渲染该 Tab。 */
  canManageUsers: boolean
}>()
defineEmits<{
  select: [key: string]
  'acl-change': [payload: { resourceType: string; resourceCode: string; permissionTags: string[]; reason: string }]
  'acl-log-page': [payload: { which: 'audit' | 'change'; page: number; size: number }]
  'select-base': [id: number]
  'create-base': []
  refresh: []
}>()

const shell = useShell()
/** 登录信息是异步拿到的，Tab 必须跟着权限一起重算，否则会停留在未裁剪的快照上。 */
const allTabs = computed(() => shell.tabs('system'))
/** 卡片宫格与 Tab 同源：隐藏无权访问的入口，保留规划中入口但不提供假数据页面。 */
const leaves = computed(() => allTabs.value.filter(tab => !tab.adminOnly || props.canManageUsers))
const modules = computed(() => {
  const desc: Record<string, string> = {
    'system.model': '统一模型配置与健康检查',
    'system.users': '平台用户、组织与状态管理',
    'system.acl': '基于 ACL 标签的角色与权限',
    'system.schema': '知识类型、字段与关系约束',
    'system.index': 'ES 索引、Milvus 向量与 Outbox',
    'system.tasks': '异步任务、告警与通知',
    'system.audit': '登录、检索、变更与调用审计',
    'system.space': '知识库空间与成员配置',
  }
  return leaves.value
    .filter(tab => tab.key !== 'system')
    .map(tab => ({ key: tab.key, label: tab.label, desc: desc[tab.key] || '', planned: !!tab.planned }))
})
</script>
<style scoped>
/* 低频配置集中管理：卡片宫格是进入各配置模块的唯一入口。 */
.config-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.config-card{
  position:relative;padding:16px 18px;text-align:left;background:var(--bg-raised);
  border:1px solid var(--line-d);border-radius:11px;box-shadow:0 2px 9px #1d35570a;cursor:pointer}
.config-card:hover{border-color:var(--brand-400)}
.config-card:disabled{cursor:not-allowed}
.config-card b{display:block;font-size:15px;color:var(--ink-1)}
.config-card span{display:block;margin-top:4px;font-size:11.5px;color:var(--ink-2)}
.config-card em{position:absolute;right:14px;top:14px;font-size:11px;font-style:normal;color:var(--ink-3)}
@media(max-width:1100px){.config-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
