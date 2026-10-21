<template>
<!-- 登录后的主页面（知识仪表盘）：对齐原型 dashboard.html 的内容结构——
     数据总览（四张统计卡）→ 可访问的知识库（卡片网格 + 新建卡）。
     这里列的是当前账号有权限看到的全部知识库，不是"最近访问的几个"：
     统计卡算的本来就是所有可见库，下面只摆 5 个会对不上数，也让人以为还有别的入口能看全。
     顶部不再放欢迎问候区，也不显示「当前空间」栏——这个页面是跨库视角。 -->
<div class="hd">
  <section aria-labelledby="hd-stats-title">
    <h2 class="hd-section-title" id="hd-stats-title">数据总览</h2>
    <div class="hd-stats">
      <div class="hd-stat">
        <span class="hd-stat-value">{{bases.length}}</span>
        <span class="hd-stat-label">知识库总数</span>
        <span class="hd-stat-sub">当前账号可访问</span>
      </div>
      <div class="hd-stat">
        <span class="hd-stat-value">{{total.documentCount}}</span>
        <span class="hd-stat-label">文档总量</span>
        <span class="hd-stat-sub">来源 {{total.sourceCount}} · 内容块 {{total.chunkCount}}</span>
      </div>
      <div class="hd-stat">
        <span class="hd-stat-value">{{total.cardCount}}</span>
        <span class="hd-stat-label">知识卡片</span>
        <span class="hd-stat-sub">断言 {{total.claimCount}} · 已生效 {{total.effectiveCardCount}}</span>
      </div>
      <div class="hd-stat">
        <span class="hd-stat-value">{{indexRate}}%</span>
        <span class="hd-stat-label">内容索引完成度</span>
        <span class="hd-stat-sub" :class="{warn:total.pendingReviewCount>0}">
          {{total.pendingReviewCount?`${total.pendingReviewCount} 个待审核`:'暂无待审核'}}
        </span>
      </div>
    </div>
  </section>

  <section aria-labelledby="hd-recent-title">
    <div class="hd-section-head">
      <h2 class="hd-section-title" id="hd-recent-title">
        可访问的知识库<span class="hd-count">共 {{ bases.length }} 个</span>
      </h2>
      <button class="hd-more" @click="$emit('open-bases')">切换 / 管理 →</button>
    </div>

    <!-- 加载骨架：首屏统计已就绪时才渲染网格，避免卡片先空后填的跳动 -->
    <div v-if="loading&&!bases.length" class="hd-grid">
      <div v-for="n in 3" :key="n" class="hd-tile hd-skeleton">
        <div class="hd-skel-top"><span class="hd-skel-icon"></span><span class="hd-skel-tag"></span></div>
        <div class="hd-skel-line w70"></div>
        <div class="hd-skel-line w100"></div>
        <div class="hd-skel-line w45"></div>
      </div>
    </div>

    <div v-else-if="!bases.length" class="hd-empty">
      <div class="hd-empty-icon">🗂</div>
      <b>还没有知识库</b>
      <p>创建第一个知识库，把团队的文档沉淀成可被 AI 检索的知识。</p>
      <button v-if="canCreate" class="hd-primary" @click="$emit('create')">＋ 创建知识库</button>
    </div>

    <div v-else class="hd-grid">
      <button v-for="base in visibleBases" :key="base.id" class="hd-tile" @click="$emit('open-base',base.id)">
        <div class="hd-tile-top">
          <span class="hd-tile-icon" :style="iconStyle(base.id)">{{(base.name||'知').trim().slice(0,1)}}</span>
          <span class="hd-tag" :class="tagClass(base.status)">{{statusLabel(base.status)}}</span>
        </div>
        <h3 class="hd-tile-title">{{base.name}}</h3>
        <p class="hd-tile-desc">{{base.description||'暂无描述'}}</p>
        <div class="hd-tile-meta">
          <span>{{docCount(base.id)}} 篇文档</span>
          <span>{{base.createdByName||'—'}} · {{fromNow(base.createdTime)}}</span>
        </div>
      </button>
      <button v-if="canCreate" class="hd-tile hd-tile-new" @click="$emit('create')">
        <span class="hd-new-plus">＋</span>
        <span>新建知识库</span>
      </button>
    </div>
  </section>
</div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeBase, Overview } from '../types'
import { fromNow } from '../utils/time'

const props = defineProps<{
  bases: KnowledgeBase[]
  /** 各知识库的概览统计：主页面的汇总数字来自它，未加载到的库按 0 处理。 */
  baseStats: Record<number, Overview>
  /** 最近访问顺序（知识库 id），未记录时按列表顺序取前几个。 */
  recentIds: number[]
  loading: boolean
  canCreate: boolean
}>()
defineEmits<{ create: []; 'open-bases': []; 'open-base': [id: number] }>()

/** 图标渐变：原型三套品牌色按 id 轮换，保证同一库每次进入颜色一致。 */
const PALETTE = [
  'linear-gradient(135deg,#5b5cff,#8a5cff)',
  'linear-gradient(135deg,#22a06b,#2fbf85)',
  'linear-gradient(135deg,#d97706,#f59e0b)',
]
function iconStyle(id: number) {
  return { background: PALETTE[Number(id) % PALETTE.length] }
}

/**
 * 可访问的知识库：最近访问的排在前面，其余接在后面，**全部列出**。
 *
 * <p>排序仍照顾"最近用过的先看到"，但不再截断到 5 个——上面对外说的是"共 N 个"，
 * 下面只摆一部分，N 和看到的数量对不上。</p>
 */
const visibleBases = computed(() => {
  const list = props.bases
  if (!list.length) return []
  const ordered = props.recentIds
    .map(id => list.find(base => base.id === id))
    .filter((base): base is KnowledgeBase => !!base)
  for (const base of list) {
    if (!ordered.some(item => item.id === base.id)) ordered.push(base)
  }
  return ordered
})

const total = computed(() => {
  const sum = { sourceCount: 0, documentCount: 0, chunkCount: 0, cardCount: 0, claimCount: 0, effectiveCardCount: 0, pendingReviewCount: 0 }
  for (const base of props.bases) {
    const stat = props.baseStats[base.id]
    if (!stat) continue
    sum.sourceCount += Number(stat.sourceCount || 0)
    sum.documentCount += Number(stat.documentCount || 0)
    sum.chunkCount += Number(stat.chunkCount || 0)
    sum.cardCount += Number(stat.cardCount || 0)
    sum.claimCount += Number(stat.claimCount || 0)
    sum.effectiveCardCount += Number(stat.effectiveCardCount || 0)
    sum.pendingReviewCount += Number(stat.pendingReviewCount || 0)
  }
  return sum
})

/** 索引完成度：已生效卡片 / 卡片总数，无卡片时按 100% 展示（没有待索引内容）。 */
const indexRate = computed(() => {
  if (!total.value.cardCount) return 100
  return Math.round((total.value.effectiveCardCount / total.value.cardCount) * 100)
})

function docCount(id: number) {
  return Number(props.baseStats[id]?.documentCount || 0)
}
function statusLabel(status?: string) {
  return String(status || '').toUpperCase() === 'DRAFT' ? '草稿' : '已发布'
}
function tagClass(status?: string) {
  return String(status || '').toUpperCase() === 'DRAFT' ? 'warn' : 'ok'
}
</script>

<style scoped>
.hd{display:flex;flex-direction:column;gap:26px}
.hd-primary{
  height:36px;padding:0 16px;border:0;border-radius:8px;cursor:pointer;font-family:inherit;
  font-size:13px;font-weight:600;color:#fff;background:linear-gradient(180deg,var(--brand-500),var(--brand-600));
  box-shadow:0 2px 8px color-mix(in srgb,var(--brand-600) 26%,transparent);
  transition:opacity .16s var(--ease),transform .16s var(--ease);
}
.hd-primary:hover{opacity:.94}
.hd-primary:active{transform:translateY(1px)}
.hd-section-title{margin:0;font-size:15px;font-weight:700;color:var(--ink-900)}
.hd-section-head{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:14px}
.hd-more{background:none;border:0;padding:0;font:inherit;font-size:12px;font-weight:600;color:var(--accent-text);cursor:pointer}
.hd-count{margin-left:8px;font-size:12px;font-weight:400;color:var(--ink-400)}

.hd-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-top:14px}
.hd-stat{
  display:flex;flex-direction:column;gap:4px;padding:16px 18px;border-radius:12px;
  background:var(--bg-raised);border:1px solid var(--line-d);box-shadow:var(--sd-1);
}
.hd-stat-value{font-size:26px;font-weight:700;line-height:1.15;color:var(--ink-900)}
.hd-stat-label{font-size:12.5px;font-weight:600;color:var(--ink-700)}
.hd-stat-sub{font-size:11px;color:var(--ink-400)}
.hd-stat-sub.warn{color:var(--warning)}

.hd-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(232px,1fr));gap:14px}
.hd-tile{
  display:flex;flex-direction:column;gap:8px;text-align:left;padding:16px 16px 14px;cursor:pointer;
  border-radius:12px;border:1px solid var(--line-d);background:var(--bg-raised);box-shadow:var(--sd-1);
  font-family:inherit;color:inherit;
  transition:box-shadow .18s var(--ease),border-color .18s var(--ease),transform .18s var(--ease);
}
.hd-tile:hover{border-color:var(--line-soft);box-shadow:var(--sd-2);transform:translateY(-1px)}
.hd-tile-top{display:flex;align-items:center;justify-content:space-between;gap:10px}
.hd-tile-icon{
  width:34px;height:34px;border-radius:9px;display:grid;place-items:center;flex:none;
  color:#fff;font-size:15px;font-weight:700;
}
.hd-tag{font-size:11px;font-weight:600;padding:2px 8px;border-radius:999px}
.hd-tag.ok{color:var(--ok);background:var(--ok-bg)}
.hd-tag.warn{color:var(--warning);background:var(--warning-bg)}
.hd-tile-title{margin:2px 0 0;font-size:14.5px;font-weight:700;color:var(--ink-900)}
.hd-tile-desc{
  margin:0;font-size:12px;line-height:1.6;color:var(--ink-500);
  display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:38px;
}
.hd-tile-meta{display:flex;align-items:center;gap:8px;font-size:11px;color:var(--ink-400);margin-top:2px}
.hd-tile-meta span:first-child{margin-right:auto}
.hd-tile-new{
  align-items:center;justify-content:center;gap:8px;border-style:dashed;border-color:var(--line);
  color:var(--ink-500);box-shadow:none;font-size:13px;
}
.hd-tile-new:hover{color:var(--accent-text);border-color:var(--brand-400)}
.hd-new-plus{font-size:20px;line-height:1}

.hd-empty{
  display:flex;flex-direction:column;align-items:center;gap:10px;padding:38px 20px;text-align:center;
  border-radius:12px;border:1px dashed var(--line);background:var(--bg-raised);
}
.hd-empty-icon{font-size:26px;opacity:.7}
.hd-empty b{font-size:14px;color:var(--ink-900)}
.hd-empty p{margin:0;font-size:12px;line-height:1.7;color:var(--ink-500);max-width:420px}

.hd-skeleton{cursor:default}
.hd-skeleton:hover{transform:none;box-shadow:var(--sd-1)}
.hd-skel-top{display:flex;align-items:center;justify-content:space-between}
.hd-skel-icon{width:34px;height:34px;border-radius:9px;background:var(--bg-sub)}
.hd-skel-tag{width:46px;height:16px;border-radius:999px;background:var(--bg-sub)}
.hd-skel-line{height:11px;border-radius:6px;background:var(--bg-sub)}
.hd-skel-line.w70{width:70%}
.hd-skel-line.w100{width:100%}
.hd-skel-line.w45{width:45%}

@media(max-width:1080px){ .hd-stats{grid-template-columns:1fr 1fr} }
@media(max-width:640px){ .hd-stats{grid-template-columns:1fr} }
</style>
