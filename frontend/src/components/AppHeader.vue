<template>
<header class="kos-topbar">
  <div class="kos-brand">
    <KosBrandMark :size="28"/>
    <span class="kos-brand-copy"><b>智能知识平台</b><small>Knowledge OS</small></span>
  </div>
  <span class="kos-pill" title="平台版本">{{ APP_VERSION }}</span>
  <div class="kos-grow"></div>
  <button class="kos-kbswitch" @click="$emit('switch-base')">
    <span>知识库：<b>{{baseName||'选择知识库'}}</b></span>
    <em class="ml-2 text-[11px] not-italic">{{visibility}}</em><i>▾</i>
  </button>
  <form class="kos-search" @submit.prevent="$emit('search')">
    <input :value="keyword" class="kos-search-input" placeholder="搜索知识…" aria-label="全局搜索"
      @input="$emit('update:keyword',($event.target as HTMLInputElement).value)"/>
    <button type="submit" class="kos-search-btn" aria-label="执行搜索">⌕</button>
  </form>
  <button v-if="showTasks" class="kos-task" :class="{alert:failedTaskCount>0}" @click="$emit('tasks')">
    任务<em v-if="failedTaskCount">{{failedTaskCount}}</em>
  </button>
  <!-- 服务状态：只留一个状态点，文案在窄屏收起并改为 hover 提示 -->
  <div class="kos-status" :title="connected?'服务已连接':'服务状态未知'">
    <i class="kos-dot" :class="{ok:connected}"></i><span class="kos-status-text">{{connected?'服务已连接':'服务状态未知'}}</span>
  </div>
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
import KosBrandMark from './KosBrandMark.vue'
import { useTheme } from '../theme'
import { APP_VERSION } from '../version'

defineProps<{baseName?:string;visibility:string;keyword:string;showTasks:boolean;failedTaskCount:number;connected:boolean;
  avatar:string;displayName?:string;roleTitle:string;roleAccent:string;loggedIn:boolean}>()
defineEmits<{(e:'switch-base'):void;(e:'search'):void;(e:'tasks'):void;(e:'logout'):void;(e:'update:keyword',v:string):void}>()

const { icon: themeIcon, label: themeLabel, cycleTheme } = useTheme()
</script>
