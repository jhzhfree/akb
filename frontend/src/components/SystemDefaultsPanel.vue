<template>
<div class="sd-panel">
  <!-- 页面标题：eyebrow 是所属模块，主标题是菜单名（与其它 Tab 一致）。 -->
  <header class="kb-section-head">
    <div>
      <div class="kb-eyebrow">系统配置</div>
      <h1 class="kb-title mt-1">系统初始化</h1>
      <p class="kb-muted mt-1">出厂默认配置的来源与恢复：场景模板、衍生规则、检索策略。</p>
    </div>
  </header>

  <p class="sd-desc">
    三类默认配置都以文件随镜像发布，并挂载到配置目录，改完文件调用「重新加载」或重启即可生效。
    这里的「恢复出厂默认配置」会<b>用镜像内的版本覆盖</b>配置目录里的文件，适用于改坏或误删之后的回退。
    <b>它只影响此后新建的知识库</b>：已建知识库已播种的配置不会被覆盖。
  </p>

  <div v-if="loading" class="sd-hint">正在读取默认配置…</div>
  <template v-else>
    <div class="sd-grid">
      <div v-for="item in items" :key="item.key" class="sd-card">
        <div class="sd-card-head">
          <b>{{item.label}}</b>
          <em>{{item.count}} 项</em>
        </div>
        <div class="sd-source" :class="{warn:item.builtin}" :title="item.source">{{item.sourceLabel}}</div>
      </div>
    </div>

    <p v-if="status?.configDir===null" class="sd-warn">
      后端未配置 KNOWLEDGE_CONFIG_DIR：默认配置直接读镜像内那份，没有可恢复的外部文件。
      本地开发想验证恢复功能，可用 <code>KNOWLEDGE_CONFIG_DIR=/tmp/kos-config</code> 启动后端（目录需可写）。
    </p>
  </template>

  <div class="sd-bar">
    <el-button size="small" plain :loading="loading" :disabled="restoring" @click="load(true)">刷新</el-button>
    <span class="sd-grow"></span>
    <el-button size="small" type="danger" plain :loading="restoring" :disabled="loading" @click="restore">
      恢复出厂默认配置
    </el-button>
  </div>

  <p v-if="message" class="sd-msg" :class="{error:messageError}">{{message}}</p>
</div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDefaultCatalog, restoreDefaultCatalog, type DefaultCatalogStatus } from '../api/system'
import { listQueryPolicyProfiles } from '../api/knowledge-impl'

/**
 * 系统初始化面板（系统配置 · 系统初始化）。
 *
 * 三类默认配置分属两个服务：场景模板与衍生规则在后端，检索策略在 AI 服务。这里把它们
 * 的来源并排展示，让「现在用的是镜像里那份还是配置目录那份」一眼可见——之前生产只剩一个
 * 默认策略，就是因为没人能在这个位置看出策略根本没被加载。
 */
const status = ref<DefaultCatalogStatus|null>(null)
/** 检索策略条数单独取：AI 服务不可达时不该让整个面板打不开。 */
const policyCount = ref<number|null>(null)
const loading = ref(true)
const restoring = ref(false)
const message = ref('')
const messageError = ref(false)

const items = computed(() => {
  const s = status.value
  return [
    {
      key: 'scenario',
      label: '知识库场景模板',
      count: s?.scenarioTemplates?.count ?? 0,
      source: s?.scenarioTemplates?.source ?? '',
      sourceLabel: sourceLabel(s?.scenarioTemplates?.source ?? ''),
      builtin: (s?.scenarioTemplates?.source ?? '') === 'built-in',
    },
    {
      key: 'rules',
      label: '常用衍生规则库',
      count: s?.derivationRules?.count ?? 0,
      source: s?.derivationRules?.source ?? '',
      sourceLabel: sourceLabel(s?.derivationRules?.source ?? ''),
      builtin: (s?.derivationRules?.source ?? '') === 'built-in',
    },
    {
      key: 'policy',
      label: '检索策略',
      count: policyCount.value ?? 0,
      source: '',
      sourceLabel: policyCount.value === null ? 'AI 服务不可达，无法读取' : 'AI 服务 · 检索策略列表',
      builtin: policyCount.value === null,
    },
  ]
})

function sourceLabel(src: string): string {
  if (!src) return '—'
  if (src === 'built-in') return '内置常量（未使用文件）'
  if (src.startsWith('classpath:')) return '镜像内 ' + src.slice('classpath:'.length)
  return src
}

/**
 * 刷新来源信息。clearMessage 只给「刷新」按钮用：恢复流程会自己写结果说明，
 * 若把清空放在这里，恢复完再刷新就会把刚写的结果抹掉，看起来像什么都没发生。
 */
async function load(clearMessage = false) {
  loading.value = true
  if (clearMessage) message.value = ''
  try {
    const data = (await getDefaultCatalog()).data.data
    status.value = data ?? null
    if (!data) {
      messageError.value = true
      message.value = '读取默认配置失败：接口未返回数据'
    }
  } catch (e) {
    messageError.value = true
    message.value = '读取默认配置失败：' + String(e)
  } finally {
    loading.value = false
  }
  try {
    const profiles = (await listQueryPolicyProfiles()).data.data as { profiles?: unknown[] } | null
    policyCount.value = profiles?.profiles?.length ?? 0
  } catch {
    policyCount.value = null
  }
}

async function restore() {
  try {
    await ElMessageBox.confirm(
      '将用镜像内的出厂默认配置覆盖配置目录里的文件：场景模板、衍生规则，并让 AI 服务把检索策略恢复为出厂策略。'
        + '配置目录里手工加过的自定义内容会丢失，已建知识库不受影响。',
      '恢复出厂默认配置',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' },
    )
  } catch (action) {
    // 只有取消/关闭才静默结束。其它异常若也吞掉，按钮看起来就是「点了没反应」。
    if (action === 'cancel' || action === 'close') return
    ElMessage.error('无法打开确认框：' + String(action))
    return
  }
  restoring.value = true
  message.value = ''
  try {
    const result = (await restoreDefaultCatalog()).data.data
    if (!result) {
      messageError.value = true
      message.value = '恢复失败：接口未返回结果'
      return
    }
    const backend = result.backend
    const parts: string[] = []
    if (!backend.configured) {
      parts.push('后端未配置配置目录（KNOWLEDGE_CONFIG_DIR），默认配置直接读镜像内那份，没有外部文件可恢复')
    } else if (backend.failed.length) {
      parts.push(`后端 ${backend.failed.length} 个文件写入失败（配置目录可能是只读挂载）：${backend.failed.join('、')}`)
    } else {
      parts.push(`后端恢复 ${backend.written.length} 个文件`
        + `（场景 ${backend.scenarioTemplates.count} 条、规则 ${backend.derivationRules.count} 条）`)
    }
    if (result.aiService.ok) {
      const names = result.aiService.result?.profiles ?? []
      parts.push(`检索策略 ${names.length} 个` + (names.length ? `：${names.join('、')}` : ''))
    } else {
      parts.push('AI 服务恢复失败：' + (result.aiService.error || '未知错误'))
    }
    messageError.value = !result.allRestored
    message.value = (result.allRestored ? '已恢复出厂默认配置。' : '部分完成：') + parts.join('；') + '。'
    // 面板底部的说明容易被忽略，结果再弹一次，避免「点了没反应」的错觉。
    if (result.allRestored) ElMessage.success('已恢复出厂默认配置')
    else ElMessage.warning('恢复未全部成功，详见面板底部说明')
    await load()
  } catch (e) {
    messageError.value = true
    message.value = '恢复失败：' + String(e)
    ElMessage.error('恢复失败：' + String(e))
  } finally {
    restoring.value = false
  }
}

onMounted(load)
</script>
<style scoped>
.sd-panel{padding:2px 0}
.sd-desc{margin:0 0 12px;font-size:12.5px;line-height:1.7;color:var(--ink-2)}
.sd-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:8px}
.sd-card{padding:10px 12px;border-radius:10px;border:1px solid var(--line-d);background:var(--bg-raised)}
.sd-card-head{display:flex;align-items:baseline;justify-content:space-between;gap:8px;margin-bottom:6px}
.sd-card-head b{font-size:13px;color:var(--ink-1)}
.sd-card-head em{font-style:normal;font-size:11px;color:var(--ink-3)}
.sd-source{font-size:11.5px;line-height:1.6;color:var(--ink-2);word-break:break-all}
.sd-source.warn{color:var(--danger)}
.sd-warn{margin:10px 0 0;font-size:12px;line-height:1.7;color:var(--danger)}
.sd-bar{display:flex;align-items:center;gap:8px;margin-top:12px}
.sd-grow{flex:1}
.sd-hint{padding:16px 0;font-size:12.5px;color:var(--ink-3)}
.sd-msg{margin:10px 0 0;font-size:12.5px;color:var(--ok)}
.sd-msg.error{color:var(--danger)}
</style>
