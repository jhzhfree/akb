<template>
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">CONNECTOR</div>
        <h1 class="kb-title mt-1">连接数据源</h1>
        <p class="kb-muted mt-1">
          把 Jira、Confluence 或任意 RESTful 列表接口接进知识库：先连通、先看清读到了什么，再决定哪些进入知识库。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="kb-btn-outline" :disabled="loading" @click="loadAll">刷新</button>
        <button class="kb-btn-primary" :disabled="!canWrite" @click="openCreate">新建连接器</button>
      </div>
    </header>

    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="连接器" :value="connectors.length" hint="已保存的数据源" />
      <MetricCard label="已连通" :value="connectedCount" hint="最近一次测试通过" />
      <MetricCard label="本次读取" :value="items.length" hint="当前页条目数" />
      <MetricCard label="远端总数" :value="page?.total ?? '—'" hint="数据源自报的条目总数" />
    </div>

    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">已保存的连接器</b>
        <span class="kb-muted">密钥只在保存时写入，列表接口不返回</span>
      </div>
      <div class="p-3">
        <PanelState
          :loading="loading" :error="error" :empty="!connectors.length"
          empty-text="还没有连接任何数据源"
          empty-hint="点右上角「新建连接器」，填入 Jira / Confluence / REST 的地址与凭据"
          retryable @retry="loadAll">
          <el-table :data="connectors" border size="small" @row-click="onRowClick">
            <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
            <el-table-column label="类型" width="110">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column prop="baseUrl" label="站点地址" min-width="200" show-overflow-tooltip />
            <el-table-column label="认证" width="90">
              <template #default="{ row }">{{ authLabel(row.authType) }}</template>
            </el-table-column>
            <el-table-column label="上次测试" width="200">
              <template #default="{ row }">
                <span
                  v-if="row.lastTestStatus"
                  class="kb-state"
                  :class="row.lastTestStatus === 'OK' ? 'kb-state-ok' : 'kb-state-danger'"
                  :title="row.lastTestMessage || ''">
                  {{ row.lastTestStatus === 'OK' ? '已连通' : '失败' }}
                </span>
                <span v-else class="kb-muted">未测试</span>
                <span class="kb-muted ml-1">{{ formatTime(row.lastTestTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="自动同步" width="190">
              <template #default="{ row }">
                <template v-if="row.syncCron">
                  <span :title="row.lastError || '按 cron 定时同步目录'">{{ cronText(row.syncCron) }}</span>
                  <span class="kb-muted ml-1">{{ row.nextFireAt ? formatTime(row.nextFireAt) : '待触发' }}</span>
                  <span
                    v-if="row.syncStatus === 'FAILED'" class="kb-state kb-state-danger ml-1"
                    :title="row.lastError || ''">失败</span>
                </template>
                <span v-else class="kb-muted">仅手动</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :loading="testingId === row.id" @click.stop="test(row)">测试连接</el-button>
                <el-button link type="primary" size="small" @click.stop="selectConnector(row)">读取列表</el-button>
                <el-button link type="primary" size="small" :disabled="!canWrite" @click.stop="openEdit(row)">编辑</el-button>
                <el-button link type="danger" size="small" :disabled="!canWrite" @click.stop="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </PanelState>
      </div>
    </article>

    <article class="kb-card mt-4">
      <div class="kb-card-head">
        <b class="text-sm">
          知识列表
          <span v-if="page?.connector" class="kb-muted">· {{ page.connector.name }}（{{ typeLabel(page.connector.type) }}）</span>
        </b>
        <div class="flex items-center gap-2">
          <span class="kb-muted">{{ rangeText }}</span>
          <button class="kb-btn-outline" :disabled="!offset || itemsLoading" @click="prevPage">上一页</button>
          <button class="kb-btn-outline" :disabled="!hasMore || itemsLoading" @click="nextPage">下一页</button>
        </div>
      </div>
      <div class="p-3">
        <PanelState
          :loading="itemsLoading" :error="itemsError" :empty="!items.length"
          empty-text="还没有读取知识列表"
          empty-hint="在上方选中一个连接器，点「读取列表」或「测试连接」">
          <el-table :data="items" border size="small" max-height="420">
            <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column label="来源" min-width="180">
              <template #default="{ row }">
                <a
                  v-if="row.url" class="text-[color:var(--accent-text)] hover:underline" :href="row.url"
                  target="_blank" rel="noreferrer">{{ row.externalId || row.url }}</a>
                <span v-else class="kb-muted">{{ row.externalId || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="170">
              <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column prop="excerpt" label="摘要" min-width="260" show-overflow-tooltip />
          </el-table>
        </PanelState>
      </div>
    </article>

    <el-dialog v-model="formVisible" :title="editing ? '编辑连接器' : '新建连接器'" width="660px">
      <el-form label-width="120px" size="small">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如：运维 Jira / 产品 Confluence / 工单 API" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" class="w-full">
            <el-option label="Jira" value="JIRA" />
            <el-option label="Confluence" value="CONFLUENCE" />
            <el-option label="RESTful API" value="REST" />
          </el-select>
        </el-form-item>
        <el-form-item label="站点地址">
          <el-input v-model="form.baseUrl" placeholder="https://jira.example.com" />
        </el-form-item>
        <el-form-item v-if="form.baseUrl.trim().toLowerCase().startsWith('https://')">
          <el-checkbox v-model="form.config.insecureSsl">
            跳过 SSL 证书校验（自签名证书 / IP 直连场景）
          </el-checkbox>
          <div class="mt-1 text-[11px] text-ink-500">
            内网 Jira / Confluence 常用自签名证书，不开启会报「PKIX path building failed」而读不到任何内容。
          </div>
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="form.authType" class="w-full">
            <el-option label="无" value="NONE" />
            <el-option label="Basic（用户名 + 密码 / API Token）" value="BASIC" />
            <el-option label="Bearer（令牌）" value="BEARER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.authType === 'BASIC'" label="用户名">
          <el-input v-model="form.username" placeholder="Jira 用邮箱，Confluence Server 用登录名" />
        </el-form-item>
        <el-form-item v-if="form.authType !== 'NONE'" label="密钥 / 令牌">
          <el-input
            v-model="form.secret" type="password" show-password
            :placeholder="editing && form.secretConfigured ? '已保存，留空表示不修改' : 'API Token 或密码'" />
        </el-form-item>

        <el-form-item v-if="form.type === 'JIRA'" label="JQL">
          <el-input v-model="form.config.jql" placeholder="project = OPS AND updated >= -30d order by updated DESC" />
          <div v-if="jqlWarning" class="mt-1 text-[11px] text-amber-600">{{ jqlWarning }}</div>
        </el-form-item>
        <el-form-item v-if="form.type === 'CONFLUENCE'" label="空间 Key">
          <el-input v-model="form.config.spaceKey" placeholder="如 OPS；留空读取全站" />
        </el-form-item>

        <template v-if="form.type === 'REST'">
          <el-form-item label="列表路径">
            <el-input v-model="form.config.path" placeholder="/api/v1/docs" />
          </el-form-item>
          <el-form-item label="数组路径">
            <el-input v-model="form.config.itemsPath" placeholder="data.list；留空按 data / results / items 等自动识别" />
          </el-form-item>
          <el-form-item label="字段映射">
            <div class="grid grid-cols-2 gap-2">
              <el-input v-model="form.config.fields.externalId" placeholder="标识字段，如 id（可选）" />
              <el-input v-model="form.config.fields.title" placeholder="标题字段，如 title" />
              <el-input v-model="form.config.fields.url" placeholder="链接字段，如 url" />
              <el-input v-model="form.config.fields.updatedAt" placeholder="更新时间字段，如 updatedAt" />
              <el-input v-model="form.config.fields.excerpt" placeholder="正文字段，如 content" />
            </div>
          </el-form-item>
          <el-form-item label="分页方式">
            <div class="flex w-full items-center gap-2">
              <el-select v-model="form.config.pageMode" class="w-40">
                <el-option label="起始位置 offset" value="OFFSET" />
                <el-option label="页码 page" value="PAGE" />
              </el-select>
              <template v-if="form.config.pageMode === 'PAGE'">
                <el-input v-model="form.config.pageParam" class="flex-1" placeholder="页码参数名，默认 page" />
                <el-input v-model="form.config.sizeParam" class="flex-1" placeholder="每页参数名，默认 size" />
              </template>
              <template v-else>
                <el-input v-model="form.config.offsetParam" class="flex-1" placeholder="起始参数名，默认 offset" />
                <el-input v-model="form.config.limitParam" class="flex-1" placeholder="条数参数名，默认 limit" />
              </template>
            </div>
          </el-form-item>
          <el-form-item label="自定义请求头">
            <el-input v-model="headersText" type="textarea" :rows="2" placeholder='{"X-Tenant":"ops"}' />
          </el-form-item>
        </template>

        <!-- 同步设置：决定目录多久追一次源端，自动入库也跟着这个节奏跑。 -->
        <div class="my-3 flex items-center gap-2">
          <span class="h-px flex-1 bg-[color:var(--line-soft)]" />
          <span class="kb-muted text-[11px]">同步设置</span>
          <span class="h-px flex-1 bg-[color:var(--line-soft)]" />
        </div>
        <el-form-item label="自动同步周期">
          <div class="w-full">
            <el-input v-model="form.syncCron" placeholder="留空表示只手动同步" />
            <div class="kb-muted mt-1 text-[11px]">
              五段 cron（分 时 日 月 周）：{{ CRON_HINT }}；常用：<button
                class="text-[color:var(--accent-text)] hover:underline" @click="form.syncCron = '0 3 * * *'">每天 03:00</button>
              · <button class="text-[color:var(--accent-text)] hover:underline" @click="form.syncCron = '0 */6 * * *'">每 6 小时</button>
              · <button class="text-[color:var(--accent-text)] hover:underline" @click="form.syncCron = ''">仅手动</button>
            </div>
          </div>
        </el-form-item>

        <!-- 入库设置：决定外部条目以什么身份进入知识库，以及是否跟着同步自动进入。 -->
        <div class="my-3 flex items-center gap-2">
          <span class="h-px flex-1 bg-[color:var(--line-soft)]" />
          <span class="kb-muted text-[11px]">入库设置</span>
          <span class="h-px flex-1 bg-[color:var(--line-soft)]" />
        </div>
        <el-form-item label="入库模式">
          <el-select v-model="form.ingest.ingestMode" class="w-full">
            <el-option label="手动：同步只刷新目录，由人在列表里点入库" value="MANUAL" />
            <el-option label="自动：同步后按范围自动入库与重抽" value="AUTO" />
          </el-select>
        </el-form-item>
        <el-form-item label="每轮上限">
          <div class="flex w-full items-center gap-2">
            <el-input-number v-model="form.ingest.ingestQuota" :min="1" :max="200" size="small" />
            <span class="kb-muted text-[11px]">自动入库一次最多处理多少条（手动批量入库不受此限）</span>
          </div>
        </el-form-item>
        <el-form-item label="权威度">
          <div class="flex w-full items-center gap-2">
            <el-input-number
              v-model="form.ingest.authorityScore" :min="0.1" :max="1" :step="0.05" :precision="2" size="small" />
            <span class="kb-muted text-[11px]">外部内容默认 0.7，比导入文件低一档：冲突时以本地文件为准</span>
          </div>
        </el-form-item>
        <el-form-item label="入库类型范围">
          <el-input v-model="form.ingest.scopeTypes" placeholder="留空表示全部，如：缺陷、需求" />
        </el-form-item>
        <el-form-item label="入库属性条件">
          <el-input v-model="form.ingest.scopeAttributes" placeholder="留空表示不限，如：status=已完成" />
        </el-form-item>
        <el-form-item label="权限标签">
          <el-input v-model="form.ingest.permissionTags" placeholder="逗号分隔，如：ops,pm；留空表示沿用知识库默认可见性" />
        </el-form-item>
      </el-form>

      <div v-if="previewMessage" class="mt-1 flex items-start gap-2">
        <span class="kb-state" :class="previewOk ? 'kb-state-ok' : 'kb-state-danger'">{{ previewOk ? '试读成功' : '试读失败' }}</span>
        <span class="min-w-0 flex-1 text-xs leading-5 text-ink-500">{{ previewMessage }}</span>
      </div>

      <template #footer>
        <el-button size="small" @click="formVisible = false">取消</el-button>
        <el-button size="small" :loading="previewing" @click="runPreview">试读</el-button>
        <el-button size="small" type="primary" :loading="saving" :disabled="!canWrite" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type {
  ConnectorAuthType, ConnectorConfig, ConnectorItem, ConnectorItemPage, ConnectorPayload, ConnectorSource,
} from '../types'
import {
  connectorItems, createConnector, deleteConnector, listConnectors, previewConnector, testConnector, updateConnector,
} from '../api/knowledge'
import { unwrap } from '../api/knowledge'
import { PERM } from '../permissions'
import { useShell } from '../shell'
import { confirmAction, errorMessage, notifyError, notifySuccess } from '../utils/feedback'
import MetricCard from './MetricCard.vue'
import PanelState from './PanelState.vue'

const props = defineProps<{ knowledgeBaseId:number }>()

const PAGE_SIZE = 20
const CRON_HINT = '分 时 日 月 周'

const shell = useShell()
const canWrite = computed(() => shell.can(PERM.GENERATE_CONNECTOR))

const connectors = ref<ConnectorSource[]>([])
const loading = ref(false)
const error = ref('')
const testingId = ref<number|null>(null)

const selectedId = ref<number|null>(null)
const page = ref<ConnectorItemPage|null>(null)
const itemsLoading = ref(false)
const itemsError = ref('')
const offset = ref(0)

const formVisible = ref(false)
const editing = ref<ConnectorSource|null>(null)
const saving = ref(false)
const previewing = ref(false)
const previewMessage = ref('')
const previewOk = ref(false)
const headersText = ref('')
/** 表单内部一律是「填了就是空串」的确定形状：可选字段只存在于保存到后端的结构里。 */
interface ConnectorForm {
  name:string
  type:ConnectorSource['type']
  baseUrl:string
  authType:ConnectorAuthType
  username:string
  secret:string
  secretConfigured:boolean
  config:{
    jql:string
    spaceKey:string
    path:string
    itemsPath:string
    fields:Record<string, string>
    pageMode:'OFFSET' | 'PAGE'
    offsetParam:string
    limitParam:string
    pageParam:string
    sizeParam:string
    insecureSsl:boolean
  }
  /** 自动同步周期：与类型、凭据同级，后端单独成列存（调度器要按它查到点的连接器）。 */
  syncCron:string
  /** 入库设置单独放：它不随连接器类型变化，也不参与试读，混进 config 容易被当成类型专属字段清掉。 */
  ingest:{
    ingestMode:'MANUAL' | 'AUTO'
    ingestQuota:number
    authorityScore:number
    permissionTags:string
    /** 逗号分隔的类型白名单，留空表示范围不限。 */
    scopeTypes:string
    /** 逗号分隔的属性条件，形如 status=已完成。 */
    scopeAttributes:string
  }
}

const form = ref<ConnectorForm>(emptyForm())

const items = computed<ConnectorItem[]>(() => page.value?.items || [])
/** JQL 里出现在字符串外的 % 会被 Jira 直接拒绝（保留字符），保存前就提醒比等 400 省事。 */
const jqlWarning = computed(() => {
  const jql = form.value.config.jql || ''
  const outsideLiterals = jql.replace(/"[^"]*"/g, '')
  if (!outsideLiterals.includes('%')) return ''
  return 'JQL 中的 % 是 Jira 保留字符：请把关键词放进英文双引号内（如 summary ~ "%关键词%"），或写成 \\u0025'
})
const connectedCount = computed(() => connectors.value.filter(v => v.lastTestStatus === 'OK').length)
const rangeText = computed(() => {
  if (!items.value.length) return '—'
  return `第 ${offset.value + 1}–${offset.value + items.value.length} 条`
})
/** 远端不给总数时，用「这一页是否读满」判断是否还有下一页。 */
const hasMore = computed(() => {
  const total = page.value?.total
  if (total != null) return offset.value + items.value.length < total
  return items.value.length >= PAGE_SIZE
})

onMounted(loadAll)
watch(() => props.knowledgeBaseId, () => {
  selectedId.value = null
  page.value = null
  offset.value = 0
  loadAll()
})

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    connectors.value = unwrap(await listConnectors(props.knowledgeBaseId), [])
  } catch (e) {
    error.value = errorMessage(e, '连接器列表加载失败')
  } finally {
    loading.value = false
  }
}

/** 整行点击即读取该连接器的知识列表：列表页最常见的下一步就是这个。 */
function onRowClick(row:ConnectorSource) {
  selectConnector(row)
}

async function selectConnector(row:ConnectorSource) {
  selectedId.value = row.id
  offset.value = 0
  await loadItems()
}

async function loadItems() {
  if (selectedId.value == null) return
  itemsLoading.value = true
  itemsError.value = ''
  try {
    page.value = unwrap(await connectorItems(selectedId.value, PAGE_SIZE, offset.value), null)
  } catch (e) {
    // 读取失败不覆盖列表：连接器的地址、JQL、凭据错一个就会失败，用户要照着报错改配置。
    page.value = null
    itemsError.value = errorMessage(e, '读取知识列表失败')
  } finally {
    itemsLoading.value = false
  }
}

function prevPage() {
  if (!offset.value) return
  offset.value = Math.max(0, offset.value - PAGE_SIZE)
  loadItems()
}

function nextPage() {
  offset.value += PAGE_SIZE
  loadItems()
}

async function test(row:ConnectorSource) {
  testingId.value = row.id
  try {
    const result = unwrap(await testConnector(row.id), { ok:false, message:'' })
    if (result.ok) notifySuccess(result.message)
    else notifyError(result.message)
    await loadAll()
    if (selectedId.value === row.id) await loadItems()
  } catch (e) {
    notifyError(errorMessage(e, '测试连接失败'))
  } finally {
    testingId.value = null
  }
}

async function remove(row:ConnectorSource) {
  try {
    await confirmAction({ title:'删除连接器', message:`删除后需要重新填写地址与凭据：${row.name}`, confirmText:'删除', danger:true })
  } catch {
    return
  }
  try {
    await deleteConnector(row.id)
    notifySuccess('已删除连接器')
    if (selectedId.value === row.id) {
      selectedId.value = null
      page.value = null
    }
    await loadAll()
  } catch (e) {
    notifyError(errorMessage(e, '删除失败'))
  }
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  headersText.value = ''
  previewMessage.value = ''
  formVisible.value = true
}

function openEdit(row:ConnectorSource) {
  editing.value = row
  form.value = {
    name:row.name,
    type:row.type,
    baseUrl:row.baseUrl,
    authType:row.authType || 'NONE',
    username:row.username || '',
    secret:'',
    secretConfigured:Boolean(row.secretConfigured),
    syncCron:row.syncCron || '',
    config:{
      jql:row.config?.jql || '',
      spaceKey:row.config?.spaceKey || '',
      path:row.config?.path || '',
      itemsPath:row.config?.itemsPath || '',
      fields:{ ...(row.config?.fields || {}) },
      pageMode:row.config?.pageMode || 'OFFSET',
      offsetParam:row.config?.offsetParam || '',
      limitParam:row.config?.limitParam || '',
      pageParam:row.config?.pageParam || '',
      sizeParam:row.config?.sizeParam || '',
      insecureSsl:row.config?.insecureSsl === true,
    },
    ingest:{
      ingestMode:row.config?.ingestMode || 'MANUAL',
      ingestQuota:row.config?.ingestQuota ?? 20,
      authorityScore:row.config?.authorityScore ?? 0.7,
      permissionTags:row.config?.permissionTags || '',
      scopeTypes:(row.config?.ingestScope?.types || []).join('、'),
      scopeAttributes:(row.config?.ingestScope?.attributes || []).join('、'),
    },
  }
  headersText.value = row.config?.headers ? JSON.stringify(row.config.headers, null, 2) : ''
  previewMessage.value = ''
  formVisible.value = true
}

/** 未保存试读：填表单时先确认 JQL / 字段映射对不对，避免保存一堆读不出东西的配置。 */
async function runPreview() {
  previewing.value = true
  previewMessage.value = ''
  try {
    const payload = buildPayload()
    const result = unwrap(await previewConnector(payload), null)
    previewOk.value = true
    const titles = (result?.items || []).slice(0, 3).map(v => v.title).filter(Boolean)
    previewMessage.value = `读取到 ${result?.items?.length || 0} 条` + (titles.length ? `：${titles.join('、')}` : '')
  } catch (e) {
    previewOk.value = false
    previewMessage.value = errorMessage(e, '试读失败')
  } finally {
    previewing.value = false
  }
}

async function save() {
  if (!form.value.name.trim()) {
    notifyError('请填写连接器名称')
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    const saved = editing.value
      ? unwrap(await updateConnector(editing.value.id, payload), null)
      : unwrap(await createConnector(payload), null)
    notifySuccess(editing.value ? '已更新连接器' : '已保存连接器')
    formVisible.value = false
    await loadAll()
    if (saved?.id) await selectConnector(saved)
  } catch (e) {
    notifyError(errorMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

function buildPayload():ConnectorPayload {
  const config:ConnectorConfig = { ...form.value.config }
  applyIngestSettings(config)
  const fields = Object.fromEntries(
    Object.entries(config.fields || {}).filter(([, v]) => String(v || '').trim()),
  ) as Record<string, string>
  config.fields = fields
  // 只保留与当前类型相关的配置：JQL 留在 Jira 上只会让后来的人困惑。
  if (form.value.type !== 'JIRA') delete config.jql
  if (form.value.type !== 'CONFLUENCE') delete config.spaceKey
  if (form.value.type !== 'REST') {
    delete config.path
    delete config.itemsPath
    delete config.fields
    delete config.pageMode
    delete config.offsetParam
    delete config.limitParam
    delete config.pageParam
    delete config.sizeParam
    delete config.headers
  } else {
    const text = headersText.value.trim()
    if (text) {
      try {
        const parsed = JSON.parse(text) as Record<string, unknown>
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          config.headers = Object.fromEntries(
            Object.entries(parsed).map(([k, v]) => [k, String(v)]),
          ) as Record<string, string>
        }
      } catch {
        throw new Error('自定义请求头不是合法 JSON')
      }
    } else {
      delete config.headers
    }
    if (config.pageMode !== 'PAGE') {
      delete config.pageParam
      delete config.sizeParam
    } else {
      delete config.offsetParam
      delete config.limitParam
    }
  }
  for (const key of Object.keys(config)) {
    const value = config[key as keyof ConnectorConfig]
    if (value == null || value === '' || (typeof value === 'object' && !Object.keys(value as object).length)) {
      delete config[key as keyof ConnectorConfig]
    }
  }
  return {
    knowledgeBaseId:props.knowledgeBaseId,
    name:form.value.name.trim(),
    type:form.value.type,
    baseUrl:form.value.baseUrl.trim(),
    authType:form.value.authType,
    username:form.value.authType === 'BASIC' ? form.value.username.trim() : '',
    // 编辑时留空 = 沿用已保存的凭据，这是后端约定的语义。
    secret:form.value.authType === 'NONE' ? '' : form.value.secret,
    config,
    syncCron:form.value.syncCron.trim(),
  }
}

/** 周期原文太抽象，列表上尽量说人话；认不出来的就原样显示，不猜。 */
function cronText(cron:string):string {
  const parts = cron.trim().split(/\s+/)
  if (parts.length !== 5) return cron
  const [minute, hour, day, month, week] = parts
  const hh = (v:string) => String(v).padStart(2, '0')
  if (day === '*' && month === '*' && week === '*') {
    if (/^\d+$/.test(minute) && /^\d+$/.test(hour)) return `每天 ${hh(hour)}:${hh(minute)}`
    if (/^\d+$/.test(minute) && /^\*\/\d+$/.test(hour)) return `每 ${hour.slice(2)} 小时`
    if (/^\*\/\d+$/.test(minute) && hour === '*') return `每 ${minute.slice(2)} 分钟`
  }
  return cron
}

/** 入库设置写进 config：与 JQL、字段映射同层，后端读的是同一份 JSON。 */
function applyIngestSettings(config:ConnectorConfig) {
  const ingest = form.value.ingest
  config.ingestMode = ingest.ingestMode
  config.ingestQuota = ingest.ingestQuota
  config.authorityScore = ingest.authorityScore
  config.permissionTags = ingest.permissionTags.trim()
  const types = splitList(ingest.scopeTypes)
  const attributes = splitList(ingest.scopeAttributes)
  if (types.length || attributes.length) {
    config.ingestScope = {
      ...(types.length ? { types } : {}),
      ...(attributes.length ? { attributes } : {}),
    }
  } else {
    delete config.ingestScope
  }
}

/** 中英文顿号、逗号、分号都当分隔符：手填的范围不该因为一个标点失效。 */
function splitList(value:string):string[] {
  return value.split(/[、,，;；\n]/).map(v => v.trim()).filter(Boolean)
}

function emptyForm():ConnectorForm {
  return {
    name:'',
    type:'JIRA' as ConnectorSource['type'],
    baseUrl:'',
    authType:'NONE' as ConnectorAuthType,
    username:'',
    secret:'',
    secretConfigured:false,
    config:{
      jql:'',
      spaceKey:'',
      path:'',
      itemsPath:'',
      fields:{},
      pageMode:'OFFSET',
      offsetParam:'',
      limitParam:'',
      pageParam:'',
      sizeParam:'',
      insecureSsl:false,
    },
    ingest:{
      ingestMode:'MANUAL',
      ingestQuota:20,
      authorityScore:0.7,
      permissionTags:'',
      scopeTypes:'',
      scopeAttributes:'',
    },
    syncCron:'',
  }
}

function typeLabel(type?:string) {
  if (type === 'JIRA') return 'Jira'
  if (type === 'CONFLUENCE') return 'Confluence'
  if (type === 'REST') return 'RESTful API'
  return type || '—'
}

function authLabel(type?:string) {
  if (type === 'BASIC') return 'Basic'
  if (type === 'BEARER') return 'Bearer'
  return '无'
}

function formatTime(value?:string|null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12:false })
}
</script>
