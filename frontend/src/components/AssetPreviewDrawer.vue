<template>
  <el-drawer :model-value="visible" size="min(860px, 94vw)" :with-header="false" @close="$emit('close')">
    <div class="min-h-full bg-ink-50 p-5 md:p-7">
      <header class="mb-4 flex items-start justify-between gap-3">
        <div class="min-w-0">
          <div class="kb-eyebrow">内容预览 · {{ type === 'source' ? '来源' : '文档' }}</div>
          <h2 class="kb-title mt-1 break-words">{{ title }}</h2>
          <p class="kb-muted mt-1">编码 {{ code }}</p>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <button v-if="fileUrl" class="kb-btn-outline" :disabled="opening" @click="openFile">
            {{ opening ? '打开中…' : '打开原件' }}
          </button>
          <button class="kb-btn-outline" @click="$emit('close')">关闭</button>
        </div>
      </header>

      <!-- 原件走独立端点，失败原因单独说清，否则只看到按钮点了没反应。 -->
      <p v-if="fileError" class="mb-3 text-[12px] text-red-600">{{ fileError }}</p>

      <section class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">内容</b>
          <span class="kb-muted">{{ metaText }}</span>
        </div>
        <div class="p-3">
          <PanelState
            :loading="loading" :error="error" :empty="!hasContent"
            :empty-text="emptyText" :empty-hint="emptyHint" retryable @retry="load">
            <!-- 文档：按 Chunk 展示，能直接看到切分位置与页码，比整篇长文好定位。 -->
            <div v-if="chunks.length">
              <article
                v-for="(chunk,i) in chunks"
                :key="String(chunk.unitCode)"
                class="mb-2 rounded-lg border border-ink-100 bg-white p-3">
                <div class="mb-1 flex flex-wrap items-center gap-2 text-[11px] text-ink-500">
                  <span class="rounded bg-ink-50 px-1.5 py-0.5">#{{ i + 1 }}</span>
                  <span v-if="chunk.headingPath" class="max-w-[60%] truncate">{{ chunk.headingPath }}</span>
                  <span v-if="chunk.pageNo">P{{ chunk.pageNo }}<template v-if="chunk.pageEnd && chunk.pageEnd !== chunk.pageNo"> – P{{ chunk.pageEnd }}</template></span>
                  <span v-if="chunk.tokenCount">{{ chunk.tokenCount }} token</span>
                  <span v-if="chunk.contentLength">{{ chunk.contentLength }} 字</span>
                </div>
                <p class="whitespace-pre-wrap text-[13px] leading-6 text-ink-700">{{ chunk.excerpt || '（该块没有文本内容）' }}</p>
              </article>
              <p class="kb-muted mt-2">Chunk 列出的是每段开头的片段（{{ excerptLimit }} 字内），完整内容请「打开原件」。</p>
            </div>
            <!-- 来源：直接展示原文或解析文本。 -->
            <div v-else>
              <div class="max-h-[60vh] overflow-auto whitespace-pre-wrap break-words rounded-lg bg-ink-50 p-4 text-[13px] leading-7 text-ink-700">
                {{ preview?.content || '' }}
              </div>
              <p v-if="preview?.truncated" class="kb-muted mt-2">
                仅预览前 {{ preview?.limit }} 字（全文 {{ preview?.totalChars }} 字），完整内容请「打开原件」。
              </p>
              <p v-if="preview?.mode === 'PARSED'" class="kb-muted mt-1">
                原件是二进制文件（如 PDF / Word），这里显示的是解析后的文本。
              </p>
            </div>
          </PanelState>
        </div>
      </section>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import PanelState from './PanelState.vue'
import { getDocumentChunks, getSourcePreview, sourceFileUrl, unwrap } from '../api/knowledge'
import { getToken } from '../api/auth'
import type { AssetRow } from '../types/knowledge'
import type { DocumentChunk, SourcePreview } from '../types-legacy'

/**
 * 来源 / 文档的内容预览。
 *
 * 来源取源文件（能读到原件就用原件，读不到回退解析文本）；文档取自己的 Chunk 清单——
 * 文档要回答的是“这段内容被切成什么样”，整篇长文在这里反而不好定位。
 */
const props = defineProps<{
  visible:boolean
  knowledgeBaseId:number
  type:'source'|'document'
  row:AssetRow|null
}>()
defineEmits<{ close:[] }>()

const loading = ref(false)
const error = ref('')
/** 原件正在打开：按钮置灰，避免连点重复拉取整份文件。 */
const opening = ref(false)
const fileError = ref('')
const preview = ref<SourcePreview|null>(null)
const chunks = ref<DocumentChunk[]>([])
/** Chunk 片段的截断长度由后端 SQL 决定（LEFT(content,200)），这里只用于在提示里说清。 */
const excerptLimit = 200

const title = computed(() => String(props.row?.[props.type === 'source' ? 'name' : 'title']
  || props.row?.[props.type === 'source' ? 'sourceCode' : 'documentCode'] || '—'))
const code = computed(() => String(props.row?.[props.type === 'source' ? 'sourceCode' : 'documentCode'] || '—'))

const hasContent = computed(() => chunks.value.length > 0 || !!preview.value?.content)
const emptyText = computed(() => props.type === 'document' ? '该文档还没有切分结果' : '该来源没有可预览的内容')
const emptyHint = computed(() => props.type === 'document'
  ? '文档解析完成后才会生成 Chunk，可到「知识生成 · 文档导入」查看解析状态'
  : '原件未落盘，也没有解析出文本')

const metaText = computed(() => {
  if (chunks.value.length) return `共 ${chunks.value.length} 个 Chunk`
  if (!preview.value) return '—'
  const parts:string[] = []
  if (preview.value.previewType) parts.push(String(preview.value.previewType))
  if (preview.value.mode) parts.push(String(preview.value.mode))
  if (preview.value.totalChars) parts.push(`${preview.value.totalChars} 字`)
  if (preview.value.document?.mimeType) parts.push(String(preview.value.document.mimeType))
  return parts.join(' · ') || '—'
})

/** 原件地址：来源预览会直接带回；文档预览只有 Chunk 时按所属来源拼。 */
const fileUrl = computed(() => {
  if (preview.value?.fileUrl) return preview.value.fileUrl
  const sourceId = Number(props.row?.sourceId || 0)
  return sourceId > 0 ? sourceFileUrl(props.knowledgeBaseId, sourceId) : ''
})

/** 下载时的默认文件名：优先用来源名，实在没有就退回通用名。 */
const fileFallbackName = computed(() => String(props.row?.name || props.row?.title || 'source'))

/** 后端用 filename*=UTF-8'' 编码中文名，取不到再退回 filename="…"。 */
function fileNameFrom(header:string|null, fallback:string):string {
  if (!header) return fallback
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header)
  if (encoded) {
    try { return decodeURIComponent(encoded[1]) } catch { return encoded[1] }
  }
  const plain = /filename\s*=\s*"([^"]+)"/i.exec(header) || /filename\s*=\s*([^;]+)/i.exec(header)
  return plain ? plain[1].trim() : fallback
}

/**
 * 打开原件：文件端点和其它接口一样要带 Authorization 头，而 window.open 没法带请求头——
 * 直接开新标签会被判成未登录（KB-401-001）。所以先按带鉴权的请求把文件取成 blob，
 * 再用本地 URL 交给浏览器：能内联渲染的（PDF / 图片 / 文本）直接预览，
 * 浏览器渲染不了的（Word / Excel 等）走下载，开新标签只会得到一个空白页。
 */
async function openFile() {
  if (!fileUrl.value || opening.value) return
  opening.value = true
  fileError.value = ''
  // 手势内先开一个空标签：等 await 之后再调 window.open，浏览器会当成弹窗拦掉。
  const win = window.open('', '_blank')
  try { if (win) win.opener = null } catch { /* 少数浏览器不允许改写 opener，忽略 */ }
  try {
    const token = getToken()
    const res = await fetch(fileUrl.value, { headers: token ? { Authorization:`Bearer ${token}` } : {} })
    if (res.status === 401 || res.status === 403) {
      if (win) win.close()
      fileError.value = '登录已过期，请重新登录后再打开原件'
      return
    }
    if (!res.ok) {
      if (win) win.close()
      fileError.value = res.status === 404 ? '原件未落盘，无法打开' : `原件获取失败（HTTP ${res.status}）`
      return
    }
    const blob = await res.blob()
    const objectUrl = URL.createObjectURL(blob)
    const type = blob.type || ''
    const inline = type === 'application/pdf' || type.startsWith('image/') || type.startsWith('text/')
    if (inline && win) {
      win.location.href = objectUrl
    } else {
      // 渲染不了就下载：留着一个空白标签页只会让人以为打开失败了。
      if (win) win.close()
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = fileNameFrom(res.headers.get('Content-Disposition'), fileFallbackName.value)
      link.click()
    }
    // 立刻回收会让刚打开的标签页拿到已释放的 URL，留一段时间再释放。
    setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000)
  } catch (e) {
    // 取文件失败时把预先打开的空标签关掉，否则用户会看到一个空白页。
    if (win) win.close()
    fileError.value = e instanceof Error ? e.message : '原件打开失败'
  } finally {
    opening.value = false
  }
}

async function load() {
  if (!props.row) return
  loading.value = true
  error.value = ''
  preview.value = null
  chunks.value = []
  try {
    if (props.type === 'document') {
      const list = unwrap(await getDocumentChunks(Number(props.row.id)), [] as DocumentChunk[])
      chunks.value = list || []
      // 文档还没切分时退回来源原文：预览点开一片空白比看个大概更糟。
      if (!chunks.value.length) {
        preview.value = unwrap(await getSourcePreview(props.knowledgeBaseId, Number(props.row.sourceId || 0)), null)
      }
      return
    }
    preview.value = unwrap(await getSourcePreview(props.knowledgeBaseId, Number(props.row.id)), null)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '预览加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => [props.visible, props.row], () => {
  if (props.visible) load()
}, { immediate:true })
</script>
