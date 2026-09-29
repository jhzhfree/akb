<template>
  <!-- 设计 §4：知识生成 = 建知识。四个任务卡是主入口，下方 Tab 组织完整工作区。 -->
  <div class="gen-hero">
    <button v-for="action in actions" :key="action.key" class="gen-action"
      @click="$emit('select',action.key)">
      <b>{{ action.label }}</b>
      <small>{{ action.desc }}</small>
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
    :batch-status="batchStatus" :batch-running="batchRunning"
    :preview="preview" :preview-loading="previewLoading" :preview-error="previewError" :preview-id="previewId"
    @upload-files="shell.uploadFiles" @remove="shell.remove"
    @cancel-batch="shell.cancelBatch" @retry-batch="shell.retryBatch"
    @preview="shell.previewSource"
    @select-run="shell.selectRun" @refresh="shell.refresh" @reloaded="shell.reloadAfterRetry"
    @open-graph="shell.openGraph" />

  <!-- 设计 §6.1：连接数据源有自己的面板——Jira / Confluence / REST 读的是外部系统的知识列表，
       和「把文件拖进来」是两条链路，共用面板只会让两边都不好懂。 -->
  <ConnectorPanel
    v-else-if="active==='create.connector'"
    :knowledge-base-id="knowledgeBaseId" />

  <!-- 文档导入 / 生成任务：同一面板按 focus 切换区块（设计 §4.1 / §4.2）。 -->
  <DataCenterPanel
    v-else-if="dcFocus"
    :focus="dcFocus"
    :knowledge-base-id="knowledgeBaseId"
    :authority="authority" :permission-tags="permissionTags"
    @update:authority="$emit('update:authority',$event)"
    @update:permission-tags="$emit('update:permission-tags',$event)"
    :documents="documents"
    :runs="runs" :run-detail="runDetail" :selected-run-id="selectedRunId"
    :uploading="uploading"
    :ingest-live="ingestLive" :ingest-stage="ingestStage" :ingest-message="ingestMessage"
    :preview="preview" :preview-loading="previewLoading" :preview-error="previewError" :preview-id="previewId"
    :reset-token="resetToken"
    :batch-status="batchStatus" :batch-running="batchRunning"
    @preview="shell.previewSource"
    @remove="shell.remove"
    @upload-files="shell.uploadFiles"
    @cancel-batch="shell.cancelBatch" @retry-batch="shell.retryBatch"
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

  <DerivationPanel v-else-if="active==='create.derive'" :knowledge-base-id="knowledgeBaseId" />

  <PanelState v-else :empty="true" empty-text="请选择一个生成动作"
    empty-hint="左侧动作卡片进入对应工作台" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type {
  AssessmentRow, BatchIngestStatus, ConflictDetail, ConflictRow, IngestionRun, IngestionRunDetail,
  KnowledgeDocument, KnowledgeSource, ReviewRow, SourcePreview,
} from '../types'
import { useShell } from '../shell'
import { PERM } from '../permissions'
import ModuleTabs from './ModuleTabs.vue'
import GenerateHomePanel from './GenerateHomePanel.vue'
import DataCenterPanel from './DataCenterPanel.vue'
import ConnectorPanel from './ConnectorPanel.vue'
import ReviewPanel from './ReviewPanel.vue'
import PanelState from './PanelState.vue'
import DerivationPanel from './DerivationPanel.vue'

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
  /** 批量接入批次状态：多文件上传的进度与逐文件结果在外壳轮询后下发。 */
  batchStatus: BatchIngestStatus | null
  /** 批次是否仍在执行：用于禁用「批量上传」按钮，避免重复提交同一批文件。 */
  batchRunning: boolean
}>()
defineEmits<{
  select: [key: string]
  'update:authority': [value: number]
  'update:permission-tags': [value: string]
}>()

const shell = useShell()
const tabs = computed(() => shell.tabs('create'))
/**
 * 生成动作卡：按角色裁剪——运营员只看到导入文档，审核员额外看到裁决，
 * 只有知识系统管理员才看到连接器与衍生（设计 §4 + 角色权限矩阵）。
 */
const actions = computed(() => [
  { key: 'create.import', label: '导入文档', desc: '文件 / URL / 文本', permission: PERM.GENERATE_IMPORT },
  { key: 'create.connector', label: '连接数据源', desc: '数据库 / API / 对象存储 / SaaS', permission: PERM.GENERATE_CONNECTOR },
  { key: 'create.judgment', label: '知识裁决', desc: '待审核 / 冲突 / Evidence', permission: PERM.GENERATE_JUDGE },
  { key: 'create.derive', label: '知识衍生', desc: '规则 / 计算 / Graph / AI', permission: PERM.GENERATE_DERIVE },
].filter(action => shell.can(action.permission)))
const badges = computed<Record<string, number | string>>(() => ({
  'create.judgment': props.pendingReviews.length + props.conflicts.length || '',
}))
/** 资料类 Tab 复用同一面板：文档导入 / 生成任务（连接器已独立成面板）。 */
const dcFocus = computed<'documents' | 'ingestion' | ''>(() => {
  if (props.active === 'create.import') return 'documents'
  if (props.active === 'create.jobs') return 'ingestion'
  return ''
})
</script>
<style scoped>
/* 四个任务入口卡：知识生成页的主操作区，与原型 hero 卡片一致。 */
/* 动作卡数量随角色变化，用 auto-fit 保证 1~4 张卡都排得下。 */
.gen-hero{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}
.gen-action{
  position:relative;padding:16px 18px;text-align:left;background:var(--bg-raised);
  border:1px solid var(--line-d);border-radius:11px;box-shadow:0 2px 9px #1d35570a;cursor:pointer}
.gen-action:hover{border-color:var(--brand-400)}
.gen-action:disabled{cursor:not-allowed;color:var(--ink-3)}
.gen-action b{display:block;font-size:15px;color:var(--ink-1)}
.gen-action small{display:block;margin-top:4px;font-size:11.5px;color:var(--ink-2)}
.gen-action em{position:absolute;right:14px;top:14px;font-size:11px;font-style:normal;color:var(--ink-3)}
@media(max-width:1100px){.gen-hero{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
