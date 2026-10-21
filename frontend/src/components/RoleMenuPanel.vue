<template>
<div class="rm-panel">
  <!-- 页面标题：eyebrow 是所属模块，主标题是菜单名（与其它 Tab 一致）。 -->
  <header class="kb-section-head">
    <div>
      <div class="kb-eyebrow">系统配置</div>
      <h1 class="kb-title mt-1">角色菜单</h1>
      <p class="kb-muted mt-1">按角色配置可见的功能入口：勾选即得该入口与其所需权限码。</p>
    </div>
  </header>
  <p class="rm-desc">
    每个角色一份可见菜单：勾选后该角色即可看到对应功能入口，同时获得该入口所需的接口权限码。
    未配置时按内置默认渲染（即各角色原本看到的菜单）。
    <b>系统管理员恒为全部菜单与全部权限</b>，不受此处配置影响——避免配置失误把管理员锁在系统外。
  </p>

  <div class="rm-roles">
    <button v-for="role in roles" :key="role.code" class="rm-role" :class="{active:role.code===current}"
      @click="current=role.code">
      {{role.label}}
      <em v-if="draft[role.code]&&role.configured" class="rm-tag">已定制</em>
    </button>
  </div>

  <div class="rm-bar">
    <span class="rm-count">已选 {{visibleSet.size}} / {{catalog.length}} 项</span>
    <span class="rm-grow"></span>
    <!-- 与「存储与索引 · 蓝绿索引版本」一致：size=small + plain，主操作用 primary，危险/中断类用 type 区分 -->
    <el-button size="small" plain @click="selectAll(true)">全选</el-button>
    <el-button size="small" plain @click="selectAll(false)">清空</el-button>
    <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
    <el-button size="small" plain :disabled="saving" @click="reset">恢复默认</el-button>
  </div>

  <div v-if="loading" class="rm-hint">正在加载菜单配置…</div>
  <template v-else>
    <div v-for="group in groups" :key="group.key" class="rm-group">
      <div class="rm-group-head">
        <b>{{group.label}}</b>
        <el-button size="small" plain @click="toggleGroup(group)">{{groupAllOn(group)?'取消本组':'选中本组'}}</el-button>
      </div>
      <div class="rm-grid">
        <label v-for="item in group.items" :key="item.key" class="rm-item"
          :class="{on:visibleSet.has(item.key)}">
          <input type="checkbox" :checked="visibleSet.has(item.key)" @change="toggle(item.key)"/>
          <span class="rm-item-label">{{item.label}}</span>
          <em class="rm-perm">{{permLabel(item.permission)}}</em>
        </label>
      </div>
    </div>
  </template>

  <p v-if="message" class="rm-msg" :class="{error:messageError}">{{message}}</p>
</div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NAV_SECTIONS } from '../navigation'
import { getRoleMenu, resetRoleMenu, saveRoleMenu, type RoleMenuConfig } from '../api/system'

/**
 * 角色可见菜单配置面板（系统配置 · 角色菜单）。
 *
 * 菜单项的文案与分组沿用前端 navigation.ts 的 IA 定义，权限码与是否管理员专属以后端
 * 下发的目录为准——后端是配置的唯一真源，前端只负责把它呈现成可勾选的矩阵。
 */
const config = ref<RoleMenuConfig|null>(null)
const current = ref('ADMIN')
/** 编辑中的副本：按角色存，切换角色不丢草稿。 */
const draft = ref<Record<string,string[]>>({})
const loading = ref(true)
const saving = ref(false)
const message = ref('')
const messageError = ref(false)

const roles = computed(() => config.value?.roles || [])
const catalog = computed(() => config.value?.menu || [])
const visibleSet = computed(() => new Set(draft.value[current.value] || []))

/** 按一级模块分组：分组与文案来自前端 IA，只保留后端目录里存在的菜单项。 */
const groups = computed(() => NAV_SECTIONS.map(section => ({
  key: section.key,
  label: section.label,
  items: section.tabs
    .filter(tab => catalog.value.some(menu => menu.key === tab.key))
    .map(tab => ({
      key: tab.key,
      label: tab.label,
      permission: catalog.value.find(menu => menu.key === tab.key)?.permission ?? null,
    })),
})).filter(group => group.items.length > 0))

function permLabel(permission: string|null):string {
  if (!permission) return '登录即可见'
  return config.value?.permissions?.[permission] || permission
}

function toggle(key: string) {
  const set = new Set(visibleSet.value)
  if (set.has(key)) set.delete(key)
  else set.add(key)
  draft.value = { ...draft.value, [current.value]: [...set] }
}

function selectAll(on: boolean) {
  draft.value = { ...draft.value, [current.value]: on ? catalog.value.map(item => item.key) : [] }
}

function groupAllOn(group: {items:{key:string}[]}):boolean {
  return group.items.every(item => visibleSet.value.has(item.key))
}

function toggleGroup(group: {items:{key:string}[]}) {
  const set = new Set(visibleSet.value)
  const on = groupAllOn(group)
  for (const item of group.items) {
    if (on) set.delete(item.key)
    else set.add(item.key)
  }
  draft.value = { ...draft.value, [current.value]: [...set] }
}

async function load() {
  loading.value = true
  try {
    const data = (await getRoleMenu()).data.data
    if (!data) {
      messageError.value = true
      message.value = '加载菜单配置失败：接口未返回数据'
      return
    }
    config.value = data
    const map: Record<string,string[]> = {}
    for (const role of data.roles) map[role.code] = [...role.visible]
    draft.value = map
    if (!data.roles.some(role => role.code === current.value)) {
      current.value = data.roles[0]?.code || 'ADMIN'
    }
  } catch (e) {
    messageError.value = true
    message.value = '加载菜单配置失败：' + String(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  message.value = ''
  try {
    const saved = await saveRoleMenu(current.value, draft.value[current.value] || [])
    messageError.value = false
    message.value = `已保存「${current.value}」的可见菜单（${saved.data.data?.visible.length ?? 0} 项），刷新页面或重新登录后生效。`
    await load()
  } catch (e) {
    messageError.value = true
    message.value = '保存失败：' + String(e)
  } finally {
    saving.value = false
  }
}

async function reset() {
  saving.value = true
  message.value = ''
  try {
    await resetRoleMenu(current.value)
    messageError.value = false
    message.value = `已恢复「${current.value}」的内置默认菜单。`
    await load()
  } catch (e) {
    messageError.value = true
    message.value = '恢复默认失败：' + String(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
<style scoped>
.rm-panel{padding:2px 0}
.rm-desc{margin:0 0 12px;font-size:12.5px;line-height:1.7;color:var(--ink-2)}
.rm-roles{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px}
.rm-role{display:inline-flex;align-items:center;gap:6px;padding:6px 12px;border-radius:999px;
  border:1px solid var(--line);background:var(--bg-raised);color:var(--ink-2);
  font-size:12.5px;cursor:pointer;font-family:inherit}
.rm-role:hover{border-color:var(--accent-text);color:var(--accent-text)}
.rm-role.active{background:linear-gradient(90deg,rgba(79,140,247,.16),rgba(45,212,191,.06));
  color:var(--accent-text);font-weight:600;border-color:var(--line)}
.rm-tag{font-style:normal;font-size:10px;padding:0 5px;border-radius:999px;
  background:var(--ok-bg);color:var(--ok)}
.rm-bar{display:flex;align-items:center;gap:8px;margin-bottom:12px;flex-wrap:wrap}
.rm-count{font-size:12px;color:var(--ink-3)}
.rm-grow{flex:1}
.rm-btn{padding:5px 12px;border-radius:8px;border:1px solid var(--line);
  background:var(--bg-raised);color:var(--ink-2);font-size:12.5px;cursor:pointer;font-family:inherit}
.rm-btn:hover{border-color:var(--accent-text);color:var(--accent-text)}
.rm-btn.primary{background:var(--accent-bg);border-color:var(--accent-bg);color:#fff}
.rm-btn:disabled{opacity:.55;cursor:not-allowed}
.rm-group{margin-bottom:14px}
.rm-group-head{display:flex;align-items:center;gap:10px;margin:0 0 6px}
.rm-group-head b{font-size:13px;color:var(--ink-1)}
.rm-link{border:0;background:transparent;color:var(--accent-text);font-size:11.5px;
  cursor:pointer;padding:0;font-family:inherit}
.rm-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:6px}
.rm-item{display:flex;align-items:center;gap:8px;padding:6px 10px;border-radius:8px;
  border:1px solid var(--line-d);background:var(--bg-raised);cursor:pointer;font-size:12.5px}
.rm-item:hover{border-color:var(--accent-text)}
.rm-item.on{border-color:var(--accent-text);background:var(--bg-sub)}
.rm-item input{flex:none;accent-color:var(--brand-400)}
.rm-item-label{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--ink-1)}
.rm-perm{font-style:normal;font-size:10.5px;color:var(--ink-3);flex:none}
.rm-hint{padding:16px 0;font-size:12.5px;color:var(--ink-3)}
.rm-msg{margin:10px 0 0;font-size:12.5px;color:var(--ok)}
.rm-msg.error{color:var(--danger)}
</style>
