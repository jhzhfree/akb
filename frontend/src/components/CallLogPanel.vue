<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">调用日志</div>
        <h1 class="kb-title mt-1">调用日志</h1>
        <p class="kb-muted mt-1">
          外部系统调用集成服务的全量记录：按协议与结果过滤，展开任一行可见调用方名称、脱敏后的传入参数与返回数据；
          点「查看 Trace」可在抽屉里看到这次调用的全部属性，请求与返回均以 JSON 格式化呈现。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <select v-model="protocol" class="kb-input w-32" @change="load">
          <option value="">全部协议</option>
          <option value="REST">REST</option>
          <option value="MCP">MCP</option>
          <option value="SSE">SSE</option>
        </select>
        <select v-model="status" class="kb-input w-32" @change="load">
          <option value="">全部结果</option>
          <option value="SUCCESS">成功</option>
          <option value="ERROR">失败</option>
        </select>
        <button class="kb-btn-outline" @click="load">刷新</button>
      </div>
    </header>

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">调用记录</b>
        <span class="kb-muted">{{ rows.length }} 条</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="error"
          :empty="!rows.length"
          empty-text="窗口内没有调用记录"
          empty-hint="在「接口联调」或业务系统中发起一次集成调用后即可看到记录"
          retryable
          @retry="load">
          <el-table :data="rows" border size="small">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="space-y-3 px-3 py-2 text-[12px]">
                  <div class="flex gap-2">
                    <b class="w-20 shrink-0 font-normal text-ink-500">调用方</b>
                    <span class="kb-muted">{{ row.caller || row.client || '—' }}</span>
                  </div>
                  <div class="flex gap-2">
                    <b class="w-20 shrink-0 font-normal text-ink-500">凭证</b>
                    <span class="kb-muted">{{ row.client || '—' }}</span>
                  </div>
                  <div class="flex gap-2">
                    <b class="w-20 shrink-0 font-normal text-ink-500">传入参数</b>
                    <pre class="max-h-56 flex-1 overflow-auto whitespace-pre-wrap break-all rounded border border-black/10 bg-black/[0.03] p-2 text-[11px] leading-relaxed">{{ pretty(row.requestParams) }}</pre>
                  </div>
                  <div class="flex gap-2">
                    <b class="w-20 shrink-0 font-normal text-ink-500">返回数据</b>
                    <pre class="max-h-56 flex-1 overflow-auto whitespace-pre-wrap break-all rounded border border-black/10 bg-black/[0.03] p-2 text-[11px] leading-relaxed">{{ pretty(row.responseData) }}</pre>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ row.time ? String(row.time).slice(0,19) : '—' }}</template>
            </el-table-column>
            <el-table-column prop="protocol" label="协议" width="90" />
            <el-table-column label="调用方" width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.caller || row.client || '—' }}</template>
            </el-table-column>
            <el-table-column prop="client" label="凭证" width="140" show-overflow-tooltip />
            <el-table-column prop="method" label="方法" width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <span :class="row.status != null && row.status >= 400 ? 'text-rose-600' : 'text-emerald-600'">
                  {{ row.status ?? '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ row.latencyMs ?? 0 }} ms</template>
            </el-table-column>
            <el-table-column prop="message" label="错误信息" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <button
                  v-if="row.traceId"
                  class="text-[12px] text-indigo-600 hover:text-indigo-700"
                  @click="$emit('open-trace',String(row.traceId))">
                  查看 Trace
                </button>
                <span v-else class="text-[12px] text-ink-300">—</span>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import PanelState from './PanelState.vue'
import { getIntegrationCallLogs, unwrap } from '../api/knowledge'
import type { IntegrationCallRow } from '../types'

const props = defineProps<{ knowledgeBaseId:number; hours?:number }>()
defineEmits<{ 'open-trace':[traceId:string] }>()

const rows = ref<IntegrationCallRow[]>([])
const loading = ref(false)
const error = ref('')
const protocol = ref('')
const status = ref('')

/** 服务端以 JSON 文本存参，这里还原成缩进格式；空值或非 JSON 原样展示。 */
function pretty(raw?:string|null) {
  if (!raw) return '—'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = unwrap(await getIntegrationCallLogs({
      hours:props.hours || 24,
      protocol:protocol.value || undefined,
      status:status.value || undefined,
      limit:50,
    }), [] as IntegrationCallRow[])
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.knowledgeBaseId, load)
</script>
