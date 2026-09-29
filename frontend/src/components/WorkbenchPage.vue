<template>
  <!-- 设计 §3：知识概览 = 看知识。资产浏览 / 版本 / 质量是 KPI 下钻的页面内视图，不占左侧菜单。
       二级入口条已提到主内容最上方（App.vue 渲染），这里只负责按当前 Tab 渲染对应面板；
       停在「首页」时不渲染任何面板——首页由 HomeDashboard 呈现，否则会与它叠在一起。 -->
  <template v-if="active!=='home'">
    <OverviewPanel
      v-if="active==='workbench'"
      :knowledge-base-id="knowledgeBaseId" :overview="overview" :capabilities="capabilities"
      :summary="summary" :lifecycle="lifecycle" :governance="governance"
      @navigate="shell.navigate" @open-assets="shell.openAssets" />

    <AssetBrowserPanel
      v-else-if="active==='workbench.assets'"
      :knowledge-base-id="knowledgeBaseId" :type="assetType"
      @update:type="$emit('update:assetType',$event)"
      @open-detail="shell.openDetail" @open-graph="shell.openGraph" @navigate="shell.navigate" />

    <VersionGovernancePanel
      v-else-if="active==='workbench.version'"
      :knowledge-base-id="knowledgeBaseId" @open-detail="openDetailFromVersion" />

    <QualityPanel v-else :knowledge-base-id="knowledgeBaseId" @navigate="shell.navigate" />
  </template>
</template>
<script setup lang="ts">
import type {
  Capabilities, DashboardGovernance, DashboardLifecycle, DashboardSummary, Overview,
} from '../types'
import type { AssetType } from './AssetBrowserPanel.vue'
import { useShell } from '../shell'
import OverviewPanel from './OverviewPanel.vue'
import AssetBrowserPanel from './AssetBrowserPanel.vue'
import VersionGovernancePanel from './VersionGovernancePanel.vue'
import QualityPanel from './QualityPanel.vue'

defineProps<{
  active: string
  knowledgeBaseId: number
  overview: Overview
  capabilities: Capabilities | null
  summary: DashboardSummary | null
  lifecycle: DashboardLifecycle | null
  governance: DashboardGovernance | null
  assetType: AssetType
}>()
defineEmits<{ select: [key: string]; 'update:assetType': [type: AssetType] }>()

const shell = useShell()

/** 版本快照只带类型与对象码，详情仍走统一对象详情抽屉。 */
function openDetailFromVersion(type: string, code: string) {
  const normalized = String(type || '').toUpperCase()
  if ((normalized === 'CARD' || normalized === 'CLAIM') && code) {
    shell.openDetail(normalized as 'CARD' | 'CLAIM', code)
  }
}
</script>
