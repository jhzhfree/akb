<template>
<div class="ua">
  <section class="ua-block">
    <div class="ua-head">
      <div><b>用户管理</b><small>维护登录账号、平台角色与用户组归属</small></div>
      <div class="ua-actions">
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <el-button size="small" type="primary" @click="openCreateUser">新建用户</el-button>
      </div>
    </div>
    <el-table :data="users" size="small" border stripe v-loading="loading">
      <el-table-column prop="username" label="账号" width="140"/>
      <el-table-column label="显示名" width="150">
        <template #default="{ row }">{{ row.displayName || '—' }}</template>
      </el-table-column>
      <el-table-column label="邮箱" min-width="200">
        <template #default="{ row }">{{ row.email || '—' }}</template>
      </el-table-column>
      <el-table-column label="角色" width="170">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ userRoleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用户组" min-width="180">
        <template #default="{ row }">{{ row.groupNames?.length ? row.groupNames.join('、') : '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small" effect="plain">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="180">
        <template #default="{ row }">{{ row.lastLoginTime || '未登录' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEditUser(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openResetPassword(row)">重置密码</el-button>
          <el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" size="small"
            @click="toggleStatus(row)">
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <section class="ua-block">
    <div class="ua-head">
      <div><b>用户组</b><small>知识库选择「用户组」可见时，仅组内成员可访问</small></div>
      <div class="ua-actions">
        <el-button size="small" type="primary" @click="openCreateGroup">新建用户组</el-button>
      </div>
    </div>
    <el-table :data="groups" size="small" border stripe>
      <el-table-column prop="name" label="名称" width="200"/>
      <el-table-column prop="groupCode" label="编码" width="200"/>
      <el-table-column label="成员" min-width="220">
        <template #default="{ row }">{{ memberNames(row) || '暂无成员' }}</template>
      </el-table-column>
      <el-table-column prop="memberCount" label="成员数" width="90"/>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openGroupMembers(row)">成员</el-button>
          <el-button link type="primary" size="small" @click="openEditGroup(row)">重命名</el-button>
          <el-button link type="danger" size="small" @click="archiveGroup(row)">归档</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="userDialog" :title="editingUser ? '编辑用户' : '新建用户'" width="480px">
    <el-form label-width="88px" size="small">
      <el-form-item label="账号">
        <el-input v-model="userForm.username" :disabled="!!editingUser" placeholder="登录用户名"/>
      </el-form-item>
      <el-form-item v-if="!editingUser" label="初始密码">
        <el-input v-model="userForm.password" type="password" show-password placeholder="至少 8 位"/>
      </el-form-item>
      <el-form-item label="显示名">
        <el-input v-model="userForm.displayName" placeholder="展示名称，留空同账号"/>
      </el-form-item>
      <el-form-item label="邮箱">
        <el-input v-model="userForm.email"/>
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="userForm.role" class="w-full">
          <el-option v-for="role in ROLES" :key="role" :value="role" :label="userRoleLabel(role)"/>
        </el-select>
      </el-form-item>
      <el-form-item label="用户组">
        <el-select v-model="userForm.groupIds" multiple class="w-full" placeholder="可加入多个用户组">
          <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="userDialog=false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="saveUser">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="passwordDialog" title="重置密码" width="420px">
    <el-input v-model="newPassword" type="password" show-password placeholder="新密码，至少 8 位"/>
    <p class="ua-tip">重置后该账号的所有会话立即失效，需要重新登录。</p>
    <template #footer>
      <el-button size="small" @click="passwordDialog=false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="savePassword">确认重置</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="groupDialog" :title="editingGroup ? '重命名用户组' : '新建用户组'" width="420px">
    <el-form label-width="88px" size="small">
      <el-form-item label="名称">
        <el-input v-model="groupForm.name"/>
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="groupForm.description" type="textarea" :rows="2"/>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="groupDialog=false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="saveGroup">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="memberDialog" title="用户组成员" width="460px">
    <el-select v-model="memberIds" multiple filterable class="w-full" placeholder="选择成员">
      <el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.displayName}（${u.username}）`"/>
    </el-select>
    <p class="ua-tip">组成员可访问可见范围为用户组的知识库。</p>
    <template #footer>
      <el-button size="small" @click="memberDialog=false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="saveMembers">保存</el-button>
    </template>
  </el-dialog>
</div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createGroup, createUser, listGroups, listUsers, resetUserPassword,
  setGroupMembers, setGroupStatus, setUserStatus, updateGroup, updateUser,
} from '../api/auth'
import { unwrap } from '../api/knowledge'
import type { UserAccount, UserGroupRow, UserRoleValue } from '../types'
import { statusLabel, userRoleLabel } from '../types'
import { errorMessage as message } from '../utils/feedback'

const ROLES:UserRoleValue[] = ['ADMIN', 'KNOWLEDGE_ADMIN', 'EDITOR', 'VIEWER']

const users = ref<UserAccount[]>([])
const groups = ref<UserGroupRow[]>([])
const loading = ref(false)
const saving = ref(false)

const userDialog = ref(false)
const editingUser = ref<UserAccount | null>(null)
const userForm = ref<{
  username:string; password:string; displayName:string; email:string
  role:UserRoleValue; groupIds:number[]
}>({ username:'', password:'', displayName:'', email:'', role:'VIEWER', groupIds:[] })

const passwordDialog = ref(false)
const passwordUser = ref<UserAccount | null>(null)
const newPassword = ref('')

const groupDialog = ref(false)
const editingGroup = ref<UserGroupRow | null>(null)
const groupForm = ref({ name:'', description:'' })

const memberDialog = ref(false)
const memberGroup = ref<UserGroupRow | null>(null)
const memberIds = ref<number[]>([])

async function load() {
  loading.value = true
  try {
    const [u, g] = await Promise.all([listUsers(), listGroups()])
    users.value = unwrap(u, [])
    groups.value = unwrap(g, [])
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    loading.value = false
  }
}

function memberNames(group:UserGroupRow):string {
  return (group.memberIds || [])
    .map(id => users.value.find(u => u.id === id)?.displayName || '')
    .filter(Boolean)
    .join('、')
}

function openCreateUser() {
  editingUser.value = null
  userForm.value = {
    username:'', password:'', displayName:'', email:'', role:'VIEWER', groupIds:[],
  }
  userDialog.value = true
}

function openEditUser(row:UserAccount) {
  editingUser.value = row
  userForm.value = {
    username:row.username, password:'', displayName:row.displayName || '',
    email:row.email || '', role:row.role as UserRoleValue, groupIds:[...(row.groupIds || [])],
  }
  userDialog.value = true
}

async function saveUser() {
  saving.value = true
  try {
    if (editingUser.value) {
      await updateUser(editingUser.value.id, {
        displayName:userForm.value.displayName,
        email:userForm.value.email,
        role:userForm.value.role,
        groupIds:userForm.value.groupIds,
      })
    } else {
      await createUser({
        username:userForm.value.username,
        password:userForm.value.password,
        displayName:userForm.value.displayName,
        email:userForm.value.email,
        role:userForm.value.role,
        groupIds:userForm.value.groupIds,
      })
    }
    ElMessage.success('用户已保存')
    userDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row:UserAccount) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try {
    await setUserStatus(row.id, next)
    ElMessage.success(`账号已${next === 'ACTIVE' ? '启用' : '停用'}`)
    await load()
  } catch (e) {
    ElMessage.error(message(e))
  }
}

function openResetPassword(row:UserAccount) {
  passwordUser.value = row
  newPassword.value = ''
  passwordDialog.value = true
}

async function savePassword() {
  if (!passwordUser.value) return
  if (newPassword.value.trim().length < 8) return ElMessage.warning('密码至少 8 位')
  saving.value = true
  try {
    await resetUserPassword(passwordUser.value.id, newPassword.value.trim())
    ElMessage.success('密码已重置')
    passwordDialog.value = false
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    saving.value = false
  }
}

function openCreateGroup() {
  editingGroup.value = null
  groupForm.value = { name:'', description:'' }
  groupDialog.value = true
}

function openEditGroup(row:UserGroupRow) {
  editingGroup.value = row
  groupForm.value = { name:row.name, description:row.description || '' }
  groupDialog.value = true
}

async function saveGroup() {
  if (!groupForm.value.name.trim()) return ElMessage.warning('请填写用户组名称')
  saving.value = true
  try {
    if (editingGroup.value) {
      await updateGroup(editingGroup.value.id, groupForm.value.name.trim(), groupForm.value.description)
    } else {
      await createGroup(groupForm.value.name.trim(), groupForm.value.description)
    }
    ElMessage.success('用户组已保存')
    groupDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    saving.value = false
  }
}

async function archiveGroup(row:UserGroupRow) {
  try {
    await ElMessageBox.confirm(
      `归档后「${row.name}」不再作为知识库的共享范围，确认归档？`, '归档用户组', { type:'warning' },
    )
  } catch {
    return
  }
  try {
    await setGroupStatus(row.id, 'ARCHIVED')
    ElMessage.success('用户组已归档')
    await load()
  } catch (e) {
    ElMessage.error(message(e))
  }
}

function openGroupMembers(row:UserGroupRow) {
  memberGroup.value = row
  memberIds.value = [...(row.memberIds || [])]
  memberDialog.value = true
}

async function saveMembers() {
  if (!memberGroup.value) return
  saving.value = true
  try {
    await setGroupMembers(memberGroup.value.id, memberIds.value)
    ElMessage.success('成员已更新')
    memberDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    saving.value = false
  }
}

onMounted(load)
defineExpose({ load })
</script>
<style scoped>
.ua{display:flex;flex-direction:column;gap:16px}
.ua-block{border:1px solid #e5e7eb;border-radius:12px;background:#fff;padding:14px}
.ua-head{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:10px}
.ua-head b{font-size:13px;color:#0f172a}
.ua-head small{display:block;margin-top:2px;font-size:11px;color:#94a3b8}
.ua-actions{display:flex;gap:8px}
.ua-tip{margin-top:8px;font-size:11px;color:#94a3b8}
.w-full{width:100%}
</style>
