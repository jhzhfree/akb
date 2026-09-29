<template>
  <el-drawer :model-value="visible" size="min(1120px, 96vw)" :with-header="false" @close="$emit('close')">
    <div class="min-h-full bg-ink-50 p-5 md:p-7">
      <div v-if="loading" class="kb-card p-10 text-center text-sm text-ink-500">正在装配知识证据链...</div>
      <template v-else-if="detail">
        <header class="mb-5 flex flex-wrap items-start justify-between gap-4">
          <div>
            <div class="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-accent">
              <span>{{ mode }}</span><span>·</span><span>{{ code }}</span>
            </div>
            <h2 class="text-2xl font-bold text-ink-900">{{ title }}</h2>
            <p class="mt-2 max-w-3xl text-sm leading-6 text-ink-500">{{ summary }}</p>
          </div>
          <div class="flex items-center gap-2">
            <span class="kb-badge">{{ status }}</span>
            <span class="kb-badge">{{ isChunk?`Token ${tokenCount}`:`可信度 ${confidence}` }}</span>
            <button v-if="!isChunk" class="kb-btn kb-btn-outline" @click="$emit('open-graph',code)">打开图谱</button>
          </div>
        </header>

        <div class="mb-5 grid gap-3 sm:grid-cols-4">
          <div class="kb-metric"><span>证据</span><b>{{ detail.evidence.length }}</b></div>
          <div class="kb-metric"><span>冲突</span><b>{{ detail.conflicts.length }}</b></div>
          <div class="kb-metric"><span>复核</span><b>{{ detail.reviews.length }}</b></div>
          <div class="kb-metric"><span>评估</span><b>{{ detail.assessments.length }}</b></div>
        </div>

        <!-- 设计 §8：统一详情框架——概览 / 关联 / Evidence / 版本 / 权限 / 审计，所有对象共用。 -->
        <el-tabs v-model="tab" class="kb-tabs">
          <!-- 原文：完整正文 + 切分边界 + 同文档上下文，引用对不上时先来这里核对 -->
          <el-tab-pane label="原文" name="raw">
            <section class="kb-card p-5">
              <div class="kb-section-head">
                <div><small>原文</small><h3>原始正文</h3></div>
                <button class="kb-btn kb-btn-outline" @click="copy(rawText)">复制原文</button>
              </div>
              <div class="max-h-[46vh] overflow-auto whitespace-pre-wrap break-words rounded-lg bg-ink-50 p-4 text-[13px] leading-7 text-ink-700">{{ rawText }}</div>
              <div class="mt-3 flex flex-wrap gap-3 text-[11px] text-ink-500">
                <span>字符 {{ rawText.length }}</span>
                <span>估算 Token {{ estimateTokens(rawText) }}</span>
                <span v-if="obj.charStart!=null">原文区间 {{ obj.charStart }} – {{ obj.charEnd }}</span>
                <span v-if="obj.pageNo">P{{ obj.pageNo }}<template v-if="obj.pageEnd && obj.pageEnd!==obj.pageNo"> – P{{ obj.pageEnd }}</template></span>
              </div>
            </section>
            <section v-if="neighbors.length" class="kb-card mt-4 p-5">
              <div class="kb-section-head"><div><small>相邻内容</small><h3>同文档上下文</h3></div></div>
              <article
                v-for="n in neighbors" :key="String(n.unitCode)"
                class="mb-2 rounded-lg border p-3 text-xs"
                :class="String(n.unitCode)===String(obj.unitCode||code)?'border-[color:var(--info-bd)] bg-[color:var(--info-bg)]':'border-ink-100 bg-white'">
                <div class="mb-1 font-semibold text-ink-700">
                  {{ n.pos==='PREV'?'上文':(n.pos==='NEXT'?'下文':'本块') }} · {{ n.headingPath || '—' }}
                </div>
                <p class="leading-6 text-ink-500">{{ n.excerpt }}</p>
              </article>
            </section>
          </el-tab-pane>

          <el-tab-pane label="概览" name="overview">
            <section class="kb-card p-5">
              <div class="kb-section-head"><div><small>正文</small><h3>知识正文</h3></div></div>
              <div class="whitespace-pre-wrap text-sm leading-7 text-ink-700">{{ content }}</div>
            </section>
            <section class="kb-card mt-4 p-5">
              <div class="kb-section-head"><div><small>属性</small><h3>关键属性</h3></div></div>
              <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                <div v-for="row in attributes" :key="row.label" class="rounded-lg bg-ink-50 p-3">
                  <div class="text-[11px] text-ink-500">{{ row.label }}</div>
                  <b class="text-[13px] text-ink-800">{{ row.value }}</b>
                </div>
              </div>
            </section>
          </el-tab-pane>

          <!-- 关联：图谱邻域与冲突对象，点开即可跳转图谱或冲突处理 -->
          <el-tab-pane :label="`关联 ${graphNodes.length}`" name="relation">
            <section class="kb-card p-5">
              <div class="kb-section-head">
                <div><small>关联图谱</small><h3>关联对象</h3></div>
                <button class="kb-btn kb-btn-outline" @click="$emit('open-graph',code)">打开图谱</button>
              </div>
              <div v-if="graphNodes.length" class="space-y-2">
                <div
                  v-for="node in graphNodes"
                  :key="String(node.code)"
                  class="flex items-center justify-between rounded-lg border border-ink-100 bg-white px-3 py-2 text-[12px]">
                  <span class="truncate text-ink-700">{{ String(node.title || node.code) }}</span>
                  <span class="ml-3 shrink-0 text-ink-500">{{ String(node.type || node.objectType || '') }}</span>
                </div>
              </div>
              <div v-else class="py-8 text-center text-sm text-ink-500">暂无关联对象</div>
            </section>
            <section v-if="detail.conflicts.length" class="kb-card mt-4 border-[color:var(--warn-bd)] p-5">
              <div class="kb-section-head"><div><small>知识知识冲突</small><h3>知识冲突</h3></div></div>
              <div
                v-for="(c,i) in detail.conflicts"
                :key="i"
                class="mb-2 rounded-lg bg-[color:var(--warn-bg)] p-3 text-xs text-[color:var(--warn)]">
                {{ c.conflictType || 'CONFLICT' }} · {{ c.objectACode }} ↔ {{ c.objectBCode }}
              </div>
            </section>
          </el-tab-pane>

          <el-tab-pane :label="`证据 ${detail.evidence.length}`" name="evidence">
            <section class="kb-card p-5">
              <div class="kb-section-head"><div><small>证据链</small><h3>证据与来源</h3></div></div>
              <div class="space-y-3">
                <article v-for="e in detail.evidence" :key="e.evidenceCode"
                  class="cursor-pointer rounded-lg border border-ink-100 bg-white p-4 transition hover:border-[color:var(--info-bd)] hover:shadow-sm"
                  @click="$emit('open-evidence',e.evidenceCode)">
                  <div
    class="mb-2 flex items-center justify-between gap-3"><b
    class="text-sm text-ink-800">{{ e.sourceName }}</b><span
    class="text-xs text-ink-500">{{ e.evidenceCode }}</span></div>
                  <p class="text-sm leading-6 text-ink-600">{{ e.quoteText }}</p>
                  <div v-if="e.documentTitle || e.contentUnitCode" class="mt-3 rounded-md bg-[color:var(--info-bg)] px-3 py-2 text-[11px] text-accent">
                    📍 {{ e.documentTitle || e.documentCode }}<span v-if="e.pageNo"> · P{{ e.pageNo }}</span>
                    <span v-if="e.headingPath"> · {{ e.headingPath }}</span><span v-if="e.contentUnitCode"> · {{ e.contentUnitCode }}</span>
                  </div>
                  <div class="mt-3 flex items-center justify-between gap-3 text-xs text-ink-500">
                    <div class="flex gap-3"><span>权威 {{ score(e.authorityScore) }}</span><span>新鲜度 {{ score(e.freshnessScore) }}</span></div>
                    <span class="font-medium text-accent">查看原文 →</span>
                  </div>
                </article>
                <div v-if="!detail.evidence.length" class="py-8 text-center text-sm text-ink-500">暂无证据关联</div>
              </div>
            </section>
          </el-tab-pane>

          <el-tab-pane :label="`版本 ${(detail.versions||[]).length}`" name="version">
            <VersionDiffPanel :versions="detail.versions || []" />
          </el-tab-pane>

          <el-tab-pane label="权限" name="acl">
            <section class="kb-card p-5">
              <div class="kb-section-head"><div><small>访问控制</small><h3>权限与可见范围</h3></div></div>
              <div class="space-y-3">
                <div class="rounded-lg bg-ink-50 p-3">
                  <div class="text-[11px] text-ink-500">可见范围</div>
                  <b class="text-[13px] text-ink-800">{{ visibility }}</b>
                </div>
                <div>
                  <div class="mb-1 text-[11px] text-ink-500">权限标签</div>
                  <div v-if="permissionTags.length" class="flex flex-wrap gap-2">
                    <span v-for="tag in permissionTags" :key="tag" class="rounded bg-[color:var(--info-bg)] px-2 py-1 text-[11px] text-accent">
                      {{ tag }}
                    </span>
                  </div>
                  <div v-else class="text-sm text-ink-500">未设置权限标签，按知识库默认可见范围继承</div>
                </div>
                <p class="kb-muted">权限变更会写入传播队列并重建索引，可在「系统配置 / 权限与审计」查看变更记录。</p>
              </div>
            </section>
          </el-tab-pane>

          <!-- 接口原始报文：接口原生报文，字段对不上契约时直接在这里比对 -->
          <el-tab-pane label="接口原始报文" name="json">
            <section class="kb-card p-5">
              <div class="kb-section-head">
                <div><small>原始报文</small><h3>接口原生报文</h3></div>
                <button class="kb-btn kb-btn-outline" @click="copy(jsonText)">复制报文</button>
              </div>
              <pre class="max-h-[60vh] overflow-auto rounded-lg bg-ink-900 p-4 text-[12px] leading-6 text-[color:var(--ok)]">{{ jsonText }}</pre>
              <p class="kb-muted">与 GET /api/knowledge/objects/{kb}/{type}/{code} 返回一致，用于对照接口契约核对字段。</p>
            </section>
          </el-tab-pane>

          <el-tab-pane label="审计" name="audit">
            <section class="kb-card p-5">
              <div class="kb-section-head"><div><small>机器裁决</small><h3>智能裁决记录</h3></div></div>
              <div
                v-for="a in detail.assessments"
                :key="String(a.id)"
                class="mb-3 rounded-lg bg-ink-50 p-3 text-xs"><b
                class="text-ink-800">{{ a.decision }}</b><p
                class="mt-1 text-ink-500">{{ a.reasonText }}</p></div>
              <div v-if="!detail.assessments.length" class="text-sm text-ink-500">暂无智能裁决记录</div>
            </section>
            <section class="kb-card mt-4 p-5">
              <div class="kb-section-head"><div><small>复核记录</small><h3>人工复核历史</h3></div></div>
              <div
                v-for="r in detail.reviews"
                :key="r.id"
                class="review-line"><b>{{ r.decision }}</b><p
                class="mt-1 text-ink-500">{{ r.comment || '—' }}</p><small
                class="text-ink-500">{{ r.reviewer }} · {{ time(r.createdTime) }}</small></div>
              <div v-if="!detail.reviews.length" class="text-sm text-ink-500">暂无人工复核</div>
            </section>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { DocumentChunk, GraphNode, KnowledgeObjectDetail } from '../types'
import { statusLabel, visibilityLabel } from '../types'
import VersionDiffPanel from './VersionDiffPanel.vue'
const props=defineProps<{visible:boolean;mode:'CARD'|'CLAIM'|'CHUNK';code:string;detail:KnowledgeObjectDetail|null;loading:boolean}>()
defineEmits<{close:[];'open-graph':[code:string];'open-evidence':[evidenceCode:string]}>()
/** 统一详情框架的当前 Tab：概览 / 关联 / Evidence / 版本 / 权限 / 审计。 */
const tab=ref('overview')
/** 切换对象时回到概览，避免把上一个对象的 Tab 状态带到下一个对象。 */
watch(()=>props.code,()=>{tab.value='overview'})
const obj=computed(()=>props.detail?.object||{})
/** Chunk 与知识对象共用抽屉：Chunk 没有状态与可信度，展示的是切分事实。 */
const isChunk=computed(()=>props.mode==='CHUNK')
const title=computed(()=>isChunk.value
  ? String(obj.value.headingPath||obj.value.unitCode||props.code)
  : String(obj.value.title||obj.value.subject||props.code))
const summary=computed(()=>isChunk.value
  ? String(obj.value.documentTitle||obj.value.documentCode||'')
  : String(obj.value.summary||obj.value.predicate||''))
const content=computed(()=>String(obj.value.content||obj.value.objectValue||obj.value.summary||''))
const status=computed(()=>isChunk.value
  ? String(obj.value.chunkType||'CHUNK')
  : statusLabel(obj.value.status))
const confidence=computed(()=>score(Number(obj.value.confidence||0)))
const tokenCount=computed(()=>Number(obj.value.tokenCount||0))
const rawText=computed(()=>String(obj.value.content||''))
const neighbors=computed<DocumentChunk[]>(()=>props.detail?.neighbors||[])
/** 原生报文：与接口返回逐字一致，便于对照契约排查字段。 */
const jsonText=computed(()=>JSON.stringify(props.detail, null, 2))
function estimateTokens(text:string):number{
  const cjk=text.match(/[\u4e00-\u9fff]/g)?.length||0
  return Math.max(1, Math.round(cjk*0.7+(text.length-cjk)/4))
}
async function copy(text:string){
  if(!text){ElMessage.warning('没有可复制的内容');return}
  try{
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  }catch{ElMessage.warning('浏览器拒绝了剪贴板访问，请手动选择复制')}
}
/** 概览属性：按对象类型取治理或切分关键字段，字段缺失显示 — 而不是渲染 undefined。 */
const attributes=computed(()=>isChunk.value?[
  { label:'块类型', value:String(obj.value.chunkType||'—') },
  { label:'序号', value:String(obj.value.seq??'—') },
  { label:'页码', value:obj.value.pageNo?`P${obj.value.pageNo}${obj.value.pageEnd&&obj.value.pageEnd!==obj.value.pageNo?` – P${obj.value.pageEnd}`:''}`:'—' },
  { label:'字符区间', value:obj.value.charStart!=null?`${obj.value.charStart} – ${obj.value.charEnd}`:'—' },
  { label:'Token', value:String(obj.value.tokenCount||'—') },
  { label:'所属文档', value:String(obj.value.documentTitle||obj.value.documentCode||'—') },
]:[
  { label:'状态', value:status.value },
  { label:'可信度', value:confidence.value },
  { label:'知识类型', value:String(obj.value.knowledgeType||obj.value.cardType||'—') },
  { label:'有效期', value:obj.value.validTo?String(obj.value.validTo).slice(0,10):'长期有效' },
  { label:'更新时间', value:obj.value.updatedTime?String(obj.value.updatedTime).slice(0,19):'—' },
  { label:'创建者', value:String(obj.value.createdBy||obj.value.author||'—') },
])
const graphNodes=computed<GraphNode[]>(()=>(props.detail?.graph?.nodes||[]).slice(0,10))
const visibility=computed(()=>visibilityLabel(String(obj.value.visibility||'')))
/** 权限标签：后端可能返回数组或逗号分隔字符串。 */
const permissionTags=computed<string[]>(()=>{
  const raw=obj.value.permissionTags
  if(Array.isArray(raw))return raw.map(v=>String(v)).filter(Boolean)
  if(typeof raw==='string')return raw.split(',').map(v=>v.trim()).filter(Boolean)
  return []
})
const score=(v?:number)=>Number(v||0).toFixed(2)
const time=(v?:string)=>v?new Date(v).toLocaleString():'—'
</script>
<style scoped>
.review-line{position:relative;border-left:1px solid var(--line-d);padding:0 0 1rem 1rem;font-size:.75rem}
.review-line:before{content:"";position:absolute;left:-4px;top:4px;width:8px;height:8px;border-radius:999px;background:var(--accent-text)}
</style>
