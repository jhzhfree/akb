<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识生成</div>
        <h1 class="kb-title mt-1">生成概览</h1>
        <p class="kb-muted mt-1">
          从原始数据到可信知识资产：拖入资料即可走完「接入 → 解析 → 抽取 → 索引」，右侧实时跟踪运行日志。
        </p>
      </div>
      <div class="flex shrink-0 gap-2">
        <button class="kb-btn-primary" @click="$emit('navigate','create.import')">导入文档</button>
      </div>
    </header>

    <!-- §4：知识生成首页以四个任务卡为核心，其余能力都在 Tab 内，不再占左侧菜单。 -->
    <div class="gen-task-grid mt-4">
      <button
        v-for="task in tasks"
        :key="task.key"
        class="gen-task"
        :title="task.desc"
        @click="$emit('navigate',task.key)">
        <span class="gen-task-ic">{{ task.icon }}</span>
        <b>{{ task.label }}</b>
        <small>{{ task.desc }}</small>
        <em v-if="task.badge" class="gen-task-badge">{{ task.badge }}</em>
      </button>
    </div>

    <!-- §4 生成概览：四个数字说明「今天进来多少、正在跑多少、多少卡在人工环节」。 -->
    <div class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <div class="kb-metric"><span>今日导入</span><b>{{ todayRuns }}</b><small class="kb-muted">已接收的资料运行</small></div>
      <div class="kb-metric"><span>运行中</span><b>{{ runningRuns }}</b><small class="kb-muted">流水线未结束</small></div>
      <div class="kb-metric"><span>待裁决</span><b>{{ pendingTotal }}</b><small class="kb-muted">待审核 + 冲突</small></div>
      <div class="kb-metric"><span>候选衍生</span><b>—</b><small class="kb-muted">衍生能力规划中</small></div>
    </div>

    <!-- §4.1 拖拽上传 + 运行日志：知识生成的主操作区，与文档导入同一套区块语言 -->
    <div class="ingest-layout ingest-layout-compact mt-4">
      <!-- 左侧只保留拖拽与实时进度：高度压到最小，设置与操作移到右列底部。 -->
      <article class="kb-card p-3">
        <div class="kb-card-head">
          <b class="text-sm">拖入资料</b>
          <span class="kb-muted">来源保存后即进入摄入流水线</span>
        </div>
        <!-- 关闭内置文件列表：已选文件由下方 .picked-file 一行呈现并提供「移除」，
             避免同一文件在拖拽区下方再叠一层列表，让上传区保持紧凑。 -->
        <el-upload
          ref="uploadRef"
          class="upload-compact"
          drag
          :auto-upload="false"
          :limit="1"
          :show-file-list="false"
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
        <!-- 来源属性与上传操作紧跟拖拽区，同一块区域完成「选文件 → 定属性 → 提交」。 -->
        <div class="ingest-actions inside mt-3">
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
        <div v-if="uploading" class="ingest-live">
          <span class="live-dot"></span>
          <span>
            <b>摄入中 · {{ stageText }}</b>
            <small>{{ ingestMessage || '等待后端写入阶段日志…' }}</small>
          </span>
        </div>
      </article>

      <!-- 右侧：只放运行日志，内部再分「已上传文件列表」与「事件时间线」两栏各自滚动。 -->
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
      </article>
    </div>

    <!-- §4.2 生成产出：文档是默认产物，Chunk 与实体归一作为专业入口在同一区块内切换 -->
    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">{{ currentTitle }}</b>
        <div class="flex items-center gap-2">
          <span class="kb-muted">{{ currentHint }}</span>
          <button class="kb-btn-outline" :class="{'kb-btn-primary':tool===''}" @click="tool=''">文档</button>
          <button class="kb-btn-outline" :class="{'kb-btn-primary':tool==='chunk'}" @click="tool='chunk'">文本分块</button>
          <button class="kb-btn-outline" :class="{'kb-btn-primary':tool==='entity'}" @click="tool='entity'">实体归一</button>
        </div>
      </div>
      <div class="p-3">
        <!-- 已解析文档：状态机 + 重跑，完成后即可在工作台检索到 -->
        <template v-if="tool===''">
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
                <el-button link type="primary" size="small" @click="tool='chunk'">文本分块</el-button>
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
        <ChunkExplorerPanel
          v-else-if="tool==='chunk'"
          :knowledge-base-id="knowledgeBaseId" :documents="documents" />
        <!-- 实体归一：同名实体合并与图谱回看 -->
        <EntityGovernancePanel
          v-else
          :knowledge-base-id="knowledgeBaseId"
          @refresh="$emit('refresh')" @open-graph="$emit('open-graph',$event)" />
      </div>
    </article>
  </section>
</template>
<style scoped>
/* §4 任务入口卡：四个任务就是知识生成的全部入口，Tab 只承载工作区。 */
.gen-task-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.gen-task{
  position:relative;padding:14px 16px;text-align:left;background:#fff;
  border:1px solid #e2e9f2;border-radius:11px;box-shadow:0 2px 9px #1d35570a;cursor:pointer}
.gen-task:hover{border-color:#77aff8}
.gen-task.planned{cursor:not-allowed;opacity:.9}
.gen-task-ic{display:block;font-size:18px;line-height:1}
.gen-task b{display:block;margin-top:6px;font-size:14px;color:#164e8b}
.gen-task small{display:block;margin-top:3px;font-size:11.5px;color:#748399}
.gen-task-badge,.gen-task-planned{
  position:absolute;right:12px;top:12px;min-width:20px;padding:0 6px;
  border-radius:999px;font-size:11px;font-style:normal;text-align:center;line-height:20px}
.gen-task-badge{background:#fdeceb;color:#c0392b}
.gen-task-planned{background:var(--ink-100,#f1f4f8);color:#9aa4b5}
@media(max-width:1100px){.gen-task-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { UploadFile, UploadInstance, UploadRawFile } from 'element-plus'
import type {
  ConflictRow, IngestionRun, IngestionRunDetail,
  KnowledgeDocument, ReviewRow,
} from '../types'
import { documentStatusLabel, documentStatusTag, isRetryableDocument, stageLabel } from '../types'
import { retryDocument, unwrap } from '../api/knowledge'
import { confirmAction, notifyError, notifySuccess } from '../utils/feedback'
import { useShell } from '../shell'
import { PERM } from '../permissions'
import IngestionLogPanel from './IngestionLogPanel.vue'
import ChunkExplorerPanel from './ChunkExplorerPanel.vue'
import EntityGovernancePanel from './EntityGovernancePanel.vue'


const props = defineProps<{
  knowledgeBaseId:number
  pendingReviews:ReviewRow[]
  conflicts:ConflictRow[]
  /** 上传相关的状态由 App 持有：生成概览与文档导入共用同一条摄入链路。 */
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
  /** 上传复位令牌：一次摄入完成后由外壳自增，面板收到后清空已选文件。 */
  resetToken?:number
}>()
const shell = useShell()
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
  /** 重跑结束：文档与运行日志都已变化，交由父组件重新拉取。 */
  reloaded:[]
}>()

/**
 * 生成概览的产出区块：文档是默认视图，Chunk 与实体归一作为专业入口，
 * 与设计 §1「Chunk / 实体归一不再占用一级或二级菜单」一致。
 */
const tool = ref<''|'chunk'|'entity'>('')
const VIEWS: Record<''|'chunk'|'entity', { title:string; hint:string }> = {
  '': { title:'已解析文档', hint:'失败与索引失效可重跑' },
  chunk: { title:'Chunk', hint:'Chunk 清单与版本' },
  entity: { title:'实体归一', hint:'合并后可回看图谱' },
}
const currentTitle = computed(() => VIEWS[tool.value].title)
const currentHint = computed(() => VIEWS[tool.value].hint)
/** §4 四个任务入口：文档导入 / 连接器 / 知识裁决 / 知识衍生，替代原有的左侧二级菜单。 */
const tasks = computed(() => [
  { key:'create.import', icon:'📄', label:'文档导入', desc:'文件 / URL / 文本走完整条摄入流水线', badge:'',
    permission: PERM.GENERATE_IMPORT },
  { key:'create.connector', icon:'⇪', label:'连接器', desc:'外部系统的增量同步与字段权限映射', badge:'',
    permission: PERM.GENERATE_CONNECTOR },
  { key:'create.judgment', icon:'⚖', label:'知识裁决', desc:'冲突与待审核的采用 / 拒绝判定',
    badge: props.pendingReviews.length + props.conflicts.length || '', permission: PERM.GENERATE_JUDGE },
  { key:'create.derive', icon:'⑃', label:'知识衍生', desc:'由基础断言推导衍生知识', badge:'',
    permission: PERM.GENERATE_DERIVE },
].filter(task => shell.can(task.permission)))
/** 概览四数：只统计本次已加载的运行数据，缺失字段按 0 处理，不臆造。 */
const todayRuns = computed(() => {
  const today = new Date().toISOString().slice(0, 10)
  return props.runs.filter(v => String(v.startedTime || '').slice(0, 10) === today).length
})
const runningRuns = computed(() =>
  props.runs.filter(v => ['RUNNING', 'PENDING', 'QUEUED'].includes(String(v.status || '').toUpperCase())).length)
const pendingTotal = computed(() => props.pendingReviews.length + props.conflicts.length)

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
/** 面板已下沉到模块页面内部，外壳拿不到实例，改用令牌触发上传区复位。 */
watch(() => props.resetToken, value => { if (value) resetUpload() })
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
