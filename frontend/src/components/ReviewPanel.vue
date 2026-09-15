<template>
  <section>
    <div class="section-header">
      <h2>知识裁决工作台</h2>
      <el-button type="primary" :loading="adjudicating" @click="$emit('adjudicate')">运行智能裁决</el-button>
    </div>
    <el-alert
      title="裁决策略：证据支持 28% + 来源权威 22% + 时效 14% + 对象置信度 20% + 跨来源支持 10% + 完整度 6%，再扣除冲突与无证据惩罚；AI Judge 只做语义辅助，高风险对象必须人工复核。"
      type="info" :closable="false" class="review-alert" />
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header>待审核知识</template>
          <el-table :data="pendingReviews" height="520">
            <el-table-column prop="objectType" label="类型" width="90" />
            <el-table-column prop="displayTitle" label="对象" />
            <el-table-column prop="confidence" label="置信度" width="90" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column label="操作" width="190">
              <template #default="{ row }">
                <el-button size="small" type="success" @click="$emit('review', row, 'APPROVED')">批准</el-button>
                <el-button size="small" type="danger" @click="$emit('review', row, 'REJECTED')">拒绝</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>知识冲突</template>
          <el-table :data="conflicts" height="520">
            <el-table-column prop="conflictType" label="冲突类型" width="150" />
            <el-table-column prop="objectACode" label="对象 A" />
            <el-table-column prop="objectBCode" label="对象 B" />
            <el-table-column prop="conflictScore" label="冲突分" width="90" />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="$emit('resolve', row, 'RESOLVED')">已解决</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    <el-card class="assessment-card">
      <template #header>智能裁决记录</template>
      <el-table :data="assessments" height="360">
        <el-table-column prop="objectType" label="类型" width="80" />
        <el-table-column prop="objectCode" label="对象代码" />
        <el-table-column prop="knowledgeConfidence" label="知识可信度" width="110" />
        <el-table-column prop="riskLevel" label="风险" width="90" />
        <el-table-column prop="decision" label="系统决策" width="150" />
        <el-table-column prop="requiresHumanReview" label="人工复核" width="100">
          <template #default="{ row }">{{ row.requiresHumanReview ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="policyVersion" label="策略版本" width="150" />
      </el-table>
    </el-card>
  </section>
</template>
<script setup>
defineProps({
  pendingReviews: { type: Array, default: () => [] },
  conflicts: { type: Array, default: () => [] },
  assessments: { type: Array, default: () => [] },
  adjudicating: Boolean,
})
defineEmits(['adjudicate', 'review', 'resolve'])
</script>
<style scoped>
.section-header { display: flex; justify-content: space-between; align-items: center; }
.review-alert { margin-bottom: 16px; }
.assessment-card { margin-top: 16px; }
</style>
