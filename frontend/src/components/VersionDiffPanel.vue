<template>
  <section class="kb-card p-5">
    <div class="kb-section-head"><div><small>VERSION HISTORY</small><h3>版本历史与差异</h3></div></div>
    <div v-if="versions.length" class="grid gap-4 lg:grid-cols-[220px_1fr]">
      <div class="max-h-[420px] space-y-2 overflow-auto pr-1">
        <button v-for="v in versions" :key="v.id" class="w-full rounded-lg border p-3 text-left transition"
          :class="selected?.id===v.id?'border-indigo-300 bg-indigo-50':'border-ink-100 bg-white hover:bg-ink-50'" @click="selected=v">
          <div class="flex items-center justify-between"><b class="text-xs text-ink-800">V{{ v.versionNo }}</b><span class="kb-badge">{{ statusLabel(v.status) }}</span></div>
          <div class="mt-1 text-[10px] text-ink-400">{{ v.changeType }} · {{ v.changedBy || 'SYSTEM' }}</div>
          <div class="mt-1 text-[10px] text-ink-400">{{ time(v.createdTime) }}</div>
          <div v-if="v.supersedesVersionNo" class="mt-2 text-[10px] text-indigo-600">SUPERSEDES V{{ v.supersedesVersionNo }}</div>
        </button>
      </div>
      <div v-if="selected" class="space-y-3">
        <div class="grid grid-cols-2 gap-3"><div class="kb-metric"><span>当前快照</span><b>V{{ selected.versionNo }}</b></div>
          <div class="kb-metric"><span>上一版本</span><b>{{ previous ? 'V'+previous.versionNo : '—' }}</b></div></div>
        <div v-for="field in fields" :key="field.key" class="rounded-lg border border-ink-100 bg-white p-4">
          <div class="mb-2 flex items-center justify-between"><b class="text-xs text-ink-700">{{ field.label }}</b>
            <span v-if="changed(field.key)" class="kb-badge bg-amber-50 text-amber-700">CHANGED</span></div>
          <div v-if="changed(field.key)" class="mb-2 rounded bg-red-50 p-2 text-xs leading-5 text-red-700 line-through">{{ value(previous,field.key) || '∅' }}</div>
          <div class="rounded bg-emerald-50 p-2 text-xs leading-5 text-emerald-800">{{ value(selected,field.key) || '∅' }}</div>
        </div>
      </div>
    </div>
    <div v-else class="py-8 text-center text-sm text-ink-400">暂无版本快照；V9 迁移后新建/裁决知识会自动记录。</div>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { KnowledgeVersion } from '../types'
import { statusLabel } from '../types'
const props=defineProps<{versions:KnowledgeVersion[]}>()
const selected=ref<KnowledgeVersion|null>(null)
watch(()=>props.versions,v=>{selected.value=v[0]||null},{immediate:true})
const previous=computed(()=>{if(!selected.value)return null;return props.versions.find(v=>v.versionNo===selected.value!.supersedesVersionNo)||null})
const fields=[{key:'title',label:'标题'},{key:'summary',label:'摘要 / 谓词'},{key:'content',label:'正文 / 对象'},{key:'status',label:'生命周期状态'}] as const
const STATUS_FIELD='status'
/** 字段值展示：状态字段与列表统一用中文标签，其余字段保持原值以免掩盖正文差异。 */
const value=(v:KnowledgeVersion|null,k:string)=>{
  if(!v)return ''
  const raw=String((v as unknown as Record<string,unknown>)[k]??'')
  return k===STATUS_FIELD?statusLabel(raw):raw
}
const changed=(k:string)=>!!previous.value&&value(selected.value,k)!==value(previous.value,k)
const time=(v?:string)=>v?new Date(v).toLocaleString():'—'
</script>
