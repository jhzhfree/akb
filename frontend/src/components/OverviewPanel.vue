<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">KNOWLEDGE WORKSPACE</div>
        <h1 class="kb-title mt-1">企业知识工作台</h1>
        <p class="kb-muted mt-1">从原始资料到可追溯知识：摄入、抽取、裁决、发布、检索与反馈形成闭环。</p>
      </div>
      <div class="flex shrink-0 gap-2">
        <button class="kb-btn-primary" @click="$emit('navigate','data')">＋ 摄入资料</button>
        <button class="kb-btn-outline" @click="$emit('navigate','query')">智能问答</button>
      </div>
    </header>

    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="来源" :value="overview.sourceCount" hint="权威源与版本" />
      <MetricCard label="文档" :value="overview.documentCount" :hint="`${overview.chunkCount || 0} 个内容块`" />
      <MetricCard label="知识卡片" :value="overview.cardCount" :hint="`${overview.effectiveCardCount || 0} 已生效`" />
      <MetricCard label="断言" :value="overview.claimCount" :hint="`${overview.pendingReviewCount || 0} 待治理`" />
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">知识生命周期</b><span class="kb-muted">Lifecycle</span></div>
        <div class="p-4">
          <div class="flex flex-wrap items-center gap-2">
            <template v-for="(step,i) in lifecycle" :key="step">
              <span class="rounded-lg bg-indigo-50 px-2.5 py-1.5 text-xs font-medium text-indigo-700">{{ step }}</span>
              <span v-if="i < lifecycle.length-1" class="text-ink-300">→</span>
            </template>
          </div>
          <p class="kb-muted mt-4">Chunk 只是证据上下文载体；Claim / Card 必须关联 Evidence，候选知识经过裁决后才能进入正式检索。</p>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">检索基础设施</b><span class="kb-muted">Runtime</span></div>
        <div class="grid grid-cols-2 gap-2 p-4">
          <div v-for="runtime in runtimes" :key="runtime.name" class="rounded-lg bg-ink-50 p-3">
            <b class="text-sm text-ink-900">{{ runtime.name }}</b><div class="mt-1 text-[11px] text-ink-500">{{ runtime.desc }}</div>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">治理状态</b>
          <button class="text-xs font-medium text-indigo-600 hover:text-indigo-700" @click="$emit('navigate','review')">进入裁决中心 →</button>
        </div>
        <div class="divide-y divide-ink-100 px-4">
          <div v-for="item in governance" :key="item.label" class="flex items-center justify-between py-3 text-sm">
            <span class="text-ink-600">{{ item.label }}</span><b>{{ item.value }}</b>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">快速入口</b><span class="kb-muted">Workbench</span></div>
        <div class="grid grid-cols-2 gap-2 p-4">
          <button
      v-for="item in shortcuts"
      :key="item.tab"
      class="rounded-lg border border-ink-200 bg-white p-3 text-left text-sm transition hover:border-indigo-200 hover:bg-indigo-50/60"
      @click="$emit('navigate',item.tab)">
            <span class="mr-2">{{ item.icon }}</span>{{ item.label }}
          </button>
        </div>
      </article>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import MetricCard from './MetricCard.vue'
import type { Capabilities, Overview } from '../types'
const props=defineProps<{ knowledgeBaseId:number; overview:Overview; capabilities:Capabilities|null }>()
defineEmits<{ navigate:[tab:string] }>()
const lifecycle=['Source','Clean','Extract','Adjudicate','Publish','Index','Retrieve']
const runtimes=computed(()=>[
  {name:'Elasticsearch',desc:'BM25 / 精确检索'}, {name:'Milvus',desc:'Dense Vector'},
  {name:'Neo4j',desc:'Graph Retrieval'}, {name:'Reranker',desc:String(props.capabilities?.reranker?.provider || '—')},
])
const governance=computed(()=>[
  {label:'待复核',value:props.overview.pendingReviewCount||0},
  {label:'开放冲突',value:props.overview.openConflictCount||0},
  {label:'最近裁决记录',value:props.overview.assessmentCount||0},
])
const shortcuts=[
  {tab:'cards',icon:'▣',label:'知识卡片'}, {tab:'claims',icon:'✓',label:'断言'},
  {tab:'graph',icon:'◎',label:'知识图谱'}, {tab:'ops',icon:'⚙',label:'索引运维'},
]
</script>
