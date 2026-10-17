<template>
  <!--
    设计 §4：生成配置是知识生成流水线的一环，入口放在知识生成模块内，而不是只在建库弹窗里——
    文档已入库后再想调整切片与抽取，不必回到「知识库管理」重新编辑整张表单。
    可选值与取值范围全部取自后端 schema（GET /api/knowledge/settings/scenario），前端不维护第二份定义。
  -->
  <section class="kb-page">
    <header class="kb-section-head">
      <div>
        <div class="kb-eyebrow">知识生成</div>
        <h1 class="kb-title mt-1">生成配置</h1>
        <p class="kb-muted mt-1">
          决定本知识库的文档如何切分、抽取哪些知识对象。保存后对新接入的文档立即生效，
          已入库的文档需要「重新生成知识」才会按新配置重切与重抽。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <span class="kb-tag">{{ customized ? '已自定义' : '跟随模板' }}</span>
        <button class="kb-btn-outline" :disabled="loading" @click="load">刷新</button>
        <button class="kb-btn-outline" :disabled="loading || saving" @click="restore">恢复模板默认值</button>
        <button class="kb-btn" :disabled="loading || saving" @click="save">
          {{ saving ? '保存中…' : '保存配置' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="kb-alert-error mt-3">{{ error }}</div>

    <!-- 场景模板：切换即套用它的推荐默认值，避免从零调参。 -->
    <article class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">场景模板</b>
        <span class="kb-muted">切换后会带出该场景推荐的生成、衍生与查询默认值，可在下方微调</span>
      </div>
      <div class="p-4">
        <div v-if="!templates.length" class="py-6 text-center text-sm text-ink-500">正在读取场景模板...</div>
        <div v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <button v-for="item in templates" :key="item.code" type="button"
            class="rounded-lg border border-ink-200 p-3 text-left"
            :class="scenario===item.code ? 'border-accent bg-ink-50' : ''"
            @click="pickTemplate(item.code)">
            <b class="text-ink-700">{{ item.label }}</b>
            <p class="mt-1 text-[12px] leading-5 text-ink-600">{{ item.summary }}</p>
            <div class="mt-2 text-[11px] text-ink-500">{{ (item.docTypes || []).join(' / ') }}</div>
          </button>
        </div>
      </div>
    </article>

    <!-- 生成配置：切片与抽取。 -->
    <article class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">切片策略</b>
        <span class="kb-muted">{{ strategyHint }}</span>
      </div>
      <div class="p-4">
        <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <label class="block">
            <span class="kb-muted text-[11px]">切片策略</span>
            <select v-model="profile.generation.chunk.strategy" class="kb-input mt-1 w-full">
              <option v-for="s in chunkStrategies" :key="s.name" :value="s.name">{{ s.label }}</option>
            </select>
          </label>
          <label class="block">
            <span class="kb-muted text-[11px]">窗口大小</span>
            <el-input-number v-model="profile.generation.chunk.chunkSize" class="mt-1"
              :min="range('chunkSize').min" :max="range('chunkSize').max" :step="50" />
          </label>
          <label class="block">
            <span class="kb-muted text-[11px]">重叠长度</span>
            <el-input-number v-model="profile.generation.chunk.chunkOverlap" class="mt-1"
              :min="range('chunkOverlap').min" :max="range('chunkOverlap').max" :step="10" />
          </label>
          <label class="block">
            <span class="kb-muted text-[11px]">最小片段</span>
            <el-input-number v-model="profile.generation.chunk.minChunkSize" class="mt-1"
              :min="range('minChunkSize').min" :max="range('minChunkSize').max" :step="10" />
          </label>
          <label class="block">
            <span class="kb-muted text-[11px]">计量方式</span>
            <select v-model="profile.generation.chunk.measure" class="kb-input mt-1 w-full">
              <option v-for="m in chunkMeasures" :key="m.name" :value="m.name">{{ m.label }}</option>
            </select>
          </label>
        </div>
        <div class="mt-3 flex flex-wrap gap-4">
          <el-checkbox v-model="profile.generation.chunk.keepStructure">保留章节结构</el-checkbox>
          <el-checkbox v-model="profile.generation.chunk.respectSentence">切分落在句末</el-checkbox>
          <el-checkbox v-model="profile.generation.chunk.parentChild">父子分块</el-checkbox>
        </div>
        <p class="mt-2 text-[11px] text-ink-500">
          重叠与最小片段超过窗口一半时后端会自动收敛到半窗，避免整篇被复制一遍。
        </p>
      </div>
    </article>

    <article class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">抽取开关</b>
        <span class="kb-muted">关闭后不再产出对应知识对象，也不再消耗模型与图谱开销</span>
      </div>
      <div class="p-4">
        <div class="flex flex-wrap gap-4">
          <el-checkbox v-model="profile.generation.extract.claims">抽取断言</el-checkbox>
          <el-checkbox v-model="profile.generation.extract.entityResolution">实体消歧</el-checkbox>
          <el-checkbox v-model="profile.generation.extract.conflictDetection">冲突检测</el-checkbox>
        </div>
        <ul class="mt-3 space-y-1 text-[11px] text-ink-500">
          <li>抽取断言：把条款与结论落成可比对的三元组，是「条件 / 适用范围 / 版本差异」类问题的主要召回通道。</li>
          <li>实体消歧：合并同一实体的多种写法，图谱与归属类问题依赖它；纯条款库的收益有限，可关闭。</li>
          <li>冲突检测：识别新旧口径并存的断言，关闭后口径冲突不会进裁决工作台。</li>
        </ul>
      </div>
    </article>

    <!-- 与模板的差异：让人知道改了什么，也知道「恢复默认」会改回什么。 -->
    <article v-if="diffs.length" class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">与模板默认值的差异</b>
        <span class="kb-muted">共 {{ diffs.length }} 项</span>
      </div>
      <div class="p-4">
        <table class="kb-table">
          <thead><tr><th>配置项</th><th>当前</th><th>模板默认</th></tr></thead>
          <tbody>
            <tr v-for="item in diffs" :key="item.label">
              <td>{{ item.label }}</td>
              <td class="font-mono text-ink-700">{{ item.current }}</td>
              <td class="kb-muted">{{ item.template }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <!-- 生效范围：改配置不会回溯重算，需要显式重新生成，这一点必须写在界面上而不是靠人记。 -->
    <article class="kb-card mt-3">
      <div class="kb-card-head">
        <b class="text-sm">生效范围</b>
        <span class="kb-muted">当前知识库 {{ documents.length }} 篇文档</span>
      </div>
      <div class="p-4">
        <p class="text-[12px] leading-5 text-ink-600">
          保存后立即对新接入的文档生效；已入库文档仍保留原有切片与抽取结果，
          需要按新配置重跑一次才会重切与重抽。
        </p>
        <div class="mt-3 flex items-center gap-3">
          <button class="kb-btn-outline" :disabled="regenerating || regen.running || !documents.length" @click="confirmRegenerate">
            {{ regen.running ? `重新生成中 ${regen.progress}%` : '重新生成知识' }}
          </button>
          <span v-if="!documents.length" class="kb-muted text-[11px]">当前知识库还没有文档</span>
        </div>
        <!-- 重跑是异步任务：进度来自任务中心，与生成概览、文档导入页看到的是同一条。 -->
        <div v-if="regenVisible" class="mt-3">
          <div class="h-2 w-full overflow-hidden rounded bg-ink-100">
            <div class="h-full transition-all" :style="{width:Math.max(2,regen.progress)+'%',background:'var(--accent)'}"></div>
          </div>
          <div class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[12px] text-ink-600">
            <span>{{ regen.processed + regen.failed + regen.skipped }} / {{ regen.documentCount || documents.length }} 篇</span>
            <span>卡片 {{ regen.cardCount }}</span>
            <span>断言 {{ regen.claimCount }}</span>
            <span v-if="regen.failed" class="text-[color:var(--danger)]">失败 {{ regen.failed }} 篇</span>
          </div>
          <p class="mt-1 truncate text-[11px] text-ink-500">{{ regen.stage || '正在排队…' }}</p>
        </div>
        <p v-if="updatedBy || updatedTime" class="mt-3 text-[11px] text-ink-500">
          最近修改：{{ updatedBy || '—' }} · {{ updatedTime || '—' }}
        </p>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { KnowledgeDocument } from '../types'
import type { KbScenarioProfile, KbScenarioSettings } from '../types/knowledge'
import { getKbScenario, saveKbScenario, resetKbScenario } from '../api/knowledge/base'
import { useShell } from '../shell'

const props = defineProps<{
  knowledgeBaseId: number
  /** 文档数量决定「重新生成」是否可用：没有文档就没有可重跑的正文。 */
  documents: KnowledgeDocument[]
  /** 整库重新生成进行中：按钮忙碌态由外壳持有。 */
  regenerating?: boolean
}>()

const shell = useShell()
/** 重新生成的进度由外壳持有：配置改完最常见的下一步就是重跑，进度必须在这一页看得见。 */
const regen = shell.regeneration
const regenVisible = computed(() => regen.running || ['SUCCEEDED', 'FAILED', 'DEAD', 'CANCELED'].includes(regen.status))
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const scenario = ref('GENERAL')
const schema = ref<KbScenarioSettings['schema'] | null>(null)
const template = ref<KbScenarioProfile | null>(null)
const customized = ref(false)
const updatedBy = ref('')
const updatedTime = ref('')
const profile = ref<KbScenarioProfile>(emptyProfile())

const templates = computed(() => schema.value?.scenarios || [])
const chunkStrategies = computed(() => schema.value?.chunk?.strategies || [])
const chunkMeasures = computed(() => schema.value?.chunk?.measures || [])
const strategyHint = computed(() =>
  chunkStrategies.value.find(s => s.name === profile.value.generation.chunk.strategy)?.description || '')

/** 取值边界来自后端 schema：界面上的上下限就是后端会夹紧的上下限。 */
function range(key: string) {
  const item = schema.value?.ranges?.[key]
  return { min: item?.min ?? 0, max: item?.max ?? 1000 }
}

function emptyProfile(): KbScenarioProfile {
  return {
    generation: {
      chunk: {
        strategy: 'SEMANTIC', chunkSize: 900, chunkOverlap: 120, minChunkSize: 120, measure: 'CHAR',
        keepStructure: true, respectSentence: true, parentChild: false,
      },
      extract: { claims: true, entityResolution: true, conflictDetection: true },
    },
    derivation: { graphEnabled: true, presets: [] },
    query: {
      defaultStrategies: ['card', 'vector', 'bm25', 'claim'], channels: {}, recallMultiplier: 3,
      minRecallCandidates: 20, rerankEnabled: true, rerankTopN: 0, rrfK: 60,
      qualitySignalsEnabled: true, chunkBoostEnabled: true, answerFormat: 'default',
      minChunkEvidence: 3, contextMaxChars: 8000,
    },
  }
}

function clone(source: KbScenarioProfile): KbScenarioProfile {
  return JSON.parse(JSON.stringify(source)) as KbScenarioProfile
}

function labelOf(list: { name: string; label: string }[], name: string) {
  return list.find(v => v.name === name)?.label || name
}

function boolText(value: boolean) {
  return value ? '开启' : '关闭'
}

/** 与模板默认值的差异：只列生成段，因为本页只改这一段的语义。 */
const diffs = computed<{ label: string; current: string; template: string }[]>(() => {
  const base = template.value
  if (!base) return []
  const now = profile.value.generation
  const def = base.generation
  const out: { label: string; current: string; template: string }[] = []
  const push = (label: string, current: string, fallback: string) => {
    if (current !== fallback) out.push({ label, current, template: fallback })
  }
  push('切片策略', labelOf(chunkStrategies.value, now.chunk.strategy), labelOf(chunkStrategies.value, def.chunk.strategy))
  push('窗口大小', String(now.chunk.chunkSize), String(def.chunk.chunkSize))
  push('重叠长度', String(now.chunk.chunkOverlap), String(def.chunk.chunkOverlap))
  push('最小片段', String(now.chunk.minChunkSize), String(def.chunk.minChunkSize))
  push('计量方式', labelOf(chunkMeasures.value, now.chunk.measure), labelOf(chunkMeasures.value, def.chunk.measure))
  push('保留章节结构', boolText(now.chunk.keepStructure), boolText(def.chunk.keepStructure))
  push('切分落在句末', boolText(now.chunk.respectSentence), boolText(def.chunk.respectSentence))
  push('父子分块', boolText(now.chunk.parentChild), boolText(def.chunk.parentChild))
  push('抽取断言', boolText(now.extract.claims), boolText(def.extract.claims))
  push('实体消歧', boolText(now.extract.entityResolution), boolText(def.extract.entityResolution))
  push('冲突检测', boolText(now.extract.conflictDetection), boolText(def.extract.conflictDetection))
  return out
})

function pickTemplate(code: string) {
  const item = templates.value.find(v => v.code === code)
  if (!item) return
  scenario.value = code
  profile.value = clone(item.default)
}

async function load() {
  if (!props.knowledgeBaseId) return
  loading.value = true
  error.value = ''
  try {
    const data = (await getKbScenario(props.knowledgeBaseId)).data?.data
    if (!data) return
    schema.value = data.schema || null
    scenario.value = data.scenario || 'GENERAL'
    template.value = data.template ? clone(data.template) : null
    customized.value = !!data.customized
    updatedBy.value = String(data.updatedBy ?? '')
    updatedTime.value = String(data.updatedTime ?? '')
    profile.value = clone(data.profile || emptyProfile())
  } catch (e) {
    error.value = '生成配置读取失败，请稍后重试'
    void e
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!props.knowledgeBaseId) return
  saving.value = true
  error.value = ''
  try {
    const data = (await saveKbScenario(props.knowledgeBaseId, {
      scenario: scenario.value,
      profile: profile.value,
    })).data?.data
    if (data) {
      // 保存回显用后端归一化后的档案：夹紧与补全发生在服务端，界面必须显示真正生效的值。
      if (data.profile) profile.value = clone(data.profile as KbScenarioProfile)
      if (data.template) template.value = clone(data.template as KbScenarioProfile)
      if (typeof data.scenario === 'string') scenario.value = data.scenario
      customized.value = data.customized === true
      updatedBy.value = String(data.updatedBy ?? '')
      updatedTime.value = String(data.updatedTime ?? '')
    }
    ElMessage.success('生成配置已保存，对新接入的文档立即生效')
  } catch (e) {
    error.value = '生成配置保存失败，请稍后重试'
    void e
  } finally {
    saving.value = false
  }
}

async function restore() {
  if (!props.knowledgeBaseId) return
  try {
    await ElMessageBox.confirm('将丢弃本知识库的全部自定义配置，恢复为所选场景模板的内置默认值。', '恢复模板默认值', {
      type: 'warning',
    })
  } catch {
    return
  }
  loading.value = true
  try {
    const data = (await resetKbScenario(props.knowledgeBaseId)).data?.data
    if (data?.profile) profile.value = clone(data.profile as KbScenarioProfile)
    if (data?.template) template.value = clone(data.template as KbScenarioProfile)
    if (typeof data?.scenario === 'string') scenario.value = data.scenario
    customized.value = false
    ElMessage.success('已恢复为模板默认值')
  } catch (e) {
    error.value = '恢复模板默认值失败，请稍后重试'
    void e
  } finally {
    loading.value = false
  }
}

/** 重新生成：配置改完最常见的下一步，放在同一页省一次跳转。 */
async function confirmRegenerate() {
  try {
    await ElMessageBox.confirm(
      `将按当前配置对 ${props.documents.length} 篇文档重新切分与抽取，使用已入库正文，不需要重新上传文件。`,
      '重新生成知识', { type: 'warning' })
  } catch {
    return
  }
  shell.regenerate(false)
}

onMounted(load)
watch(() => props.knowledgeBaseId, load)
</script>
