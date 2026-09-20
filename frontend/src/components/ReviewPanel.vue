<template>
  <section class="review-stack">
    <div class="section-header" :style="{ order: 0 }">
      <h2>{{ titleText }}</h2>
      <el-button type="primary" :loading="adjudicating" @click="$emit('adjudicate')">运行智能裁决</el-button>
    </div>
    <el-alert
      title="裁决策略：证据支持 28% + 来源权威 22% + 时效 14% + 对象置信度 20% + 跨来源支持 10% + 完整度 6%，再扣除冲突与无证据惩罚；AI Judge 只做语义辅助，高风险对象必须人工复核。"
      type="info" :closable="false" class="review-alert" :style="{ order: 1 }" />
    <el-row :gutter="16" :style="{ order: rowOrder }">
      <el-col :span="primarySpan" :style="{ order: conflictFirst ? 2 : 1 }">
        <el-card>
          <template #header>待审核知识</template>
          <el-table :data="pendingReviews" height="520">
            <el-table-column label="类型" width="130">
              <template #default="{ row }">{{ objectTypeLabel(row.objectType) }}</template>
            </el-table-column>
            <el-table-column prop="displayTitle" label="对象" />
            <el-table-column prop="confidence" label="置信度" width="90" />
            <el-table-column label="状态" width="140">
              <template #default="{ row }">{{ statusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="190">
              <template #default="{ row }">
                <el-button size="small" type="success" @click="$emit('review', row, 'APPROVED')">批准</el-button>
                <el-button size="small" type="danger" @click="$emit('review', row, 'REJECTED')">拒绝</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="secondarySpan" :style="{ order: conflictFirst ? 1 : 2 }">
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
    <el-card class="assessment-card" :style="{ order: cardOrder }">
      <template #header>智能裁决记录</template>
      <el-table :data="assessments" height="360">
        <el-table-column label="类型" width="130">
          <template #default="{ row }">{{ objectTypeLabel(row.objectType) }}</template>
        </el-table-column>
        <el-table-column prop="objectCode" label="对象代码" />
        <el-table-column prop="knowledgeConfidence" label="知识可信度" width="110" />
        <el-table-column prop="riskLevel" label="风险" width="90" />
        <el-table-column label="系统决策" width="160">
          <template #default="{ row }">{{ statusLabel(row.decision) }}</template>
        </el-table-column>
        <el-table-column prop="requiresHumanReview" label="人工复核" width="100">
          <template #default="{ row }">{{ row.requiresHumanReview ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="policyVersion" label="策略版本" width="150" />
      </el-table>
    </el-card>
  </section>
</template>
<script setup lang="ts">
import{computed}from'vue'
import type{AssessmentRow,ConflictRow,ReviewRow}from'../types'
import{objectTypeLabel,statusLabel}from'../types'
/** IA「知识治理」下的三个入口共用同一工作台：focus 决定标题、主次列宽与区块顺序。 */
const props=defineProps<{
  pendingReviews:ReviewRow[]; conflicts:ConflictRow[]; assessments:AssessmentRow[]; adjudicating:boolean
  focus?:'review'|'conflict'|'judgment'
}>()
defineEmits<{adjudicate:[];review:[row:ReviewRow,decision:string];resolve:[row:ConflictRow,status:string]}>()

const focus=computed(()=>props.focus||'judgment')
const TITLES:Record<string,string>={review:'待审核知识',conflict:'知识冲突',judgment:'知识裁决工作台'}
const titleText=computed(()=>TITLES[focus.value]||TITLES.judgment)
const conflictFirst=computed(()=>focus.value==='conflict')
const primarySpan=computed(()=>conflictFirst.value?8:16)
const secondarySpan=computed(()=>conflictFirst.value?16:8)
/** 裁决入口把「智能裁决记录」提到两列之上，其他入口保持审核/冲突优先。 */
const rowOrder=computed(()=>focus.value==='judgment'?3:2)
const cardOrder=computed(()=>focus.value==='judgment'?2:4)
</script>
<style scoped>
/* 区块顺序由治理入口决定：裁决入口优先展示裁决记录，冲突入口优先展示冲突列。 */
.review-stack{display:flex;
flex-direction:column}
.section-header { display: flex;
 justify-content: space-between;
 align-items: center;
 }

.review-alert { margin-bottom: 16px;
 }

.assessment-card { margin-top: 16px;
 }

</style>
