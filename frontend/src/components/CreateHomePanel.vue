<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">KNOWLEDGE CREATION</div>
        <h1 class="kb-title mt-1">知识创建</h1>
        <p class="kb-muted mt-1">
          拖入资料即可走完「解析 → 抽取 → 索引」：右侧实时跟踪运行日志，按流水线逐段核对知识产出。
        </p>
      </div>
      <div class="flex shrink-0 gap-2">
        <button class="kb-btn-outline" @click="$emit('navigate','create.sources')">连接器</button>
        <button class="kb-btn-outline" @click="selectStep('documents')">文档中心</button>
      </div>
    </header>

    <!-- §4 知识生产流水线：按步骤推进，任一环节都能在下方看到当前产物 -->
    <nav class="kb-pipeline mt-4" aria-label="知识生产流水线">
      <template v-for="(step,i) in steps" :key="step.key">
        <button
          class="kb-step"
          :class="{active:current===step.key,planned:step.planned}"
          :title="step.planned?'规划中：该能力尚未实现':step.desc"
          @click="selectStep(step.key)">
          <span class="kb-step-index">{{ i+1 }}</span>
          <span class="kb-step-copy">
            <b>{{ step.icon }} {{ step.label }}</b>
            <small>{{ step.desc }}</small>
          </span>
          <em v-if="stepBadge(step)" class="kb-step-badge">{{ stepBadge(step) }}</em>
          <em v-else-if="step.planned" class="kb-step-planned">规划</em>
        </button>
        <span v-if="i<steps.length-1" class="kb-step-arrow" aria-hidden="true">→</span>
      </template>
    </nav>

    <!-- §4.1 拖拽上传 + 运行日志：知识创建的主操作区，与文档中心同一套区块语言 -->
    <div class="ingest-layout ingest-layout-compact mt-4">
      <!-- 左侧只保留拖拽与实时进度：高度压到最小，设置与操作移到右列底部。 -->
      <article class="kb-card p-3">
        <div class="kb-card-head">
          <b class="text-sm">拖入资料</b>
          <span class="kb-muted">Source 保存后即入摄入流水线</span>
        </div>
        <el-upload
          ref="uploadRef"
          class="upload-compact"
          drag
          :auto-upload="false"
          :limit="1"
          :on-change="onPick"
          :on-remove="onRemove"
          :on-exceed="onExceed">
          <div class="upload-icon">📥</div>
          <b>拖拽或选择资料</b>
          <small>PDF / DOCX / Markdown / HTML / TXT</small>
        </el-upload>
        <div v-if="pickedName" class="picked-file">
          <span>已选择：{{ pickedName }}</span>
          <el-button link type="primary" :disabled="uploading" @click="clearPicked">移除</el-button>
        </div>
        <div v-if="uploading" class="ingest-live">
          <span class="live-dot"></span>
          <span>
            <b>摄入中 · {{ stageText }}</b>
            <small>{{ ingestMessage || '等待后端写入阶段日志…' }}</small>
          </span>
        </div>
      </article>

      <!-- 右侧：运行日志固定高度且可拖动调整；权威分 / ACL 标签 / 上传按钮收在同一列底部。 -->
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">运行日志</b>
          <span class="kb-muted">{{ ingestLive ? `实时跟踪 · ${stageText}` : '历史运行可选中查看' }}</span>
        </div>
        <div class="p-3">
          <IngestionLogPanel
            :runs="runs"
            :detail="runDetail"
            :selected-id="selectedRunId"
            :live="ingestLive"
            :height="logHeight"
            @select="$emit('select-run',$event)" />
        </div>
        <div
          class="log-resize"
          title="拖动调整运行日志高度"
          role="separator"
          aria-orientation="horizontal"
          @mousedown="startResize">
          <span>⋯ 拖动调整高度 · {{ logHeight }}px</span>
        </div>
        <div class="ingest-actions">
          <div class="field">
            <label>来源权威分</label>
            <el-input-number
              size="small"
              controls-position="right"
              :model-value="authority" :min="0" :max="1" :step=".05"
              @update:model-value="updateAuthority" />
          </div>
          <div class="field grow">
            <label>来源 ACL 标签</label>
            <el-input
              size="small"
              :model-value="permissionTags"
              placeholder="如 team-a,ops；留空=公开"
              @update:model-value="$emit('update:permission-tags',String($event))" />
          </div>
          <el-button type="primary" size="small" :loading="uploading" :disabled="!pickedName" @click="$emit('upload')">
            上传并生成知识
          </el-button>
        </div>
      </article>
    </div>

    <!-- §4.2 流水线产出：当前步骤的产物就在这里查看，不再跳到其他页面 -->
    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">{{ currentTitle }}</b>
        <span class="kb-muted">{{ currentHint }}</span>
      </div>
      <div class="p-3">
        <!-- 已解析文档：状态机 + 重跑，完成后即可在工作台检索到 -->
        <template v-if="current==='documents'">
          <el-table :data="documents" max-height="360" size="small">
            <el-table-column prop="title" label="文档" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="documentStatusTag(row.status)">{{ documentStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="阶段" width="110">
              <template #default="{ row }">{{ stageLabel(row.currentStage) || '—' }}</template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="内容块" width="90" />
            <el-table-column label="重试" width="70">
              <template #default="{ row }">
                <span v-if="Number(row.retryCount||0)>0" class="retry-count">{{ row.retryCount }} 次</span>
                <span v-else class="retry-count muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="selectStep('create.chunk')">Chunk</el-button>
                <el-button
                  link type="warning" size="small"
                  :disabled="!isRetryableDocument(row)||retryingId===row.id"
                  :loading="retryingId===row.id"
                  @click="retryDocumentRow(row)">重跑</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <span class="text-[12px] text-ink-400">尚未解析任何文档：拖拽上方资料后即可生成</span>
            </template>
          </el-table>
          <p v-if="failedCount" class="doc-hint">
            有 {{ failedCount }} 个文档处于失败/失效状态，可用「重跑」从原始文件重新解析；旧 Chunk 版本保留为 SUPERSEDED，引用链不会断。
          </p>
        </template>

        <!-- Chunk：按文档查看切分结果，核对切分是否符合预期 -->
        <ChunkExplorerPanel v-else-if="current==='create.chunk'" :documents="documents" />
        <!-- 实体归一：同名实体合并与图谱回看 -->
        <EntityGovernancePanel
          v-else-if="current==='create.entity'"
          :knowledge-base-id="knowledgeBaseId"
          @refresh="$emit('refresh')" @open-graph="$emit('open-graph',$event)" />
        <!-- 待审核 / 冲突 / 裁决：三块共享同一组件，用 focus 决定主区块（设计 §4.3） -->
        <ReviewPanel
          v-else-if="reviewFocus"
          :focus="reviewFocus"
          :pending-reviews="pendingReviews"
          :conflicts="conflicts"
          :assessments="assessments"
          :adjudicating="adjudicating"
          @adjudicate="$emit('adjudicate')"
          @review="(row,decision)=>$emit('review',row,decision)"
          @resolve="(row,status)=>$emit('resolveConflict',row,status)" />
        <!-- 知识派生：IA 保留但当前版本未实现，不提供假数据页面 -->
        <PanelState
          v-else
          :empty="true"
          empty-text="知识派生：规划中"
          empty-hint="信息架构已保留该位置，能力落地后会在此开放" />
      </div>
    </article>
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { UploadFile, UploadInstance, UploadRawFile } from 'element-plus'
import type {
  AssessmentRow, ConflictRow, IngestionRun, IngestionRunDetail,
  KnowledgeDocument, ReviewRow,
} from '../types'
import { documentStatusLabel, documentStatusTag, isRetryableDocument, stageLabel } from '../types'
import { retryDocument, unwrap } from '../api/knowledge'
import { confirmAction, notifyError, notifySuccess } from '../utils/feedback'
import IngestionLogPanel from './IngestionLogPanel.vue'
import ChunkExplorerPanel from './ChunkExplorerPanel.vue'
import EntityGovernancePanel from './EntityGovernancePanel.vue'
import ReviewPanel from './ReviewPanel.vue'
import PanelState from './PanelState.vue'

const props = defineProps<{
  knowledgeBaseId:number
  pendingReviews:ReviewRow[]
  conflicts:ConflictRow[]
  assessments:AssessmentRow[]
  adjudicating:boolean
  /** 上传相关的状态由 App 持有：知识创建首页与文档中心共用同一条摄入链路。 */
  authority:number
  permissionTags:string
  documents:KnowledgeDocument[]
  runs:IngestionRun[]
  runDetail:IngestionRunDetail|null
  selectedRunId:number|null
  uploading:boolean
  ingestLive:boolean
  ingestStage:string
  ingestMessage:string
  /** 外部跳转进来的流水段：工作台治理入口与任务中心直接落到对应环节。 */
  step?:string
}>()
const emit = defineEmits<{
  navigate:[key:string]
  pick:[file:UploadFile]
  remove:[]
  upload:[]
  'update:authority':[value:number]
  'update:permission-tags':[value:string]
  'select-run':[id:number]
  refresh:[]
  'open-graph':[code:string]
  adjudicate:[]
  review:[row:ReviewRow,decision:string]
  resolveConflict:[row:ConflictRow,status:string]
  /** 重跑结束：文档与运行日志都已变化，交由父组件重新拉取。 */
  reloaded:[]
}>()

/**
 * 流水线：知识按「文档 → Chunk → 实体归一 → 待审核 → 冲突 → 裁决 → 派生」逐段产生，
 * 点击任一步骤即在下方查看该环节的产物，而不是离开当前页。
 */
type StepKey = 'documents'|'create.chunk'|'create.entity'|'create.review'|'create.conflict'|'create.judgment'|'create.derivation'
const STEPS: {key:StepKey;icon:string;label:string;desc:string;hint:string;planned?:boolean}[] = [
  { key:'documents', icon:'📄', label:'已解析文档', desc:'Source 解析后的文档与状态机',
    hint:'失败与索引失效可重跑' },
  { key:'create.chunk', icon:'▦', label:'Chunk', desc:'切分策略与 revision', hint:'Chunk 清单与版本' },
  { key:'create.entity', icon:'⬡', label:'实体归一', desc:'同名实体合并与图谱回看', hint:'合并后可回看图谱' },
  { key:'create.review', icon:'🕘', label:'待审核', desc:'等待人工确认的知识', hint:'待人工介入的知识' },
  { key:'create.conflict', icon:'⚠', label:'冲突', desc:'同一对象的多版本冲突', hint:'待处理的冲突' },
  { key:'create.judgment', icon:'⚖', label:'知识裁决', desc:'采用 / 拒绝的最终判定', hint:'冲突与待审核的裁决' },
  { key:'create.derivation', icon:'⑃', label:'知识派生', desc:'由基础断言推导衍生知识', hint:'规划中', planned:true },
]
const steps = computed(() => STEPS)
const current = ref<StepKey>(resolveStep(props.step))
/** step 由父组件的视图 key 给出：同一容器可承接多个入口，值无效时回到第一步。 */
function resolveStep(step?:string):StepKey{
  return (STEPS.find(v => v.key === step)?.key || 'documents') as StepKey
}
watch(() => props.step, step => { current.value = resolveStep(step) })
function selectStep(key:StepKey){current.value=key}
const currentStep = computed(() => STEPS.find(step => step.key === current.value) || STEPS[0])
const currentTitle = computed(() => currentStep.value.label)
const currentHint = computed(() => currentStep.value.hint)
/** 治理三步共享 ReviewPanel：裁决入口同时能处理待审核与冲突。 */
const reviewFocus = computed<'review'|'conflict'|'judgment'|''>(() =>
  current.value === 'create.review' ? 'review'
    : current.value === 'create.conflict' ? 'conflict'
      : current.value === 'create.judgment' ? 'judgment' : '')
/** 徽标只用于需要人工介入的环节：待审核与未解决冲突。 */
function stepBadge(step:{key:StepKey}):string|number{
  if(step.key==='create.review')return props.pendingReviews.length||''
  if(step.key==='create.conflict')return props.conflicts.length||''
  if(step.key==='create.judgment')return props.pendingReviews.length+props.conflicts.length||''
  return ''
}

const stageText = computed(() => stageLabel(props.ingestStage) || '准备中')

/** 运行日志固定高度：默认取紧凑值，拖动分隔条可在 140–640px 之间调整。 */
const LOG_MIN = 140, LOG_MAX = 640
const logHeight = ref(240)
function startResize(event:MouseEvent){
  const startY = event.clientY, startHeight = logHeight.value
  function onMove(e:MouseEvent){
    logHeight.value = Math.min(LOG_MAX, Math.max(LOG_MIN, startHeight + e.clientY - startY))
  }
  function onUp(){
    window.removeEventListener('mousemove',onMove)
    window.removeEventListener('mouseup',onUp)
  }
  window.addEventListener('mousemove',onMove)
  window.addEventListener('mouseup',onUp)
  event.preventDefault()
}
onUnmounted(() => { window.dispatchEvent(new Event('mouseup')) })

const uploadRef = ref<UploadInstance>(), pickedName = ref('')
function onPick(file:UploadFile){pickedName.value=file?.name||'';emit('pick',file)}
function onRemove(){pickedName.value='';emit('remove')}
/** 已选一个文件时再次拖拽/选择：替换旧文件，而不是被 limit=1 拦下。 */
function onExceed(files:File[]){
  const next = files[0]
  if (!next) return
  const raw = Object.assign(next,{uid:Date.now()}) as UploadRawFile
  uploadRef.value?.clearFiles()
  uploadRef.value?.handleStart(raw)
  pickedName.value = raw.name
  emit('pick',{name:raw.name,raw} as unknown as UploadFile)
}
function clearPicked(){uploadRef.value?.clearFiles();pickedName.value='';emit('remove')}
/** 摄入完成后由父组件复位上传区：清空 el-upload 内部列表，否则名额被占用无法再选下一个文件。 */
function resetUpload(){clearPicked()}
defineExpose({resetUpload})
function updateAuthority(value:number|undefined){emit('update:authority',value??.8)}

/** 文档状态机：失败/失效文档从原件重跑（设计 §16.1）。 */
const retryingId = ref<number|null>(null)
const failedCount = computed(() => props.documents.filter(v => isRetryableDocument(v)).length)
async function retryDocumentRow(row:KnowledgeDocument){
  try{
    await confirmAction({
      title:'重跑该文档？',
      message:`将从原始文件重新解析《${row.title}》，生成新的 Chunk revision；` +
        '旧 Chunk 保留为 SUPERSEDED，已有引用不会断。',
      confirmText:'重跑',
    })
  }catch{return}
  retryingId.value = row.id
  try{
    const d = unwrap(await retryDocument(row.id),{})
    const revision = d.chunkRevision ?? d.revision
    notifySuccess(
      `重跑完成：${Number(d.chunkCount||0)} 个 Chunk` +
      `${revision!==undefined?`，revision ${revision}`:''}`,
    )
    emit('reloaded')
  }catch(e){notifyError(e,'重跑失败')}
  finally{retryingId.value=null}
}
</script>
