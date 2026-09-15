<template>
  <section>
    <h2>{{ mode === 'cards' ? '知识卡片' : '断言' }}</h2>
    <el-table :data="items" border>
      <template v-if="mode === 'cards'">
        <el-table-column prop="type" label="类型" width="130" />
        <el-table-column prop="title" label="标题" />
      </template>
      <template v-else>
        <el-table-column prop="subject" label="主体" />
        <el-table-column prop="predicate" label="谓词" width="140" />
        <el-table-column prop="objectValue" label="客体" />
      </template>
      <el-table-column prop="confidence" label="置信度" width="100" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column label="图谱" width="100">
        <template #default="{ row }">
          <el-button size="small" @click="$emit('open-graph', objectCode(row))">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>
<script setup>
const props = defineProps({ mode: { type: String, required: true }, items: { type: Array, default: () => [] } })
defineEmits(['open-graph'])
function objectCode(row) { return props.mode === 'cards' ? row.knowledgeCode : row.claimCode }
</script>
