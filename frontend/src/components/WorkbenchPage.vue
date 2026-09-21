<template>
  <!-- 设计 §3：知识工作台 = 看知识。资产浏览 / 版本 / 质量是 KPI 下钻的页面内视图，不占左侧菜单。 -->
  <ModuleTabs label="知识工作台" :leaves="tabs" :active="active" @select="$emit('select',$event)" />

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
<script setup lang="ts">
import type {
  Capabilities, DashboardGovernance, DashboardLifecycle, DashboardSummary, Overview,
} from '../types'
import { computed } from 'vue'
import type { AssetType } from './AssetBrowserPanel.vue'
import { useShell } from '../shell'
import ModuleTabs from './ModuleTabs.vue'
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
const tabs = computed(() => shell.tabs('workbench'))

/** 版本快照只带类型与对象码，详情仍走统一对象详情抽屉。 */
function openDetailFromVersion(type: string, code: string) {
  const normalized = String(type || '').toUpperCase()
  if ((normalized === 'CARD' || normalized === 'CLAIM') && code) {
    shell.openDetail(normalized as 'CARD' | 'CLAIM', code)
  }
}
</script>
