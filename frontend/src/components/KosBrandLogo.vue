<template>
  <!--
    品牌 logo 原型：按「Ai」字形 + 亮蓝圆点 + 中文名 + 大间距英文名的组合还原，
    字形用几何路径绘制而非字体，保证任何环境、任何尺寸下形态一致（中文仍走系统字体）。
    tone=onDark 用于深色品牌底（白字形 + 亮蓝点），tone=onLight 用于浅底（深蓝字形 + 品牌蓝点）。
  -->
  <span class="kbl" :class="'tone-'+tone" :style="{'--kbl-size':markHeight+'px'}">
    <svg class="kbl-mark" :height="markHeight" viewBox="0 0 66 56" role="img" :aria-label="label">
      <!-- A：外轮廓 + 内部三角镂空（evenodd），做成等宽笔画的几何无衬线体 -->
      <path class="kbl-a" fill-rule="evenodd" d="M18 0H28L46 56H34.5L30.5 44H15.5L11.5 56H0ZM23 12L17.5 33H28.5Z" />
      <!-- i：与 A 分离的方形竖条，圆点居其上方 -->
      <rect class="kbl-i" x="50" y="22" width="14" height="34" />
      <circle class="kbl-dot" cx="57" cy="11" r="7.6" />
    </svg>
    <span v-if="showText" class="kbl-copy">
      <b>智能知识库平台</b>
      <small>AI KNOWLEDGE BASE</small>
    </span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  /** 「Ai」符号高度（px）：中文名与英文名按比例缩放，改一处即可整体缩放 */
  size?: number
  /** onDark：深色底（白字形）；onLight：浅色底（深蓝字形） */
  tone?: 'onDark' | 'onLight'
  /** 是否带中英文名：图标位（如顶栏）可只留字形 */
  showText?: boolean
  label?: string
}>(), {
  size: 34,
  tone: 'onLight',
  showText: true,
  label: '智能知识库平台 AI KNOWLEDGE BASE',
})

const markHeight = computed(() => props.size)
</script>

<style scoped>
.kbl{display:inline-flex;align-items:center;gap:calc(var(--kbl-size)*.4)}
.kbl-mark{display:block;flex:none;height:var(--kbl-size);width:auto}
.kbl-a,.kbl-i,.kbl-dot{transition:fill .18s var(--ease)}
.kbl-copy{display:flex;flex-direction:column;line-height:1.08}
.kbl-copy b{font-size:calc(var(--kbl-size)*.56);font-weight:800;letter-spacing:.05em}
.kbl-copy small{
  margin-top:calc(var(--kbl-size)*.1);
  font-size:calc(var(--kbl-size)*.232);
  font-weight:600;letter-spacing:.32em;text-transform:uppercase;
}
/* 深色品牌底：与 logo 原图一致的白字形 + 亮蓝圆点 */
.tone-onDark .kbl-a,.tone-onDark .kbl-i{fill:#ffffff}
.tone-onDark .kbl-dot{fill:#2f6bff}
.tone-onDark .kbl-copy b{color:#ffffff}
.tone-onDark .kbl-copy small{color:#5b8def}
/* 浅色底：深蓝字形 + 品牌蓝圆点 */
.tone-onLight .kbl-a,.tone-onLight .kbl-i{fill:#0e1b36}
.tone-onLight .kbl-dot{fill:#2563eb}
.tone-onLight .kbl-copy b{color:#0e1b36}
.tone-onLight .kbl-copy small{color:#2563eb}
</style>
