<template>
<header class="kos-topbar">
  <div class="kos-brand">
    <KosBrandMark :size="28"/>
    <span class="kos-brand-copy"><b>智能知识平台</b><small>Knowledge OS</small></span>
  </div>
  <div class="kos-grow"></div>
  <!-- 视图切换：管理员登录为管理视图并可切到用户视图核对普通成员看到的样子；普通账号固定用户视图。 -->
  <div class="kos-viewswitch" role="group" aria-label="视图切换" :title="viewSwitchHint">
    <button
      v-for="option in viewOptions"
      :key="option.mode"
      class="kos-viewswitch-chip"
      :class="{active:viewMode===option.mode}"
      :disabled="!canSwitchView"
      :aria-pressed="viewMode===option.mode"
      @click="$emit('set-view',option.mode)">
      {{option.label}}
    </button>
  </div>
  <button class="kos-kbswitch" @click="$emit('switch-base')">
    <span>知识库：<b>{{baseName||'选择知识库'}}</b></span>
    <em class="ml-2 text-[11px] not-italic">{{visibility}}</em><i>▾</i>
  </button>
  <form class="kos-search" @submit.prevent="$emit('search')">
    <input :value="keyword" class="kos-search-input" placeholder="搜索知识…" aria-label="全局搜索"
      @input="$emit('update:keyword',($event.target as HTMLInputElement).value)"/>
    <button type="submit" class="kos-search-btn" aria-label="执行搜索">⌕</button>
  </form>
  <button class="kos-icon-btn" :title="themeLabel" :aria-label="themeLabel" @click="cycleTheme()">{{themeIcon}}</button>
  <!-- 用户区：头像（人物色即角色身份色）+ 用户名 + 退出；角色名不再占用顶栏，移到头像 hover 说明 -->
  <div class="kos-user">
    <span class="kos-avatar" :style="{ '--role-accent': roleAccent }" :title="roleTitle">{{avatar}}</span>
    <b class="kos-user-name" :title="displayName">{{displayName||'未登录'}}</b>
    <button v-if="loggedIn" class="kos-logout" @click="$emit('logout')">退出</button>
  </div>
</header>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import KosBrandMark from './KosBrandMark.vue'
import { useTheme } from '../theme'

/**
 * 顶栏只保留：视图切换、知识库切换、全局搜索、主题、用户区。
 * 任务入口与服务状态点已移除——任务在「知识生成 · 生成任务」页内查看，
 * 服务状态由页面内的空态与错误提示表达，不再占用顶栏位置。
 */
const props = defineProps<{baseName?:string;visibility:string;keyword:string;
  avatar:string;displayName?:string;roleTitle:string;roleAccent:string;loggedIn:boolean;
  viewMode:'user'|'admin';canSwitchView:boolean}>()
defineEmits<{(e:'switch-base'):void;(e:'search'):void;(e:'logout'):void;(e:'update:keyword',v:string):void;
  (e:'set-view',mode:'user'|'admin'):void}>()

const { icon: themeIcon, label: themeLabel, cycleTheme } = useTheme()

/** 两个视图的切换项：文案与顺序固定，选中态由外壳的当前视图决定。 */
const viewOptions:{mode:'user'|'admin'; label:string}[] = [
  {mode:'user', label:'用户视图'},
  {mode:'admin', label:'管理视图'},
]
/** 普通账号不能切换：提示里说清原因，避免出现点了没反应却不解释的按钮。 */
const viewSwitchHint = computed(() => props.canSwitchView
  ? '管理视图含运维与配置入口；用户视图只保留日常使用入口'
  : '当前账号为普通用户，仅提供用户视图')
</script>
