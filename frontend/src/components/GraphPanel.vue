<template>
  <section>
    <h2>知识图谱邻域</h2>
    <el-card>
      <el-input :model-value="code" placeholder="输入 KnowledgeCode / ClaimCode" class="graph-input"
        @update:model-value="$emit('update:code', $event)" />
      <el-button type="primary" class="graph-button" @click="$emit('load')">查询邻域</el-button>
      <div v-if="result" class="graph-result">
        <el-alert v-if="!result.available"
          title="Neo4j 当前不可用，启动 Neo4j 并配置 GRAPH_PROVIDER=neo4j 后即可返回真实图谱。"
          type="info" :closable="false" />
        <h3>节点</h3>
        <el-table :data="result.nodes || []">
          <el-table-column prop="type" label="类型" width="140" />
          <el-table-column prop="code" label="代码" />
          <el-table-column prop="title" label="名称" />
        </el-table>
        <h3>关系</h3>
        <el-table :data="result.edges || []">
          <el-table-column prop="source" label="源" />
          <el-table-column prop="type" label="关系" width="160" />
          <el-table-column prop="target" label="目标" />
        </el-table>
      </div>
    </el-card>
  </section>
</template>
<script setup>
defineProps({ code: String, result: Object })
defineEmits(['load', 'update:code'])
</script>
<style scoped>
.graph-input { width: 420px; }
.graph-button { margin-left: 8px; }
.graph-result { margin-top: 20px; }
</style>
