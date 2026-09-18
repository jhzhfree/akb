<template>
  <section class="kb-card p-4">
    <div class="kb-section-head">
      <div><small>ANSWER QUALIFICATION</small><h3>答案资格与 Claim 审计</h3></div>
      <span :class="badgeClass">{{ value?.gateStatus || 'UNKNOWN' }}</span>
    </div>
    <div class="grid gap-2 sm:grid-cols-4">
      <div class="kb-metric"><span>策略版本</span><b class="text-xs">{{ value?.policyVersion || '—' }}</b></div>
      <div class="kb-metric"><span>Evidence Gate</span><b>{{ value?.evidenceGateStatus || '—' }}</b></div>
      <div class="kb-metric"><span>Structured Gate</span><b>{{ value?.structuredGateStatus || '—' }}</b></div>
      <div class="kb-metric"><span>Claims</span><b>{{ claims.length }}</b></div>
    </div>
    <div v-if="claims.length" class="mt-4 overflow-x-auto">
      <table class="w-full text-left text-xs">
        <thead class="text-ink-400"><tr><th class="p-2">Claim</th><th class="p-2">判定</th><th class="p-2">蕴含度</th><th class="p-2">Evidence</th></tr></thead>
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
interface Qualification { gateStatus?:string; policyVersion?:string; evidenceGateStatus?:string; structuredGateStatus?:string; claims?:ClaimAudit[] }
const props=defineProps<{ value:Qualification|null|undefined }>()
const claims=computed(()=>props.value?.claims || [])
const badgeClass=computed(()=>{
  const base='kb-badge '
  if(props.value?.gateStatus==='PASS')return base+'bg-emerald-50 text-emerald-700'
  if(props.value?.gateStatus==='PARTIAL')return base+'bg-amber-50 text-amber-700'
  return base+'bg-rose-50 text-rose-700'
})
const percent=(value:number)=>`${Math.round((Number(value)||0)*100)}%`
const evidenceList=(claim:ClaimAudit):string[]=>{
  const raw=claim.evidenceIds ?? claim.evidenceIdsJson
  if(Array.isArray(raw))return raw.map(String)
  if(typeof raw==='string'){try{const value=JSON.parse(raw);return Array.isArray(value)?value.map(String):[]}catch{return []}}
  return []
}
</script>
