<template>
  <!-- 第 4 章：进入知识空间后，页面顶部保留「当前空间 + 环境 + 索引状态」。 -->
  <div class="kb-context-bar">
    <div class="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
      <span class="kb-ctx-label">当前空间</span>
      <button class="kb-ctx-space" @click="emit('switch')">
        <b class="truncate">{{ base?.name || '未选择知识库' }}</b>
        <span class="kb-ctx-chip">{{ visibilityLabel(base?.visibility) }}</span>
        <i>▾</i>
      </button>
      <span class="kb-ctx-sep">/</span>
      <span class="kb-ctx-item">环境 <b>{{ environment || '—' }}</b></span>
      <span class="kb-ctx-sep">/</span>
      <span class="kb-ctx-item">编号 <b>{{ base?.id ?? '—' }}</b></span>
    </div>

    <div class="flex shrink-0 items-center gap-2">
      <span class="kb-ctx-index" :class="stateClass">
        <i class="kb-ctx-dot"></i>
        索引 · {{ indexText }}
      </span>
      <button class="kb-btn-ghost text-[12px]" :disabled="loading" @click="emit('refresh')">刷新</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { IndexState, KnowledgeBase } from '../types'
import { visibilityLabel } from '../types'

const props = defineProps<{
  base?: KnowledgeBase | null
  environment?: string | null
  index: IndexState
  loading?: boolean
}>()
const emit = defineEmits<{ switch: []; refresh: [] }>()

/** 索引状态取三者的最高严重级别，避免出现「一致」但同时存在死信的误读。 */
const severity = computed(() => {
  const { dead, drift, pending, loading } = props.index
  if (loading) return 'loading' as const
  if (dead > 0) return 'dead' as const
  if (drift > 0) return 'drift' as const
  if (pending > 0) return 'pending' as const
  return 'ok' as const
})

const indexText = computed(() => {
  const { dead, drift, pending } = props.index
  switch (severity.value) {
    case 'loading': return '检查中…'
    case 'dead': return `死信 ${dead}`
    case 'drift': return `漂移 ${drift}`
    case 'pending': return `待投递 ${pending}`
    default: return '一致'
  }
})

const stateClass = computed(() => `kb-ctx-index-${severity.value}`)
</script>
