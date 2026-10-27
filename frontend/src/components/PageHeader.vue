<template>
  <header class="kb-section-head">
    <div class="min-w-0">
      <div class="kb-eyebrow">{{ eyebrow }}</div>
      <h1 class="kb-title mt-1">{{ title }}</h1>
      <p class="kb-muted mt-1">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="shrink-0">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
defineProps<{
  eyebrow: string
  title: string
  description: string
}>()
</script>
