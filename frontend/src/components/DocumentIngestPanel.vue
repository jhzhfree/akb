<template>
  <!-- 「拖入资料 → 已解析文档」是知识生成的主路径：生成概览与文档导入共用这一套区块，
       两处只差外壳（任务卡/聚焦文案），避免同一条摄入链路长出两份实现。 -->
  <div class="mt-4">
    <article class="kb-card p-3">
      <div class="kb-card-head">
        <b class="text-sm">拖入资料</b>
        <span class="kb-muted">支持多选 · 来源保存后即进入摄入流水线</span>
        <!-- 分块策略按知识库生效，覆盖这里上传的文档与连接器同步的数据，
             因此入口放在资料接入区（策略只影响保存之后的接入）。 -->
        <button class="kb-btn-outline kb-head-action" @click="policyOpen=true">分块策略</button>
      </div>
      <!-- 关闭内置文件列表：已选文件由下方清单统一呈现并可逐个移除，
           否则同一批文件会在拖拽区下方再叠一层，上传区就不再紧凑。 -->
      <el-upload
        ref="uploadRef"
        class="upload-compact"
        drag
        multiple
        :auto-upload="false"
        :show-file-list="false"
        :limit="batchLimit"
        :on-change="onPick"
        :on-remove="onRemove"
        :on-exceed="onExceed">
        <div class="upload-icon">📥</div>
        <b>拖拽或选择资料（支持多选，批量异步解析）</b>
        <small>PDF / DOCX / Markdown / HTML / TXT · 单次最多 {{ batchLimit }} 个</small>
      </el-upload>
      <div v-if="pickedFiles.length" class="picked-file mt-2">
        <div class="picked-head">
          <span>已选择 {{ pickedFiles.length }} 个文件 · 合计 {{ pickedSizeText }}</span>
          <el-button link type="primary" :disabled="uploading" @click="clearPicked">全部移除</el-button>
        </div>
        <div class="picked-list">
          <div v-for="name in pickedNames" :key="name" class="picked-row">
            <span class="picked-name" :title="name">{{ name }}</span>
            <el-button link type="danger" :disabled="uploading" @click="removeOne(name)">移除</el-button>
          </div>
        </div>
      </div>
      <div v-if="exceedHint" class="exceed-hint">{{ exceedHint }}</div>
      <!-- 来源属性与提交紧跟拖拽区：「选文件 → 定属性 → 提交」在同一块内完成。 -->
      <div class="ingest-actions inside mt-3">
        <div class="field">
          <label>来源权威分</label>
          <el-input-number
            size="small" controls-position="right"
            :model-value="authority" :min="0" :max="1" :step=".05"
            @update:model-value="updateAuthority" />
        </div>
        <div class="field grow">
          <label>来源 ACL 标签</label>
          <el-input
            size="small"
            :model-value="permissionTags" placeholder="如 team-a,ops；留空=公开"
            @update:model-value="emit('update:permission-tags',String($event))" />
        </div>
        <el-button
          type="primary" size="small" :loading="uploading"
          :disabled="!pickedFiles.length||batchRunning" @click="submitPicked">
          上传并生成知识{{ pickedFiles.length>1?`（${pickedFiles.length} 个文件）`:'' }}
        </el-button>
      </div>
      <div v-if="uploading" class="ingest-live mt-2">
        <span class="live-dot"></span>
        <span>
          <b>摄入中 · {{ stageText }}</b>
          <small>{{ ingestMessage || '等待后端写入阶段日志…' }}</small>
        </span>
        <el-button link type="primary" size="small" @click="openLog(null)">查看实时日志</el-button>
      </div>
    </article>

    <!-- 批量进度：多文件由外壳排队解析，这里按文件回显结果，失败可整批重跑。 -->
    <article v-if="batchStatus" class="kb-card mt-3 p-3">
      <div class="kb-card-head">
        <b class="text-sm">批量接入 · {{ batchStatus.progress }}%</b>
        <span class="kb-state" :class="tagStateClass(batchTagType)">{{ batchText }}</span>
      </div>
      <el-progress :percentage="batchStatus.progress" :status="batchProgressStatus" />
      <div class="batch-summary">
        <span>成功 {{ batchStatus.succeededCount }}</span>
        <span>失败 {{ batchStatus.failedCount }}</span>
        <span v-if="batchStatus.canceledCount">取消 {{ batchStatus.canceledCount }}</span>
        <span class="batch-code">批次 {{ batchStatus.batchCode }}</span>
      </div>
      <div class="batch-list">
        <div v-for="item in batchStatus.items" :key="item.index" class="batch-row">
          <span class="batch-dot" :class="'s-'+(item.status||'').toLowerCase()"></span>
          <span class="batch-name" :title="item.error||item.filename">{{ item.filename }}</span>
          <small v-if="item.status==='SUCCEEDED'">Chunk {{ item.chunkCount }} · 卡片 {{ item.cardCount }} · 断言 {{ item.claimCount }}</small>
          <small v-else-if="item.error" class="err">{{ item.error }}</small>
          <small v-else>{{ itemStatusText(item.status) }}</small>
        </div>
      </div>
      <div class="batch-actions">
        <el-button v-if="batchRunning" size="small" @click="emit('cancel-batch',batchStatus.batchCode)">
          取消剩余文件
        </el-button>
        <el-button v-else-if="batchStatus.failedCount" size="small" type="primary"
          @click="emit('retry-batch',batchStatus.batchCode)">
          重跑失败文件（{{ batchStatus.failedCount }}）
        </el-button>
      </div>
    </article>

    <!-- 产出区块：文档是默认产物，Chunk 与实体归一作为专业入口在同一区块内切换。 -->
    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">{{ currentTitle }}</b>
        <div class="flex items-center gap-2">
          <span class="kb-muted">{{ currentHint }}</span>
          <template v-if="showTools">
            <button class="kb-btn-outline" :class="{'kb-btn-primary':tool===''}" @click="tool=''">文档</button>
            <button class="kb-btn-outline" :class="{'kb-btn-primary':tool==='chunk'}" @click="tool='chunk'">文本分块</button>
            <button class="kb-btn-outline" :class="{'kb-btn-primary':tool==='entity'}" @click="tool='entity'">实体归一</button>
          </template>
        </div>
      </div>
      <div class="p-3">
        <template v-if="tool===''">
          <el-table :data="documents" max-height="360" size="small" @row-click="onDocumentClick">
            <el-table-column prop="title" label="文档" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <span class="kb-state" :class="tagStateClass(documentStatusTag(row.status))">
                  {{ documentStatusLabel(row.status) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="阶段" width="110">
              <template #default="{ row }">{{ stageLabel(row.currentStage) || '—' }}</template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="内容块" width="90" />
            <el-table-column label="重跑" width="70">
              <template #default="{ row }">
                <span v-if="Number(row.retryCount||0)>0" class="retry-count">{{ row.retryCount }} 次</span>
                <span v-else class="retry-count muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click.stop="viewChunks(row)">文本分块</el-button>
                <el-button
                  link type="warning" size="small"
                  :disabled="!isRetryableDocument(row)||retryingId===row.id"
                  :loading="retryingId===row.id"
                  @click.stop="retryDocumentRow(row)">重跑</el-button>
                <el-button link type="info" size="small" @click.stop="openLog(row)">运行日志</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <span class="empty-inline">尚未解析任何文档：拖入上方资料后即可生成</span>
            </template>
          </el-table>
          <p v-if="failedCount" class="doc-hint">
            有 {{ failedCount }} 个文档处于失败/失效状态，可用「重跑」从原始文件重新解析；旧 Chunk 版本保留为 SUPERSEDED，引用链不会断。
          </p>
        </template>
        <ChunkExplorerPanel
          v-else-if="tool==='chunk'"
          :knowledge-base-id="knowledgeBaseId" :documents="documents" :document-id="chunkDocId" />
        <EntityGovernancePanel
          v-else
          :knowledge-base-id="knowledgeBaseId"
          @refresh="emit('refresh')" @open-graph="emit('open-graph',$event)" />
      </div>
    </article>
  </div>

  <!-- 运行日志抽屉：日志不再单独占一块，从文档行进入，先看文档再看它这一次怎么跑完。 -->
  <el-drawer v-model="logOpen" class="log-drawer" size="46%" :title="logTitle" destroy-on-close>
    <div class="log-drawer-body">
      <p v-if="logDoc && !docRuns.length" class="log-drawer-tip">
        未匹配到《{{ logDoc.title }}》的摄入运行，下面列出该知识库的全部运行记录。
      </p>
      <IngestionLogPanel
        :runs="logRuns" :detail="runDetail" :selected-id="selectedRunId" :live="ingestLive"
        @select="emit('select-run',$event)" />
    </div>
  </el-drawer>

  <!-- 源文件预览：从「已解析文档」行发起，按 sourceId 回到原件，数据由外壳按 preview 事件拉取。 -->
  <el-dialog v-model="dialogVisible" :title="preview?.source?.name||'源文件预览'" width="760px" destroy-on-close>
    <PanelState
      :loading="!!previewLoading"
      :error="previewError||''"
      :empty="!preview&&!previewLoading&&!previewError"
      empty-text="尚未选择资料"
      empty-hint="点击「已解析文档」的任意一行，即可查看该文档的源文件原文" />
    <div v-if="preview" class="preview-body">
      <div class="preview-meta">
        <span class="kb-state kb-state-neutral">{{ preview.source?.sourceType||'未知格式' }}</span>
        <span class="kb-state" :class="preview.mode==='SOURCE'?'kb-state-ok':'kb-state-warn'">{{ modeLabel }}</span>
        <span v-if="preview.stored" class="preview-size">{{ sizeLabel }}</span>
        <a v-if="preview.stored&&preview.fileUrl" class="preview-link" :href="preview.fileUrl" target="_blank" rel="noopener">打开原文件 ↗</a>
      </div>
      <p v-if="preview.available===false" class="preview-note">{{ preview.reason }}</p>
      <pre v-else-if="preview.content" class="preview-text dialog-text">{{ preview.content }}</pre>
      <p v-else class="preview-note">该格式无法以文本预览，请打开原文件查看。</p>
      <p v-if="preview.truncated" class="preview-note">已截断显示前 {{ preview.limit }} 字，共 {{ preview.totalChars }} 字。</p>
    </div>
  </el-dialog>

  <!-- 分块策略设置层：由「拖入资料」右上角的按钮打开。
       策略按知识库保存，接入时下发给解析器，因此上传文档与连接器同步的数据都按它切分。 -->
  <ChunkPolicyDialog :visible="policyOpen" :knowledge-base-id="knowledgeBaseId" @close="policyOpen=false" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UploadFile, UploadInstance } from 'element-plus'
import type {
  BatchIngestStatus, IngestionRun, IngestionRunDetail, KnowledgeDocument, SourcePreview,
} from '../types'
import { documentStatusLabel, documentStatusTag, isRetryableDocument, stageLabel } from '../types'
import { retryDocument, unwrap } from '../api/knowledge'
import { confirmAction, notifyError, notifySuccess } from '../utils/feedback'
import IngestionLogPanel from './IngestionLogPanel.vue'
import ChunkExplorerPanel from './ChunkExplorerPanel.vue'
import EntityGovernancePanel from './EntityGovernancePanel.vue'
import ChunkPolicyDialog from './ChunkPolicyDialog.vue'
import PanelState from './PanelState.vue'

const props = defineProps<{
  knowledgeBaseId:number
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
  /** 批量接入批次状态：多文件上传后由外壳轮询，这里逐文件展示结果。 */
  batchStatus?:BatchIngestStatus|null
  /** 批次执行中：禁用提交按钮，避免同一批文件被提交两次。 */
  batchRunning?:boolean
  preview?:SourcePreview|null
  previewLoading?:boolean
  previewError?:string
  previewId?:number|null
  /** 产出区块是否提供「文本分块 / 实体归一」切换，默认开启。 */
  showTools?:boolean
}>()
const emit = defineEmits<{
  /** 批量提交：面板收集原始 File，外壳负责批次编排；单文件由外壳走同步路径以便实时跟踪。 */
  'upload-files':[files:File[]]
  remove:[]
  'cancel-batch':[batchCode:string]
  'retry-batch':[batchCode:string]
  'update:authority':[value:number]
  'update:permission-tags':[value:string]
  'select-run':[id:number]
  preview:[id:number]
  refresh:[]
  'open-graph':[code:string]
  /** 重跑结束：文档与运行日志都已变化，交由外壳重新拉取。 */
  reloaded:[]
}>()

/**
 * 产出区块：文档是默认视图，Chunk 与实体归一作为专业入口（设计 §1：
 * Chunk / 实体归一不再占用一级或二级菜单）。
 */
const tool = ref<''|'chunk'|'entity'>('')
const VIEWS:Record<''|'chunk'|'entity',{title:string;hint:string}> = {
  '': { title:'已解析文档', hint:'失败与索引失效可重跑' },
  chunk: { title:'Chunk', hint:'Chunk 清单与版本' },
  entity: { title:'实体归一', hint:'合并后可回看图谱' },
}
const showTools = computed(() => props.showTools !== false)
const currentTitle = computed(() => VIEWS[tool.value].title)
const currentHint = computed(() => VIEWS[tool.value].hint)
/** 从文档行进入 Chunk 视图时带上的文档：省掉进去之后再手动选一次。 */
const chunkDocId = ref<number|null>(null)
function viewChunks(row:KnowledgeDocument){
  chunkDocId.value = row?.id ?? null
  tool.value = 'chunk'
}
/** 分块策略设置层：资料接入区右上角的入口，属于接入前配置而非某个文档的属性。 */
const policyOpen = ref(false)

/* ---- 批量上传 ---- */
/** 单次批量上限：与后端 knowledge.batch.max-files 一致，先在此挡住，避免提交后才被拒绝。 */
const batchLimit = 50
/**
 * 选中的原始 File 由面板持有：多选的价值在于一次提交多个文件，
 * 外壳拿到完整清单才能走批次，而不是逐个串行等待。
 */
interface PickedEntry{
  /** 原始 File：提交时交给外壳，接收方只认字节，不认 el-upload 的包装。 */
  file:File; name:string; size:number
  /** el-upload 内部条目：按同名记录下来，移除时才有东西交给它的删除流程。 */
  entry:UploadFile
}
const uploadRef = ref<UploadInstance>()
const pickedFiles = ref<PickedEntry[]>([])
const exceedHint = ref('')
const pickedNames = computed(() => pickedFiles.value.map(item => item.name))
const pickedSizeText = computed(() => {
  const total = pickedFiles.value.reduce((sum,item) => sum + (item.size||0), 0)
  return total >= 1048576
    ? (total/1048576).toFixed(1)+' MB'
    : Math.max(1,Math.round(total/1024))+' KB'
})
function onPick(file:UploadFile){
  const raw = file?.raw as File|undefined
  if (!raw || !file) return
  if (pickedFiles.value.length >= batchLimit) {
    exceedHint.value = `单次最多 ${batchLimit} 个文件`
    return
  }
  if (pickedFiles.value.some(item => item.name===raw.name && item.size===raw.size)) return
  exceedHint.value = ''
  pickedFiles.value = [...pickedFiles.value,{file:raw,name:raw.name,size:raw.size,entry:file}]
}
/** el-upload 自己的删除也会走到这里：两边都从这里同步，清单不会和控件各说一套。 */
function onRemove(file:UploadFile){ removePicked(file?.raw?.name || file?.name) }
function removeOne(name:string){
  const picked = pickedFiles.value.find(item => item.name===name)
  // 走 el-upload 的删除流程：它会回调 onRemove 同步本组件清单，不必再删一次。
  if (picked) uploadRef.value?.handleRemove(picked.entry)
  else removePicked(name)
}
function removePicked(name?:string){
  if (!name) return
  pickedFiles.value = pickedFiles.value.filter(item => item.name!==name)
  if (!pickedFiles.value.length) emit('remove')
}
function onExceed(){ exceedHint.value = `单次最多 ${batchLimit} 个文件，超出部分未加入` }
function submitPicked(){
  if (!pickedFiles.value.length) return
  emit('upload-files',pickedFiles.value.map(item => item.file))
}
function clearPicked(){
  uploadRef.value?.clearFiles()
  pickedFiles.value = []
  exceedHint.value = ''
  emit('remove')
}
/** 摄入完成后由外壳复位上传区：清空 el-upload 内部列表，否则名额被占用无法再选下一批。 */
function resetUpload(){ clearPicked() }
defineExpose({resetUpload})
/** 面板已下沉到模块页面内部，外壳拿不到实例，改用令牌触发上传区复位。 */
watch(() => props.resetToken, value => { if (value) resetUpload() })
function updateAuthority(value:number|undefined){ emit('update:authority',value??.8) }

/* ---- 批量进度文案 ---- */
const BATCH_STATUS:Record<string,{text:string;tag:string;progress:string}> = {
  RUNNING:{text:'执行中',tag:'',progress:''},
  SUCCEEDED:{text:'全部成功',tag:'success',progress:'success'},
  PARTIAL:{text:'部分成功',tag:'warning',progress:'warning'},
  FAILED:{text:'全部失败',tag:'danger',progress:'exception'},
  CANCELED:{text:'已取消',tag:'info',progress:'warning'},
}
const ITEM_STATUS:Record<string,string> = {
  QUEUED:'排队中',RUNNING:'解析中',SUCCEEDED:'成功',FAILED:'失败',CANCELED:'已跳过',
}
function itemStatusText(status?:string){ return ITEM_STATUS[status||''] || status || '排队中' }
const batchKey = computed(() => props.batchStatus?.status || '')
const batchText = computed(() => BATCH_STATUS[batchKey.value]?.text || batchKey.value)
const batchTagType = computed(() => BATCH_STATUS[batchKey.value]?.tag || 'info')
const batchProgressStatus = computed(() => BATCH_STATUS[batchKey.value]?.progress || '')
/** 状态标签 -> 语义胶囊：success/warning/danger/info 在两个主题下都映射到同一套状态三件套。 */
function tagStateClass(tag?:string){
  return {success:'kb-state-ok',warning:'kb-state-warn',danger:'kb-state-danger',info:'kb-state-info'}[tag||'']
    || 'kb-state-neutral'
}
const stageText = computed(() => stageLabel(props.ingestStage) || '准备中')

/* ---- 源文件预览 ---- */
const dialogVisible = ref(false)
function openPreview(id:number){
  emit('preview',id)
  dialogVisible.value = true
}
/** 文档行按来源回到原件：预览的主体始终是 source，而不是派生出来的文档。 */
function onDocumentClick(row:KnowledgeDocument){
  if (row?.sourceId) openPreview(row.sourceId)
}
const modeLabel = computed(() => {
  if (props.preview?.mode==='SOURCE') return '原文'
  if (props.preview?.mode==='PARSED') return '解析文本'
  return '无可用文本'
})
const sizeLabel = computed(() => {
  const bytes = Number(props.preview?.sizeBytes||0)
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024*1024) return `${(bytes/1024).toFixed(1)} KB`
  return `${(bytes/1024/1024).toFixed(1)} MB`
})

/* ---- 运行日志抽屉 ----
 * 文档与运行之间没有外键，只能按名字匹配——去掉扩展名与空白后双向包含即可命中，
 * 重跑会产生多条同名运行，命中多条时取最近一次。 */
const logOpen = ref(false)
const logDoc = ref<KnowledgeDocument|null>(null)
function normalizeName(value?:string){
  return String(value||'').toLowerCase().replace(/\.(pdf|docx?|md|html?|txt)$/,'').replace(/\s+/g,'')
}
const docRuns = computed(() => {
  const target = normalizeName(logDoc.value?.title)
  if (!target) return []
  return props.runs
    .filter(run => {
      const source = normalizeName(run.sourceName)
      return source===target || source.includes(target) || target.includes(source)
    })
    .slice()
    .sort((a,b) => String(b.startedTime||'').localeCompare(String(a.startedTime||'')))
})
/** 该文档没有匹配到运行时不留空白，退回整库运行列表并说明原因。 */
const logRuns = computed(() => docRuns.value.length ? docRuns.value : props.runs)
const logTitle = computed(() => logDoc.value ? `运行日志 · ${logDoc.value.title}` : '运行日志')
/** row 为空表示从上传区的实时条进入，此时只看实时/最新运行，不做文档匹配。 */
function openLog(row:KnowledgeDocument|null){
  logDoc.value = row
  logOpen.value = true
  const latest = row ? docRuns.value[0] : null
  if (latest) emit('select-run',latest.id)
}

/* ---- 文档状态机：失败/失效文档从原件重跑（设计 §16.1） ---- */
const retryingId = ref<number|null>(null)
const failedCount = computed(() => props.documents.filter(v => isRetryableDocument(v)).length)
async function retryDocumentRow(row:KnowledgeDocument){
  // 重跑会重新生成 Chunk revision，是代价不低且影响引用版本的操作，必须二次确认。
  try{
    await confirmAction({
      title:'重跑该文档？',
      message:`将从原始文件重新解析《${row.title}》，生成新的 Chunk revision；`
        + '旧 Chunk 保留为 SUPERSEDED，已有引用不会断。',
      confirmText:'重跑',
    })
  }catch{ return }
  retryingId.value = row.id
  try{
    const d = unwrap(await retryDocument(row.id),{})
    // 后端重跑只返回 chunkRevision（没有裸 revision 字段）：revision 由服务端递增，旧的转 SUPERSEDED。
    const revision = d.chunkRevision
    notifySuccess(
      `重跑完成：${Number(d.chunkCount||0)} 个 Chunk`
      + `${revision!==undefined?`，revision ${revision}`:''}`,
    )
    emit('reloaded')
  }catch(e){ notifyError(e,'重跑失败') }
  finally{ retryingId.value = null }
}
</script>

<style scoped>
/* 卡片头部的操作按钮：无论标题与说明多长都贴右对齐，保持「右上角操作」的位置稳定。 */
.kb-head-action{margin-left:auto;flex:none}
/* 已选文件清单：拖拽区下方一行一个，超出滚动，避免占用过多纵向空间。 */
.picked-file{display:flex;
flex-direction:column;
gap:6px;
border:1px dashed var(--line-soft);
border-radius:8px;
padding:8px 10px}
.picked-head{display:flex;
justify-content:space-between;
align-items:center;
font-size:12px;
color:var(--ink-3)}
.picked-list{max-height:132px;
overflow:auto;
display:flex;
flex-direction:column;
gap:4px}
.picked-row{display:flex;
justify-content:space-between;
align-items:center;
gap:8px;
font-size:12px}
.picked-name{overflow:hidden;
text-overflow:ellipsis;
white-space:nowrap}
.exceed-hint{font-size:12px;
color:var(--warn)}
/* 批量进度：逐文件一行，状态用圆点，成功行补上 Chunk/卡片/断言计数。 */
.batch-summary{display:flex;
flex-wrap:wrap;
gap:10px;
font-size:12px;
color:var(--ink-3);
margin:8px 0}
.batch-code{color:var(--ink-3)}
.batch-list{max-height:220px;
overflow:auto;
display:flex;
flex-direction:column;
gap:6px}
.batch-row{display:flex;
align-items:center;
gap:8px;
font-size:12px}
.batch-dot{width:8px;
height:8px;
border-radius:50%;
background:var(--line-soft);
flex:none}
.batch-dot.s-succeeded{background:var(--ok)}
.batch-dot.s-running{background:var(--info)}
.batch-dot.s-failed{background:var(--danger)}
.batch-dot.s-canceled{background:var(--neutral-fg)}
.batch-name{flex:1;
overflow:hidden;
text-overflow:ellipsis;
white-space:nowrap}
.batch-row .err{color:var(--danger)}
.batch-actions{display:flex;
justify-content:flex-end;
margin-top:10px}
.empty-inline{color:var(--ink-3);
font-size:12px}
/* 预览弹窗：元数据一行 + 可滚动原文。 */
.preview-body{display:flex;
flex-direction:column;
gap:8px}
.preview-meta{display:flex;
align-items:center;
gap:8px;
flex-wrap:wrap}
.preview-size,.preview-link{font-size:12px}
.preview-size{color:var(--ink-3)}
.preview-link{color:var(--accent-text)}
.preview-text{margin:0;
max-height:420px;
overflow:auto;
padding:12px;
border:1px solid var(--line-d);
border-radius:10px;
background:var(--bg-sub);
font-size:12px;
line-height:1.75;
white-space:pre-wrap;
word-break:break-word}
.preview-text.dialog-text{max-height:56vh;
margin:0}
.preview-note{margin:0;
font-size:12px;
color:var(--ink-3)}
/* 运行日志抽屉：body 交给 flex，日志面板自己吃掉剩余高度并在内部滚动。 */
.log-drawer:deep(.el-drawer__body){padding:12px;display:flex;overflow:hidden}
.log-drawer-body{display:flex;flex:1;min-width:0;min-height:0;flex-direction:column;gap:8px}
.log-drawer-body:deep(.ingestion-log){flex:1;min-height:0}
.log-drawer-tip{margin:0;font-size:11.5px;color:var(--ink-3)}
</style>
