<template>
  <section>
    <h2>智能查询</h2>
    <el-card>
      <el-input :model-value="query" type="textarea" :rows="4" placeholder="输入问题"
        @update:model-value="$emit('update:query', $event)" />
      <el-button type="primary" :loading="asking" class="query-button" @click="$emit('ask')">查询</el-button>
      <div v-if="result" class="result">
        <h3>回答</h3><div class="answer">{{ result.answer }}</div>
        <h3>检索与重排序</h3>
        <el-tag v-for="strategy in result.strategies" :key="strategy" class="strategy">{{ strategy }}</el-tag>
        <el-tag type="success">{{ result.reranker?.name }}</el-tag>
        <h3>引用证据</h3>
        <el-table :data="result.citations">
          <el-table-column prop="channel" label="通道" width="100" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="sourceName" label="来源" />
          <el-table-column prop="pageNo" label="页码" width="80" />
        </el-table>
        <h3>Top Candidates</h3>
        <el-table :data="result.candidates">
          <el-table-column prop="channel" label="通道" width="90" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="preRerankScore" label="融合分" width="110" />
          <el-table-column prop="rerankScore" label="重排分" width="110" />
        </el-table>
      </div>
    </el-card>
  </section>
</template>
<script setup>
defineProps({ query: String, result: Object, asking: Boolean })
defineEmits(['ask', 'update:query'])
</script>
<style scoped>
.query-button { margin-top: 12px; }
.result { margin-top: 20px; }
.answer { line-height: 1.8; background: #f8fafc; border: 1px solid #e2e8f0; padding: 16px; border-radius: 8px; }
.strategy { margin-right: 8px; }
</style>
