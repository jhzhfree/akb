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
    @change="$emit('acl-change',$event)" />

  <PanelState
    v-else-if="active==='system.schema'"
    :empty="true" empty-text="知识 Schema：规划中"
    empty-hint="信息架构已保留该位置，能力落地后会在此开放" />

  <OpsPanel v-else-if="active==='system.index'" :knowledge-base-id="knowledgeBaseId" :base-name="baseName"
    @refresh="$emit('refresh')" />

  <PanelState
    v-else-if="active==='system.tasks'"
    :empty="true" empty-text="任务与告警：规划中"
    empty-hint="信息架构已保留该位置，能力落地后会在此开放" />

  <!-- 审计日志与权限同源：同一治理面板的审计区块，不另造一份日志来源。 -->
  <AclAdministrationPanel
    v-else-if="active==='system.audit'"
    :subjects="aclSubjects" :roles="aclRoles" :changes="aclChanges" :audits="aclAudits"
    @change="$emit('acl-change',$event)" />

  <SpacePanel
    v-else-if="active==='system.space'"
    :bases="bases" :overview="overview" :active-id="knowledgeBaseId"
    @select="$emit('select-base',$event)" @create="$emit('create-base')" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { AclAudit, AclChange, AclRole, AclSubject, KnowledgeBase, Overview } from '../types'
import { NAV_SECTIONS } from '../navigation'
import ModuleTabs from './ModuleTabs.vue'
import ModelSettingsPanel from './ModelSettingsPanel.vue'
import UserAdminPanel from './UserAdminPanel.vue'
import AclAdministrationPanel from './AclAdministrationPanel.vue'
import OpsPanel from './OpsPanel.vue'
import SpacePanel from './SpacePanel.vue'
import PanelState from './PanelState.vue'

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
  /** 用户与组织仅管理员可见，非管理员不渲染该 Tab。 */
  canManageUsers: boolean
}>()
defineEmits<{
  select: [key: string]
  'acl-change': [payload: { resourceType: string; resourceCode: string; permissionTags: string[]; reason: string }]
  'select-base': [id: number]
  'create-base': []
  refresh: []
}>()

const allTabs = NAV_SECTIONS[4].tabs
/** 卡片宫格与 Tab 同源：隐藏无权访问的入口，保留规划中入口但不提供假数据页面。 */
const leaves = computed(() => allTabs.filter(tab => !tab.adminOnly || props.canManageUsers))
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
  position:relative;padding:16px 18px;text-align:left;background:#fff;
  border:1px solid #e2e9f2;border-radius:11px;box-shadow:0 2px 9px #1d35570a;cursor:pointer}
.config-card:hover{border-color:#77aff8}
.config-card:disabled{cursor:not-allowed}
.config-card b{display:block;font-size:15px;color:#164e8b}
.config-card span{display:block;margin-top:4px;font-size:11.5px;color:#748399}
.config-card em{position:absolute;right:14px;top:14px;font-size:11px;font-style:normal;color:#9aa4b5}
@media(max-width:1100px){.config-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
