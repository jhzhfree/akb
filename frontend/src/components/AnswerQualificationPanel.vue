<template>
  <section class="kb-card p-4">
    <div class="kb-section-head">
      <div><small>答案校验</small><h3>答案资格与 断言 审计</h3></div>
      <span :class="badgeClass">{{ value?.gateStatus || 'UNKNOWN' }}</span>
    </div>
    <div class="grid gap-2 sm:grid-cols-4">
      <div class="kb-metric"><span>策略版本</span><b class="text-xs">{{ value?.policyVersion || '—' }}</b></div>
      <div class="kb-metric"><span>证据门槛</span><b>{{ value?.evidenceGateStatus || '—' }}</b></div>
      <div class="kb-metric"><span>结构化门槛</span><b>{{ value?.structuredGateStatus || '—' }}</b></div>
      <div class="kb-metric"><span>断言数</span><b>{{ claims.length }}</b></div>
    </div>
    <!-- 拦截构成：门控把答案拦下时，先说清楚是哪一类原因，而不是只给一个 BLOCKED -->
    <div v-if="blockRows.length" class="mt-2 flex flex-wrap items-center gap-2">
      <span class="text-[11px] text-ink-500">句子拦截构成</span>
      <span v-for="row in blockRows" :key="row.key" class="kb-badge">{{ row.label }} {{ row.count }}</span>
      <span class="text-[10px] text-ink-500">处理顺序：先补引用 → 再看引用是否精确 → 最后才是忠实度阈值</span>
    </div>
    <div v-if="claims.length" class="mt-4 overflow-x-auto">
      <table class="w-full text-left text-xs">
        <thead class="text-ink-500"><tr><th class="p-2">断言</th><th class="p-2">判定</th><th class="p-2">蕴含度</th><th class="p-2">证据</th></tr></thead>
        <tbody>
          <tr v-for="claim in claims" :key="claim.claimId" class="border-t border-ink-100">
            <td class="max-w-xl p-2 text-ink-700">{{ claim.statement }}</td>
            <td class="p-2 font-semibold">{{ claim.decision }}</td>
            <td class="p-2">{{ percent(claim.entailmentScore) }}</td>
            <td class="p-2">{{ evidenceList(claim).join(', ') || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
interface ClaimAudit { claimId:string; statement:string; decision:string; entailmentScore:number; evidenceIds?:unknown; evidenceIdsJson?:unknown }
interface Qualification { gateStatus?:string; policyVersion?:string; evidenceGateStatus?:string; structuredGateStatus?:string; claims?:ClaimAudit[]; blockReasons?:Record<string,number> }
const props=defineProps<{ value:Qualification|null|undefined }>()
const claims=computed(()=>props.value?.claims || [])
const BLOCK_LABELS:Record<string,string> = { missingCitation:'缺引用', nonExactEvidence:'引用无精确证据', lowFaithfulness:'忠实度不足' }
const blockRows=computed(()=>Object.entries(props.value?.blockReasons || {})
  .filter(([, count]) => Number(count) > 0)
  .map(([key, count]) => ({ key, label:BLOCK_LABELS[key] || key, count:Number(count) })))
const badgeClass=computed(()=>{
  const base='kb-badge '
  if(props.value?.gateStatus==='PASS')return base+'kb-state-ok'
  if(props.value?.gateStatus==='PARTIAL')return base+'kb-state-warn'
  return base+'kb-state-danger'
})
const percent=(value:number)=>`${Math.round((Number(value)||0)*100)}%`
const evidenceList=(claim:ClaimAudit):string[]=>{
  const raw=claim.evidenceIds ?? claim.evidenceIdsJson
  if(Array.isArray(raw))return raw.map(String)
  if(typeof raw==='string'){try{const value=JSON.parse(raw);return Array.isArray(value)?value.map(String):[]}catch{return []}}
  return []
}
</script>
