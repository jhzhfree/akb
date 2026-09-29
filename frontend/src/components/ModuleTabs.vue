<template>
  <!-- V2.1 §1/§2：二级入口不再挂在左侧，进入一级模块后由页面顶部的 Tab 条横向切换。 -->
  <nav class="kos-modnav" :aria-label="label">
    <button
      v-for="leaf in leaves"
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
  </nav>
</template>
<script setup lang="ts">
import type { NavLeaf } from '../navigation'
import NavIcon from './NavIcon.vue'

defineProps<{
  label: string
  leaves: NavLeaf[]
  active: string
  /** 需要人工介入的入口角标：待审核 / 未解决冲突，键为视图 key。 */
  badges?: Record<string, number | string>
}>()
const emit = defineEmits<{ select: [key: string] }>()

function select(leaf: NavLeaf) {
  if (leaf.planned) return
  emit('select', leaf.key)
}
</script>
