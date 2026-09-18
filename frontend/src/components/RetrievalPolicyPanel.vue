<template>
<section class="kb-card p-4">
  <div class="kb-section-head"><div><small>FILTER / GOVERNANCE</small><h3>检索治理策略</h3></div></div>
  <div class="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
    <div v-for="item in items" :key="item.name" class="rounded-lg border border-ink-100 bg-white p-3">
      <div class="flex items-center justify-between"><b class="text-xs text-ink-700">{{ item.name }}</b><span class="kb-badge">{{ item.state }}</span></div>
      <p class="mt-2 text-[11px] leading-5 text-ink-400">{{ item.detail }}</p>
    </div>
  </div>
  <p class="mt-3 text-[10px] text-ink-400">仅展示实际执行策略；当前 Trace 未采集 rejected count 时不推断淘汰数量。</p>
</section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
const props=defineProps<{ filters:Record<string,unknown> }>()
const items=computed(()=>[
  {name:'Lifecycle',state:'PRE-TOPK',detail:String(props.filters.status||'未记录')},
  {name:'ACL',state:'PRE-TOPK',detail:`${String(props.filters.acl||'未记录')} · tags=${Number(props.filters.permissionTagCount)||0}`},
  {name:'Dedup',state:'FUSION',detail:String(props.filters.dedup||'未记录')},
  {name:'Conflict',state:'POLICY',detail:String(props.filters.conflict||'未记录')},
])
</script>
