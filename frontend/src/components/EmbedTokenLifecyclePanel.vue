<template>
  <section class="card">
    <div class="lifecycle-head">
      <h3>4. 令牌生命周期：吊销与调用日志</h3>
      <div class="row">
        <el-select v-model="windowHours" size="small" style="width:120px" @change="load">
          <el-option :value="24" label="近 24 小时" />
          <el-option :value="168" label="近 7 天" />
          <el-option :value="720" label="近 30 天" />
        </el-select>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <p class="hint">
      每个令牌签发时带唯一 ID（jti），调用日志按令牌逐条记录：可以查到是哪个站点、哪个令牌、在什么时候问了什么。
      吊销单个令牌立即生效，且<b>不影响同一 API Key 签发的其他令牌</b>；误吊销可恢复。
    </p>

    <h4>窗口内出现过的令牌</h4>
    <el-table :data="tokens" size="small" v-loading="loading">
      <el-table-column prop="displayId" label="令牌" width="110" />
      <el-table-column label="来源站点" min-width="150">
        <template #default="{ row }">{{ row.origin || '—' }}</template>
      </el-table-column>
      <el-table-column label="API Key" min-width="130">
        <template #default="{ row }">{{ row.keyCode || row.keyId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="calls" label="调用" width="70" />
      <el-table-column label="失败" width="70">
        <template #default="{ row }">
          <span :class="{ 'err-text': (row.errors || 0) > 0 }">{{ row.errors || 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近调用" width="150">
        <template #default="{ row }">{{ fmtTime(row.lastCallTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="86">
        <template #default="{ row }">
          <el-tag :type="row.revoked ? 'danger' : 'success'" size="small">
            {{ row.revoked ? '已吊销' : '有效' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="viewCalls(row.jti)">日志</el-button>
          <el-button v-if="!row.revoked" link type="danger" @click="openRevoke(row)">吊销</el-button>
          <el-button v-else link type="success" @click="restore(row.jti)">恢复</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4>
      调用日志
      <span v-if="filterJti" class="filter-tag">
        · 仅看令牌 {{ filterJti.slice(0, 4) }}…{{ filterJti.slice(-4) }}
        <el-button link type="primary" @click="clearFilter">查看全部</el-button>
      </span>
    </h4>
    <el-table :data="calls" size="small" v-loading="callsLoading" max-height="360">
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createdTime) }}</template>
      </el-table-column>
      <el-table-column label="来源站点" min-width="140">
        <template #default="{ row }">{{ row.origin || '—' }}</template>
      </el-table-column>
      <el-table-column label="令牌 / Key" width="150">
        <template #default="{ row }">
          <span>{{ row.displayId || '—' }}</span>
          <div class="sub">{{ row.keyCode || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="200">
        <template #default="{ row }">{{ questionOf(row) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="76">
        <template #default="{ row }">
          <el-tag :type="failed(row) ? 'danger' : 'success'" size="small">{{ row.statusCode ?? '—' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">{{ row.durationMs == null ? '—' : row.durationMs + 'ms' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!row.jti" @click="viewCalls(row.jti)">只看它</el-button>
          <el-button v-if="row.jti" link type="danger" @click="openRevoke(row)">吊销</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4>已吊销名单</h4>
    <el-table :data="revocations" size="small">
      <el-table-column label="令牌" width="110">
        <template #default="{ row }">{{ row.jti.slice(0, 4) }}…{{ row.jti.slice(-4) }}</template>
      </el-table-column>
      <el-table-column label="来源站点" min-width="150">
        <template #default="{ row }">{{ row.origin || '—' }}</template>
      </el-table-column>
      <el-table-column label="API Key" min-width="130">
        <template #default="{ row }">{{ row.keyCode || row.keyId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="160">
        <template #default="{ row }">{{ row.reason || '—' }}</template>
      </el-table-column>
      <el-table-column label="吊销时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.revokedTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="success" @click="restore(row.jti)">恢复</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert v-if="error" type="error" :closable="false" style="margin-top:12px">{{ error }}</el-alert>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchEmbedTokenCalls, fetchEmbedTokenLifecycle, restoreEmbedToken, revokeEmbedToken } from '../api/external'
import type { EmbedTokenCall, EmbedTokenRevocationItem, EmbedTokenSummary } from '../api/external'
import { unwrap } from '../api/knowledge'
import { errorMessage } from '../utils/feedback'

const windowHours = ref(168)
const tokens = ref<EmbedTokenSummary[]>([])
const calls = ref<EmbedTokenCall[]>([])
const revocations = ref<EmbedTokenRevocationItem[]>([])
const loading = ref(false)
const callsLoading = ref(false)
const filterJti = ref('')
const error = ref('')

async function load() {
  error.value = ''
  loading.value = true
  try {
    const data = unwrap(await fetchEmbedTokenLifecycle(windowHours.value, 50), null)
    tokens.value = data?.tokens ?? []
    revocations.value = data?.revocations ?? []
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
  await loadCalls()
}

async function loadCalls() {
  callsLoading.value = true
  try {
    calls.value = unwrap(await fetchEmbedTokenCalls(filterJti.value || null, null, 50), [])
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    callsLoading.value = false
  }
}

function viewCalls(jti: string | null) {
  if (!jti) {
    ElMessage.warning('这条调用没有令牌 ID（v1 旧令牌），只能吊销其 API Key')
    return
  }
  filterJti.value = jti
  loadCalls()
}

function clearFilter() {
  filterJti.value = ''
  loadCalls()
}

async function openRevoke(row: EmbedTokenSummary | EmbedTokenCall) {
  const jti = row.jti
  if (!jti) {
    ElMessage.warning('这条调用没有令牌 ID（v1 旧令牌），只能吊销其 API Key')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt(
      `吊销后该令牌立即失效，同一 API Key 签发的其他令牌不受影响。\n令牌：${jti.slice(0, 4)}…${jti.slice(-4)}`,
      '吊销嵌入令牌',
      { inputPlaceholder: '吊销原因（可选，会记入吊销名单）', inputValue: '', confirmButtonText: '确认吊销', cancelButtonText: '取消' },
    )
    await revokeEmbedToken({
      jti,
      keyId: row.keyId ?? null,
      keyCode: row.keyCode ?? null,
      knowledgeBaseId: row.knowledgeBaseId ?? null,
      origin: row.origin ?? null,
      reason: value || null,
    })
    ElMessage.success('令牌已吊销，立即生效')
    await load()
  } catch (e) {
    // 取消输入会走这里，不提示错误
    if (e !== 'cancel' && errorMessage(e) !== 'cancel') {
      ElMessage.error(errorMessage(e))
    }
  }
}

async function restore(jti: string) {
  try {
    const data = unwrap(await restoreEmbedToken(jti), null)
    if (data?.restored) {
      ElMessage.success('已恢复该令牌')
    } else {
      ElMessage.warning('该令牌不在吊销名单中')
    }
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  }
}

function fmtTime(value: string | null) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

function failed(row: EmbedTokenCall) {
  return (row.statusCode ?? 0) >= 400
}

/** 日志里的"内容"：优先取提问，其次取失败原因 */
function questionOf(row: EmbedTokenCall) {
  if (row.requestParams) {
    try {
      const parsed = JSON.parse(row.requestParams)
      const picked = parsed?.question ?? parsed?.query ?? parsed?.q
      if (picked) return String(picked)
    } catch {
      /* 不是 JSON 就退回原始文本 */
    }
    return row.requestParams.length > 80 ? `${row.requestParams.slice(0, 80)}…` : row.requestParams
  }
  if (row.message) return row.message
  return `${row.channel} ${row.method}`
}

onMounted(load)
</script>

<style scoped>
.lifecycle-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card { background: #fff; border-radius: 12px; padding: 16px 18px; box-shadow: 0 6px 18px rgba(15,23,42,.06); margin-bottom: 14px; }
.card h3 { margin: 0; font-size: 15px; }
.card h4 { margin: 18px 0 8px; font-size: 13.5px; color: #334155; }
.row { display: flex; align-items: center; gap: 10px; }
.hint { font-size: 12.5px; color: #94a3b8; line-height: 1.7; margin: 10px 0 0; }
.sub { font-size: 11.5px; color: #94a3b8; }
.err-text { color: #dc2626; }
.filter-tag { font-weight: 400; font-size: 12.5px; color: #64748b; }
</style>
