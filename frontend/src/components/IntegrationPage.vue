<template>
  <!-- 设计 §6：接口服务 = 用知识。协议配置与凭证管理都在本页 Tab 内，不占左侧菜单。 -->
  <ModuleTabs label="接口服务" :leaves="tabs" :active="active" @select="$emit('select',$event)" />

  <IntegrationOverviewPanel
    v-if="active==='integration.overview'"
    :knowledge-base-id="knowledgeBaseId" :bases="bases"
    @navigate="shell.navigate" @open-trace="shell.openCallTrace" />

  <!-- 服务与凭证同属外部服务面板，按 Tab 聚焦到协议区块或凭证区块。 -->
  <ExternalServicePanel
    v-else-if="active==='integration.services'"
    :bases="bases" focus="services" />
  <ExternalServicePanel
    v-else-if="active==='integration.credentials'"
    :bases="bases" focus="credentials" />

  <!-- API / SSE 复用联调台：按协议进入对应页签（设计 §6.3）。 -->
  <ExternalServiceLab v-else-if="active==='integration.api'" :bases="bases" protocol="rest" />
  <ExternalServiceLab v-else-if="active==='integration.sse'" :bases="bases" protocol="sse" />

  <McpServicePanel v-else-if="active==='integration.mcp'" :knowledge-base-id="knowledgeBaseId" />

  <CallLogPanel v-else :knowledge-base-id="knowledgeBaseId" @open-trace="shell.openCallTrace" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeBase } from '../types'
import { useShell } from '../shell'
import ModuleTabs from './ModuleTabs.vue'
import IntegrationOverviewPanel from './IntegrationOverviewPanel.vue'
import ExternalServicePanel from './ExternalServicePanel.vue'
import ExternalServiceLab from './ExternalServiceLab.vue'
import McpServicePanel from './McpServicePanel.vue'
import CallLogPanel from './CallLogPanel.vue'

defineProps<{ active: string; knowledgeBaseId: number; bases: KnowledgeBase[] }>()
defineEmits<{ select: [key: string] }>()

const shell = useShell()
const tabs = computed(() => shell.tabs('integration'))
</script>
