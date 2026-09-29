<template>
  <div class="gc-wrap">
    <!-- 概览模式（工作台卡片内）只留画布与计数：工具栏与图例在工作台里是噪音，完整交互留给图谱页。 -->
    <div v-if="!compact" class="gc-toolbar">
      <span class="tag gray">{{ nodes.length }} 节点 · {{ edges.length }} 关系</span>
      <button class="kb-btn-outline gc-tool" title="放大" @click="zoomBy(1.25)">＋</button>
      <button class="kb-btn-outline gc-tool" title="缩小" @click="zoomBy(0.8)">－</button>
      <button class="kb-btn-outline" @click="fit">自适应</button>
      <button class="kb-btn-outline" @click="restart">重新布局</button>
      <span class="gc-hint">滚轮缩放 · 拖拽空白平移 · 拖动节点可固定位置 · 点击节点聚焦邻域</span>
    </div>

    <svg
      ref="svgRef"
      class="gc-svg"
      :style="{ height: `${canvasHeight}px` }"
      @wheel.prevent="onWheel"
      @pointerdown="onCanvasDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointerleave="onPointerUp">
      <defs>
        <marker :id="arrowId" markerWidth="10" markerHeight="10" refX="9" refY="4" orient="auto">
          <path d="M0,0 L9,4 L0,8 z" fill="#94a3b8" />
        </marker>
      </defs>
      <g :transform="`translate(${tx},${ty}) scale(${scale})`">
        <g>
          <template v-for="edge in renderedEdges" :key="edge.key">
            <line
              class="gc-edge"
              :class="{ dim:!edge.active, hot:edge.active && !!focused, dashed:edge.dashed }"
              :x1="edge.x1" :y1="edge.y1" :x2="edge.x2" :y2="edge.y2"
              :marker-end="`url(#${arrowId})`" />
            <text
              v-if="edge.active && !!focused && scale > 0.7"
              class="gc-edge-label"
              :x="(edge.x1 + edge.x2) / 2" :y="(edge.y1 + edge.y2) / 2 - 4">
              {{ edge.label }}
            </text>
          </template>
        </g>
        <g>
          <g
            v-for="node in sim" :key="node.id"
            class="gc-node"
            :class="{ dim:!!focusedId && node.code !== focusedId && !activeIds.has(node.id), focused:node.code === focusedId }"
            @pointerdown.stop="onNodeDown($event, node)">
            <circle :cx="node.x" :cy="node.y" :r="node.r" :fill="colorOf(node.type)" />
            <text class="gc-node-label" :x="node.x" :y="node.y + node.r + 13">{{ node.label }}</text>
          </g>
        </g>
      </g>
    </svg>

    <div v-if="!compact" class="gc-legend">
      <span v-for="item in legend" :key="item.type" class="gc-legend-item">
        <i :style="{ background:item.color }"></i>{{ item.label }}
      </span>
      <span class="gc-legend-item"><i class="gc-legend-dash"></i>候选 / 低置信度关系</span>
    </div>
  </div>
</template>

<script lang="ts">
/**
 * 配色与图例对外导出：工作台「图谱概览」用的是同一张画布，
 * 概览里那行小图例必须与这里同源，否则两处各写一份迟早颜色漂移。
 */
export const GRAPH_COLORS: Record<string, string> = {
  CARD: '#2563eb', CLAIM: '#0f766e', EVIDENCE: '#b45309',
  ENTITY: '#7c3aed', SOURCE: '#15803d',
}
export const GRAPH_LEGEND: { type:string; label:string; color:string }[] = [
  { type:'CARD', label:'知识卡片', color:GRAPH_COLORS.CARD },
  { type:'CLAIM', label:'断言', color:GRAPH_COLORS.CLAIM },
  { type:'EVIDENCE', label:'证据', color:GRAPH_COLORS.EVIDENCE },
  { type:'ENTITY', label:'实体', color:GRAPH_COLORS.ENTITY },
  { type:'SOURCE', label:'来源', color:GRAPH_COLORS.SOURCE },
]
</script>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { GraphEdge, GraphNode } from '../types'

/**
 * Dependency-free force-directed graph canvas.
 *
 * A layout library was deliberately avoided: the neighborhood is bounded by the API (tens of
 * nodes), and owning the simulation lets the canvas keep node identity stable across refreshes,
 * which matters more than layout perfection when an operator is tracking one entity.
 */
const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
  focused?: string
  /** 画布高度：工作台卡片里用矮画布，图谱页保持默认的展开高度。 */
  height?: number
  /** 概览模式：隐去工具栏与图例，只留关系视图本身。 */
  compact?: boolean
}>()
const emit = defineEmits<{ select: [code:string] }>()

const WIDTH = 900
const canvasHeight = computed(() => (props.height && props.height >= 220 ? props.height : 520))
const REPULSION = 7200
const SPRING = 0.012
const REST_LENGTH = 150
const GRAVITY = 0.018
const DAMPING = 0.82
const MIN_DISTANCE = 16

const COLORS = GRAPH_COLORS
const LEGEND = GRAPH_LEGEND

interface SimNode {
  id:string; code:string; type:string; label:string
  x:number; y:number; vx:number; vy:number; r:number; fixed:boolean
}
interface SimLink { a:number; b:number; label:string; dashed:boolean }

const arrowId = `gc-arrow-${Math.random().toString(36).slice(2, 8)}`
const svgRef = ref<SVGSVGElement | null>(null)
const sim = ref<SimNode[]>([])
const links = ref<SimLink[]>([])
const scale = ref(1)
const tx = ref(0)
const ty = ref(0)
const legend = LEGEND

let frame = 0
let alpha = 1
let dragging: SimNode | null = null
let panning = false
let lastPointer = { x:0, y:0 }

/** Focus is addressed by object code, the same key edges use, so selection survives type changes. */
const focusedId = computed(() => String(props.focused || ''))

/** Nodes/edges to keep at full opacity: everything within one hop of the focused node. */
const activeIds = computed(() => {
  const set = new Set<string>()
  if (!focusedId.value) return set
  for (const link of links.value) {
    const a = sim.value[link.a]
    const b = sim.value[link.b]
    if (!a || !b) continue
    if (a.code === focusedId.value) set.add(b.id)
    if (b.code === focusedId.value) set.add(a.id)
  }
  return set
})

const renderedEdges = computed(() => links.value.map((link, index) => {
  const a = sim.value[link.a]
  const b = sim.value[link.b]
  if (!a || !b) return null
  const active = !focusedId.value || (a.code === focusedId.value || b.code === focusedId.value)
  return {
    key: `${a.id}->${b.id}-${index}`,
    x1: a.x, y1: a.y, x2: b.x, y2: b.y,
    label: link.label, dashed: link.dashed, active,
  }
}).filter(Boolean) as {
  key:string; x1:number; y1:number; x2:number; y2:number
  label:string; dashed:boolean; active:boolean
}[])

function colorOf(type: string) {
  return COLORS[type] || '#64748b'
}

function nodeId(node: GraphNode) {
  return `${node.type || 'NODE'}:${node.code}`
}

function labelOf(node: GraphNode) {
  const raw = String(node.title || node.code || '')
  const text = raw.replace(/\s+/g, ' ').trim()
  return text.length > 16 ? `${text.slice(0, 16)}…` : text
}

function radiusOf(degree: number) {
  return 12 + Math.min(11, Math.sqrt(Math.max(0, degree)) * 3.4)
}

function build() {
  const nodes = props.nodes || []
  const height = canvasHeight.value
  // A short canvas needs a tighter initial ring, or the first frame starts off-screen.
  const ring = Math.max(60, Math.min(150, height / 3.6))
  const ids = new Map(nodes.map((node, index) => [nodeId(node), index]))
  sim.value = nodes.map((node, index) => {
    const angle = (index / Math.max(1, nodes.length)) * Math.PI * 2
    const radius = ring + (index % 6) * (ring / 6)
    return {
      id: nodeId(node),
      code: String(node.code || ''),
      type: String(node.type || 'NODE').toUpperCase(),
      label: labelOf(node),
      x: WIDTH / 2 + Math.cos(angle) * radius,
      y: height / 2 + Math.sin(angle) * radius,
      vx: 0, vy: 0,
      r: radiusOf(Number(node.degree || 0)),
      fixed: false,
    }
  })
  links.value = (props.edges || []).map(edge => {
    const a = ids.get(`${edge.sourceType || ''}:${edge.source}`)
    const b = ids.get(`${edge.targetType || ''}:${edge.target}`)
    const fallbackA = ids.get(`CARD:${edge.source}`) ?? ids.get(`CLAIM:${edge.source}`)
    const fallbackB = ids.get(`EVIDENCE:${edge.target}`) ?? ids.get(`ENTITY:${edge.target}`)
    return {
      a: a ?? fallbackA ?? -1,
      b: b ?? fallbackB ?? -1,
      label: String(edge.type || edge.semanticType || ''),
      dashed: typeof edge.confidence === 'number' && edge.confidence < 0.7,
    }
  }).filter(link => link.a >= 0 && link.b >= 0 && link.a !== link.b)
  alpha = 1
  start()
}

function step() {
  const nodes = sim.value
  const total = nodes.length
  for (let i = 0; i < total; i++) {
    const a = nodes[i]
    for (let j = i + 1; j < total; j++) {
      const b = nodes[j]
      let dx = b.x - a.x
      let dy = b.y - a.y
      let distance = Math.sqrt(dx * dx + dy * dy)
      if (distance < 1) { dx = 0.5; dy = 0.5; distance = 1 }
      const clamped = Math.max(distance, MIN_DISTANCE)
      const force = REPULSION / (clamped * clamped)
      const ux = dx / distance
      const uy = dy / distance
      a.vx -= ux * force
      a.vy -= uy * force
      b.vx += ux * force
      b.vy += uy * force
    }
  }
  for (const link of links.value) {
    const a = nodes[link.a]
    const b = nodes[link.b]
    if (!a || !b) continue
    const dx = b.x - a.x
    const dy = b.y - a.y
    const distance = Math.max(Math.sqrt(dx * dx + dy * dy), 1)
    const force = (distance - REST_LENGTH) * SPRING
    const ux = dx / distance
    const uy = dy / distance
    a.vx += ux * force
    a.vy += uy * force
    b.vx -= ux * force
    b.vy -= uy * force
  }
  const centerY = canvasHeight.value / 2
  for (const node of nodes) {
    node.vx += (WIDTH / 2 - node.x) * GRAVITY
    node.vy += (centerY - node.y) * GRAVITY
  }
  for (const node of nodes) {
    if (node.fixed) { node.vx = 0; node.vy = 0; continue }
    node.vx = (node.vx + 0) * DAMPING
    node.vy = (node.vy + 0) * DAMPING
    node.x += node.vx * alpha
    node.y += node.vy * alpha
    node.vx *= 0.5
    node.vy *= 0.5
  }
  alpha *= 0.985
  if (alpha > 0.02) {
    frame = requestAnimationFrame(step)
  } else {
    frame = 0
  }
}

function start() {
  if (frame) cancelAnimationFrame(frame)
  frame = requestAnimationFrame(step)
}

function restart() {
  build()
}

function zoomBy(factor: number) {
  const next = Math.min(3, Math.max(0.25, scale.value * factor))
  const view = svgRef.value
  if (!view) { scale.value = next; return }
  const rect = view.getBoundingClientRect()
  const px = rect.width / 2
  const py = rect.height / 2
  tx.value = px - (px - tx.value) * (next / scale.value)
  ty.value = py - (py - ty.value) * (next / scale.value)
  scale.value = next
}

function onWheel(event: WheelEvent) {
  const view = svgRef.value
  if (!view) return
  const rect = view.getBoundingClientRect()
  const px = event.clientX - rect.left
  const py = event.clientY - rect.top
  const next = Math.min(3, Math.max(0.25, scale.value * Math.exp(-event.deltaY * 0.0015)))
  tx.value = px - (px - tx.value) * (next / scale.value)
  ty.value = py - (py - ty.value) * (next / scale.value)
  scale.value = next
}

function toCanvas(event: PointerEvent) {
  const view = svgRef.value
  if (!view) return { x:0, y:0 }
  const rect = view.getBoundingClientRect()
  return {
    x: (event.clientX - rect.left - tx.value) / scale.value,
    y: (event.clientY - rect.top - ty.value) / scale.value,
  }
}

function onCanvasDown(event: PointerEvent) {
  panning = true
  lastPointer = { x:event.clientX, y:event.clientY }
}

function onNodeDown(event: PointerEvent, node: SimNode) {
  node.fixed = true
  dragging = node
  lastPointer = { x:event.clientX, y:event.clientY }
  emit('select', node.code)
  alpha = Math.max(alpha, 0.35)
  start()
}

function onPointerMove(event: PointerEvent) {
  if (dragging) {
    const point = toCanvas(event)
    dragging.x = point.x
    dragging.y = point.y
    return
  }
  if (!panning) return
  tx.value += event.clientX - lastPointer.x
  ty.value += event.clientY - lastPointer.y
  lastPointer = { x:event.clientX, y:event.clientY }
}

function onPointerUp() {
  if (dragging) { dragging.fixed = false; dragging = null }
  panning = false
}

function fit() {
  const view = svgRef.value
  const nodes = sim.value
  if (!view || !nodes.length) return
  const rect = view.getBoundingClientRect()
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const node of nodes) {
    minX = Math.min(minX, node.x - node.r)
    minY = Math.min(minY, node.y - node.r)
    maxX = Math.max(maxX, node.x + node.r)
    maxY = Math.max(maxY, node.y + node.r)
  }
  const padding = 48
  const width = Math.max(1, maxX - minX)
  const height = Math.max(1, maxY - minY)
  const next = Math.min(2.5, Math.max(0.25,
    Math.min((rect.width - padding * 2) / width, (rect.height - padding * 2) / height)))
  scale.value = next
  tx.value = rect.width / 2 - ((minX + maxX) / 2) * next
  ty.value = rect.height / 2 - ((minY + maxY) / 2) * next
}

watch(() => [props.nodes, props.edges], build, { immediate:true, deep:false })

onBeforeUnmount(() => { if (frame) cancelAnimationFrame(frame) })

defineExpose({ fit })
</script>

<style scoped>
.gc-wrap { display:flex; flex-direction:column; gap:8px; }
.gc-toolbar { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.gc-tool { padding:2px 9px; font-size:13px; }
.gc-hint { font-size:11px; color:var(--ink-3); }
.gc-svg {
  width:100%; height:520px; background:
    radial-gradient(circle at 1px 1px, var(--gc-grid) 1px, transparent 0) 0 0 / 22px 22px, var(--gc-bg);
  border:1px solid var(--line-d); border-radius:12px; cursor:grab; touch-action:none;
}
.gc-svg:active { cursor:grabbing; }
.gc-edge { stroke:var(--ink-400); stroke-width:1.5; transition:opacity .15s; }
.gc-edge.dashed { stroke-dasharray:5 4; stroke:var(--ink-400); }
.gc-edge.hot { stroke:var(--brand-600); stroke-width:2.2; }
.gc-edge.dim { opacity:.16; }
.gc-edge-label { font-size:9.5px; fill:var(--ink-3); text-anchor:middle; }
.gc-node { cursor:pointer; }
.gc-node circle { stroke:var(--gc-bg); stroke-width:2; transition:opacity .15s; }
.gc-node.focused circle { stroke:var(--warn); stroke-width:3; }
.gc-node.dim { opacity:.2; }
.gc-node-label {
  font-size:10.5px; fill:var(--ink-2); text-anchor:middle; pointer-events:none;
  paint-order:stroke; stroke:var(--gc-bg); stroke-width:3px; stroke-linejoin:round;
}
.gc-legend { display:flex; gap:14px; flex-wrap:wrap; font-size:11.5px; color:var(--ink-3); }
.gc-legend-item { display:inline-flex; align-items:center; gap:5px; }
.gc-legend-item i { width:9px; height:9px; border-radius:50%; display:inline-block; }
.gc-legend-dash { width:16px !important; height:0 !important; border-radius:0 !important; border-top:2px dashed var(--ink-400); }
</style>
