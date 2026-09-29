<template>
  <article class="kb-metric group transition hover:-translate-y-0.5 hover:border-[color:var(--info-bd)] hover:shadow-md">
    <span class="kb-metric-label">{{ label }}</span>
    <strong class="kb-metric-value">{{ value ?? 0 }}</strong>
    <small class="kb-metric-hint">{{ hint || '—' }}</small>
  </article>
</template>
<script setup lang="ts">
defineProps<{ label:string; value?:number|string; hint?:string }>()
</script>
