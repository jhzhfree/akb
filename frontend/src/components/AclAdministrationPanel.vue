<template>
<section class="kb-page space-y-4">
  <div class="grid gap-3 md:grid-cols-4">
    <div class="kb-metric"><span>授权对象</span><b>{{ subjects.length }}</b></div>
    <div class="kb-metric"><span>角色</span><b>{{ roles.length }}</b></div>
    <div class="kb-metric"><span>权限变更</span><b>{{ changeTotal ?? changes.length }}</b></div>
    <div class="kb-metric"><span>访问审计日志</span><b>{{ auditTotal ?? audits.length }}</b></div>
  </div>
  <section class="kb-card p-4">
    <div class="kb-section-head"><div><small>权限变更</small><h3>资源权限修改</h3></div></div>
    <div class="grid gap-3 md:grid-cols-4">
      <select v-model="form.resourceType" class="kb-input">
        <option>来源</option><option>知识卡片</option><option>断言</option>
      </select>
      <input v-model="form.resourceCode" class="kb-input" placeholder="资源编码" />
      <input v-model="form.tags" class="kb-input" placeholder="team-a,ops；留空=公开" />
      <button class="kb-btn kb-btn-primary" :disabled="saving" @click="submit">保存并传播</button>
    </div>
    <input v-model="form.reason" class="kb-input mt-3 w-full" placeholder="变更原因 / 工单号" />
  </section>
  <div class="grid gap-4 xl:grid-cols-2">
    <section class="kb-card overflow-hidden">
      <div class="kb-card-head">
        <b>权限变更 / 待同步队列</b>
        <span class="kb-muted">共 {{ changeTotal ?? changes.length }} 条</span>
      </div>
      <div class="divide-y divide-ink-100">
        <div v-for="c in changes" :key="c.id" class="p-3 text-xs">
          <div class="flex justify-between gap-3">
            <b>{{ c.resourceType }} · {{ c.resourceCode }}</b><span class="kb-badge">{{ c.propagationState }}</span>
          </div>
          <div class="mt-1 text-ink-500">{{ c.changeCode }} · {{ c.changedBy || 'system' }} · {{ c.createdTime }}</div>
          <div v-if="c.propagationState==='REBUILD_REQUIRED'" class="mt-2 rounded bg-[color:var(--warn-bg)] p-2 text-[color:var(--warn)]">
            Milvus/Source cascade 需要权威重建；未验证前不标记 COMPLETED。
          </div>
          <div v-if="c.lastError" class="mt-2 rounded bg-[color:var(--danger-bg)] p-2 text-[color:var(--danger)]">{{ c.lastError }}</div>
        </div>
        <div v-if="!changes.length" class="p-6 text-center text-xs text-ink-500">暂无 ACL 变更</div>
      </div>
      <div v-if="(changeTotal ?? 0) > (changeSize ?? 0)" class="flex justify-end p-3">
        <el-pagination
          small layout="prev,pager,next,total,sizes" :current-page="changePage ?? 1" :page-size="changeSize ?? 20"
          :page-sizes="[20,50,100]" :total="changeTotal ?? 0" :pager-count="5"
          @current-change="(p:number)=>emit('log-page',{which:'change',page:p,size:changeSize ?? 20})"
          @size-change="(s:number)=>emit('log-page',{which:'change',page:1,size:s})" />
      </div>
    </section>
    <section class="kb-card overflow-hidden">
      <div class="kb-card-head">
        <b>访问审计</b>
        <span class="kb-muted">共 {{ auditTotal ?? audits.length }} 条</span>
      </div>
      <div class="divide-y divide-ink-100">
        <div v-for="a in audits" :key="a.id" class="p-3 text-xs">
          <div class="flex justify-between gap-3">
            <b>{{ a.subjectCode || 'anonymous' }} · {{ a.actionName }}</b>
            <span class="kb-badge">{{ a.decision }}</span>
          </div>
          <div class="mt-1 text-ink-500">{{ a.resourceType }} · {{ a.resourceCode || '—' }}</div>
          <div class="mt-1 text-[10px] text-ink-500">{{ a.reason || '—' }} · {{ a.createdTime }}</div>
        </div>
        <div v-if="!audits.length" class="p-6 text-center text-xs text-ink-500">暂无访问审计</div>
      </div>
      <div v-if="(auditTotal ?? 0) > (auditSize ?? 0)" class="flex justify-end p-3">
        <el-pagination
          small layout="prev,pager,next,total,sizes" :current-page="auditPage ?? 1" :page-size="auditSize ?? 20"
          :page-sizes="[20,50,100]" :total="auditTotal ?? 0" :pager-count="5"
          @current-change="(p:number)=>emit('log-page',{which:'audit',page:p,size:auditSize ?? 20})"
          @size-change="(s:number)=>emit('log-page',{which:'audit',page:1,size:s})" />
      </div>
    </section>
  </div>
</section>
</template>
<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { AclAudit, AclChange, AclRole, AclSubject } from '../types'

defineProps<{
  subjects:AclSubject[]; roles:AclRole[]; changes:AclChange[]; audits:AclAudit[]
  /** 流水分页：总数与页码由上层持有，本面板只负责回传翻页动作。 */
  changeTotal?:number; changePage?:number; changeSize?:number
  auditTotal?:number; auditPage?:number; auditSize?:number
}>()
const emit=defineEmits<{
  change:[payload:{resourceType:string; resourceCode:string; permissionTags:string[]; reason:string}]
  'log-page':[payload:{ which:'audit'|'change'; page:number; size:number }]
}>()
const saving=ref(false)
const form=reactive({resourceType:'CARD',resourceCode:'',tags:'',reason:''})
function submit(){
  if(!form.resourceCode.trim())return
  saving.value=true
  emit('change', {
    resourceType:form.resourceType,
    resourceCode:form.resourceCode.trim(),
    permissionTags:form.tags.split(',').map(v=>v.trim()).filter(Boolean),
    reason:form.reason.trim(),
  })
  window.setTimeout(()=>{saving.value=false},500)
}
</script>
