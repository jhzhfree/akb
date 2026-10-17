<template>
  <div class="embed-panel">
    <PanelState v-if="!bases.length" title="暂无可用知识库" description="请先创建知识库，再生成嵌入组件代码。" />

    <template v-else>
      <div class="embed-grid">
        <section class="card">
          <h3>1. 选择知识库与外观</h3>
          <label>知识库</label>
          <el-select v-model="kbId" placeholder="选择知识库" style="width:100%">
            <el-option v-for="b in bases" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>

          <label>组件标题</label>
          <el-input v-model="title" placeholder="知识库助手" />

          <label>主题色</label>
          <div class="row">
            <el-color-picker v-model="primary" />
            <el-input v-model="primary" style="flex:1" />
          </div>

          <label>答案格式</label>
          <el-select v-model="answerFormat" style="width:100%">
            <el-option value="markdown" label="Markdown（推荐：渲染标题 / 列表 / 表格 / 代码块）" />
            <el-option value="html" label="HTML（服务端渲染，客户端白名单清洗）" />
            <el-option value="text" label="纯文本（等宽展示，不做渲染）" />
          </el-select>

          <label>示例问题（用 | 分隔，可留空）</label>
          <el-input v-model="samples" placeholder="这份制度适用于哪些部门？|最近一次变更了什么？" />

          <label>允许嵌入的站点来源</label>
          <el-input v-model="origin" placeholder="https://oa.corp.com" />
          <p class="hint">
            来源会写进令牌并校验：令牌被拿到别的站点使用会被拒绝。
            留空表示沿用当前站点（{{ currentOrigin }}）。
          </p>
        </section>

        <section class="card">
          <h3>2. 用 API Key 换取组件令牌</h3>
          <label>API Key（明文，仅在本次请求中使用，不会保存）</label>
          <el-input v-model="apiKey" type="password" show-password placeholder="kos_ak_..." />

          <label>令牌有效期</label>
          <el-select v-model="ttl" style="width:100%">
            <el-option v-for="o in ttlOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <p v-if="ttl === -1" class="hint">
            长期令牌不会自动过期：吊销或停用其 API Key 才会让它立即失效，请仅用于可信站点。
          </p>

          <div class="row" style="margin-top:10px">
            <el-button type="primary" :loading="issuing" :disabled="!apiKey || !kbId" @click="issue">
              生成令牌
            </el-button>
            <el-button v-if="token" @click="copy(token)">复制令牌</el-button>
          </div>

          <el-alert v-if="token" type="success" :closable="false" style="margin-top:12px">
            <template #title>
              {{ permanent ? '令牌已生成，长期有效' : `令牌已生成，${expiresText}后过期` }}
            </template>
            绑定知识库 {{ kbId }}，来源 {{ tokenOrigin || '未限制'
            }}<template v-if="tokenDisplayId">，令牌 ID {{ tokenDisplayId }}</template>
          </el-alert>
          <el-alert v-if="error" type="error" :closable="false" style="margin-top:12px">{{ error }}</el-alert>

          <p class="hint">
            生产环境请在业务系统服务端换取令牌后再下发给页面，不要把 API Key 放进浏览器。
            吊销 API Key 会让其签发的所有令牌立即失效；也可以在下方的生命周期面板里按令牌 ID 单独吊销。
          </p>
        </section>
      </div>

      <section class="card">
        <h3>3. 复制这段代码到目标站点</h3>
        <el-input v-model="snippet" type="textarea" :rows="9" readonly />
        <div class="row" style="margin-top:10px">
          <el-button type="primary" :disabled="!token" @click="copy(snippet)">复制代码</el-button>
          <el-button :disabled="!token" @click="openDemo">打开联调演示页</el-button>
        </div>
        <p class="hint">
          组件脚本由前端静态资源提供（<code>/embed/kos-chat.js</code>），
          样式隔离在 Shadow DOM 内，不会污染宿主页面；
          问答走 SSE，检索过程（改写 / 召回 / 融合 / 重排 / 生成 / 引用校验）实时展示。
        </p>
      </section>

      <EmbedTokenLifecyclePanel />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { KnowledgeBase } from '../types'
import { issueEmbedToken } from '../api/external'
import { unwrap } from '../api/knowledge'
import { errorMessage } from '../utils/feedback'
import PanelState from './PanelState.vue'
import EmbedTokenLifecyclePanel from './EmbedTokenLifecyclePanel.vue'

const props = defineProps<{ bases: KnowledgeBase[] }>()

const kbId = ref<number>(props.bases[0]?.id ?? 0)
const title = ref('知识库助手')
const primary = ref('#2f6bff')
const samples = ref('')
const answerFormat = ref('markdown')
const apiKey = ref('')
const origin = ref('')
const token = ref('')
const tokenOrigin = ref('')
const tokenDisplayId = ref('')
const permanent = ref(false)
const expiresText = ref('')

/** 有效期（秒）：>0 = 指定时长，-1 = 长期有效 */
const ttl = ref<number>(3600)
const ttlOptions = [
  { value: 3600, label: '1 小时' },
  { value: 21600, label: '6 小时' },
  { value: 86400, label: '24 小时' },
  { value: 604800, label: '7 天' },
  { value: 2592000, label: '30 天' },
  { value: -1, label: '长期有效' },
]
const issuing = ref(false)
const error = ref('')

const currentOrigin = window.location.origin
const scriptOrigin = computed(() => window.location.origin)
const apiBase = computed(() => `${scriptOrigin.value}/api/v1/external`)

const snippet = computed(() => {
  if (!token.value) {
    return '<!-- 先在上方生成嵌入令牌，代码会自动填充 -->'
  }
  const attrs = [
    `src="${scriptOrigin.value}/embed/kos-chat.js"`,
    `data-api-base="${apiBase.value}"`,
    `data-token="${token.value}"`,
    `data-kb="${kbId.value}"`,
    `data-title="${title.value}"`,
    `data-primary="${primary.value}"`,
    `data-answer-format="${answerFormat.value}"`,
  ]
  if (samples.value.trim()) {
    attrs.push(`data-samples="${samples.value.trim()}"`)
  }
  return `<script ${attrs.join('\n        ')}><\/script>`
})

async function issue() {
  error.value = ''
  issuing.value = true
  try {
    const data = unwrap(
      await issueEmbedToken(apiKey.value.trim(), kbId.value, origin.value.trim() || undefined, ttl.value || null),
      null,
    )
    if (!data?.token) {
      error.value = '未返回令牌，请检查 API Key 与来源白名单'
      return
    }
    token.value = data.token
    tokenOrigin.value = data.origin || ''
    tokenDisplayId.value = data.displayId || (data.jti ? `${data.jti.slice(0, 4)}…${data.jti.slice(-4)}` : '')
    permanent.value = !!(data.permanent || data.expiresAt === null || data.expiresAt === undefined)
    if (permanent.value) {
      expiresText.value = '长期有效'
      return
    }
    const seconds = data.expiresInSeconds ?? Math.max(0, Math.round(((data.expiresAt ?? 0) - Date.now()) / 1000))
    const hours = Math.round(seconds / 3600)
    expiresText.value = hours >= 1 ? `${hours} 小时` : `${Math.max(1, Math.round(seconds / 60))} 分钟`
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    issuing.value = false
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器拒绝了剪贴板访问，请手动复制')
  }
}

function openDemo() {
  const params = new URLSearchParams()
  params.set('apiBase', apiBase.value)
  params.set('kb', String(kbId.value))
  params.set('title', title.value.trim() || '知识库助手')
  if (primary.value.trim()) params.set('primary', primary.value.trim())
  if (samples.value.trim()) params.set('samples', samples.value.trim())
  params.set('format', answerFormat.value)
  if (token.value) params.set('token', token.value)
  if (apiKey.value.trim()) params.set('apiKey', apiKey.value.trim())
  params.set('ttl', String(ttl.value))
  // 参数放在 hash 里：不会随请求发送到服务器，也不会出现在 Referer 与服务端访问日志中
  window.open(`${scriptOrigin.value}/embed/demo.html#${params.toString()}`, '_blank')
}
</script>

<style scoped>
.embed-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 14px; }
.card { background: #fff; border-radius: 12px; padding: 16px 18px; box-shadow: 0 6px 18px rgba(15,23,42,.06); margin-bottom: 14px; }
.card h3 { margin: 0 0 12px; font-size: 15px; }
label { display: block; font-size: 12.5px; color: #64748b; margin: 10px 0 4px; }
.row { display: flex; align-items: center; gap: 10px; }
.hint { font-size: 12.5px; color: #94a3b8; line-height: 1.7; margin: 10px 0 0; }
code { background: #f1f5f9; padding: 1px 5px; border-radius: 4px; }
</style>
