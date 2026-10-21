<template>
  <!--
    设计 §7：知识结构定义 = 知识类型 / Entity / Relation / Ontology。
    类型字典由平台持有，可编辑、发布前校验、按版本快照留存，并持续比对存量数据是否使用了未登记类型。
  -->
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">系统配置</div>
        <h1 class="kb-title mt-1">知识 Schema</h1>
        <p class="kb-muted mt-1">
          知识类型、实体类型与关系类型的字典：知识库可继承平台默认并扩展自定义类型，发布前校验，发布后同步给抽取侧。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <el-button size="small" plain :disabled="loading" @click="load">刷新</el-button>
        <el-button size="small" type="primary" :loading="publishing" @click="publish">
          {{ publishing ? '发布中...' : '校验并发布' }}
        </el-button>
      </div>
    </header>

    <div class="kb-stats mt-4">
      <div class="kb-stat"><span>类型总数</span><b>{{ validation.typeCount || 0 }}</b></div>
      <div class="kb-stat"><span>本库自定义</span><b>{{ customCount }}</b></div>
      <div class="kb-stat"><span>校验问题</span><b>{{ (validation.errors || []).length }}</b></div>
      <div class="kb-stat"><span>未登记类型使用</span><b>{{ validation.violationCount || 0 }}</b></div>
      <div class="kb-stat"><span>当前版本</span><b>{{ published?.version || '未发布' }}</b></div>
    </div>

    <div v-if="error" class="kb-alert-error mt-3">{{ error }}</div>

    <!-- 切换样式与「知识资产」保持一致：一排圆角小按钮，选中态用 info 底色，条数跟在名称后。
         放在表格卡片**上方**独立成行，而不是塞进卡片头里——卡片头留给「当前分类 + 新增类型」，
         两者挤在一起会让切换看起来像卡片内的一个控件，而不是切换整张表内容的入口。 -->
    <div class="mt-4 flex flex-wrap gap-2">
      <button v-for="item in kinds" :key="item.kind" :title="item.hint"
        class="rounded-lg border px-3 py-1.5 text-[13px] transition"
        :class="kind===item.kind
          ? 'border-[color:var(--info-bd)] kb-state-info'
          : 'border-ink-200 bg-white text-ink-600 hover:border-[color:var(--info-bd)]'"
        @click="kind=item.kind">
        {{ item.label }}<em class="ml-1 not-italic tabular-nums opacity-70">{{ kindCount(item.kind) }}</em>
      </button>
    </div>

    <div class="mt-3 grid gap-3 lg:grid-cols-[1fr_320px]">
      <article class="kb-card">
        <div class="kb-card-head">
          <b class="text-sm">{{ currentKindLabel }}</b>
          <el-button size="small" plain @click="openType()">新增类型</el-button>
        </div>
        <div class="p-4">
          <div v-if="loading && !types.length" class="py-6 text-center text-sm text-ink-500">正在读取类型字典...</div>
          <div v-else-if="!visibleTypes.length" class="py-6 text-center text-sm text-ink-500">该分类下没有类型</div>
          <table v-else class="kb-table">
            <thead>
              <tr><th>标识</th><th>名称</th><th>说明</th><th>属性</th><th>来源</th><th>状态</th><th></th></tr>
            </thead>
            <tbody>
              <tr v-for="type in visibleTypes" :key="`${type.kind}-${type.typeCode}`">
                <td class="font-mono text-[11px]">{{ type.typeCode }}</td>
                <td>{{ type.label || type.typeCode }}</td>
                <td class="kb-muted text-[11px]">{{ type.description || '—' }}</td>
                <td>{{ propertyCount(type) }}</td>
                <td class="kb-muted text-[11px]">{{ type.builtIn ? '平台内置' : '本库自定义' }}</td>
                <td>
                  <span class="kb-badge" :class="type.status==='ACTIVE'?'kb-badge-emerald':'kb-badge-ink'">
                    {{ type.status==='ACTIVE' ? '启用' : '已弃用' }}
                  </span>
                </td>
                <!-- 按钮与「存储与索引 · 蓝绿索引版本」一致：size=small + plain，危险动作只靠 type 区分颜色 -->
                <td class="whitespace-nowrap text-right">
                  <el-button size="small" plain @click="openType(type)">编辑</el-button>
                  <el-button v-if="type.status==='ACTIVE'" size="small" type="warning" plain @click="deprecate(type)">弃用</el-button>
                  <el-button v-if="!type.builtIn" size="small" type="danger" plain @click="remove(type)">删除</el-button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <div class="space-y-3">
        <article class="kb-card p-4">
          <b class="text-sm">已发布版本</b>
          <div v-if="published?.published" class="mt-2 text-[12px]">
            <div class="kb-muted">版本 {{ published.version }} · {{ published.publishedTime || '-' }}</div>
            <div class="kb-muted mt-1 break-all">校验和 {{ (published.checksum || '').slice(0, 24) }}…</div>
            <div v-if="published.delivery && !published.delivery.delivered" class="mt-2 text-[11px] text-[color:var(--warn)]">
              已发布，但抽取侧同步未完成：{{ published.delivery.error || 'AI 服务不可用' }}（下次发布会重试）
            </div>
          </div>
          <div v-else class="mt-2 text-[12px] text-ink-500">尚未发布，当前使用平台默认字典</div>
        </article>

        <article class="kb-card p-4">
          <b class="text-sm">校验结果</b>
          <div class="mt-2 text-[12px]">
            <div class="flex items-center gap-2">
              <span class="kb-badge" :class="validation.valid?'kb-badge-emerald':'kb-badge-rose'">
                {{ validation.valid ? '可发布' : '存在错误' }}
              </span>
              <span class="kb-muted">{{ validation.validatedAt || '' }}</span>
            </div>
            <ul v-if="(validation.errors || []).length" class="mt-2 list-disc pl-4 text-[color:var(--danger)]">
              <li v-for="item in validation.errors" :key="item">{{ item }}</li>
            </ul>
            <ul v-if="(validation.warnings || []).length" class="mt-2 list-disc pl-4 text-[color:var(--warn)]">
              <li v-for="item in validation.warnings" :key="item">{{ item }}</li>
            </ul>
            <div v-if="(validation.violations || []).length" class="mt-2">
              <b class="text-[11px]">存量数据使用了未登记类型</b>
              <ul class="mt-1 list-disc pl-4 kb-muted">
                <li v-for="violation in validation.violations" :key="`${violation.kind}-${violation.typeCode}`">
                  {{ violation.kind }} · {{ violation.typeCode }} · {{ violation.useCount }} 处
                </li>
              </ul>
            </div>
            <div v-if="!validation.errors?.length && !validation.warnings?.length && !validation.violations?.length"
              class="mt-2 kb-muted">字典与存量数据一致</div>
          </div>
        </article>

        <article class="kb-card p-4">
          <b class="text-sm">发布历史</b>
          <div v-if="!releases.length" class="mt-2 text-[12px] text-ink-500">还没有发布记录</div>
          <ul v-else class="mt-2 space-y-2 text-[12px]">
            <li v-for="release in releases" :key="release.releaseCode" class="kb-muted">
              <b class="text-ink-700">{{ release.version }}</b> · {{ release.state }} ·
              {{ release.publishedTime || release.createdTime || '-' }}
            </li>
          </ul>
        </article>
      </div>
    </div>

    <el-dialog v-model="typeVisible" :title="typeForm.typeCode ? '编辑类型' : '新增类型'" width="520px">
      <div class="grid grid-cols-2 gap-3">
        <label class="block"><span class="mb-1 block text-[11px] text-ink-500">类型标识（大写）</span>
          <input v-model="typeForm.typeCode" class="kb-input w-full" placeholder="METRIC" /></label>
        <label class="block"><span class="mb-1 block text-[11px] text-ink-500">分类</span>
          <select v-model="typeForm.kind" class="kb-input w-full">
            <option v-for="item in kinds" :key="item.kind" :value="item.kind">{{ item.label }}</option>
          </select></label>
      </div>
      <label class="block mt-3"><span class="mb-1 block text-[11px] text-ink-500">展示名</span>
        <input v-model="typeForm.label" class="kb-input w-full" /></label>
      <label class="block mt-3"><span class="mb-1 block text-[11px] text-ink-500">说明</span>
        <input v-model="typeForm.description" class="kb-input w-full" /></label>
      <label class="block mt-3">
        <span class="mb-1 block text-[11px] text-ink-500">
          属性定义（JSON 数组，可选）：name / type / required，type 取 STRING、TEXT、NUMBER、BOOLEAN、DATE、ENUM、REF
        </span>
        <textarea v-model="propertiesText" rows="5" class="kb-input w-full font-mono text-[11px]" /></label>
      <label class="block mt-3" v-if="typeForm.kind==='RELATION_TYPE'">
        <span class="mb-1 block text-[11px] text-ink-500">端点约束（JSON，可选）：{"fromKinds":["SYSTEM"],"toKinds":["METRIC"]}</span>
        <textarea v-model="constraintsText" rows="3" class="kb-input w-full font-mono text-[11px]" /></label>
      <template #footer>
        <el-button size="small" plain @click="typeVisible=false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="saveType">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteSchemaType, deprecateSchemaType, getSchemaOverview, publishSchema, unwrap, upsertSchemaType,
} from '../api/knowledge'
import type { SchemaRelease, SchemaType, SchemaValidation } from '../types'
import { errorMessage } from '../utils/feedback'

const props = defineProps<{ knowledgeBaseId:number }>()

const kinds = [
  { kind:'KNOWLEDGE_TYPE', label:'知识类型', hint:'系统、指标、流程等知识对象' },
  { kind:'ENTITY_TYPE', label:'实体类型', hint:'抽取与图谱使用的实体' },
  { kind:'RELATION_TYPE', label:'关系类型', hint:'实体之间的关系与端点约束' },
]

const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const error = ref('')
const kind = ref('ENTITY_TYPE')
const types = ref<SchemaType[]>([])
const releases = ref<SchemaRelease[]>([])
const published = ref<SchemaRelease>({ releaseCode:'', state:'DRAFT' })
const validation = ref<SchemaValidation>({ valid:true, errors:[], warnings:[], violations:[] })

const typeVisible = ref(false)
const typeForm = ref<Record<string, unknown>>({ typeCode:'', kind:'ENTITY_TYPE', label:'', description:'' })
const propertiesText = ref('')
const constraintsText = ref('')

const visibleTypes = computed(() => types.value.filter(type => type.kind === kind.value))
const customCount = computed(() => types.value.filter(type => !type.builtIn).length)

/** 每个分类的条数：切换按钮上直接显示，省得点进去数。 */
function kindCount(target:string) {
  return types.value.filter(type => type.kind === target).length
}

/** 卡片头的当前分类名：切换在卡片上方，卡片头这里说明"现在看的是哪一类"。 */
const currentKindLabel = computed(() => kinds.find(item => item.kind === kind.value)?.label || '类型')

function propertyCount(type:SchemaType) {
  if (!type.propertiesJson) return 0
  try {
    const parsed = JSON.parse(type.propertiesJson)
    const properties = Array.isArray(parsed) ? parsed : parsed?.properties
    return Array.isArray(properties) ? properties.length : 0
  } catch { return 0 }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = unwrap(await getSchemaOverview({ kb:props.knowledgeBaseId }), null)
    if (data) {
      types.value = data.types || []
      releases.value = data.releases || []
      published.value = data.published || { releaseCode:'', state:'DRAFT' }
      validation.value = data.validation || { valid:true, errors:[], warnings:[], violations:[] }
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function publish() {
  publishing.value = true
  try {
    const release = unwrap(await publishSchema(props.knowledgeBaseId), null)
    ElMessage.success(release?.version ? `已发布 ${release.version}` : 'Schema 已发布')
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    publishing.value = false
  }
}

function openType(type?:SchemaType) {
  if (type) {
    typeForm.value = {
      typeCode:type.typeCode, kind:type.kind, label:type.label || '', description:type.description || '',
    }
    propertiesText.value = ''
    constraintsText.value = ''
  } else {
    typeForm.value = { typeCode:'', kind:kind.value, label:'', description:'' }
    propertiesText.value = ''
    constraintsText.value = ''
  }
  typeVisible.value = true
}

async function saveType() {
  if (!typeForm.value.typeCode) { ElMessage.warning('请填写类型标识'); return }
  const payload:Record<string, unknown> = { ...typeForm.value }
  if (propertiesText.value.trim()) {
    try { payload.properties = JSON.parse(propertiesText.value) }
    catch { ElMessage.warning('属性定义不是合法 JSON'); return }
  }
  if (constraintsText.value.trim()) {
    try { payload.constraints = JSON.parse(constraintsText.value) }
    catch { ElMessage.warning('端点约束不是合法 JSON'); return }
  }
  saving.value = true
  try {
    unwrap(await upsertSchemaType(props.knowledgeBaseId, payload), null)
    typeVisible.value = false
    await load()
    ElMessage.success('类型已保存')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function deprecate(type:SchemaType) {
  try {
    unwrap(await deprecateSchemaType(props.knowledgeBaseId, String(type.kind), type.typeCode), null)
    await load()
  } catch (e) { error.value = errorMessage(e) }
}

async function remove(type:SchemaType) {
  try {
    await ElMessageBox.confirm(`删除类型「${type.typeCode}」前请确认存量数据未在使用。`, '删除类型', { type:'warning' })
  } catch { return }
  try {
    unwrap(await deleteSchemaType(props.knowledgeBaseId, String(type.kind), type.typeCode), null)
    await load()
  } catch (e) { error.value = errorMessage(e) }
}

onMounted(load)
</script>
