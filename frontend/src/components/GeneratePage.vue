<template>
  <!-- 设计 §4：知识生成 = 建知识。四个任务卡是主入口，下方 Tab 组织完整工作区。 -->
  <div class="gen-hero">
    <button v-for="action in actions" :key="action.key" class="gen-action" :disabled="action.planned"
      @click="$emit('select',action.key)">
      <b>{{ action.label }}</b>
      <small>{{ action.desc }}</small>
      <em v-if="action.planned">规划中</em>
    </button>
  </div>

  <ModuleTabs
    class="mt-4" label="知识生成" :leaves="tabs" :active="active" :badges="badges"
    @select="$emit('select',$event)" />

  <GenerateHomePanel
    v-if="active==='create.overview'"
    :knowledge-base-id="knowledgeBaseId"
    :pending-reviews="pendingReviews" :conflicts="conflicts"
    :authority="authority" :permission-tags="permissionTags"
    @update:authority="$emit('update:authority',$event)"
    @update:permission-tags="$emit('update:permission-tags',$event)"
    :documents="documents"
    :runs="runs" :run-detail="runDetail" :selected-run-id="selectedRunId"
    :uploading="uploading"
    :ingest-live="ingestLive" :ingest-stage="ingestStage" :ingest-message="ingestMessage"
    :reset-token="resetToken"
    @navigate="shell.navigate"
    @pick="shell.pick" @remove="shell.remove" @upload="shell.upload"
    @select-run="shell.selectRun" @refresh="shell.refresh" @reloaded="shell.reloadAfterRetry"
    @open-graph="shell.openGraph" />

  <!-- 文档导入 / 连接器 / 生成任务：同一面板按 focus 切换区块（设计 §4.1 / §4.2）。 -->
  <DataCenterPanel
    v-else-if="dcFocus"
    :focus="dcFocus"
    :authority="authority" :permission-tags="permissionTags"
    @update:authority="$emit('update:authority',$event)"
    @update:permission-tags="$emit('update:permission-tags',$event)"
    :sources="sources" :documents="documents"
    :runs="runs" :run-detail="runDetail" :selected-run-id="selectedRunId"
    :uploading="uploading"
    :ingest-live="ingestLive" :ingest-stage="ingestStage" :ingest-message="ingestMessage"
    :preview="preview" :preview-loading="previewLoading" :preview-error="previewError" :preview-id="previewId"
    :reset-token="resetToken"
    @preview="shell.previewSource"
    @pick="shell.pick" @remove="shell.remove" @upload="shell.upload"
    @select-run="shell.selectRun" @reloaded="shell.reloadAfterRetry" />

  <!-- 设计 §4.4：裁决工作台同时承载冲突与待审核，左列表 + 中对比 + 右 Evidence。 -->
  <ReviewPanel
    v-else-if="active==='create.judgment'"
    focus="judgment"
    :pending-reviews="pendingReviews" :conflicts="conflicts" :assessments="assessments"
    :adjudicating="adjudicating"
    :selected-conflict-id="selectedConflictId" :conflict-detail="conflictDetail"
    :conflict-detail-loading="conflictDetailLoading"
    :selected-review="selectedReview" :review-detail="reviewDetail"
    @adjudicate="shell.adjudicate"
    @review="(row,decision,comment)=>shell.review(row,decision,comment)"
    @resolve="(row,status)=>shell.resolveConflict(row,status)"
    @select-conflict="shell.selectConflict"
    @select-review="shell.selectReview"
    @adjudicate-conflict="(id,decision,comment)=>shell.adjudicateConflict(id,decision,comment)"
    @open-evidence="shell.openEvidence" />

  <PanelState v-else :empty="true" empty-text="知识衍生：规划中"
    empty-hint="信息架构已保留该位置，能力落地后会在此开放" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type {
  AssessmentRow, ConflictDetail, ConflictRow, IngestionRun, IngestionRunDetail, KnowledgeDocument,
  KnowledgeSource, ReviewRow, SourcePreview,
} from '../types'
import { NAV_SECTIONS } from '../navigation'
import { useShell } from '../shell'
import ModuleTabs from './ModuleTabs.vue'
import GenerateHomePanel from './GenerateHomePanel.vue'
import DataCenterPanel from './DataCenterPanel.vue'
import ReviewPanel from './ReviewPanel.vue'
import PanelState from './PanelState.vue'

const props = defineProps<{
  active: string
  knowledgeBaseId: number
  pendingReviews: ReviewRow[]
  conflicts: ConflictRow[]
  assessments: AssessmentRow[]
  adjudicating: boolean
  /** 裁决工作台选中态：冲突双方对照、待审核对象详情与证据链由外壳加载。 */
  selectedConflictId: number | null
  conflictDetail: ConflictDetail | null
  conflictDetailLoading: boolean
  selectedReview: ReviewRow | null
  reviewDetail: Record<string, unknown> | null
  authority: number
  permissionTags: string
  documents: KnowledgeDocument[]
  sources: KnowledgeSource[]
  runs: IngestionRun[]
  runDetail: IngestionRunDetail | null
  selectedRunId: number | null
  uploading: boolean
  ingestLive: boolean
  ingestStage: string
  ingestMessage: string
  preview: SourcePreview | null
  previewLoading: boolean
  previewError: string
  previewId: number | null
  /** 上传复位令牌：由外壳自增后透传给摄入面板。 */
  resetToken: number
}>()
defineEmits<{
  select: [key: string]
  'update:authority': [value: number]
  'update:permission-tags': [value: string]
}>()

const shell = useShell()
const tabs = NAV_SECTIONS[1].tabs
/** 四个任务卡：文档导入 / 连接数据源 / 知识裁决 / 知识衍生（设计 §4）。 */
const actions = [
  { key: 'create.import', label: '导入文档', desc: '文件 / URL / 文本' },
  { key: 'create.connector', label: '连接数据源', desc: '数据库 / API / 对象存储 / SaaS' },
  { key: 'create.judgment', label: '知识裁决', desc: '待审核 / 冲突 / Evidence' },
  { key: 'create.derive', label: '知识衍生', desc: '规则 / 计算 / Graph / AI', planned: true },
]
const badges = computed<Record<string, number | string>>(() => ({
  'create.judgment': props.pendingReviews.length + props.conflicts.length || '',
}))
/** 资料类 Tab 复用同一面板：文档导入 / 连接器 / 生成任务。 */
const dcFocus = computed<'sources' | 'documents' | 'ingestion' | ''>(() => {
  if (props.active === 'create.import') return 'documents'
  if (props.active === 'create.connector') return 'sources'
  if (props.active === 'create.jobs') return 'ingestion'
  return ''
})
</script>
<style scoped>
/* 四个任务入口卡：知识生成页的主操作区，与原型 hero 卡片一致。 */
.gen-hero{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.gen-action{
  position:relative;padding:16px 18px;text-align:left;background:#fff;
  border:1px solid #e2e9f2;border-radius:11px;box-shadow:0 2px 9px #1d35570a;cursor:pointer}
.gen-action:hover{border-color:#77aff8}
.gen-action:disabled{cursor:not-allowed;color:#9aa4b5}
.gen-action b{display:block;font-size:15px;color:#164e8b}
.gen-action small{display:block;margin-top:4px;font-size:11.5px;color:#748399}
.gen-action em{position:absolute;right:14px;top:14px;font-size:11px;font-style:normal;color:#9aa4b5}
@media(max-width:1100px){.gen-hero{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
