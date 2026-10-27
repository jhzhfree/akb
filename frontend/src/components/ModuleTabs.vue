<template>
  <!-- V2.1 §1/§2：二级入口不再挂在左侧，进入一级模块后由页面顶部的 Tab 条横向切换。 -->
  <nav class="kos-modnav" :aria-label="label">
    <button
      v-for="leaf in primaryLeaves"
      :key="leaf.key"
      class="kos-modnav-chip"
      :class="{ active: active === leaf.key, planned: leaf.planned }"
      :aria-current="active === leaf.key ? 'page' : undefined"
      :disabled="leaf.planned"
      :title="leaf.planned ? '规划中：该能力尚未实现' : ''"
      @click="select(leaf)">
      <NavIcon class="kos-nav-ic" :name="leaf.icon" :size="14"/>
      <span>{{ leaf.label }}</span>
      <em v-if="badges?.[leaf.key]" class="kos-nav-dot">{{ badges[leaf.key] }}</em>
      <em v-else-if="leaf.planned" class="kos-nav-planned">规划</em>
    </button>
    <details v-if="overflowGroups.length" ref="overflowMenu" class="kos-modnav-more" :class="{active:hasActiveOverflow}">
      <summary :aria-label="hasActiveOverflow ? `更多能力，当前：${activeOverflowLabel}` : '更多能力'">更多能力 <span aria-hidden="true">⌄</span></summary>
      <div class="kos-modnav-menu">
        <section v-for="group in overflowGroups" :key="group.label" class="kos-modnav-menu-group">
          <b>{{group.label}}</b>
          <button v-for="leaf in group.leaves" :key="leaf.key" :class="{active:active===leaf.key}"
            :aria-current="active===leaf.key?'page':undefined"
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
import { computed, ref } from 'vue'
import type { NavLeaf } from '../navigation'
import NavIcon from './NavIcon.vue'

const props=defineProps<{
  label:string
  /** 明确模块类型，避免通过展示文案猜测菜单分组。未知模块默认展示全部入口。 */
  sectionKey?:'create'|'search'|'system'|'integration'
  leaves:NavLeaf[]
  active:string
  badges?:Record<string,number|string>
}>()
const overflowMenu=ref<HTMLDetailsElement|null>(null)
const NAV_GROUPS:Record<NonNullable<typeof props.sectionKey>,{primary:string[];groups:{label:string;keys:string[]}[]}>={
  create:{
    primary:['create.overview','create.generation','create.import','create.connector'],
    groups:[
      {label:'数据接入与治理',keys:['create.connectorCatalog','create.ledger']},
      {label:'知识处理',keys:['create.judgment','create.derive']},
    ],
  },
  search:{
    primary:['search.query','search.test','search.profile','search.graph'],
    groups:[
      {label:'链路排查',keys:['search.trace']},
      {label:'质量评估',keys:['search.feedback','search.benchmark']},
    ],
  },
  system:{
    primary:['system','system.model'],
    groups:[
      {label:'用户与权限',keys:['system.users','system.acl','system.menu']},
      {label:'知识库设置',keys:['system.schema','system.space']},
      {label:'运行维护',keys:['system.index','system.tasks','system.defaults']},
      {label:'审计安全',keys:['system.audit']},
    ],
  },
  integration:{
    primary:['integration.overview','integration.services'],
    groups:[
      {label:'接入协议',keys:['integration.api','integration.mcp','integration.sse']},
      {label:'服务接入',keys:['integration.credentials','integration.embed']},
      {label:'运行观察',keys:['integration.events','integration.logs']},
    ],
  },
}
const navConfig=computed(()=>props.sectionKey?NAV_GROUPS[props.sectionKey]:undefined)
const primaryLeaves=computed(()=>navConfig.value
  ?props.leaves.filter(leaf=>navConfig.value!.primary.includes(leaf.key))
  :props.leaves)
const overflowGroups=computed(()=>{
  const config=navConfig.value
  if(!config)return []
  const overflow=props.leaves.filter(leaf=>!config.primary.includes(leaf.key))
  if(!overflow.length)return []
  const assigned=new Set<string>()
  const groups=config.groups.map(group=>{
    const leaves=overflow.filter(leaf=>{
      if(!group.keys.includes(leaf.key)||assigned.has(leaf.key))return false
      assigned.add(leaf.key)
      return true
    })
    return{label:group.label,leaves}
  }).filter(group=>group.leaves.length)
  const uncategorized=overflow.filter(leaf=>!assigned.has(leaf.key))
  if(uncategorized.length)groups.push({label:'其他入口',leaves:uncategorized})
  return groups
})
const hasActiveOverflow=computed(()=>overflowGroups.value.some(group=>group.leaves.some(leaf=>leaf.key===props.active)))
const activeOverflowLabel=computed(()=>overflowGroups.value.flatMap(group=>group.leaves).find(leaf=>leaf.key===props.active)?.label||'')
const emit = defineEmits<{ select: [key: string] }>()

function select(leaf: NavLeaf) {
  if (leaf.planned) return
  if(overflowMenu.value)overflowMenu.value.open=false
  emit('select', leaf.key)
}
</script>
