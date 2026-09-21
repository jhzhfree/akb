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
        <button class="kb-btn-outline" :disabled="loading" @click="emit('refresh')">刷新</button>
      </div>
    </header>

    <article class="kb-card">
      <div class="kb-card-head">
        <b class="text-sm">最近查询</b>
        <span class="kb-muted">点击「查看链路」打开链路抽屉</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :empty="!history.length"
          empty-text="尚无查询记录"
          empty-hint="在「智能问答」提问后会生成 trace"
          retryable
          @retry="emit('refresh')">
          <el-table :data="history" border size="small" max-height="520">
            <el-table-column prop="queryId" label="查询标识" min-width="150" show-overflow-tooltip />
            <el-table-column prop="originalQuery" label="问题" min-width="240" show-overflow-tooltip />
            <el-table-column prop="modelName" label="模型" width="140" />
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ row.latencyMs ?? '—' }} ms</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
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
                  查看 Trace
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import type { QueryHistoryItem } from '../types'
import PanelState from './PanelState.vue'

defineProps<{ history:QueryHistoryItem[]; loading?:boolean }>()
const emit = defineEmits<{ 'open-trace':[qid:string]; refresh:[] }>()
</script>
