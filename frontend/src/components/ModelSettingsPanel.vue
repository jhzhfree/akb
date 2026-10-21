<template>
  <!-- 与其它面板同构：页头放标题与操作，内容收进卡片，而不是整页都是一张卡。 -->
  <section class="kb-page">
    <header class="kb-section-head">
      <!-- 标题口径与其它 Tab 一致：eyebrow 写所属模块，主标题写菜单名。 -->
      <div>
        <div class="kb-eyebrow">系统配置</div>
        <h1 class="kb-title mt-1">模型配置</h1>
        <p class="kb-muted mt-1">对话模型与向量模型的接入参数：改完先「测试连通」，通过再保存。</p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <span class="kb-badge bg-[color:var(--bg-sub)] text-ink-600">来源：{{ settings?.source==='file'?'配置文件':'环境变量' }}</span>
        <el-button size="small" plain @click="load">刷新</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存配置</el-button>
      </div>
    </header>

    <section class="kb-card p-4">

    <div v-if="message" class="mb-3 rounded-lg bg-[color:var(--ok-bg)] px-3 py-2 text-[11px] leading-5 text-[color:var(--ok)]">{{ message }}</div>
    <div v-if="error" class="mb-3 rounded-lg bg-[color:var(--danger-bg)] px-3 py-2 text-[11px] leading-5 text-[color:var(--danger)]">{{ error }}</div>

    <div v-if="settings" class="grid gap-4 lg:grid-cols-2">
      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3 flex items-center justify-between">
          <b class="text-xs text-ink-700">对话模型（LLM）</b>
          <el-button size="small" plain :loading="testing==='llm'" @click="test('llm')">测试连通</el-button>
        </div>
        <div class="grid gap-3 sm:grid-cols-2">
          <label class="field"><span>服务提供方</span>
            <el-select v-model="settings.llm.provider" size="small" @change="onProvider('llm')">
              <el-option label="OpenAI 兼容（DeepSeek / 智谱 / Kimi …）" value="openai"/>
              <el-option label="本地 Ollama" value="ollama"/>
              <el-option label="内置 Mock（不调用模型）" value="mock"/>
            </el-select>
          </label>
          <label class="field"><span>模型名称</span>
            <el-input v-model="settings.llm.model" size="small" placeholder="deepseek-flash / qwen2.5:7b"/>
          </label>
          <label class="field"><span>接口地址</span>
            <el-input v-model="settings.llm.base_url" size="small" placeholder="https://api.deepseek.com"/>
          </label>
          <label class="field"><span>API 密钥</span>
            <el-input v-model="settings.llm.api_key" size="small" type="password" show-password
              :placeholder="settings.llm.api_key_set?'已配置（留空保持原值）':'sk-...'"/>
          </label>
          <label class="field"><span>采样温度</span>
            <el-input-number v-model="settings.llm.temperature" size="small" :min="0" :max="2" :step="0.1" class="!w-full"/>
          </label>
          <label class="field"><span>最大生成长度</span>
            <el-input-number v-model="settings.llm.max_tokens" size="small" :min="1" :max="32768" :step="256" class="!w-full"/>
          </label>
          <label class="field"><span>超时（秒）</span>
            <el-input-number v-model="settings.llm.timeout" size="small" :min="1" :max="600" class="!w-full"/>
          </label>
        </div>
      </div>

      <div class="rounded-lg border border-ink-100 p-4">
        <div class="mb-3 flex items-center justify-between">
          <b class="text-xs text-ink-700">向量模型（Embedding）</b>
          <el-button size="small" plain :loading="testing==='embedding'" @click="test('embedding')">测试连通</el-button>
        </div>
        <label class="mb-3 flex items-center gap-2 text-[11px] text-ink-600">
          <el-switch v-model="settings.embedding.enabled" size="small"/>
          启用外部向量模型（关闭后退化为本地哈希向量）
        </label>
        <div class="grid gap-3 sm:grid-cols-2">
          <label class="field"><span>服务提供方</span>
            <el-select v-model="settings.embedding.provider" size="small" @change="onProvider('embedding')">
              <el-option label="OpenAI 兼容（智谱 embedding-3 …）" value="openai"/>
              <el-option label="本地 Ollama" value="ollama"/>
              <el-option label="本地哈希向量（零依赖）" value="hash"/>
            </el-select>
          </label>
          <label class="field"><span>模型名称</span>
            <el-input v-model="settings.embedding.model" size="small" placeholder="embedding-3 / nomic-embed-text"/>
          </label>
          <label class="field"><span>接口地址</span>
            <el-input v-model="settings.embedding.base_url" size="small" placeholder="https://open.bigmodel.cn/api/paas/v4"/>
          </label>
          <label class="field"><span>API 密钥</span>
            <el-input v-model="settings.embedding.api_key" size="small" type="password" show-password
              :placeholder="settings.embedding.api_key_set?'已配置（留空保持原值）':'sk-...'"/>
          </label>
          <label class="field"><span>向量维度</span>
            <el-input-number v-model="settings.embedding.dim" size="small" :min="16" :max="4096" :step="256" class="!w-full"/>
          </label>
          <label class="field"><span>超时（秒）</span>
            <el-input-number v-model="settings.embedding.timeout" size="small" :min="1" :max="600" class="!w-full"/>
          </label>
        </div>
      </div>
    </div>
    <div v-else class="py-10 text-center text-xs text-ink-500">加载配置中…</div>

    <p class="mt-3 text-[10px] leading-5 text-ink-500">
      配置持久化到 <b>{{ settings?.settingsFile || 'ai-service/data/settings.json' }}</b>（含密钥、不入版本库、文件权限 600），保存后立即生效，无需重启服务。
      切换向量模型或调整维度后，历史索引维度不一致，需执行索引重建。
    </p>
    </section>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getModelSettings, saveModelSettings, testModelSettings, unwrap } from '../api/knowledge'
import type { ModelSettings, ModelTestResult } from '../types'
import { errorMessage } from '../utils/feedback'

const settings = ref<ModelSettings|null>(null)
const saving = ref(false)
const testing = ref('')
const message = ref('')
const error = ref('')

const err = (e:unknown) => errorMessage(e)

async function load() {
  error.value = ''
  try {
    settings.value = unwrap(await getModelSettings(), null as unknown as ModelSettings)
  } catch (e) {
    error.value = err(e)
  }
}

async function save() {
  if (!settings.value) return
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    settings.value = unwrap(
      await saveModelSettings({ llm: settings.value.llm, embedding: settings.value.embedding }),
      null as unknown as ModelSettings,
    )
    message.value = '配置已保存并立即生效'
    ElMessage.success('模型配置已生效')
  } catch (e) {
    error.value = err(e)
  } finally {
    saving.value = false
  }
}

async function test(kind:'llm'|'embedding') {
  testing.value = kind
  message.value = ''
  error.value = ''
  try {
    const result = unwrap(await testModelSettings(kind), {} as ModelTestResult)
    message.value = kind === 'llm'
      ? `对话模型连通成功：provider=${result.provider} model=${result.model || '-'} 耗时=${result.latencyMs ?? '-'}ms 样例=${result.sample || '-'}`
      : `向量模型连通成功：provider=${result.provider} model=${result.model || '-'} 维度=${result.dimension ?? '-'} 耗时=${result.latencyMs ?? '-'}ms`
  } catch (e) {
    error.value = err(e)
  } finally {
    testing.value = ''
  }
}

/** 切换 provider 时补齐该生态最常见的默认端点，避免手抄地址出错。 */
function onProvider(section:'llm'|'embedding') {
  if (!settings.value) return
  const target = settings.value[section]
  if (section === 'llm') {
    if (target.provider === 'ollama') target.base_url = 'http://127.0.0.1:11434'
    if (target.provider === 'openai' && !target.base_url) target.base_url = 'https://api.deepseek.com'
  } else if (target.provider === 'ollama') {
    target.base_url = 'http://127.0.0.1:11434'
  }
}

onMounted(load)
</script>
<style scoped>
.field{display:block}
.field>span{display:block;margin-bottom:4px;font-size:11px;color:var(--ink-3)}
</style>
