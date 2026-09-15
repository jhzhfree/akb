<template>
  <el-container class="app-shell">
    <el-aside width="230px" class="aside">
      <h2>AI 知识操作系统</h2>
      <el-menu :default-active="active" background-color="#111827" text-color="#cbd5e1"
        active-text-color="#fff" @select="active = $event">
        <el-menu-item index="data">数据中心</el-menu-item>
        <el-menu-item index="review">知识裁决</el-menu-item>
        <el-menu-item index="query">智能查询</el-menu-item>
        <el-menu-item index="cards">知识卡片</el-menu-item>
        <el-menu-item index="claims">断言</el-menu-item>
        <el-menu-item index="graph">知识图谱</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main>
      <div class="topbar">
        <div>
          <strong>V1.1 开发版</strong>
          <span class="muted"> · 知识生产、裁决、图谱、混合检索</span>
        </div>
        <div v-if="capabilities">
          <el-tag :type="capabilities.graph?.available ? 'success' : 'info'">
            Graph: {{ capabilities.graph?.provider }}
          </el-tag>
          <el-tag class="capability-tag">Reranker: {{ capabilities.reranker?.provider }}</el-tag>
        </div>
      </div>

      <DataCenterPanel v-if="active === 'data'" v-model:authority="authority"
        :documents="documents" :uploading="uploading" @pick="pick" @remove="remove" @upload="upload" />
      <ReviewPanel v-else-if="active === 'review'" :pending-reviews="pendingReviews"
        :conflicts="conflicts" :assessments="assessments" :adjudicating="adjudicating"
        @adjudicate="runIntelligentAdjudication" @review="review" @resolve="resolveConflict" />
      <QueryPanel v-else-if="active === 'query'" v-model:query="q" :result="result" :asking="asking" @ask="ask" />
      <KnowledgeListPanel v-else-if="active === 'cards'" mode="cards" :items="cards" @open-graph="openGraph" />
      <KnowledgeListPanel v-else-if="active === 'claims'" mode="claims" :items="claims" @open-graph="openGraph" />
      <GraphPanel v-else v-model:code="graphCode" :result="graphResult" @load="loadGraph" />
    </el-main>
  </el-container>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import DataCenterPanel from './components/DataCenterPanel.vue'
import ReviewPanel from './components/ReviewPanel.vue'
import QueryPanel from './components/QueryPanel.vue'
import KnowledgeListPanel from './components/KnowledgeListPanel.vue'
import GraphPanel from './components/GraphPanel.vue'
import {
  getAssessments,
  getCapabilities,
  getCards,
  getClaims,
  getDocuments,
  getGraph,
  getOpenConflicts,
  getPendingReviews,
  queryKnowledge,
  resolveKnowledgeConflict,
  reviewKnowledge,
  runAdjudication,
  unwrap,
  uploadDocument,
} from './api/knowledge'

// Page state is intentionally local in V1.1. Move it into stores only when
// cross-page state or routing becomes complex enough to justify that overhead.
const active = ref('data')
const file = ref(null)
const authority = ref(0.85)
const documents = ref([])
const cards = ref([])
const claims = ref([])
const pendingReviews = ref([])
const conflicts = ref([])
const capabilities = ref(null)
const assessments = ref([])
const q = ref('知识为什么需要裁决？')
const result = ref(null)
const graphCode = ref('')
const graphResult = ref(null)
const uploading = ref(false)
const asking = ref(false)
const adjudicating = ref(false)

function pick(uploadFile) {
  file.value = uploadFile.raw
}

function remove() {
  file.value = null
}

async function loadDataCenter() {
  documents.value = unwrap(await getDocuments(), [])
}

async function loadReviewWorkspace() {
  // Review panels are independent. One failed endpoint should not blank the
  // whole workspace, so use allSettled rather than fail-fast Promise.all.
  const tasks = await Promise.allSettled([
    getPendingReviews(),
    getOpenConflicts(),
    getAssessments(),
  ])
  if (tasks[0].status === 'fulfilled') {
    pendingReviews.value = unwrap(tasks[0].value, [])
  }
  if (tasks[1].status === 'fulfilled') {
    conflicts.value = unwrap(tasks[1].value, [])
  }
  if (tasks[2].status === 'fulfilled') {
    assessments.value = unwrap(tasks[2].value, [])
  }
}

async function loadActiveSection() {
  try {
    // Load only data required by the current page. This avoids the historical
    // behavior where every tab change fan-out requested all backend resources.
    if (active.value === 'data') await loadDataCenter()
    if (active.value === 'review') await loadReviewWorkspace()
    if (active.value === 'cards') cards.value = unwrap(await getCards(), [])
    if (active.value === 'claims') claims.value = unwrap(await getClaims(), [])
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '加载失败')
  }
}

async function upload() {
  if (!file.value) return ElMessage.warning('请先选择文件')
  uploading.value = true
  try {
    const data = unwrap(await uploadDocument(file.value, authority.value), {})
    ElMessage.success(`处理完成：${data.chunkCount || 0} 个内容块`)
    await loadDataCenter()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

async function ask() {
  asking.value = true
  try {
    result.value = unwrap(await queryKnowledge(q.value), null)
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '查询失败')
  } finally {
    asking.value = false
  }
}

async function runIntelligentAdjudication() {
  adjudicating.value = true
  try {
    const data = unwrap(await runAdjudication(), {})
    ElMessage.success(
      `裁决完成：自动批准 ${data.autoApproved || 0}，人工复核 ${data.requiresHumanReview || 0}，冲突 ${data.conflicted || 0}`,
    )
    await loadReviewWorkspace()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '智能裁决失败')
  } finally {
    adjudicating.value = false
  }
}

async function review(row, decision) {
  await reviewKnowledge(row, decision)
  ElMessage.success('裁决完成')
  await loadReviewWorkspace()
}

async function resolveConflict(row, status) {
  await resolveKnowledgeConflict(row.id, status)
  ElMessage.success('冲突状态已更新')
  await loadReviewWorkspace()
}

async function openGraph(code) {
  graphCode.value = code
  active.value = 'graph'
  await loadGraph()
}

async function loadGraph() {
  if (!graphCode.value) return ElMessage.warning('请输入对象代码')
  graphResult.value = unwrap(await getGraph(graphCode.value), null)
}

watch(active, loadActiveSection)

onMounted(async () => {
  const capabilityResult = await Promise.allSettled([getCapabilities()])
  if (capabilityResult[0].status === 'fulfilled') {
    capabilities.value = unwrap(capabilityResult[0].value, null)
  }
  await loadActiveSection()
})
</script>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
  font-family: 'Microsoft YaHei', Arial, sans-serif;
}
.app-shell { height: 100vh; }
.aside { background: #111827; color: #fff; padding-top: 12px; }
.aside h2 { padding: 0 18px; }
.topbar { display: flex; justify-content: space-between; align-items: center; padding: 10px 0 18px;
  border-bottom: 1px solid #e5e7eb; margin-bottom: 18px; }
.muted { color: #64748b; }
.capability-tag { margin-left: 8px; }
</style>
