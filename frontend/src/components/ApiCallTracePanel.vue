<template>
<el-drawer :model-value="visible" size="min(1100px,96vw)" :with-header="false" @close="$emit('close')">
  <div class="min-h-full bg-ink-50 p-5 md:p-7">
    <header class="mb-5 flex items-start justify-between gap-4">
      <div>
        <div class="kb-eyebrow">集成调用观测</div>
        <h2 class="kb-title mt-1">接口调用 Trace</h2>
        <p class="kb-muted mt-1">{{ detail?.caller || detail?.client || '正在读取这一次调用…' }}</p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <span v-if="detail?.latencyMs != null" class="kb-badge">{{ detail.latencyMs }} ms</span>
        <span v-if="detail" class="kb-badge" :class="failed ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]'">
          {{ detail.status ?? '—' }}
        </span>
      </div>
    </header>

    <div v-if="loading" class="kb-card p-12 text-center text-sm text-ink-500">正在读取这次调用的请求与返回…</div>
    <div v-else-if="!detail" class="kb-card p-12 text-center text-sm text-ink-500">
      没有找到这次调用的明细，可能已超出调用日志的保留窗口。
    </div>
    <template v-else>
      <!-- 身份区：先让排障的人确认这就是他要找的那一次调用。 -->
      <div class="mb-4 grid gap-3 md:grid-cols-4">
        <div class="kb-metric"><span>调用方</span><b class="truncate text-sm">{{ detail.caller || detail.client || '—' }}</b></div>
        <div class="kb-metric"><span>凭证 Key</span><b class="truncate text-sm">{{ detail.client || '—' }}</b></div>
        <div class="kb-metric"><span>协议 / 通道</span><b class="text-sm">{{ detail.protocol || '—' }}</b></div>
        <div class="kb-metric"><span>调用方法</span><b class="truncate text-sm" :title="detail.method||''">{{ detail.method || '—' }}</b></div>
      </div>
      <div class="mb-4 grid gap-3 md:grid-cols-4">
        <div class="kb-metric"><span>响应状态</span><b :class="failed ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]'">{{ detail.status ?? '—' }}</b></div>
        <div class="kb-metric"><span>耗时</span><b>{{ detail.latencyMs ?? 0 }} ms</b></div>
        <div class="kb-metric"><span>知识库</span><b class="text-sm">{{ detail.knowledgeBaseId ?? '—' }}</b></div>
        <div class="kb-metric"><span>调用时间</span><b class="text-sm">{{ detail.time ? String(detail.time).slice(0,19) : '—' }}</b></div>
      </div>

      <div v-if="detail.message" class="kb-card mb-4 border-l-4 border-[color:var(--danger)] p-4">
        <div class="kb-section-head"><div><small>错误信息</small><h3>调用失败原因</h3></div></div>
        <div class="whitespace-pre-wrap break-all text-[12px] leading-relaxed text-[color:var(--danger)]">{{ detail.message }}</div>
      </div>

      <!-- 排障真正要看的两块：对方发了什么，我们回了什么。 -->
      <section v-for="item in payloads" :key="item.title" class="kb-card mb-4 p-4">
        <div class="kb-section-head">
          <div><small>{{ item.hint }}</small><h3>{{ item.title }}</h3></div>
          <button class="kb-btn-outline" :disabled="!item.raw" @click="copy(item.raw)">复制</button>
        </div>
        <pre
          class="mt-2 max-h-96 overflow-auto whitespace-pre-wrap break-all rounded-lg border border-black/10 bg-black/[0.03] p-3 text-[12px] leading-relaxed">
          {{ pretty(item.raw) }}</pre>
      </section>

      <!-- 原始行：调用留给运维的每一个字段，一个都不少。 -->
      <el-collapse class="mb-4">
        <el-collapse-item title="本次调用的全部属性">
          <div class="flex justify-end pb-2">
            <button class="kb-btn-outline" @click="copy(JSON.stringify(detail,null,2))">复制全部</button>
          </div>
          <pre
            class="max-h-[28rem] overflow-auto whitespace-pre-wrap break-all rounded-lg border border-black/10 bg-black/[0.03] p-3 text-[12px] leading-relaxed">
          {{ allProps }}</pre>
        </el-collapse-item>
      </el-collapse>

      <div class="kb-muted text-[12px]">Trace ID：{{ detail.traceId || '—' }}</div>
    </template>
  </div>
</el-drawer>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { IntegrationCallDetail } from '../types'

const props = defineProps<{ visible:boolean; detail:IntegrationCallDetail|null; loading:boolean }>()
defineEmits<{ close:[] }>()

const failed = computed(() => props.detail?.status != null && props.detail.status >= 400)

/** 服务端以紧凑 JSON 文本存参，还原成缩进格式；空值或非 JSON（如原始查询串）原样展示。 */
function pretty(raw?:string|null) {
  if (!raw) return '—'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

const payloads = computed(() => [
  { title:'请求参数', hint:'查询串与请求体已合并为一个视图；密钥、token 等字段入库前已替换为 ***',
    raw:props.detail?.requestParams },
  { title:'返回数据', hint:'本次实际返回给调用方的报文；SSE 只记录握手与错误，不记录流式正文',
    raw:props.detail?.responseData },
])

const allProps = computed(() => JSON.stringify(props.detail, null, 2))

async function copy(value?:string|null) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(pretty(value))
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器拒绝了剪贴板访问，请手动选择复制')
  }
}
</script>
