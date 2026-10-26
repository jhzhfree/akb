<template>
  <!-- V2.1 §1/§2：二级入口不再挂在左侧，进入一级模块后由页面顶部的 Tab 条横向切换。 -->
  <nav class="kos-modnav" :aria-label="label">
    <button
      v-for="leaf in primaryLeaves"
      :key="leaf.key"
      class="kos-modnav-chip"
      :class="{ active: active === leaf.key, planned: leaf.planned }"
      :disabled="leaf.planned"
      :title="leaf.planned ? '规划中：该能力尚未实现' : ''"
      @click="select(leaf)">
      <NavIcon class="kos-nav-ic" :name="leaf.icon" :size="14"/>
      <span>{{ leaf.label }}</span>
      <em v-if="badges?.[leaf.key]" class="kos-nav-dot">{{ badges[leaf.key] }}</em>
      <em v-else-if="leaf.planned" class="kos-nav-planned">规划</em>
    </button>
    <details v-if="overflowGroups.length" class="kos-modnav-more" :class="{active:overflowGroups.some(group=>group.leaves.some(leaf=>leaf.key===active))}">
      <summary>更多能力 <span aria-hidden="true">⌄</span></summary>
      <div class="kos-modnav-menu">
        <section v-for="group in overflowGroups" :key="group.label" class="kos-modnav-menu-group">
          <b>{{group.label}}</b>
          <button v-for="leaf in group.leaves" :key="leaf.key" :class="{active:active===leaf.key}"
            :disabled="leaf.planned" @click="select(leaf)">
            <NavIcon :name="leaf.icon" :size="14"/><span>{{leaf.label}}</span>
            <em v-if="badges?.[leaf.key]" class="kos-nav-dot">{{badges[leaf.key]}}</em>
          </button>
        </section>
      </div>
    </details>
  </nav>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { NavLeaf } from '../navigation'
import NavIcon from './NavIcon.vue'

const props=defineProps<{
  label:string
  leaves:NavLeaf[]
  active:string
  badges?:Record<string,number|string>
}>()
const primaryLeaves=computed(()=>props.leaves.length>7?props.leaves.slice(0,2):props.leaves)
const overflowGroups=computed(()=>{
  const overflow=props.leaves.length>7?props.leaves.slice(2):[]
  if(!overflow.length)return []
  const categories:Record<string,string[]> = props.label==='系统配置' ? {
    '用户与权限':['system.users','system.acl','system.menu'],
    '知识库设置':['system.schema','system.space'],
    '运行维护':['system.index','system.tasks','system.defaults'],
    '审计安全':['system.audit'],
  } : {
    '接入协议':['integration.api','integration.mcp','integration.sse'],
    '服务接入':['integration.credentials','integration.embed'],
    '运行观察':['integration.events','integration.logs'],
  }
  return Object.entries(categories).map(([label,keys])=>({label,leaves:overflow.filter(leaf=>keys.includes(leaf.key))})).filter(group=>group.leaves.length)
})
const emit = defineEmits<{ select: [key: string] }>()

function select(leaf: NavLeaf) {
  if (leaf.planned) return
  emit('select', leaf.key)
}
</script>
