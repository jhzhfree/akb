<template>
  <!--
    统一品牌标识：圆角方形渐变底 + 等距三层「知识层」+ 顶部 AI 光核。
    语义自下而上：数据 → 知识 → 洞察，光核表示知识被 AI 直接调用。
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
        <stop offset="0" stop-color="#8b7bff" />
        <stop offset=".5" stop-color="#5b5cff" />
        <stop offset="1" stop-color="#18b8c8" />
      </linearGradient>
      <linearGradient :id="`${uid}-gloss`" x1="0" y1="0" x2=".62" y2="1">
        <stop offset="0" stop-color="#ffffff" stop-opacity=".42" />
        <stop offset=".58" stop-color="#ffffff" stop-opacity="0" />
      </linearGradient>
      <radialGradient :id="`${uid}-core`" cx=".5" cy=".5" r=".5">
        <stop offset="0" stop-color="#ffffff" stop-opacity="1" />
        <stop offset=".62" stop-color="#ffffff" stop-opacity=".88" />
        <stop offset="1" stop-color="#ffffff" stop-opacity=".3" />
      </radialGradient>
    </defs>
    <rect x="1.6" y="1.6" width="28.8" height="28.8" rx="9.2" :fill="baseFill" />
    <rect x="1.6" y="1.6" width="28.8" height="28.8" rx="9.2" :fill="`url(#${uid}-gloss)`" />
    <!-- 内描边：小尺寸（favicon 16px）下也能把底座边缘交代清楚 -->
    <rect
      x="2.2" y="2.2" width="27.6" height="27.6" rx="8.7"
      fill="none" :stroke="baseStroke" stroke-width=".9"
    />
    <!-- 知识层：三块等距薄板，越往上越亮 -->
    <g fill="#ffffff">
      <path d="M16 21.1 23.2 23.7 16 26.3 8.8 23.7z" :opacity="plateOpacity[0]" />
      <path d="M16 15.5 23.2 18.1 16 20.7 8.8 18.1z" :opacity="plateOpacity[1]" />
      <path d="M16 9.9 23.2 12.5 16 15.1 8.8 12.5z" :opacity="plateOpacity[2]" />
    </g>
    <!-- AI 光核：浮在知识层之上 -->
    <circle cx="16" cy="7.5" r="2.1" :fill="`url(#${uid}-core)`" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * id 必须实例唯一：同名 <defs> 在文档里只命中第一个，
 * 一旦首个实例被卸载，其余实例的渐变引用就会落空（Chrome 表现为图标变黑）。
 */
const uid = `kosbm${Math.random().toString(36).slice(2, 8)}`

const props = withDefaults(defineProps<{
  size?: number | string
  label?: string
  /**
   * brand：品牌渐变底，用于顶栏与 favicon；
   * onBrand：玻璃质感（半透明白底 + 白图形），用于登录页紫色品牌区，避免彩底压彩底。
   */
  tone?: 'brand' | 'onBrand'
}>(), {
  size: 26,
  label: 'Knowledge Lifecycle OS',
  tone: 'brand',
})

const onBrand = computed(() => props.tone === 'onBrand')
const baseFill = computed(() => onBrand.value ? 'rgba(255,255,255,.16)' : `url(#${uid}-base)`)
const baseStroke = computed(() => onBrand.value ? 'rgba(255,255,255,.45)' : 'rgba(255,255,255,.26)')
const plateOpacity = computed(() => onBrand.value ? [0.5, 0.74, 0.94] as const : [0.55, 0.8, 1] as const)
</script>

<style scoped>
.kos-brandmark {
  display: block;
  flex: none;
  /* 深色顶栏 / 紫色品牌区上给标识一点浮起感 */
  filter: drop-shadow(0 2px 5px rgba(8, 15, 40, 0.32));
}
</style>
