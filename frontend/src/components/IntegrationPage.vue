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

  <!-- 事件总线：持久事件历史 + 实时订阅，独立成页后可单独进来翻历史 -->
  <PlatformEventPanel
    v-else-if="active==='integration.events'"
    :bases="bases" :knowledge-base-id="knowledgeBaseId" />

  <McpServicePanel
    v-else-if="active==='integration.mcp'"
    :knowledge-base-id="knowledgeBaseId"
    @view-calls="openCallLogs" />

  <!-- 网站嵌入组件：生成可贴到第三方站点的脚本（设计 §6，嵌入令牌在服务端换取） -->
  <EmbedWidgetPanel v-else-if="active==='integration.embed'" :bases="bases" />

  <!-- 显式匹配调用日志：不再用 v-else 兜底，未知的 view key 不该悄悄落到日志页。 -->
  <CallLogPanel
    v-else-if="active==='integration.logs'"
    :knowledge-base-id="knowledgeBaseId"
    :default-protocol="logProtocol"
    @open-trace="shell.openCallTrace" />
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import type { KnowledgeBase } from '../types'
import { useShell } from '../shell'
import ModuleTabs from './ModuleTabs.vue'
import IntegrationOverviewPanel from './IntegrationOverviewPanel.vue'
import ExternalServicePanel from './ExternalServicePanel.vue'
import ExternalServiceLab from './ExternalServiceLab.vue'
import McpServicePanel from './McpServicePanel.vue'
import PlatformEventPanel from './PlatformEventPanel.vue'
import EmbedWidgetPanel from './EmbedWidgetPanel.vue'
import CallLogPanel from './CallLogPanel.vue'

defineProps<{ active: string; knowledgeBaseId: number; bases: KnowledgeBase[] }>()
const emit = defineEmits<{ select: [key: string] }>()

const shell = useShell()
const tabs = computed(() => shell.tabs('integration'))

/** 从协议视角（如 MCP 的「调用日志 →」）跳进来时的预置过滤条件。 */
const logProtocol = ref('')
function openCallLogs(protocol: string) {
  logProtocol.value = protocol
  emit('select', 'integration.logs')
}
</script>
