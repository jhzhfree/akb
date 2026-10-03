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
       角色名 / 职责说明 / 常用入口原先放在主页面顶部的角色横幅里，
       现在收进这里：点用户名展开面板查看与操作，主页面顶部不再占版面。 -->
  <div class="kos-side-foot">
    <div class="kos-side-user-wrap" :style="{'--role-accent':roleAccent||'#4f8cf7'}">
      <button class="kos-side-user" :class="{active:panel}" :aria-expanded="panel"
        :title="`${userName||'未登录'} · ${roleName||''}：点击查看角色与常用入口`"
        @click.stop="panel=!panel">
        <span class="kos-side-avatar">{{avatar||'K'}}</span>
        <span class="kos-side-user-copy">
          <b>{{userName||'未登录'}}</b>
          <small>{{roleName||roleBrief||'未登录'}}</small>
        </span>
        <span class="kos-side-caret" :class="{up:panel}" aria-hidden="true">⌃</span>
      </button>

      <!-- 角色面板：向上展开，宽度与侧栏同宽；点击面板外 / Esc 关闭。
           系统配置即使无权限也保留入口并置灰，避免普通成员「看不到有这个模块」。 -->
      <div v-if="panel" class="kos-user-panel" role="dialog" aria-label="账号与角色" @click.stop>
        <div class="kos-user-panel-head">
          <span class="kos-user-panel-avatar">{{avatar||'K'}}</span>
          <span class="kos-user-panel-copy">
            <b>{{userName||'未登录'}}</b>
            <small>{{roleName||'未登录'}}</small>
          </span>
        </div>
        <p v-if="roleBrief" class="kos-user-panel-brief">{{roleBrief}}</p>
        <div v-if="actions?.length" class="kos-user-panel-actions">
          <button v-for="a in actions||[]" :key="a.key" class="kos-user-panel-action"
            :class="{disabled:a.disabled}" :disabled="a.disabled"
            :title="a.disabled?`当前角色无权访问「${a.label}」`:a.label" @click="go(a)">
            <NavIcon class="kos-user-panel-ic" :name="a.icon" :size="14"/>{{a.label}}
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
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type {NavGroup, NavSection} from '../navigation'
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
const systemTitle = computed(()=>
  props.canSystem?'系统配置':(props.systemHint||'当前角色无权访问系统配置'))

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
