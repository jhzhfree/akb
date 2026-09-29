<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识空间</div>
        <h1 class="kb-title mt-1">知识库</h1>
        <p class="kb-muted mt-1">
          知识空间是权限与检索的最小边界：数据源、文档、知识对象与索引都归属于某个知识库。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-primary" @click="emit('create')">新建知识库</button>
      </div>
    </header>

    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="资料" :value="overview.sourceCount" hint="数据源" />
      <MetricCard label="文档" :value="overview.documentCount" hint="已解析文档" />
      <MetricCard label="知识卡片 / 断言" :value="`${overview.cardCount} / ${overview.claimCount}`" hint="当前空间" />
      <MetricCard
        label="待审核 / 冲突"
        :value="`${overview.pendingReviewCount} / ${overview.openConflictCount}`"
        hint="需要人工介入" />
    </div>

    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">知识库列表</b>
        <span class="kb-muted">共 {{ bases.length }} 个 · 切换后全站上下文随之变化</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading"
          :empty="!bases.length"
          empty-text="尚未创建知识库"
          empty-hint="新建知识库后即可上传资料并生成知识">
          <div class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
            <div
              v-for="base in bases" :key="base.id"
              class="rounded-lg border p-3 transition"
              :class="base.id === activeId ? 'border-[color:var(--accent-text)] bg-[color:var(--accent-bg)]' : 'border-[color:var(--line-d)] bg-[color:var(--bg-raised)] hover:border-[color:var(--line-soft)]'">
              <div class="flex items-start justify-between gap-2">
                <b class="truncate text-sm">{{ base.name }}</b>
                <span class="kb-badge shrink-0" :class="visibilityClass(base.visibility)">
                  {{ visibilityLabel(base.visibility) }}
                </span>
              </div>
              <p class="kb-muted mt-1 line-clamp-2 min-h-[32px]">{{ base.description || '暂无描述' }}</p>
              <div class="kb-muted mt-2 flex flex-wrap items-center gap-x-3 gap-y-1">
                <span>编码 {{ base.baseCode }}</span>
                <span v-if="base.ownerGroupName">用户组 {{ base.ownerGroupName }}</span>
                <span v-else-if="base.createdByName">创建人 {{ base.createdByName }}</span>
                <span v-if="base.createdTime">{{ base.createdTime }}</span>
              </div>
              <div class="mt-3 flex items-center justify-between">
                <span class="kb-muted">状态 {{ base.status || '—' }}</span>
                <button
                  class="kb-btn-outline text-[12px]" :disabled="base.id === activeId"
                  @click="emit('select', base.id)">
                  {{ base.id === activeId ? '当前空间' : '进入' }}
                </button>
              </div>
            </div>
          </div>
        </PanelState>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import type { KnowledgeBase, Overview } from '../types'
import { visibilityLabel } from '../types'
import MetricCard from './MetricCard.vue'
import PanelState from './PanelState.vue'

defineProps<{ bases:KnowledgeBase[]; overview:Overview; activeId:number; loading?:boolean }>()
const emit = defineEmits<{ select:[id:number]; create:[] }>()

function visibilityClass(visibility?:string) {
  switch (visibility) {
    case 'PUBLIC': return 'bg-[color:var(--ok-bg)] text-[color:var(--ok)]'
    case 'GROUP': return 'bg-[color:var(--accent-bg)] text-[color:var(--accent-text)]'
    default: return 'bg-ink-100 text-ink-600'
  }
}
</script>
