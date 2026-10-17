<template>
  <el-drawer :model-value="visible" title="任务中心" size="420px" @update:model-value="onVisible">
    <p class="kb-muted mb-3">
      异步任务可在后台继续执行，离开页面不会中断：这里统一查看解析、抽取与索引任务的进度与失败原因。
    </p>
    <div class="mb-3 grid grid-cols-3 gap-2 text-center">
      <div class="rounded-lg bg-ink-50 p-2">
        <div class="text-[11px] text-ink-500">运行中</div><b class="text-base">{{ running }}</b>
      </div>
      <div class="rounded-lg bg-ink-50 p-2">
        <div class="text-[11px] text-ink-500">已成功</div><b class="text-base">{{ success }}</b>
      </div>
      <div class="rounded-lg bg-ink-50 p-2">
        <div class="text-[11px] text-ink-500">失败</div><b class="text-base text-[color:var(--danger)]">{{ failed }}</b>
      </div>
    </div>
    <PanelState
      :loading="loading" :error="error" :empty="!runs.length"
      empty-text="暂无任务" empty-hint="导入文档或创建连接器后，任务会出现在这里"
      retryable @retry="load">
      <div class="divide-y divide-ink-100">
        <button
          v-for="run in runs"
          :key="run.id"
          class="block w-full py-2.5 text-left transition hover:bg-ink-50/60"
          @click="$emit('open-run',run.id)">
          <div class="flex items-center justify-between text-[13px]">
            <span class="truncate text-ink-800">{{ run.sourceName || `任务 #${run.id}` }}</span>
            <el-tag size="small" effect="plain" :type="statusType(run.status)">{{ run.status }}</el-tag>
          </div>
          <div class="mt-0.5 text-[11px] text-ink-500">
            {{ run.currentStage || '—' }} · Chunk {{ run.chunkCount ?? 0 }} · {{ run.startedTime ? String(run.startedTime).slice(0,19) : '' }}
          </div>
          <div v-if="run.errorMessage" class="mt-1 truncate text-[11px] text-[color:var(--danger)]">{{ run.errorMessage }}</div>
        </button>
      </div>
    </PanelState>
    <template #footer>
      <button class="kb-btn-outline w-full" @click="$emit('navigate','create.import')">进入文档中心</button>
    </template>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import PanelState from './PanelState.vue'
import { getIngestionRuns, unwrap } from '../api/knowledge'
import type { IngestionRun } from '../types'

const props = defineProps<{ visible:boolean; knowledgeBaseId:number }>()
const emit = defineEmits<{
  'update:visible':[value:boolean]
  navigate:[key:string]
  'open-run':[runId:number]
}>()

const runs = ref<IngestionRun[]>([])
const loading = ref(false)
const error = ref('')

function onVisible(value:boolean) { emit('update:visible', value) }
function statusType(status:string):''|'success'|'warning'|'danger' {
  const value = String(status || '').toUpperCase()
  if (value === 'SUCCESS') return 'success'
  if (value === 'FAILED') return 'danger'
  if (value === 'RUNNING' || value === 'STARTED') return 'warning'
  return ''
}
const running = computed(() => runs.value.filter(v => /RUNNING|STARTED|PENDING/i.test(String(v.status || ''))).length)
const success = computed(() => runs.value.filter(v => String(v.status || '').toUpperCase() === 'SUCCESS').length)
const failed = computed(() => runs.value.filter(v => String(v.status || '').toUpperCase() === 'FAILED').length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    runs.value = unwrap(await getIngestionRuns(props.knowledgeBaseId, 20), [] as IngestionRun[])
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}
watch(() => [props.visible, props.knowledgeBaseId], ([visible]) => { if (visible) void load() }, { immediate:true })
</script>
