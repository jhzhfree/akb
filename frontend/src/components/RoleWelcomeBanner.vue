<template>
<div v-if="brief" class="role-banner" :style="{ '--role-accent': accent }">
  <div class="role-banner-accent">{{roleGlyph}}</div>
  <div class="role-banner-copy">
    <h2>{{roleName}}，欢迎回来</h2>
    <p>{{brief}}</p>
    <template v-if="actions.length">
      <div class="role-banner-actions" style="margin-top:10px">
        <button v-for="a in actions" :key="a.key" class="role-banner-action"
          :class="{disabled:a.disabled}" :disabled="a.disabled"
          @click="!a.disabled && $emit('go',a.key)">
          <span>{{a.icon}}</span>{{a.label}}
        </button>
      </div>
    </template>
  </div>
</div>
</template>
<script setup lang="ts">
/** 角色欢迎区：登录后落地页顶部展示角色身份色 + 职责说明 + 常用入口快捷。
 *  纯视觉与导航增强，不参与权限判定；入口的可访问性由 shell.canNavigate 预置 disabled。
 */
import { computed } from 'vue'
const props = defineProps<{
  roleName:string
  brief:string
  accent:string
  /** 当前角色可见可用的常用入口（已按权限裁剪；无权目标预置 disabled） */
  actions: { key:string; label:string; icon:string; disabled?:boolean }[]
}>()
defineEmits<{(e:'go',key:string):void}>()
const roleGlyph = computed(()=>{
  const m = props.roleName||''
  return m ? m.replace(/员|管理员|者|长$/,'').slice(0,1)||m.slice(0,1) : 'K'
})
</script>