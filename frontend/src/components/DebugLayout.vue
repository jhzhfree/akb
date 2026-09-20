<template>
  <!-- 第 4 章：调试类页面统一「配置区 + 结果区 + Trace 抽屉」的交互模式。 -->
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">{{ eyebrow }}</div>
        <h1 class="kb-title mt-1">{{ title }}</h1>
        <p class="kb-muted mt-1">{{ hint }}</p>
      </div>
      <div class="flex shrink-0 items-center gap-2"><slot name="actions" /></div>
    </header>

    <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_300px]">
      <div class="min-w-0">
        <article class="kb-card">
          <div class="kb-card-head">
            <b class="text-sm">配置区</b>
            <span class="kb-muted">{{ configHint || '检索参数' }}</span>
          </div>
          <div class="p-3"><slot name="config" /></div>
        </article>

        <article class="kb-card mt-4">
          <div class="kb-card-head">
            <b class="text-sm">结果区</b>
            <span class="kb-muted">{{ resultHint || '返回结果' }}</span>
          </div>
          <div class="p-3"><slot name="result" /></div>
        </article>

        <slot />
      </div>
      <aside class="min-w-0"><slot name="side" /></aside>
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  eyebrow:string; title:string; hint:string
  configHint?:string; resultHint?:string
}>()
</script>
