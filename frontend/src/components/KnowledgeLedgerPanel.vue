<template>
  <section class="kbl-panel">
    <header class="kbl-toolbar">
      <div class="kbl-filter">
        <span class="kbl-filter-label">筛选</span>
        <el-radio-group v-model="filterKey" size="small">
          <el-radio-button v-for="f in filters" :key="f.key" :value="f.key">{{ f.label }}</el-radio-button>
        </el-radio-group>
        <el-input v-model="keyword" size="small" clearable placeholder="按名称搜索"
          class="kbl-search" />
      </div>
      <div class="kbl-stats">
        <span>共 {{ filteredSources.length }} / {{ sources.length }} 条</span>
        <span v-if="expiredCount" class="kbl-stat-warn">已过期 {{ expiredCount }}</span>
        <span v-if="expiringSoonCount" class="kbl-stat-warn">30 天内到期 {{ expiringSoonCount }}</span>
        <span v-if="noOwnerCount" class="kbl-stat-warn">无责任人 {{ noOwnerCount }}</span>
      </div>
    </header>

    <el-table
      ref="tableRef"
      :data="filteredSources"
      :row-key="(row:KnowledgeSource) => row.id"
      @selection-change="onSelectionChange"
      class="kbl-table"
      empty-text="当前筛选下没有来源，台账是空的">
      <el-table-column type="selection" width="44" />
      <el-table-column prop="name" label="名称" min-width="220">
        <template #default="{ row }">
          <div class="kbl-name">
            <span class="kbl-name-main">{{ row.name }}</span>
            <span class="kbl-name-sub">{{ row.sourceType || '—' }} · {{ row.sourceCode }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="责任人" width="160">
        <template #default="{ row }">
          <span v-if="row.ownerUserId" :class="['kbl-owner', isDisabledUser(row.ownerUserId) && 'kbl-warn']">
            {{ ownerName(row.ownerUserId) }}
          </span>
          <span v-else class="kbl-warn">未登记</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ARCHIVED' ? 'info' : 'success'" effect="light">
            {{ row.status === 'ARCHIVED' ? '已废弃' : '在用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生效" width="120">
        <template #default="{ row }">{{ formatDate(row.effectiveAt) || '—' }}</template>
      </el-table-column>
      <el-table-column label="失效" width="120">
        <template #default="{ row }">
          <span :class="isExpired(row) && 'kbl-warn'">{{ formatDate(row.expireAt) || '未登记' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="scope" label="适用范围" min-width="160">
        <template #default="{ row }">
          <span v-if="row.scope">{{ row.scope }}</span>
          <span v-else class="kbl-muted">未登记</span>
        </template>
      </el-table-column>
    </el-table>

    <footer v-if="selectedIds.length" class="kbl-footer">
      <span>已选 {{ selectedIds.length }} 条</span>
      <div class="kbl-footer-actions">
        <el-button @click="clearSelection">取消选择</el-button>
        <el-button @click="quickArchive" :loading="busy" type="warning" plain>标记废弃</el-button>
        <el-button @click="openEditor" type="primary" plain>维护身份证</el-button>
      </div>
    </footer>

    <el-dialog
      v-model="editorVisible" title="台账身份证维护" width="520px"
      :close-on-click-modal="false" @open="resetEditor">
      <p class="kbl-hint">只更新勾选的来源。表单里留空的字段不会被改动——盘点是一列一列补的活儿。</p>
      <el-form label-width="80px" size="default">
        <el-form-item label="责任人">
          <el-select v-model="editor.ownerUserId" clearable filterable placeholder="指定维护人"
            style="width:100%">
            <el-option v-for="u in userOptions" :key="u.id" :value="u.id"
              :label="u.displayName || u.username" :disabled="u.status !== 'ACTIVE'">
              <span>{{ u.displayName || u.username }}</span>
              <span class="kbl-muted">@{{ u.username }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="editor.effectiveAt" type="date" value-format="YYYY-MM-DD"
            placeholder="如：2026-01-01" style="width:100%" />
        </el-form-item>
        <el-form-item label="失效日期">
          <el-date-picker v-model="editor.expireAt" type="date" value-format="YYYY-MM-DD"
            placeholder="如：2027-01-01" style="width:100%" />
        </el-form-item>
        <el-form-item label="适用范围">
          <el-input v-model="editor.scope" placeholder="如：全公司 / 财务部 / 支付产品线" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editor.status">
            <el-radio-button value="">不动</el-radio-button>
            <el-radio-button value="ACTIVE">在用</el-radio-button>
            <el-radio-button value="ARCHIVED">废弃</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible=false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitEditor">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
/**
 * Knowledge ledger.
 *
 * The window onto every authoritative origin: who keeps it current, when does it stop
 * being authoritative, who may cite it. Stock-take starts here — a column-by-column sweep
 * that ends with the same handful of "no owner" / "no expiry" warnings as before, but
 * with the list of unowned sources now actually shorter.
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { KnowledgeSource, UserAccount } from '../types-legacy'
import { listUsers } from '../api/auth'
import { updateSourceGovernance } from '../api/knowledge-impl'
import { unwrap } from '../api/knowledge'
import { useShell } from '../shell'
import { errorMessage as message } from '../utils/feedback'

const props = defineProps<{
  knowledgeBaseId:number
  sources:KnowledgeSource[]
}>()

const shell = useShell()
const keyword = ref('')
const filterKey = ref<'all' | 'expired' | 'expiring' | 'noOwner' | 'noDates' | 'archived'>('all')
const tableRef = ref()
const selectedRows = ref<KnowledgeSource[]>([])
const userOptions = ref<UserAccount[]>([])
const editorVisible = ref(false)
const busy = ref(false)
/** Each field is null while untouched: only changed fields go on the wire, so a stock-take
 *  filling in owners must not silently clear the expiry date. */
const editor = ref<{ ownerUserId:number|null; effectiveAt:string|null; expireAt:string|null; scope:string; status:string }>({
  ownerUserId:null, effectiveAt:null, expireAt:null, scope:'', status:'',
})

const filters = [
  { key:'all', label:'全部' },
  { key:'expired', label:'已过期' },
  { key:'expiring', label:'30 天内到期' },
  { key:'noOwner', label:'无责任人' },
  { key:'noDates', label:'无有效期' },
  { key:'archived', label:'已废弃' },
] as const

onMounted(async() => {
  // User list is admin-only; the ledger itself is also admin-only at the navigation level,
  // so this is fine. If the call ever opens up, fall back to "type a user id" gracefully.
  try {
    userOptions.value = unwrap(await listUsers(), []) as UserAccount[]
  } catch {
    userOptions.value = []
  }
})

const isExpired = (row:KnowledgeSource) => {
  if (!row.expireAt) {
    return false
  }
  return new Date(row.expireAt).getTime() < Date.now()
}
const isExpiringSoon = (row:KnowledgeSource) => {
  if (!row.expireAt) {
    return false
  }
  const diff = new Date(row.expireAt).getTime() - Date.now()
  return diff > 0 && diff <= 30 * 24 * 60 * 60 * 1000
}
const ownerName = (id:number) => {
  const u = userOptions.value.find(v => v.id === id)
  return u ? (u.displayName || u.username) : `用户#${id}`
}
/** Owner points at a disabled / missing user — a softer warning than "unowned" but still
 *  a yellow flag the operator should chase down. */
const isDisabledUser = (id:number) => {
  const u = userOptions.value.find(v => v.id === id)
  return !u || u.status !== 'ACTIVE'
}

const filteredSources = computed<KnowledgeSource[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  return props.sources.filter(row => {
    if (k && !row.name.toLowerCase().includes(k) && !row.sourceCode.toLowerCase().includes(k)) {
      return false
    }
    switch (filterKey.value) {
      case 'expired': return isExpired(row)
      case 'expiring': return isExpiringSoon(row)
      case 'noOwner': return !row.ownerUserId
      case 'noDates': return !row.effectiveAt && !row.expireAt
      case 'archived': return row.status === 'ARCHIVED'
      default: return true
    }
  })
})

const expiredCount = computed(() => props.sources.filter(isExpired).length)
const expiringSoonCount = computed(() => props.sources.filter(isExpiringSoon).length)
const noOwnerCount = computed(() => props.sources.filter(v => !v.ownerUserId).length)

const selectedIds = computed(() => selectedRows.value.map(v => v.id))

const onSelectionChange = (rows:KnowledgeSource[]) => {
  selectedRows.value = rows
}
const clearSelection = () => {
  selectedRows.value = []
  tableRef.value?.clearSelection()
}

const openEditor = () => {
  editorVisible.value = true
}
const resetEditor = () => {
  editor.value = { ownerUserId:null, effectiveAt:null, expireAt:null, scope:'', status:'' }
}

const quickArchive = async() => {
  if (!selectedIds.value.length) {
    return
  }
  try {
    await ElMessageBox.confirm(`确认将 ${selectedIds.value.length} 条来源标记为废弃？`, '批量废弃', { type:'warning' })
  } catch {
    return
  }
  busy.value = true
  try {
    const result = unwrap(
      await updateSourceGovernance(props.knowledgeBaseId, { ids:selectedIds.value, status:'ARCHIVED' }),
      null,
    ) as { updated:number } | null
    ElMessage.success(`已废弃 ${result?.updated ?? 0} 条来源`)
    await shell.refresh()
  } catch (e) {
    message(e)
  } finally {
    busy.value = false
  }
}

const submitEditor = async() => {
  if (!selectedIds.value.length) {
    return
  }
  /** Only fields the operator actually touched ride on the wire. Null means "leave alone",
   *  which matters when the form is partly filled. */
  const payload:Record<string, unknown> = { ids:selectedIds.value }
  if (editor.value.ownerUserId !== null) {
    payload.ownerUserId = editor.value.ownerUserId
  }
  if (editor.value.effectiveAt !== null) {
    payload.effectiveAt = editor.value.effectiveAt
  }
  if (editor.value.expireAt !== null) {
    payload.expireAt = editor.value.expireAt
  }
  if (editor.value.scope !== '') {
    payload.scope = editor.value.scope
  }
  if (editor.value.status !== '') {
    payload.status = editor.value.status
  }
  if (Object.keys(payload).length === 1) {
    ElMessage.warning('请至少填写一项要维护的字段')
    return
  }
  busy.value = true
  try {
    const result = unwrap(
      await updateSourceGovernance(props.knowledgeBaseId, payload),
      null,
    ) as { updated:number } | null
    ElMessage.success(`已更新 ${result?.updated ?? 0} 条来源`)
    editorVisible.value = false
    await shell.refresh()
  } catch (e) {
    message(e)
  } finally {
    busy.value = false
  }
}

const formatDate = (v?:string|null) => v ? v.slice(0, 10) : ''
</script>

<style scoped>
.kbl-panel{display:flex;flex-direction:column;gap:12px;padding:4px 2px 12px}
.kbl-toolbar{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px}
.kbl-filter{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.kbl-filter-label{font-size:12px;color:var(--ink-3)}
.kbl-search{width:220px}
.kbl-stats{display:flex;gap:12px;font-size:12px;color:var(--ink-3)}
.kbl-stat-warn{color:#d97706}
.kbl-table :deep(.cell){white-space:nowrap}
.kbl-name{display:flex;flex-direction:column;line-height:1.3}
.kbl-name-main{font-weight:500}
.kbl-name-sub{font-size:11px;color:var(--ink-3)}
.kbl-warn{color:#d97706}
.kbl-muted{color:var(--ink-3);font-size:12px;margin-left:6px}
.kbl-footer{
  position:sticky;bottom:0;display:flex;justify-content:space-between;align-items:center;
  padding:10px 14px;background:var(--surface-1);border:1px solid var(--line-1);border-radius:6px
}
.kbl-footer-actions{display:flex;gap:8px}
.kbl-hint{color:var(--ink-3);font-size:12px;margin:0 0 12px}
</style>
