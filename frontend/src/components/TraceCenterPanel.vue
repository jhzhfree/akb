<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">查询链路观测</div>
        <h1 class="kb-title mt-1">检索链路追踪</h1>
        <p class="kb-muted mt-1">
          每次问答都会落一条 trace：查询改写、路由决策、多路召回、RRF 融合、Rerank 与上下文装配逐段可回放。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-outline" :disabled="loading" @click="load(1)">刷新</button>
      </div>
    </header>

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">查询记录</b>
        <span class="kb-muted">共 {{ total }} 条 · 点击「查看链路」打开链路抽屉</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :error="error"
          :empty="!rows.length"
          empty-text="尚无查询记录"
          empty-hint="在「智能问答」提问后会生成 trace"
          retryable
          @retry="load(page)">
          <el-table :data="rows" border size="small" max-height="520">
            <el-table-column prop="queryId" label="查询标识" min-width="150" show-overflow-tooltip />
            <el-table-column prop="originalQuery" label="问题" min-width="240" show-overflow-tooltip />
            <el-table-column label="调用方 IP" width="130" show-overflow-tooltip>
              <template #default="{ row }">{{ row.callerIp || '—' }}</template>
            </el-table-column>
            <el-table-column prop="modelName" label="模型" width="130" show-overflow-tooltip />
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ row.latencyMs ?? '—' }} ms</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.errorCode ? 'danger' : 'success'" effect="plain">
                  {{ row.errorCode ? '失败' : '成功' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdTime" label="时间" width="160" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="emit('open-trace', row.queryId)">
                  查看链路
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
        <div v-if="total > pageSize" class="mt-3 flex justify-end">
          <el-pagination
            small layout="prev,pager,next,total,sizes" :current-page="page" :page-size="pageSize"
            :page-sizes="[10,20,50,100]" :total="total" :pager-count="5"
            @current-change="load" @size-change="onSizeChange" />
        </div>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { QueryHistoryItem } from '../types'
import { getQueryHistory, unwrap } from '../api/knowledge'
import PanelState from './PanelState.vue'

const props = defineProps<{ knowledgeBaseId:number }>()
const emit = defineEmits<{ 'open-trace':[qid:string] }>()

const rows = ref<QueryHistoryItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref('')

/**
 * 链路记录只增不减，必须服务端分页：一次拉全量既慢又没必要，
 * 而且这里要显示总条数，否则「下一页」永远可点、点到底才发现是空的。
 */
async function load(target = page.value) {
  if (!props.knowledgeBaseId) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(
      await getQueryHistory(props.knowledgeBaseId, target, pageSize.value),
      { items:[], total:0, page:1, pageSize:pageSize.value },
    )
    rows.value = (data.items || []) as QueryHistoryItem[]
    total.value = data.total || 0
    page.value = data.page || target
  } catch (e) {
    error.value = (e as Error)?.message || '加载检索链路记录失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSizeChange(size:number) {
  pageSize.value = size
  load(1)
}

watch(() => props.knowledgeBaseId, () => load(1), { immediate:true })
</script>
