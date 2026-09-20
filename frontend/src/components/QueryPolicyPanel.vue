<template>
  <section class="kb-card p-4">
    <div class="kb-section-head">
      <div><small>QUERY POLICY</small><h3>问答策略配置</h3></div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="kb-badge bg-slate-100 text-ink-600">来源：{{ policy?.source==='file'?'配置文件':'内置默认' }}</span>
        <span class="kb-badge bg-slate-100 text-ink-600">{{ policy?.version || '-' }}</span>
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" @click="reset">恢复默认</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存配置</el-button>
      </div>
    </div>

    <div v-if="message" class="mb-3 rounded-lg bg-emerald-50 px-3 py-2 text-[11px] leading-5 text-emerald-700">{{ message }}</div>
    <div v-if="error" class="mb-3 rounded-lg bg-rose-50 px-3 py-2 text-[11px] leading-5 text-rose-700">{{ error }}</div>

    <div v-if="policy" class="grid gap-4">
      <!-- 1. 查询理解与意图路由 -->
      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div>
            <b class="text-xs text-ink-700">① 查询理解与路由规则</b>
            <p class="mt-1 text-[11px] leading-5 text-ink-500">决定一个提问走哪些召回通道；命中多条规则时，最长关键词优先，其次看优先级。</p>
          </div>
          <label class="flex items-center gap-2 text-[11px] text-ink-600">
            <el-switch v-model="policy.route.enabled" size="small"/>
            启用路由
          </label>
        </div>
        <div class="overflow-x-auto">
          <table class="w-full min-w-[880px] text-[11px]">
            <thead class="text-left text-ink-500">
              <tr>
                <th class="w-14 py-1">启用</th><th class="w-40 py-1">问题类型</th><th class="py-1">触发关键词</th>
                <th class="w-56 py-1">召回通道</th><th class="w-24 py-1">优先级</th><th class="py-1">命中说明</th><th class="w-14 py-1"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(rule,index) in policy.route.rules" :key="index" class="border-t border-ink-100 align-top">
                <td class="py-2"><el-switch v-model="rule.enabled" size="small"/></td>
                <td class="py-2 pr-1"><el-input v-model="rule.questionType" size="small"/></td>
                <td class="py-2 pr-1">
                  <el-input :model-value="rule.keywords.join('、')" size="small"
                    placeholder="多个关键词用「、」或逗号分隔，留空表示兜底规则"
                    @change="(v:string)=>setKeywords(rule,v)"/>
                </td>
                <td class="py-2 pr-1">
                  <el-select v-model="rule.strategies" multiple collapse-tags collapse-tags-tooltip size="small" class="w-full">
                    <el-option v-for="c in channels" :key="c.name" :label="c.label" :value="c.name"/>
                  </el-select>
                </td>
                <td class="py-2 pr-1">
                  <el-input-number v-model="rule.priority" size="small" :min="0" :max="999" controls-position="right" class="!w-full"/>
                </td>
                <td class="py-2 pr-1"><el-input v-model="rule.reason" size="small"/></td>
                <td class="py-2">
                  <el-button size="small" type="danger" plain @click="policy.route.rules.splice(index,1)">删除</el-button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="mt-3 flex flex-wrap items-end gap-3">
          <el-button size="small" @click="addRule">新增路由规则</el-button>
          <label class="field min-w-[260px] flex-1"><span>未命中任何规则时的默认召回通道</span>
            <el-select v-model="policy.route.defaultStrategies" multiple size="small">
              <el-option v-for="c in channels" :key="c.name" :label="c.label" :value="c.name"/>
            </el-select>
          </label>
        </div>
      </div>

      <!-- 2. 查询改写 -->
      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3">
          <b class="text-xs text-ink-700">② 查询改写</b>
          <p class="mt-1 text-[11px] leading-5 text-ink-500">同义词扩展 + 可选的 LLM 改写；生成的多个改写依次尝试召回，直到结果足够。</p>
        </div>
        <div class="mb-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <label class="field"><span>启用查询改写</span>
            <el-switch v-model="policy.rewrite.enabled" size="small"/>
          </label>
          <label class="field"><span>启用 LLM 改写（依赖对话模型）</span>
            <el-switch v-model="policy.rewrite.llmEnabled" size="small"/>
          </label>
          <label class="field"><span>最大改写数量</span>
            <el-input-number v-model="policy.rewrite.maxVariants" size="small" :min="1" :max="8" class="!w-full"/>
          </label>
          <label class="field"><span>最多召回轮次</span>
            <el-input-number v-model="policy.rewrite.maxRecallRounds" size="small" :min="1" :max="5" class="!w-full"/>
          </label>
        </div>
        <b class="mb-2 block text-[11px] text-ink-600">同义词映射</b>
        <div class="grid gap-2">
          <div v-for="(item,index) in synonyms" :key="index" class="flex items-center gap-2">
            <el-input v-model="item.source" size="small" class="!w-40" placeholder="原文"/>
            <el-input :model-value="item.targets.join('、')" size="small" placeholder="替换为，多个用「、」分隔"
              @change="(v:string)=>setTargets(item,v)"/>
            <el-button size="small" type="danger" plain @click="synonyms.splice(index,1)">删除</el-button>
          </div>
        </div>
        <el-button size="small" class="mt-2" @click="addSynonym">新增同义词</el-button>
      </div>

      <!-- 3. 多路召回 -->
      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3">
          <b class="text-xs text-ink-700">③ 多路召回通道</b>
          <p class="mt-1 text-[11px] leading-5 text-ink-500">逐通道开关、融合权重与召回预算；关闭全部通道时自动回退到默认通道组合，不会导致空召回。</p>
        </div>
        <div class="overflow-x-auto">
          <table class="w-full min-w-[640px] text-[11px]">
            <thead class="text-left text-ink-500">
              <tr><th class="w-14 py-1">启用</th><th class="py-1">召回通道</th><th class="w-32 py-1">融合权重</th><th class="w-32 py-1">召回规模倍数</th></tr>
            </thead>
            <tbody>
              <tr v-for="name in channelNames" :key="name" class="border-t border-ink-100">
                <td class="py-2"><el-switch v-model="policy.recall.channels[name].enabled" size="small"/></td>
                <td class="py-2">
                  <b class="text-ink-700">{{ channelLabel(name) }}</b>
                  <p class="text-[10px] leading-4 text-ink-400">{{ channelDesc(name) }}</p>
                </td>
                <td class="py-2 pr-1">
                  <el-input-number v-model="policy.recall.channels[name].weight" size="small"
                    :min="0" :max="5" :step="0.05" :precision="2" class="!w-full"/>
                </td>
                <td class="py-2">
                  <el-input-number v-model="policy.recall.channels[name].scale" size="small"
                    :min="0.1" :max="5" :step="0.1" :precision="1" class="!w-full"/>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="mt-3 grid gap-3 sm:grid-cols-2">
          <label class="field"><span>召回放大倍数（TopK × 倍数 = 每通道召回候选数）</span>
            <el-input-number v-model="policy.recall.recallMultiplier" size="small" :min="1" :max="10" class="!w-full"/>
          </label>
          <label class="field"><span>每通道最小召回候选数</span>
            <el-input-number v-model="policy.recall.minRecallCandidates" size="small" :min="1" :max="200" class="!w-full"/>
          </label>
        </div>
      </div>

      <!-- 4. 融合排序 -->
      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3">
          <b class="text-xs text-ink-700">④ 融合排序（加权 RRF）</b>
          <p class="mt-1 text-[11px] leading-5 text-ink-500">把各通道的排序结果合成一份；质量信号会在融合分上叠加加权增益。</p>
        </div>
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <label class="field"><span>RRF k 常数（越大越平滑）</span>
            <el-input-number v-model="policy.fusion.rrfK" size="small" :min="1" :max="1000" class="!w-full"/>
          </label>
          <label class="field"><span>启用质量信号加权</span>
            <el-switch v-model="policy.fusion.qualitySignalsEnabled" size="small"/>
          </label>
          <label class="field"><span>权威性权重</span>
            <el-input-number v-model="policy.fusion.qualityWeights.authority" size="small" :min="0" :max="1" :step="0.05" :precision="2" class="!w-full"/>
          </label>
          <label class="field"><span>时效性权重</span>
            <el-input-number v-model="policy.fusion.qualityWeights.freshness" size="small" :min="0" :max="1" :step="0.05" :precision="2" class="!w-full"/>
          </label>
          <label class="field"><span>置信度权重</span>
            <el-input-number v-model="policy.fusion.qualityWeights.confidence" size="small" :min="0" :max="1" :step="0.05" :precision="2" class="!w-full"/>
          </label>
          <label class="field"><span>证据完备度权重</span>
            <el-input-number v-model="policy.fusion.qualityWeights.evidence" size="small" :min="0" :max="1" :step="0.05" :precision="2" class="!w-full"/>
          </label>
        </div>
      </div>

      <!-- 5. 重排 -->
      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3">
          <b class="text-xs text-ink-700">⑤ 重排（Rerank）</b>
          <p class="mt-1 text-[11px] leading-5 text-ink-500">关闭后保持融合顺序；参与重排的候选数 0 表示对全部融合候选重排。</p>
        </div>
        <div class="grid gap-3 sm:grid-cols-3">
          <label class="field"><span>启用重排</span>
            <el-switch v-model="policy.rerank.enabled" size="small"/>
          </label>
          <label class="field"><span>参与重排的候选数（0=全部）</span>
            <el-input-number v-model="policy.rerank.topN" size="small" :min="0" :max="200" class="!w-full"/>
          </label>
        </div>
      </div>

      <!-- 6. 答案生成与质量门控 -->
      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3">
          <b class="text-xs text-ink-700">⑥ 答案生成与质量门控</b>
          <p class="mt-1 text-[11px] leading-5 text-ink-500">控制进入提示词的证据长度、以证据为准的门控阈值，以及无证据时的兜底话术。</p>
        </div>
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <label class="field"><span>上下文最大字符数</span>
            <el-input-number v-model="policy.answer.contextMaxChars" size="small" :min="500" :max="60000" :step="500" class="!w-full"/>
          </label>
          <label class="field"><span>启用答案证据门控</span>
            <el-switch v-model="policy.answer.evidenceGateEnabled" size="small"/>
          </label>
          <label class="field"><span>忠实度最小阈值</span>
            <el-input-number v-model="policy.answer.faithfulnessMin" size="small" :min="0" :max="1" :step="0.01" :precision="2" class="!w-full"/>
          </label>
          <label class="field"><span>断言覆盖最小阈值</span>
            <el-input-number v-model="policy.answer.entailmentMin" size="small" :min="0" :max="1" :step="0.01" :precision="2" class="!w-full"/>
          </label>
          <label class="field"><span>答案策略版本</span>
            <el-input v-model="policy.answer.policyVersion" size="small"/>
          </label>
          <label class="field"><span>无证据兜底话术</span>
            <el-input v-model="policy.answer.noAnswerText" size="small"/>
          </label>
        </div>
      </div>
    </div>
    <div v-else class="py-10 text-center text-xs text-ink-400">加载问答策略中…</div>

    <p class="mt-3 text-[10px] leading-5 text-ink-400">
      配置持久化到 <b>{{ policy?.policyFile || schema?.policyFile || 'ai-service/data/query_policy.json' }}</b>（文件权限 600），
      保存后下一次问答起生效，无需重启；每一次问答的链路追踪都会记录当时生效的策略指纹（通道、权重、RRF k、门控阈值），便于回溯结果差异。
      当前为平台级策略，对所有知识库生效。
    </p>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getQueryPolicy, getQueryPolicySchema, resetQueryPolicy, saveQueryPolicy, unwrap } from '../api/knowledge'
import type { QueryPolicy, QueryPolicySchema, RouteRule } from '../types'
import { errorMessage } from '../utils/feedback'

const policy = ref<QueryPolicy|null>(null)
const schema = ref<QueryPolicySchema|null>(null)
const saving = ref(false)
const message = ref('')
const error = ref('')
const synonyms = ref<{ source:string; targets:string[] }[]>([])

const channels = computed(() => schema.value?.channels || [])
const channelNames = computed(() => channels.value.length
  ? channels.value.map(c => c.name)
  : Object.keys(policy.value?.recall.channels || {}))

const err = (e:unknown) => errorMessage(e)
const channelLabel = (name:string) => channels.value.find(c => c.name === name)?.label || name
const channelDesc = (name:string) => channels.value.find(c => c.name === name)?.description || ''
const split = (text:string) => text.split(/[、,，;；\s]+/).map(item => item.trim()).filter(Boolean)

/** 关键词在同义词/UI 中以顿号分隔展示，提交前拆成数组。 */
function setKeywords(rule:RouteRule, text:string) { rule.keywords = split(text) }
function setTargets(item:{ source:string; targets:string[] }, text:string) { item.targets = split(text) }

function addRule() {
  if (!policy.value) return
  policy.value.route.rules.push({
    questionType:`CUSTOM_${policy.value.route.rules.length + 1}`, enabled:true,
    keywords:[], strategies:['card','vector','bm25'], priority:20, reason:'自定义规则',
  })
}
function addSynonym() { synonyms.value.push({ source:'', targets:[] }) }

/** 把同义词行编辑态同步回策略对象。 */
function collectSynonyms() {
  if (!policy.value) return
  const merged:Record<string, string[]> = {}
  for (const item of synonyms.value) {
    const source = item.source.trim()
    if (source) merged[source] = item.targets
  }
  policy.value.rewrite.synonyms = merged
}

async function load() {
  error.value = ''
  try {
    schema.value = unwrap(await getQueryPolicySchema(), null as unknown as QueryPolicySchema)
    policy.value = unwrap(await getQueryPolicy(), null as unknown as QueryPolicy)
    synonyms.value = Object.entries(policy.value?.rewrite.synonyms || {})
      .map(([source, targets]) => ({ source, targets:[...targets] }))
  } catch (e) {
    error.value = err(e)
  }
}

async function save() {
  if (!policy.value) return
  saving.value = true
  message.value = ''
  error.value = ''
  collectSynonyms()
  const payload = JSON.parse(JSON.stringify(policy.value)) as Record<string, unknown>
  for (const key of ['source', 'policyFile', 'policyVersionDefault']) delete payload[key]
  try {
    policy.value = unwrap(await saveQueryPolicy(payload), null as unknown as QueryPolicy)
    message.value = '问答策略已保存，下一次问答起生效'
    ElMessage.success('问答策略已生效')
  } catch (e) {
    error.value = err(e)
  } finally {
    saving.value = false
  }
}

async function reset() {
  message.value = ''
  error.value = ''
  try {
    policy.value = unwrap(await resetQueryPolicy(), null as unknown as QueryPolicy)
    synonyms.value = Object.entries(policy.value?.rewrite.synonyms || {})
      .map(([source, targets]) => ({ source, targets:[...targets] }))
    message.value = '已恢复内置默认策略'
  } catch (e) {
    error.value = err(e)
  }
}

onMounted(load)
</script>
<style scoped>
.field{display:block}
.field>span{display:block;margin-bottom:4px;font-size:11px;color:#66738e}
th{font-weight:600}
</style>
