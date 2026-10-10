<script setup lang="ts">
/**
 * 当前知识库的场景测试语句。
 *
 * 不同场景的提问方式差别很大——运维问命令与主机号，制度问天数与金额，研发问 issue 与工单号——
 * 所以示例问题必须取自该知识库自己的评测用例，而不是一套放之四海皆准的通用问题。
 */
import type { SampleQuestion } from '../api/knowledge'

defineProps<{ items:SampleQuestion[] }>()
const emit = defineEmits<{ pick:[question:string] }>()
</script>

<template>
  <div v-if="items.length" class="mt-2 flex flex-wrap items-center gap-1.5">
    <span class="kb-muted shrink-0">本场景测试语句</span>
    <button
      v-for="item in items"
      :key="item.question"
      type="button"
      class="kb-badge max-w-[320px] cursor-pointer truncate text-left"
      :title="`${item.difficulty} · ${item.question}`"
      @click="emit('pick', item.question)">
      {{ item.question }}
    </button>
  </div>
</template>
