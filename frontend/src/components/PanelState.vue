<template>
  <div v-if="loading" class="flex items-center gap-2 px-1 py-6 text-xs text-ink-500">
    <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-[color:var(--info-bg)]" />
    载入中…
  </div>
  <div
    v-else-if="error"
    class="flex items-start gap-2 rounded-lg border border-[color:var(--danger-bd)] bg-[color:var(--danger-bg)] px-4 py-3">
    <span class="text-[13px] leading-5 text-[color:var(--danger)]">⚠</span>
    <div class="min-w-0 flex-1 text-xs leading-5 text-[color:var(--danger)]">{{ error }}</div>
    <button v-if="retryable" class="shrink-0 text-xs text-[color:var(--danger)] hover:text-[color:var(--danger)]" @click="$emit('retry')">重试</button>
  </div>
  <div v-else-if="empty" class="flex flex-col items-center gap-1 px-4 py-10 text-center">
    <span class="text-lg text-ink-300">◌</span>
    <b class="text-xs font-medium text-ink-600">{{ emptyText || '暂无数据' }}</b>
    <small v-if="emptyHint" class="kb-muted">{{ emptyHint }}</small>
  </div>
  <slot v-else />
</template>

<script setup lang="ts">
defineProps<{
  loading?:boolean
  error?:string
  empty?:boolean
  emptyText?:string
  emptyHint?:string
  retryable?:boolean
}>()
defineEmits<{ retry:[] }>()
</script>
