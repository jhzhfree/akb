<template>
<aside class="kos-side">
  <!-- 一级入口：按业务域分组（工作台 / 知识库 / 系统集成），系统配置已下沉到底部工具区 -->
  <div class="kos-side-nav">
    <template v-for="group in groups" :key="group.key">
      <div class="kos-nav-group">{{group.label}}</div>
      <button v-for="section in group.sections" :key="section.key" class="kos-nav-item kos-nav-l1"
        :class="{active:activeSection===section.key}" :title="section.desc" @click="$emit('enter',section)">
        <NavIcon class="kos-nav-ic" :name="section.icon" :size="16"/>
        <span class="kos-nav-tx">{{section.label}}</span>
        <em v-if="badge(section)" class="kos-nav-dot">{{badge(section)}}</em>
      </button>
    </template>
  </div>

  <!-- 底部工具区：当前用户 + 系统配置入口 + 版本信息。
       角色名 / 职责说明 / 入口菜单原先放在主页面顶部的角色横幅里，
       现在收进这里：点用户名展开面板查看与操作，主页面顶部不再占版面。 -->
  <div class="kos-side-foot">
    <div class="kos-side-user-wrap" :style="{'--role-accent':roleAccent||'#4f8cf7'}">
      <button class="kos-side-user" :class="{active:panel}" :aria-expanded="panel"
        :title="`${userName||'未登录'} · ${roleName||''}：点击查看角色与入口菜单`"
        @click.stop="panel=!panel">
        <span class="kos-side-avatar">{{avatar||'K'}}</span>
        <span class="kos-side-user-copy">
          <b>{{userName||'未登录'}}</b>
          <small>{{roleName||roleBrief||'未登录'}}</small>
        </span>
        <span class="kos-side-caret" :class="{up:panel}" aria-hidden="true">⌃</span>
      </button>

      <!-- 角色面板：向上展开，宽度与侧栏同宽；点击面板外 / Esc 关闭。
           菜单是「角色常用入口 + 系统配置各 Tab」合并去重后的一份：
           点底部用户名（管理员角色显示「系统管理员」）即可直达任一配置项，
           不必先落配置总览再点一次卡片，也不再分「常用入口」和「系统配置」两处。 -->
      <div v-if="panel" class="kos-user-panel" role="dialog" aria-label="账号与角色" @click.stop>
        <div class="kos-user-panel-head">
          <span class="kos-user-panel-avatar">{{avatar||'K'}}</span>
          <span class="kos-user-panel-copy">
            <b>{{userName||'未登录'}}</b>
            <small>{{roleName||'未登录'}}</small>
          </span>
        </div>
        <p v-if="roleBrief" class="kos-user-panel-brief">{{roleBrief}}</p>
        <div v-if="panelMenu.length" class="kos-user-panel-actions">
          <button v-for="item in panelMenu" :key="item.key" class="kos-user-panel-action"
            :class="{disabled:item.disabled,current:item.key===systemLeaf}"
            :disabled="item.disabled"
            :title="item.disabled?`当前角色无权访问「${item.label}」`:item.label" @click="go(item)">
            <NavIcon class="kos-user-panel-ic" :name="item.icon" :size="14"/>{{item.label}}
          </button>
        </div>
        <p v-if="environment||version" class="kos-user-panel-env">
          {{[environment,version?`v${version}`:''].filter(Boolean).join(' · ')}}
        </p>
        <div class="kos-user-panel-foot">
          <button class="kos-user-panel-link" :class="{disabled:!canSystem}" :disabled="!canSystem"
            :title="systemTitle" @click="enterSystem">
            <NavIcon name="settings" :size="14"/>系统配置
          </button>
          <button v-if="loggedIn" class="kos-user-panel-link danger" @click="logout">⇥ 退出登录</button>
        </div>
      </div>
    </div>

    <!-- 设置图标与用户名同一行：底部工具区只有一行，窄侧栏也保持不换行。
         版本信息已移到主内容底部的统一页脚，侧栏不再占用这一行。 -->
    <button class="kos-side-icon" :class="{active:systemActive}" :disabled="!canSystem"
      :title="systemTitle" aria-label="系统配置" @click="enterSystem">
      <NavIcon name="settings" :size="16"/>
    </button>
  </div>
</aside>
<nav class="kos-mobile-nav" aria-label="主导航">
  <button v-for="section in mobileSections" :key="section.key" class="kos-mobile-nav-item"
    :class="{active:activeSection===section.key}" :aria-current="activeSection===section.key?'page':undefined"
    :title="section.desc" @click="$emit('enter',section)">
    <NavIcon :name="section.icon" :size="19"/>
    <span>{{section.label}}</span>
    <em v-if="badge(section)" class="kos-mobile-nav-dot">{{badge(section)}}</em>
  </button>
  <button class="kos-mobile-nav-item" :class="{active:systemActive}" :disabled="!canSystem"
    :aria-current="systemActive?'page':undefined" :title="systemTitle" @click="enterSystem">
    <NavIcon name="settings" :size="19"/><span>系统</span>
  </button>
</nav>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type {NavGroup, NavLeaf, NavSection} from '../navigation'
import NavIcon from './NavIcon.vue'

const props = defineProps<{
  /** 按业务域分组的一级入口：工作台 / 知识库 / 系统集成 */
  groups:NavGroup[];activeSection:string;badge:(section:NavSection)=>string
  /** 底部工具区：用户名 / 头像 / 角色身份色 / 角色名 / 角色职责说明 */
  userName?:string;avatar?:string;roleAccent?:string;roleBrief?:string;roleName?:string
  /** 当前角色可用的常用入口（外壳已按权限裁剪，无权项预置 disabled） */
  actions?:{key:string;label:string;icon:string;disabled?:boolean}[]
  /** 是否已登录：未登录时不展示退出登录 */
  loggedIn?:boolean
  /** 系统配置入口：无权限时置灰并给出原因，不再整块隐藏；当前停留在系统配置页时高亮 */
  canSystem?:boolean;systemActive?:boolean
  /** 系统配置下的 Tab：与常用入口合并成面板菜单，外壳已按角色与权限裁剪 */
  systemLeaves?:NavLeaf[]
  /** 当前停留的系统配置 Tab key：面板菜单里标出当前所在项 */
  systemLeaf?:string
  /** 无系统配置权限时的悬浮说明（外壳带上角色名，说明为什么点不动） */
  systemHint?:string
  /** 运行环境与版本号：面板里给一条环境信息，便于排查问题 */
  environment?:string;version?:string
}>()
const emit = defineEmits<{
  (e:'enter',section:NavSection):void
  (e:'enter-system'):void
  (e:'go',key:string):void
  (e:'logout'):void
}>()

const panel = ref(false)
const mobileSections = computed(()=>props.groups.flatMap(group=>group.sections))
const systemTitle = computed(()=>
  props.canSystem?'系统配置':(props.systemHint||'当前角色无权访问系统配置'))
/**
 * 面板菜单：角色常用入口 + 系统配置各 Tab 合并去重后的一份。
 * 系统配置部分严格按配置页 Tab 的顺序输出（与页面顶部标签栏一一对应，不会各排各的），
 * 常用入口里属于系统配置的项（如管理员的审计日志、知识 Schema）并入该顺序，不再单独前置；
 * 只有不属于系统配置的业务快捷入口（如上传文档、知识衍生）排在前面。
 */
const panelMenu = computed<{key:string;label:string;icon:string;disabled?:boolean}[]>(()=>{
  const list:{key:string;label:string;icon:string;disabled?:boolean}[]=[]
  const seen=new Set<string>()
  const actions=props.actions||[]
  const leaves=props.systemLeaves||[]
  const systemKeys=new Set(leaves.map(leaf=>leaf.key))
  /** 1. 业务快捷入口：不在系统配置里的先列，保持角色高频操作在手边。 */
  for(const action of actions){
    if(systemKeys.has(action.key)||seen.has(action.key))continue
    seen.add(action.key)
    list.push({key:action.key,label:action.label,icon:action.icon,disabled:action.disabled})
  }
  /** 2. 系统配置：顺序、文案、图标全部跟随配置页 Tab。 */
  for(const leaf of leaves){
    if(seen.has(leaf.key))continue
    seen.add(leaf.key)
    const shortcut=actions.find(action=>action.key===leaf.key)
    list.push({key:leaf.key,label:leaf.label,icon:leaf.icon,
      disabled:leaf.planned||shortcut?.disabled})
  }
  return list
})

function go(action:{key:string;disabled?:boolean}){
  if(action.disabled)return
  panel.value=false
  emit('go',action.key)
}
function enterSystem(){
  if(!props.canSystem)return
  panel.value=false
  emit('enter-system')
}
function logout(){
  panel.value=false
  emit('logout')
}
/** 面板外点击与 Esc 关闭：面板是浮层，不关闭会挡住导航。 */
function onDocClick(){panel.value=false}
function onKey(e:KeyboardEvent){if(e.key==='Escape')panel.value=false}
onMounted(()=>{
  document.addEventListener('click',onDocClick)
  document.addEventListener('keydown',onKey)
})
onBeforeUnmount(()=>{
  document.removeEventListener('click',onDocClick)
  document.removeEventListener('keydown',onKey)
})
/** 切换一级入口时收起面板：浮层不该跟着页面留在这里。 */
defineExpose({close:()=>{panel.value=false}})
</script>
<style scoped>
/* 合并后的菜单最多十几项：给浮层一个高度上限，超出滚动而不是把面板顶出侧栏。 */
.kos-user-panel-actions{max-height:46vh;overflow:auto}
/* 当前停留的入口在面板里标出来，便于确认自己在哪一项。 */
.kos-user-panel-action.current{color:var(--accent-text);font-weight:600}
</style>
