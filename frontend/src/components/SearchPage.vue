<template>
  <!-- 设计 §5：知识检索 = 找知识。知识图谱整体归入检索（V2.1 基线变更②）。 -->
  <ModuleTabs label="知识检索" :leaves="tabs" :active="active" @select="$emit('select',$event)" />

  <QueryPanel
    v-if="active==='search.query'"
    :query="query" @update:query="$emit('update:query',$event)"
    :result="result" :asking="asking" :history="history" :answer-format="answerFormat"
    :samples="samples"
    @update:answer-format="$emit('update:answerFormat',$event)"
    @ask="$emit('ask')" @open-trace="shell.openTrace" @refresh-history="$emit('refresh-history')"
    @open-citation="shell.openCitation" />

  <RetrievalTestPanel
    v-else-if="active==='search.test'"
    :knowledge-base-id="knowledgeBaseId" :samples="samples" @open-trace="shell.openTrace" />

  <QueryPolicyPanel v-else-if="active==='search.profile'" />

  <TraceCenterPanel
    v-else-if="active==='search.trace'"
    :history="history" :loading="historyLoading"
    @open-trace="shell.openTrace" @refresh="$emit('refresh-history')" />

  <!-- 设计 §5.x：图谱是检索内部的高级能力，探索结果与 Trace 同源可回溯。 -->
  <GraphPanel
    v-else-if="active==='search.graph'"
    :knowledge-base-id="knowledgeBaseId" :code="graphCode"
    @update:code="$emit('update:graphCode',$event)" />

  <EvaluationPanel v-else :knowledge-base-id="knowledgeBaseId" />
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { QueryHistoryItem, QueryResult } from '../types'
import type { AnswerFormat } from '../api/contracts'
import type { SampleQuestion } from '../api/knowledge'
import { listSampleQuestions, unwrap } from '../api/knowledge'
import { useShell } from '../shell'
import ModuleTabs from './ModuleTabs.vue'
import QueryPanel from './QueryPanel.vue'
import RetrievalTestPanel from './RetrievalTestPanel.vue'
import QueryPolicyPanel from './QueryPolicyPanel.vue'
import TraceCenterPanel from './TraceCenterPanel.vue'
import GraphPanel from './GraphPanel.vue'
import EvaluationPanel from './EvaluationPanel.vue'

defineEmits<{
  select: [key: string]
  'update:query': [value: string]
  'update:graphCode': [value: string]
  'update:answerFormat': [value: AnswerFormat]
  ask: []
  'refresh-history': []
}>()

const shell = useShell()
const tabs = computed(() => shell.tabs('search'))

const props = defineProps<{
  active: string
  knowledgeBaseId: number
  query: string
  result: QueryResult | null
  asking: boolean
  history: QueryHistoryItem[]
  historyLoading: boolean
  graphCode: string
  /** 答案返回格式：跟随问答面板的选择，由 ask 带上一起提交。 */
  answerFormat: AnswerFormat
}>()

/** 示例问题随知识库切换：每个场景有自己的测试语句。 */
const samples = ref<SampleQuestion[]>([])
watch(() => props.knowledgeBaseId, async kb => {
  samples.value = []
  if (!kb) return
  try {
    samples.value = unwrap(await listSampleQuestions(kb, 6)) || []
  } catch {
    samples.value = []
  }
}, { immediate:true })
</script>
