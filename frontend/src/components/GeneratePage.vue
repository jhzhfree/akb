<template>
  <!-- 设计 §4：知识生成 = 建知识。入口统一走顶部 Tab，这里不再重复一排动作卡——同样的能力只保留一处入口。
       Tab 由外壳按角色下发：管理员是「生成概览 / 生成配置 / 文档导入 / 连接器 / 外部知识 / 知识裁决 / 知识衍生」，
       普通成员精简为「生成配置 / 文档导入（默认进入）/ 知识裁决 / 知识衍生」——生成配置管的是本库怎么产出知识，
       与「能把资料接进来」同一级权限，不随运维类 Tab 一起收起。本页不重复判断角色。 -->
  <ModuleTabs
    label="知识生成" :leaves="tabs" :active="active" :badges="badges"
    @select="$emit('select',$event)" />

  <GenerateHomePanel
    v-if="active==='create.overview'"
    :knowledge-base-id="knowledgeBaseId"
    :regenerating="regenerating"
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

  <!-- 生成配置：场景模板、切片与抽取开关。与建库弹窗同源（同一份场景档案），
       这里解决的是库已建好、文档已入库后还要再调的情况。 -->
  <GenerationConfigPanel
    v-else-if="active==='create.generation'"
    :knowledge-base-id="knowledgeBaseId"
    :documents="documents"
    :regenerating="regenerating" />

  <!-- 设计 §6.1：连接数据源有自己的面板——Jira / Confluence / REST 读的是外部系统的知识列表，
       和「把文件拖进来」是两条链路，共用面板只会让两边都不好懂。 -->
  <ConnectorPanel
    v-else-if="active==='create.connector'"
    :knowledge-base-id="knowledgeBaseId" />

  <!-- 连接器同步下来的外部知识：按类型与属性浏览，与连接器配置分开两个入口。 -->
  <ConnectorKnowledgePanel
    v-else-if="active==='create.connectorCatalog'"
    :knowledge-base-id="knowledgeBaseId" />

  <!-- 文档导入：拖入资料 + 已解析文档 + 运行日志抽屉。
       原「生成任务」入口与之共用同一份区块，已合并到此处（设计 §4.1 / §4.2）。 -->
  <DataCenterPanel
    v-else-if="active==='create.import'"
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

  <!-- 知识台账：把库里每一条来源都挂上责任人 / 生效 / 失效 / 适用范围。
       治理动作挂在「知识生成」下是因为它紧跟在「文档导入」之后——刚把资料接进来
       的时候正好顺手把身份证补齐，比半年后再盘点省事得多。 -->
  <KnowledgeLedgerPanel
    v-else-if="active==='create.ledger'"
    :knowledge-base-id="knowledgeBaseId"
    :sources="sources" />

  <DerivationPanel v-else-if="active==='create.derive'" :knowledge-base-id="knowledgeBaseId" />

  <PanelState v-else :empty="true" empty-text="请选择一个生成能力"
    empty-hint="从上方入口条进入对应工作台" />
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type {
  AssessmentRow, BatchIngestStatus, ConflictDetail, ConflictRow, IngestionRun, IngestionRunDetail,
  KnowledgeDocument, KnowledgeSource, ReviewRow, SourcePreview,
} from '../types'
import { useShell } from '../shell'
import ModuleTabs from './ModuleTabs.vue'
import GenerateHomePanel from './GenerateHomePanel.vue'
import DataCenterPanel from './DataCenterPanel.vue'
import ConnectorPanel from './ConnectorPanel.vue'
import ConnectorKnowledgePanel from './ConnectorKnowledgePanel.vue'
import ReviewPanel from './ReviewPanel.vue'
import PanelState from './PanelState.vue'
import GenerationConfigPanel from './GenerationConfigPanel.vue'
import DerivationPanel from './DerivationPanel.vue'
import KnowledgeLedgerPanel from './KnowledgeLedgerPanel.vue'

const props = defineProps<{
  active: string
  knowledgeBaseId: number
  pendingReviews: ReviewRow[]
  conflicts: ConflictRow[]
  assessments: AssessmentRow[]
  adjudicating: boolean
  /** 知识库重新生成进行中：结果由外壳提示，这里只负责按钮的忙碌态。 */
  regenerating?: boolean
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
const badges = computed<Record<string, number | string>>(() => ({
  'create.judgment': props.pendingReviews.length + props.conflicts.length || '',
}))
</script>
