<template>
  <section class="kb-card overflow-hidden">
    <div class="kb-card-head"><b class="text-xs">{{ title }}</b></div>
    <pre class="max-h-72 overflow-auto whitespace-pre-wrap p-4 text-[11px] leading-5 text-ink-600">{{ formatted }}</pre>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
const props=defineProps<{ title:string; value:unknown }>()
const formatted=computed(()=>{
  if (props.value == null || props.value === '') return '—'
  if (typeof props.value === 'string') {
    try { return JSON.stringify(JSON.parse(props.value), null, 2) } catch { return props.value }
  }
  return JSON.stringify(props.value, null, 2)
})
</script>
