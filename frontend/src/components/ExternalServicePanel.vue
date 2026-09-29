<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">外部服务</div>
        <h1 class="kb-title mt-1">外部服务</h1>
        <p class="kb-muted mt-1">
          对外以 RESTful、MCP 服务、SSE 三种形态开放同一套知识能力：一个 API 密钥 决定可见的知识库范围与可用动作，
          三个通道共用一套限流与调用审计。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <!-- V2.2：服务与凭证在知识集成内是两个 Tab，同一面板按入口聚焦到对应区块。 -->
        <button class="kb-btn-outline" :class="{'kb-btn-primary':section==='services'}" @click="section='services'">服务</button>
        <button class="kb-btn-outline" :class="{'kb-btn-primary':section==='credentials'}" @click="section='credentials'">凭证</button>
        <button class="kb-btn-outline" :disabled="loading" @click="loadAll">刷新</button>
      </div>
    </header>

    <div v-if="section==='services'" class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="RESTful" :value="overview?.rest?.endpoints?.length ?? 0" hint="对外接口数" />
      <MetricCard label="MCP 工具" :value="overview?.mcp?.tools?.length ?? 0" hint="智能体可用工具" />
      <MetricCard label="SSE" :value="overview?.sse?.endpoints?.length ?? 0" hint="推送通道" />
      <MetricCard label="在用凭证" :value="overview?.activeKeys ?? 0" hint="有效状态的 API 密钥" />
    </div>

    <div v-if="error" class="mt-3 rounded-lg border border-[color:var(--warn-bd)] bg-[color:var(--warn-bg)] px-4 py-2.5 text-xs text-[color:var(--warn)]">
      {{ error }}
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">REST 接口</b>
          <el-tag :type="overview?.rest?.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ overview?.rest?.enabled ? '已开放' : '已关闭' }}
          </el-tag>
        </div>
        <div class="p-3">
          <div class="kb-muted">基础路径 <b class="text-ink-700">{{ overview?.rest?.basePath || '—' }}</b></div>
          <div class="kb-muted mt-1">鉴权 {{ overview?.rest?.auth || '—' }}</div>
          <div class="mt-3 max-h-64 overflow-auto">
            <div v-for="item in overview?.rest?.endpoints || []" :key="item.method + item.path"
              class="flex items-start gap-2 border-b border-ink-100 py-1.5 last:border-0">
              <span class="w-12 shrink-0 text-[11px] font-semibold text-accent">{{ item.method }}</span>
              <span class="min-w-0 flex-1">
                <span class="block truncate text-[11px] text-ink-700">{{ item.path }}</span>
                <span class="block text-[11px] text-ink-500">{{ item.summary }}</span>
              </span>
            </div>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">MCP 服务</b>
          <el-tag :type="overview?.mcp?.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ overview?.mcp?.enabled ? '已开放' : '已关闭' }}
          </el-tag>
        </div>
        <div class="p-3">
          <div class="kb-muted">端点 <b class="text-ink-700">{{ overview?.mcp?.endpoint || '—' }}</b></div>
          <div class="kb-muted mt-1">协议版本 {{ overview?.mcp?.protocolVersion || '—' }}</div>
          <div class="mt-2 flex flex-wrap gap-1">
            <span v-for="method in overview?.mcp?.methods || []" :key="method"
              class="rounded border border-ink-200 bg-ink-50 px-2 py-0.5 text-[11px] text-ink-600">{{ method }}</span>
          </div>
          <div class="mt-3 kb-muted">可用工具</div>
          <div class="mt-1 flex flex-wrap gap-1">
            <span v-for="tool in overview?.mcp?.tools || []" :key="tool"
              class="rounded border border-[color:var(--info-bd)] bg-[color:var(--info-bg)] px-2 py-0.5 text-[11px] text-accent">{{ tool }}</span>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">SSE 推送</b>
          <el-tag :type="overview?.sse?.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ overview?.sse?.enabled ? '已开放' : '已关闭' }}
          </el-tag>
        </div>
        <div class="p-3">
          <div class="kb-muted">心跳 {{ overview?.sse?.heartbeatSeconds ?? '—' }} 秒 · 单次最长 {{ overview?.sse?.maxMinutes ?? '—' }} 分钟</div>
          <div class="mt-3">
            <div v-for="item in overview?.sse?.endpoints || []" :key="item.path"
              class="border-b border-ink-100 py-1.5 last:border-0">
              <span class="block truncate text-[11px] text-ink-700">{{ item.path }}</span>
              <span class="block text-[11px] text-ink-500">{{ item.summary }}</span>
            </div>
          </div>
        </div>
      </article>
    </div>

    <article v-if="section==='credentials'" class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">API 密钥凭证</b>
        <div class="flex items-center gap-2">
          <span class="kb-muted">明文只在签发时展示一次</span>
          <button class="kb-btn-primary" @click="openCreate">签发凭证</button>
        </div>
      </div>
      <div class="p-3">
        <PanelState :loading="loading" :empty="!keys.length" empty-text="尚未签发任何 API Key"
          empty-hint="签发后外部系统即可按范围调用知识服务" retryable @retry="loadAll">
          <el-table :data="keys" border size="small">
            <el-table-column prop="name" label="调用方" min-width="150" show-overflow-tooltip />
            <el-table-column label="密钥前缀" width="180">
              <template #default="{ row }"><span class="text-[11px]">{{ row.keyPrefix || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="知识库范围" min-width="150">
              <template #default="{ row }">
                {{ row.knowledgeBaseName || (row.knowledgeBaseId ? `知识库 #${row.knowledgeBaseId}` : '全部可见库') }}
              </template>
            </el-table-column>
            <el-table-column label="权限" width="180">
              <template #default="{ row }">
                <el-tag v-for="scope in scopeList(row)" :key="scope" size="small" effect="plain" class="mr-1">
                  {{ scopeLabel(scope) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="限流" width="120">
              <template #default="{ row }">{{ row.rateLimitPerMinute ?? '—' }} 次/分</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small" effect="plain">
                  {{ row.status === 'ACTIVE' ? '有效' : '已吊销' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="有效期" width="170">
              <template #default="{ row }">{{ row.expiresAt || '长期有效' }}</template>
            </el-table-column>
            <el-table-column label="最近调用" width="170">
              <template #default="{ row }">{{ row.lastUsedAt || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'ACTIVE'" link type="danger" size="small" @click="revoke(row)">吊销</el-button>
                <span v-else class="text-[11px] text-ink-500">已失效</span>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>

    <div v-if="section==='services'" class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
      <article class="kb-card">
        <div class="kb-card-head"><b class="text-sm">接入示例</b><span class="kb-muted">复制即可联调</span></div>
        <div class="p-3">
          <el-tabs v-model="exampleTab">
            <el-tab-pane label="RESTful" name="rest">
              <pre class="code-block">{{ restExample }}</pre>
            </el-tab-pane>
            <el-tab-pane label="MCP" name="mcp">
              <pre class="code-block">{{ mcpExample }}</pre>
            </el-tab-pane>
            <el-tab-pane label="SSE" name="sse">
              <pre class="code-block">{{ sseExample }}</pre>
            </el-tab-pane>
          </el-tabs>
          <div class="mt-3 border-t border-ink-100 pt-3">
            <div class="flex flex-wrap items-center gap-2">
              <input v-model="probeSecret" class="kb-input flex-1" placeholder="粘贴明文 API 密钥做连通性自检" />
              <button class="kb-btn-outline" :disabled="probing || !probeSecret.trim()" @click="probe">REST 自检</button>
              <button class="kb-btn-outline" :disabled="probing || !probeSecret.trim()" @click="probeSse">SSE 自检</button>
            </div>
            <p v-if="probeResult" class="mt-2 text-[11px]" :class="probeOk ? 'text-[color:var(--ok)]' : 'text-[color:var(--danger)]'">
              {{ probeResult }}
            </p>
            <p class="kb-muted mt-2">
              自检请求不带控制台会话，与外部调用方完全一致：可验证凭证是否有效、知识库范围是否生效、配额是否被限流。
            </p>
          </div>
        </div>
      </article>

      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">调用审计</b>
          <div class="flex items-center gap-2">
            <select v-model.number="auditLimit" class="kb-input w-28" @change="loadAudits">
              <option v-for="size in [20, 50, 100]" :key="size" :value="size">{{ size }} 条</option>
            </select>
            <button class="kb-btn-outline" :disabled="auditLoading" @click="loadAudits">刷新</button>
          </div>
        </div>
        <div class="p-3">
          <PanelState :loading="auditLoading" :empty="!audits.length" empty-text="暂无对外调用记录"
            empty-hint="外部系统发起调用后会在此留痕" retryable @retry="loadAudits">
            <el-table :data="audits" border size="small" height="320">
              <el-table-column label="时间" width="170">
                <template #default="{ row }">{{ row.createdTime || '—' }}</template>
              </el-table-column>
              <el-table-column label="通道" width="90">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain" :type="channelType(row.channel)">{{ row.channel }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="method" label="方法 / 工具" min-width="180" show-overflow-tooltip />
              <el-table-column label="调用方" width="140" show-overflow-tooltip>
                <template #default="{ row }">{{ row.callerName || row.keyCode || '—' }}</template>
              </el-table-column>
              <el-table-column label="知识库" width="100">
                <template #default="{ row }">{{ row.knowledgeBaseId || '—' }}</template>
              </el-table-column>
              <el-table-column label="结果" width="90">
                <template #default="{ row }">
                  <span :class="row.statusCode >= 400 ? 'text-[color:var(--danger)]' : 'text-[color:var(--ok)]'">{{ row.statusCode }}</span>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="90">
                <template #default="{ row }">{{ row.durationMs }} ms</template>
              </el-table-column>
              <el-table-column label="Trace" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.traceId || '—' }}</template>
              </el-table-column>
            </el-table>
          </PanelState>
        </div>
      </article>
    </div>

    <el-dialog v-model="createVisible" title="签发 API 密钥" width="480px">
      <el-form label-width="96px" size="small">
        <el-form-item label="调用方名称">
          <el-input v-model="form.name" placeholder="如：ERP 问答助手" />
        </el-form-item>
        <el-form-item label="知识库范围">
          <el-select v-model="form.knowledgeBaseId" clearable class="w-full" placeholder="不绑定=仅可见公开库">
            <el-option v-for="base in bases" :key="base.id" :value="base.id" :label="base.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限">
          <el-select v-model="form.scopes" multiple class="w-full">
            <el-option value="READ" label="读取（检索 / 问答）" />
            <el-option value="WRITE" label="写入（知识治理）" />
            <el-option value="INGEST" label="接入（文本摄入）" />
          </el-select>
        </el-form-item>
        <el-form-item label="限流">
          <el-input-number v-model="form.rateLimitPerMinute" :min="1" :max="6000" />
          <span class="ml-2 text-[11px] text-ink-500">次 / 分钟</span>
        </el-form-item>
        <el-form-item label="有效期">
          <el-input v-model="form.expiresAt" placeholder="留空长期有效，如 2026-12-31T23:59:59" />
        </el-form-item>
      </el-form>
      <p class="kb-muted">
        绑定知识库后，凭证只能访问该库；不绑定时仅能看到公开知识库。
      </p>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="saveKey">签发</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretVisible" title="请立即保存明文密钥" width="520px">
      <p class="kb-muted">明文密钥只会展示这一次，关闭后无法再次获取，只能重新签发。</p>
      <pre class="code-block mt-2">{{ issuedSecret }}</pre>
      <template #footer>
        <el-button size="small" @click="copySecret">复制</el-button>
        <el-button size="small" type="primary" @click="secretVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ExternalAuditRow, ExternalKeyRow, ExternalServiceOverview, KnowledgeBase } from '../types'
import {
  createExternalKey, getExternalServices, listExternalAudits, listExternalKeys,
  probeExternal, revokeExternalKey,
} from '../api/external'
import { unwrap } from '../api/knowledge'
import { errorMessage as message } from '../utils/feedback'
import MetricCard from './MetricCard.vue'
import PanelState from './PanelState.vue'

/** focus 由知识集成的 Tab 决定：服务 Tab 看协议与接入示例，凭证 Tab 看 API Key 与调用审计。 */
const props = defineProps<{ bases:KnowledgeBase[]; focus?:'services'|'credentials' }>()
const section = ref<'services'|'credentials'>(props.focus === 'credentials' ? 'credentials' : 'services')
watch(() => props.focus, value => {
  section.value = value === 'credentials' ? 'credentials' : 'services'
})

const overview = ref<ExternalServiceOverview | null>(null)
const keys = ref<ExternalKeyRow[]>([])
const audits = ref<ExternalAuditRow[]>([])
const loading = ref(false)
const auditLoading = ref(false)
const saving = ref(false)
const error = ref('')
const auditLimit = ref(50)

const createVisible = ref(false)
const secretVisible = ref(false)
const issuedSecret = ref('')
const form = ref<{
  name:string; knowledgeBaseId:number | null; scopes:string[]
  rateLimitPerMinute:number; expiresAt:string
}>({ name:'', knowledgeBaseId:null, scopes:['READ'], rateLimitPerMinute:60, expiresAt:'' })

const exampleTab = ref<'rest' | 'mcp' | 'sse'>('rest')
const probeSecret = ref('')
const probing = ref(false)
const probeResult = ref('')
const probeOk = ref(false)

const restExample = computed(() => `curl -X POST '${origin()}/api/v1/external/query' \\
  -H 'Content-Type: application/json' \\
  -H 'X-API-Key: <YOUR_API_KEY>' \\
  -d '{"knowledgeBaseId":1,"question":"报销流程是什么？","topK":10}'`)
const mcpExample = computed(() => `{
  "mcpServers": {
    "knowledge-os": {
      "url": "${origin()}/api/mcp",
      "headers": { "X-API-Key": "<YOUR_API_KEY>" }
    }
  }
}
# tools: knowledge_query / knowledge_search / list_knowledge_bases ...`)
const sseExample = computed(() => `const es = new EventSource(
  '${origin()}/api/v1/external/stream/query?knowledgeBaseId=1'
  + '&question=' + encodeURIComponent('报销流程是什么？')
  + '&apiKey=' + encodeURIComponent('<YOUR_API_KEY>'))
es.addEventListener('stage', e => console.log('阶段', JSON.parse(e.data)))
es.addEventListener('delta', e => process.stdout.write(JSON.parse(e.data)))
es.addEventListener('citation', e => console.log('引用', JSON.parse(e.data)))
es.addEventListener('done', e => { console.log('完成', JSON.parse(e.data)); es.close() })`)

function origin() {
  return typeof window === 'undefined' ? '' : window.location.origin
}

function scopeList(row:ExternalKeyRow) {
  return String(row.scopes || 'READ').split(',').filter(Boolean)
}
function scopeLabel(scope:string) {
  return scope === 'READ' ? '读取（READ）' : scope === 'WRITE' ? '写入（WRITE）' : '接入（INGEST）'
}
function channelType(channel:string) {
  if (channel === 'MCP') return 'warning' as const
  if (channel === 'SSE') return 'success' as const
  return 'primary' as const
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    overview.value = unwrap(await getExternalServices(), null)
    keys.value = unwrap(await listExternalKeys(), [])
  } catch (e) {
    error.value = message(e)
  } finally {
    loading.value = false
  }
  await loadAudits()
}

async function loadAudits() {
  auditLoading.value = true
  try {
    audits.value = unwrap(await listExternalAudits(null, auditLimit.value), [])
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    auditLoading.value = false
  }
}

function openCreate() {
  form.value = {
    name:'', knowledgeBaseId:null, scopes:['READ'], rateLimitPerMinute:60, expiresAt:'',
  }
  createVisible.value = true
}

async function saveKey() {
  saving.value = true
  try {
    const created = unwrap(await createExternalKey({
      name:form.value.name,
      knowledgeBaseId:form.value.knowledgeBaseId,
      scopes:form.value.scopes,
      rateLimitPerMinute:form.value.rateLimitPerMinute,
      expiresAt:form.value.expiresAt.trim() || null,
    }), null)
    createVisible.value = false
    issuedSecret.value = String(created?.secret || '')
    secretVisible.value = true
    await loadAll()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    saving.value = false
  }
}

async function revoke(row:ExternalKeyRow) {
  try {
    await ElMessageBox.confirm(
      `吊销「${row.name}」后，使用该凭证的外部系统会立即失去访问能力，确认吊销？`,
      '吊销 API Key',
      { type:'warning', confirmButtonText:'确认吊销', cancelButtonText:'取消' },
    )
  } catch {
    return
  }
  try {
    unwrap(await revokeExternalKey(row.id), null)
    ElMessage.success('凭证已吊销')
    await loadAll()
  } catch (e) {
    ElMessage.error(message(e))
  }
}

async function copySecret() {
  try {
    await navigator.clipboard.writeText(issuedSecret.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器未授权剪贴板，请手动复制')
  }
}

/** 自检以外部调用方身份发起：凭证、范围、限流都能在真机上一并验证。 */
async function probe() {
  probing.value = true
  probeResult.value = '正在自检…'
  try {
    const response = await probeExternal(probeSecret.value.trim())
    const payload = response.data as { success?:boolean; message?:string; data?:unknown }
    probeOk.value = payload?.success !== false
    const count = Array.isArray(payload?.data) ? payload.data.length : 0
    probeResult.value = probeOk.value
      ? `凭证有效，可见知识库 ${count} 个`
      : `调用失败：${payload?.message || '未知错误'}`
  } catch (e) {
    probeOk.value = false
    probeResult.value = `调用失败：${message(e)}`
  } finally {
    probing.value = false
  }
}

/** SSE 自检只连 5 秒：确认通道、凭证与心跳后可立即关闭，避免长时间占用连接。 */
function probeSse() {
  const kb = props.bases[0]?.id
  if (!kb) {
    probeResult.value = '缺少知识库，无法自检 SSE'
    probeOk.value = false
    return
  }
  probing.value = true
  probeResult.value = '正在建立 SSE 连接…'
  const url = `/api/v1/external/stream/events?knowledgeBaseId=${kb}`
    + `&apiKey=${encodeURIComponent(probeSecret.value.trim())}`
  const source = new EventSource(url)
  let received = 0
  source.onopen = () => { probeOk.value = true; probeResult.value = 'SSE 连接已建立，正在接收事件…' }
  source.onmessage = () => { received += 1 }
  source.onerror = () => {
    probeOk.value = false
    probeResult.value = 'SSE 连接失败：请检查凭证与 SSE 通道开关'
    source.close()
    probing.value = false
  }
  window.setTimeout(() => {
    source.close()
    probing.value = false
    if (probeOk.value) probeResult.value = `SSE 通道正常，已收到 ${received} 条消息（含心跳注释）`
  }, 5000)
}



onMounted(loadAll)
</script>

<style scoped>
.code-block{white-space:pre-wrap;
word-break:break-all;
border:1px solid var(--line-d);
border-radius:10px;
background:var(--bg-sub);
padding:10px 12px;
font-size:11px;
line-height:1.6;
color:var(--ink-1);
max-height:280px;
overflow:auto}
.w-full{width:100%}
</style>
