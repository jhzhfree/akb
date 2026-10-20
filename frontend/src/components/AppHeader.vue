<template>
<header class="kos-topbar">
  <div class="kos-brand">
    <KosBrandMark :size="28"/>
    <span class="kos-brand-copy"><b>智能知识平台</b><small>Knowledge OS</small></span>
  </div>
  <div class="kos-grow"></div>
  <!-- 原先的「用户视图 / 管理视图」切换已移除：菜单可见性改由角色配置决定，
       每个角色看到的就是自己被授权的菜单，不再需要人工切换视角。 -->
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
import KosBrandMark from './KosBrandMark.vue'
import { useTheme } from '../theme'

/**
 * 顶栏只保留：知识库切换、全局搜索、主题、用户区。
 * 任务入口与服务状态点已移除——任务在「知识生成 · 文档导入」页内查看，
 * 服务状态由页面内的空态与错误提示表达，不再占用顶栏位置。
 */
defineProps<{baseName?:string;visibility:string;keyword:string;
  avatar:string;displayName?:string;roleTitle:string;roleAccent:string;loggedIn:boolean}>()
defineEmits<{(e:'switch-base'):void;(e:'search'):void;(e:'logout'):void;(e:'update:keyword',v:string):void;}>()

const { icon: themeIcon, label: themeLabel, cycleTheme } = useTheme()
</script>
