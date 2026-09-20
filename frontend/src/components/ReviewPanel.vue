<template>
  <section class="review-stack">
    <div class="section-header">
      <h2>{{ titleText }}</h2>
      <el-button type="primary" :loading="adjudicating" @click="emit('adjudicate')">运行智能裁决</el-button>
    </div>
    <div class="review-metrics">
      <div class="review-metric"><span>待审核知识</span><b>{{ pendingReviews.length }}</b></div>
      <div class="review-metric"><span>待处理冲突</span><b>{{ openConflicts.length }}</b></div>
      <div class="review-metric"><span>需人工复核</span><b>{{ humanReviewCount }}</b></div>
      <div class="review-metric"><span>裁决记录</span><b>{{ assessments.length }}</b></div>
    </div>
    <el-alert
      title="裁决策略：证据支持 28% + 来源权威 22% + 时效 14% + 对象置信度 20% + 跨来源支持 10% + 完整度 6%，再扣除冲突与无证据惩罚；AI Judge 只做语义辅助，高风险对象必须人工复核。"
      type="info" :closable="false" class="review-alert" />

    <!-- 设计 §4.4：左（冲突/待审核）→ 中（A/B 对照 + 系统建议）→ 右（Evidence）三栏裁决工作台 -->
    <el-row :gutter="12" class="judge-row">
      <el-col :span="7">
        <el-card shadow="never" class="judge-col">
          <template #header>
            <div class="judge-head"><span>知识冲突</span><el-tag size="small" type="danger">{{ openConflicts.length }}</el-tag></div>
          </template>
          <el-table
            :data="openConflicts" height="232" :row-class-name="conflictRowClass"
            @row-click="(row:ConflictRow)=>emit('selectConflict',row.id)">
            <el-table-column label="冲突双方" min-width="200">
              <template #default="{ row }">
                <div class="judge-cell">{{ row.objectATitle || row.objectACode }}</div>
                <div class="judge-cell judge-cell-sub">↔ {{ row.objectBTitle || row.objectBCode }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="conflictScore" label="冲突分" width="76" />
          </el-table>
        </el-card>
        <el-card shadow="never" class="judge-col">
          <template #header>
            <div class="judge-head"><span>待审核知识</span><el-tag size="small" type="warning">{{ pendingReviews.length }}</el-tag></div>
          </template>
          <el-table
            :data="pendingReviews" height="232" :row-class-name="reviewRowClass"
            @row-click="(row:ReviewRow)=>emit('selectReview',row)">
            <el-table-column label="对象" min-width="170">
              <template #default="{ row }">
                <div class="judge-cell">{{ row.displayTitle || row.objectCode }}</div>
                <div class="judge-cell judge-cell-sub">{{ objectTypeLabel(row.objectType) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">{{ statusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column prop="confidence" label="置信度" width="76" />
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card shadow="never" class="judge-col judge-main">
          <template #header>
            <div class="judge-head">
              <span>知识裁决</span>
              <el-tag v-if="selectedConflictId" size="small" type="danger">冲突 #{{ selectedConflictId }}</el-tag>
              <el-tag v-else-if="selectedReviewCode" size="small" type="warning">{{ selectedReviewCode }}</el-tag>
            </div>
          </template>

          <div v-if="conflictDetailLoading" class="judge-empty">正在装载对照与证据...</div>

          <template v-else-if="conflictDetail">
            <div class="judge-advice">
              <div class="judge-advice-head">
                <span>系统建议</span>
                <el-tag size="small" :type="adviceTagType">{{ advice.decisionText }}</el-tag>
              </div>
              <div class="judge-advice-body">
                <span>知识可信度 {{ score(advice.knowledgeConfidence) }}</span>
                <span>风险 {{ advice.riskLevel || '—' }}</span>
                <span>{{ advice.requiresHumanReview ? '必须人工复核' : '可自动生效' }}</span>
                <span v-if="advice.policyVersion">策略 {{ advice.policyVersion }}</span>
              </div>
              <p class="judge-advice-reason">{{ advice.reasonText || '暂无系统裁决说明，请依据下方证据人工判断。' }}</p>
            </div>

            <div class="judge-sides">
              <article v-for="(side,i) in conflictDetail.sides" :key="i" class="judge-side">
                <header>
                  <span class="judge-side-tag" :class="i===0?'is-a':'is-b'">{{ i===0?'A':'B' }}</span>
                  <b>{{ sideTitle(i) }}</b>
                </header>
                <div class="judge-side-value">{{ side.content || '—' }}</div>
                <div class="judge-side-meta">
                  {{ statusLabel(side.status) }} · 版本 v{{ side.versionNo ?? 1 }} · 置信度 {{ score(side.confidence) }} · 证据 {{ side.evidenceCount ?? 0 }}
                </div>
                <div v-if="conditionText(side)" class="judge-side-cond">条件：{{ conditionText(side) }}</div>
              </article>
            </div>

            <div class="judge-actions">
              <el-button size="small" type="success" :disabled="!selectedConflictId" @click="submitConflict('KEEP_A')">采用 A</el-button>
              <el-button size="small" type="success" :disabled="!selectedConflictId" @click="submitConflict('KEEP_B')">采用 B</el-button>
              <el-button size="small" type="primary" :disabled="!selectedConflictId" @click="submitConflict('MERGE')">合并</el-button>
              <el-button size="small" type="danger" :disabled="!selectedConflictId" @click="submitConflict('REJECT_BOTH')">全部拒绝</el-button>
              <el-button size="small" type="warning" :disabled="!selectedConflictId" @click="submitConflict('REQUEST_EVIDENCE')">要求补充证据</el-button>
            </div>
          </template>

          <template v-else-if="selectedReviewCode">
            <div class="judge-advice">
              <div class="judge-advice-head">
                <span>系统建议</span>
                <el-tag size="small" :type="adviceTagType">{{ advice.decisionText }}</el-tag>
              </div>
              <div class="judge-advice-body">
                <span>知识可信度 {{ score(advice.knowledgeConfidence) }}</span>
                <span>风险 {{ advice.riskLevel || '—' }}</span>
                <span>{{ advice.requiresHumanReview ? '必须人工复核' : '可自动生效' }}</span>
              </div>
              <p class="judge-advice-reason">{{ advice.reasonText || '暂无系统裁决说明，请依据右侧证据人工判断。' }}</p>
            </div>
            <div class="judge-side">
              <header><span class="judge-side-tag is-a">{{ objectTypeLabel(selectedReviewType) }}</span><b>{{ selectedReviewTitle }}</b></header>
              <div class="judge-side-meta">
                {{ statusLabel(selectedReviewStatus) }} · 置信度 {{ score(selectedReviewConfidence) }} · 证据 {{ reviewEvidence.length }}
              </div>
            </div>
            <div class="judge-actions">
              <el-button size="small" type="success" :disabled="!selectedReviewCode" @click="submitReview('APPROVED')">批准生效</el-button>
              <el-button size="small" type="danger" :disabled="!selectedReviewCode" @click="submitReview('REJECTED')">驳回</el-button>
              <el-button size="small" type="warning" :disabled="!selectedReviewCode" @click="submitReview('PENDING_REVIEW')">要求补充证据</el-button>
            </div>
          </template>

          <div v-else class="judge-empty">从左侧选择一条冲突或待审核知识，开始并排比对与裁决</div>

          <el-input
            v-model="comment" type="textarea" :rows="2" maxlength="500" show-word-limit
            placeholder="裁决备注：记录判断依据，与决策、审核人一并写入复核记录"
            class="judge-comment" />
        </el-card>
      </el-col>

      <el-col :span="7">
        <el-card shadow="never" class="judge-col">
          <template #header>
            <div class="judge-head"><span>Evidence</span><el-tag size="small">{{ evidenceTotal }}</el-tag></div>
          </template>
          <div v-for="group in evidenceGroups" :key="group.key" class="judge-evidence-group">
            <div class="judge-evidence-label">{{ group.label }}</div>
            <article
              v-for="e in group.items" :key="String(e.evidenceCode)"
              class="judge-evidence" @click="emit('openEvidence',String(e.evidenceCode))">
              <p class="judge-evidence-quote">{{ e.quoteText }}</p>
              <div class="judge-evidence-meta">
                <span>{{ e.sourceName || e.documentTitle || e.documentCode || '—' }}</span>
                <span v-if="e.pageNo">P{{ e.pageNo }}</span>
                <span v-if="e.headingPath">{{ e.headingPath }}</span>
                <span>权威 {{ score(e.authorityScore) }}</span>
              </div>
            </article>
            <div v-if="!group.items.length" class="judge-evidence-empty">无支撑证据</div>
          </div>
          <div v-if="!evidenceGroups.length" class="judge-empty">选中冲突或待审核对象后，按来源 / 页码 / 权威度展示支撑证据</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="assessment-card">
      <template #header>智能裁决记录</template>
      <el-table :data="assessments" height="300">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ objectTypeLabel(row.objectType) }}</template>
        </el-table-column>
        <el-table-column prop="objectCode" label="对象代码" min-width="180" />
        <el-table-column prop="knowledgeConfidence" label="知识可信度" width="110" />
        <el-table-column prop="riskLevel" label="风险" width="80" />
        <el-table-column label="系统决策" width="150">
          <template #default="{ row }">{{ statusLabel(row.decision) }}</template>
        </el-table-column>
        <el-table-column label="人工复核" width="100">
          <template #default="{ row }">{{ row.requiresHumanReview ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="reasonText" label="裁决说明" min-width="220" show-overflow-tooltip />
        <el-table-column prop="policyVersion" label="策略版本" width="140" />
      </el-table>
    </el-card>
  </section>
</template>
<script setup lang="ts">
import{computed,ref}from'vue'
import type{AdjudicationEvidence,AdjudicationSide,AssessmentRow,ConflictDetail,ConflictRow,ReviewRow}from'../types'
import{objectTypeLabel,statusLabel}from'../types'
/**
 * 知识裁决工作台（设计 §4.4）：左列给出冲突与待审核队列，中列并排展示 Assertion A / B
 * 的值、条件、版本、置信度与系统建议，右列给出 Evidence 原文 / 来源 / 页码 / 权威度，
 * 裁决动作（采用 A / 采用 B / 合并 / 全部拒绝 / 要求补充证据）必须携带备注，便于回溯。
 */
const props=defineProps<{
  pendingReviews:ReviewRow[]; conflicts:ConflictRow[]; assessments:AssessmentRow[]; adjudicating:boolean
  focus?:'review'|'conflict'|'judgment'
  conflictDetail?:ConflictDetail|null; conflictDetailLoading?:boolean; selectedConflictId?:number|null
  selectedReview?:ReviewRow|null; reviewDetail?:Record<string,unknown>|null
}>()
const emit=defineEmits<{
  adjudicate:[];review:[row:ReviewRow,decision:string,comment:string]
  resolve:[row:ConflictRow,status:string];selectConflict:[id:number];selectReview:[row:ReviewRow]
  adjudicateConflict:[id:number,decision:string,comment:string];openEvidence:[code:string]
}>()

const comment=ref('')
const focus=computed(()=>props.focus||'judgment')
const TITLES:Record<string,string>={review:'待审核知识',conflict:'知识冲突',judgment:'知识裁决工作台'}
const titleText=computed(()=>TITLES[focus.value]||TITLES.judgment)
const openConflicts=computed(()=>props.conflicts.filter(item=>String(item.status||'OPEN').toUpperCase()==='OPEN'))
const humanReviewCount=computed(()=>props.assessments.filter(item=>item.requiresHumanReview).length)
const selectedRow=computed(()=>props.conflicts.find(item=>item.id===props.selectedConflictId)||null)

const score=(value?:unknown):string=>{
  const num=Number(value??0)
  return Number.isFinite(num)?num.toFixed(2):'—'
}
const selectedReviewCode=computed(()=>String(props.selectedReview?.objectCode||''))
const conflictRowClass=({row}:{row:ConflictRow}):string=>row.id===props.selectedConflictId?'is-selected':''
const reviewRowClass=({row}:{row:ReviewRow}):string=>String(row.objectCode)===selectedReviewCode.value?'is-selected':''

/** 并排比对时优先用冲突行带出的对象标题，退回对象编码，避免只看到编码。 */
const sideTitle=(index:number):string=>{
  const row=selectedRow.value
  if(!row)return String(props.conflictDetail?.sides?.[index]?.objectCode||'')
  const title=index===0?row.objectATitle:row.objectBTitle
  const code=index===0?row.objectACode:row.objectBCode
  return title||code||''
}
/** 条件为 JSON 文本，展示时展开为可读文本，避免评审者面对原始转义串。 */
const conditionText=(side:AdjudicationSide):string=>{
  const raw=String(side?.conditionJson||'').trim()
  if(!raw)return ''
  try{
    const parsed=JSON.parse(raw)
    if(Array.isArray(parsed))return parsed.join('、')
    if(parsed&&typeof parsed==='object')return Object.entries(parsed).map(([k,v])=>`${k}=${String(v)}`).join('，')
  }catch{return raw}
  return raw
}

/** 系统建议取冲突双方中风险更高的一条，评审者看到的是最保守的判断。 */
const advice=computed(()=>{
  const codes=selectedRow.value
    ? [selectedRow.value.objectACode,selectedRow.value.objectBCode]
    : [selectedReviewCode.value]
  const rows=props.assessments.filter(item=>codes.includes(String(item.objectCode)))
  const risky=rows.find(item=>item.requiresHumanReview)||rows[0]
  return{
    decisionText:risky?statusLabel(risky.decision):'未裁决',
    knowledgeConfidence:risky?.knowledgeConfidence,
    riskLevel:risky?.riskLevel||'',
    requiresHumanReview:Boolean(risky?.requiresHumanReview),
    policyVersion:String(risky?.policyVersion||''),
    reasonText:String(risky?.reasonText||''),
  }
})
const adviceTagType=computed(()=>advice.value.requiresHumanReview?'warning':'success')

const selectedReviewTitle=computed(()=>
  String(props.reviewDetail?.displayTitle||props.selectedReview?.displayTitle||selectedReviewCode.value||''))
const selectedReviewType=computed(()=>String(props.reviewDetail?.objectType||props.selectedReview?.objectType||''))
const selectedReviewStatus=computed(()=>String(props.reviewDetail?.status||props.selectedReview?.status||''))
const selectedReviewConfidence=computed(()=>props.reviewDetail?.confidence??props.selectedReview?.confidence)
const reviewEvidence=computed<AdjudicationEvidence[]>(()=>{
  const list=props.reviewDetail?.evidence
  return Array.isArray(list)?list as AdjudicationEvidence[]:[]
})

const evidenceGroups=computed<{key:string;label:string;items:AdjudicationEvidence[]}[]>(()=>{
  if(props.conflictDetail?.sides?.length){
    return props.conflictDetail.sides.map((side,index)=>({
      key:`${side.objectCode}-${index}`,
      label:`${index===0?'A':'B'} · ${sideTitle(index)||side.objectCode}`,
      items:Array.isArray(side.evidence)?side.evidence:[],
    }))
  }
  if(selectedReviewCode.value){
    return [{key:selectedReviewCode.value,label:`对象 · ${selectedReviewTitle.value}`,items:reviewEvidence.value}]
  }
  return []
})
const evidenceTotal=computed(()=>evidenceGroups.value.reduce((sum,group)=>sum+group.items.length,0))

const submitConflict=(decision:string):void=>{
  if(!props.selectedConflictId)return
  emit('adjudicateConflict',props.selectedConflictId,decision,comment.value)
  comment.value=''
}
const submitReview=(decision:string):void=>{
  const row=props.selectedReview
  if(!row)return
  emit('review',row,decision,comment.value)
  comment.value=''
}
</script>
<style scoped>
.review-stack{display:flex;flex-direction:column}
.section-header{display:flex;justify-content:space-between;align-items:center}
.review-alert{margin-bottom:12px}
.review-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:12px}
.review-metric{border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:10px 12px;background:#fff}
.review-metric span{display:block;font-size:12px;color:var(--el-text-color-secondary)}
.review-metric b{font-size:20px;color:var(--el-text-color-primary)}
.judge-row{align-items:stretch}
.judge-col{margin-bottom:12px}
.judge-main{min-height:520px}
.judge-head{display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:13px;font-weight:600}
.judge-cell{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}
.judge-cell-sub{font-size:11px;color:var(--el-text-color-secondary)}
.judge-empty{padding:48px 12px;text-align:center;font-size:13px;color:var(--el-text-color-secondary)}
.judge-advice{border:1px solid var(--el-color-primary-light-7);background:var(--el-color-primary-light-9);border-radius:8px;padding:10px 12px;margin-bottom:12px}
.judge-advice-head{display:flex;align-items:center;justify-content:space-between;font-size:12px;font-weight:600;margin-bottom:6px}
.judge-advice-body{display:flex;flex-wrap:wrap;gap:12px;font-size:12px;color:var(--el-text-color-regular)}
.judge-advice-reason{margin:6px 0 0;font-size:12px;line-height:20px;color:var(--el-text-color-secondary)}
.judge-sides{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.judge-side{border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:10px 12px;background:#fff}
.judge-side header{display:flex;align-items:center;gap:6px;margin-bottom:6px;font-size:13px}
.judge-side-tag{display:inline-flex;width:20px;height:20px;align-items:center;justify-content:center;border-radius:4px;font-size:11px;font-weight:700;color:#fff}
.judge-side-tag.is-a{background:var(--el-color-primary)}
.judge-side-tag.is-b{background:var(--el-color-warning)}
.judge-side-value{font-size:13px;line-height:20px;color:var(--el-text-color-primary);word-break:break-word}
.judge-side-meta{margin-top:6px;font-size:11px;color:var(--el-text-color-secondary)}
.judge-side-cond{margin-top:4px;font-size:11px;color:var(--el-color-primary)}
.judge-actions{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}
.judge-comment{margin-top:4px}
.judge-evidence-group{margin-bottom:12px}
.judge-evidence-label{font-size:11px;font-weight:600;color:var(--el-text-color-secondary);margin-bottom:6px}
.judge-evidence{border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:8px 10px;margin-bottom:8px;cursor:pointer;background:#fff;transition:border-color .15s}
.judge-evidence:hover{border-color:var(--el-color-primary-light-5)}
.judge-evidence-quote{margin:0;font-size:12px;line-height:19px;color:var(--el-text-color-regular)}
.judge-evidence-meta{display:flex;flex-wrap:wrap;gap:10px;margin-top:6px;font-size:11px;color:var(--el-text-color-secondary)}
.judge-evidence-empty{font-size:12px;color:var(--el-text-color-placeholder);padding:4px 0}
.assessment-card{margin-top:4px}
.judge-row :deep(.is-selected){background:var(--el-color-primary-light-9)}
</style>
