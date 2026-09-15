<template>
  <section>
    <h2>数据中心</h2>
    <el-card>
      <el-upload :auto-upload="false" :limit="1" :on-change="onPick" :on-remove="onRemove">
        <el-button>选择 PDF / DOCX / Markdown / HTML / TXT</el-button>
      </el-upload>
      <div class="toolbar">
        <span>来源权威分：</span>
        <el-input-number :model-value="authority" :min="0" :max="1" :step="0.05"
          @update:model-value="$emit('update:authority', $event)" />
        <el-button type="primary" :loading="uploading" @click="$emit('upload')">上传并生成知识</el-button>
      </div>
    </el-card>
    <el-card class="spaced-card">
      <template #header>已接入文档</template>
      <el-table :data="documents">
        <el-table-column prop="sourceName" label="来源" />
        <el-table-column prop="title" label="文档" />
        <el-table-column prop="parseStatus" label="解析状态" />
        <el-table-column prop="chunkCount" label="内容块" width="100" />
      </el-table>
    </el-card>
  </section>
</template>
<script setup>
defineProps({ authority: Number, documents: { type: Array, default: () => [] }, uploading: Boolean })
const emit = defineEmits(['pick', 'remove', 'upload', 'update:authority'])
function onPick(file) { emit('pick', file) }
function onRemove() { emit('remove') }
</script>
<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; margin-top: 12px; }
.spaced-card { margin-top: 16px; }
</style>
