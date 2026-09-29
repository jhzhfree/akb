<template>
<aside class="kos-side">
  <!-- 一级入口：只有 IA 的四个业务模块，系统配置已下沉到底部工具区 -->
  <div class="kos-side-nav">
    <div class="kos-nav-title">信息架构</div>
    <button v-for="section in sections" :key="section.key" class="kos-nav-item kos-nav-l1"
      :class="{active:activeSection===section.key}" :title="section.desc" @click="$emit('enter',section)">
      <span class="kos-nav-ic">{{section.icon}}</span><span class="kos-nav-tx">{{section.label}}</span>
      <em v-if="badge(section)" class="kos-nav-dot">{{badge(section)}}</em>
    </button>
  </div>

  <!-- 底部工具区：当前用户 + 系统配置入口 + 版本信息。
       与一级业务入口分开，低频配置不再占用业务导航位置。 -->
  <div class="kos-side-foot">
    <div class="kos-side-user">
      <span class="kos-side-avatar" :style="{'--role-accent':roleAccent||'#4f8cf7'}"
        :title="roleBrief||userName">{{avatar||'K'}}</span>
      <div class="kos-side-user-copy">
        <b :title="userName">{{userName||'未登录'}}</b>
        <small v-if="roleBrief" :title="roleBrief">{{roleBrief}}</small>
      </div>
      <button v-if="canSystem" class="kos-side-icon" :class="{active:systemActive}"
        title="系统配置" aria-label="系统配置" @click="$emit('enter-system')">⚙</button>
      <button class="kos-side-ver" :title="`版本信息 · ${version}`" @click="versionVisible=true">
        {{version}}
      </button>
    </div>
  </div>

  <VersionInfoDialog v-model:visible="versionVisible" :product-version="version"/>
</aside>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import type {NavSection} from '../navigation'
import VersionInfoDialog from './VersionInfoDialog.vue'

defineProps<{
  sections:NavSection[];activeSection:string;badge:(section:NavSection)=>string
  /** 底部工具区：用户名 / 头像 / 角色身份色 / 角色说明 */
  userName?:string;avatar?:string;roleAccent?:string;roleBrief?:string
  /** 系统配置入口：无 system 权限时隐藏；当前停留在系统配置页时高亮 */
  canSystem?:boolean;systemActive?:boolean
  /** 产品版本号，点击弹出「版本信息」弹窗 */
  version:string
}>()
defineEmits<{(e:'enter',section:NavSection):void;(e:'enter-system'):void}>()

const versionVisible = ref(false)
</script>
