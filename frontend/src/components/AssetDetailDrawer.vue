<template>
  <el-drawer :model-value="visible" size="min(760px, 92vw)" :with-header="false" @close="$emit('close')">
    <div v-if="row" class="min-h-full bg-ink-50 p-5 md:p-7">
      <header class="mb-4 flex items-start justify-between gap-3">
        <div class="min-w-0">
          <div class="kb-eyebrow">知识资产 · {{ kindLabel }}</div>
          <h2 class="kb-title mt-1 break-words">{{ title }}</h2>
          <p class="kb-muted mt-1">编码 {{ code }}</p>
        </div>
        <button class="kb-btn-outline shrink-0" @click="$emit('close')">关闭</button>
      </header>

      <!-- 基本属性：列表只放得下几列，这里把该对象的全部字段摊开，避免为了看一个 URI 再去翻接口。 -->
      <section class="kb-card p-5">
        <div class="kb-section-head"><div><small>基本属性</small><h3>{{ kindLabel }}信息</h3></div></div>
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <div v-for="f in fields" :key="f.label" class="rounded-lg bg-ink-50 p-3">
            <div class="text-[11px] text-ink-500">{{ f.label }}</div>
            <b class="block break-all text-[13px] text-ink-800">{{ f.value }}</b>
          </div>
        </div>
      </section>

      <!-- 关系的两端是实体：直接给出跳图谱的入口，比让人回到列表再点「图谱」短一步。 -->
      <section v-if="type==='relation' && (sourceCode || targetCode)" class="kb-card mt-4 p-5">
        <div class="kb-section-head"><div><small>关联实体</small><h3>主体与客体</h3></div></div>
        <div class="space-y-2">
          <div
            v-if="sourceCode"
            class="flex items-center justify-between rounded-lg border border-ink-100 bg-white px-3 py-2">
            <span class="truncate text-[13px] text-ink-700">主体 · {{ sourceName }}</span>
            <el-button link type="primary" size="small" @click="$emit('openGraph', sourceCode)">在图谱中查看</el-button>
          </div>
          <div
            v-if="targetCode"
            class="flex items-center justify-between rounded-lg border border-ink-100 bg-white px-3 py-2">
            <span class="truncate text-[13px] text-ink-700">客体 · {{ targetName }}</span>
            <el-button link type="primary" size="small" @click="$emit('openGraph', targetCode)">在图谱中查看</el-button>
          </div>
        </div>
      </section>

      <section class="kb-card mt-4 p-5">
        <div class="kb-section-head"><div><small>下一步</small><h3>去业务页面继续</h3></div></div>
        <p class="kb-muted">{{ jumpHint }}</p>
        <el-button class="mt-3" size="small" plain type="primary" @click="$emit('navigate', jumpTarget)">
          {{ jumpLabel }}
        </el-button>
      </section>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { AssetRow } from '../types/knowledge'

/**
 * 来源 / 文档 / 关系的只读详情。
 *
 * 这三类不是知识对象：它们没有 Evidence、版本与审计，套统一详情抽屉（KnowledgeDetailWorkspace）
 * 只会得到一排空 Tab。所以这里单独做一个抽屉，展示列表放不下的字段，并给出下一步入口。
 */
export type AssetDetailKind = 'source'|'document'|'relation'

const props = defineProps<{ visible:boolean; type:AssetDetailKind; row:AssetRow|null }>()
defineEmits<{ close:[]; openGraph:[code:string]; navigate:[key:string] }>()

const KIND_LABEL:Record<AssetDetailKind,string> = { source:'来源', document:'文档', relation:'关系' }

/** 下一跳：来源看它的同步配置，文档看它的解析与 Chunk，关系去图谱里看上下文。 */
const JUMP:Record<AssetDetailKind,{ target:string; label:string; hint:string }> = {
  source:{ target:'create.connector', label:'去「连接器」查看同步配置',
    hint:'来源的同步与属性维护在「知识生成 · 连接器」，这里只做查看。' },
  document:{ target:'create.import', label:'去「文档导入」查看解析进度',
    hint:'文档的解析状态与 Chunk 在「知识生成 · 文档导入」，这里只做查看。' },
  relation:{ target:'search.graph', label:'去「知识图谱」探索关系',
    hint:'关系在图谱里是一条边，进入图谱后以主体或客体为起点展开邻域。' },
}

const kindLabel = computed(() => KIND_LABEL[props.type])
const jumpTarget = computed(() => JUMP[props.type].target)
const jumpLabel = computed(() => JUMP[props.type].label)
const jumpHint = computed(() => JUMP[props.type].hint)

function value(key:string):string {
  const raw = props.row?.[key]
  return raw == null || raw === '' ? '—' : String(raw)
}

const code = computed(() => value(props.type === 'source' ? 'sourceCode'
  : props.type === 'document' ? 'documentCode' : 'code'))

const sourceCode = computed(() => value('sourceCode'))
const targetCode = computed(() => value('targetCode'))
const sourceName = computed(() => value('sourceName'))
const targetName = computed(() => value('targetName'))

const title = computed(() => {
  if (props.type === 'source') return value('name')
  if (props.type === 'document') return value('title')
  return `${sourceName.value} · ${value('relationType')} → ${targetName.value}`
})

const fields = computed<{ label:string; value:string }[]>(() => {
  if (props.type === 'source') {
    return [
      { label:'来源编码', value:code.value },
      { label:'名称', value:value('name') },
      { label:'类型', value:value('sourceType') },
      { label:'地址', value:value('sourceUri') },
      { label:'权威度', value:value('authorityScore') },
      { label:'状态', value:value('status') },
      { label:'创建时间', value:value('createdTime') },
    ]
  }
  if (props.type === 'document') {
    return [
      { label:'文档编码', value:code.value },
      { label:'标题', value:value('title') },
      { label:'所属来源', value:value('sourceName') },
      { label:'文件类型', value:value('mimeType') },
      { label:'解析状态', value:value('parseStatus') },
      { label:'文档状态', value:value('status') },
      { label:'Chunk 数', value:value('chunkCount') },
      { label:'创建时间', value:value('createdTime') },
    ]
  }
  return [
    { label:'关系编码', value:code.value },
    { label:'主体', value:`${sourceName.value}（${sourceCode.value}）` },
    { label:'关系类型', value:value('relationType') },
    { label:'客体', value:`${targetName.value}（${targetCode.value}）` },
    { label:'状态', value:value('status') },
    { label:'置信度', value:value('confidence') },
  ]
})
</script>
