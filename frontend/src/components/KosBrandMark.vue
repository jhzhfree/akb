<template>
  <!--
    统一品牌标识：圆角方形渐变底 + 三层递进的「知识层」，
    顶栏、登录页、favicon（public/favicon.svg）三处共用同一形态，改一处时请同步。
  -->
  <svg
    class="kos-brandmark"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    role="img"
    :aria-label="label"
  >
    <defs>
      <linearGradient :id="`${uid}-base`" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#5b8bf7" />
        <stop offset=".45" stop-color="#2563eb" />
        <stop offset="1" stop-color="#0f766e" />
      </linearGradient>
      <linearGradient :id="`${uid}-gloss`" x1="0" y1="0" x2=".7" y2="1">
        <stop offset="0" stop-color="#ffffff" stop-opacity=".38" />
        <stop offset=".55" stop-color="#ffffff" stop-opacity="0" />
      </linearGradient>
    </defs>
    <rect x="1" y="1" width="30" height="30" rx="9" :fill="`url(#${uid}-base)`" />
    <rect x="1" y="1" width="30" height="30" rx="9" :fill="`url(#${uid}-gloss)`" />
    <g fill="#ffffff">
      <rect x="8.4" y="7.7" width="15.2" height="3.1" rx="1.55" opacity=".55" />
      <rect x="8.4" y="14.45" width="15.2" height="3.1" rx="1.55" opacity=".82" />
      <rect x="8.4" y="21.2" width="9.4" height="3.1" rx="1.55" />
    </g>
  </svg>
</template>

<script setup lang="ts">
/**
 * id 必须实例唯一：同名 <defs> 在文档里只命中第一个，
 * 一旦首个实例被卸载，其余实例的渐变引用就会落空（Chrome 表现为图标变黑）。
 */
const uid = `kosbm${Math.random().toString(36).slice(2, 8)}`

withDefaults(defineProps<{ size?: number | string; label?: string }>(), {
  size: 26,
  label: 'Knowledge Lifecycle OS',
})
</script>

<style scoped>
.kos-brandmark {
  display: block;
  flex: none;
  /* 深色顶栏上给标识一点浮起感 */
  filter: drop-shadow(0 2px 5px rgba(8, 15, 40, 0.32));
}
</style>
