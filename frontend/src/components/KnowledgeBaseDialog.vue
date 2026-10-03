<template>
<el-dialog :model-value="visible" title="知识库管理" width="680px" @close="$emit('close')">
  <div class="kb-create">
    <el-input v-model="name" :placeholder="editing?'新名称':'知识库名称'"/>
    <el-input v-model="description" :placeholder="editing?'新用途 / 描述':'用途 / 描述'"/>
    <el-select v-model="visibility" class="kb-vis" placeholder="可见范围">
      <el-option v-for="item in VISIBILITIES" :key="item" :value="item" :label="visibilityLabel(item)"/>
    </el-select>
    <el-select v-model="ownerGroupId" class="kb-vis" :disabled="visibility!=='GROUP'" clearable
      placeholder="用户组（仅用户组可见时需要）">
      <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
    </el-select>
    <el-button v-if="editing" type="primary" :disabled="!ready" @click="submit">保存修改</el-button>
    <el-button v-if="editing" @click="reset">取消</el-button>
    <el-button v-else type="primary" :disabled="!ready" @click="submit">新建知识库</el-button>
  </div>
  <p v-if="editing" class="kb-hint">正在编辑「{{editingName}}」，保存后立即对该知识库生效。</p>
  <div class="kb-list">
    <div v-for="base in bases" :key="base.id" class="kb-row" :class="{active:base.id===activeId}" @click="$emit('select',base.id)">
      <span class="kb-icon">📚</span>
<span class="kb-copy">
<b>{{base.name}}</b>
<small>{{base.description||base.baseCode}}</small>
</span>
      <span class="kb-meta">
        <em class="kb-owner">创建人：{{base.createdByName||'—'}}</em>
        <el-tag size="small" effect="plain">{{visibilityLabel(base.visibility)}}</el-tag>
      </span>
      <el-tag v-if="base.id===activeId" size="small">当前</el-tag>
      <span class="kb-code">{{base.baseCode}}</span>
      <el-button v-if="canEdit(base)" size="small" text type="primary" class="kb-edit" @click.stop="startEdit(base)">编辑</el-button>
    </div>
  </div>
</el-dialog>
</template>
<script setup lang="ts">
import{computed,ref,watch}from'vue'
import type{KnowledgeBase,UserGroupRow}from'../types'
import{visibilityLabel}from'../types'
const VISIBILITIES=['PUBLIC','PRIVATE','GROUP'] as const
type BasePayload={name:string;description:string;visibility:string;ownerGroupId:number|null}
const props=defineProps<{
  visible:boolean;bases:KnowledgeBase[];activeId:number;groups:UserGroupRow[]
  /** 知识库管理员可编辑全部知识库；其余账号仅能编辑自己创建的。 */
  canManage?:boolean
  /** 当前账号 id，用于判定知识库归属。 */
  currentUserId?:number|null
}>()
const emit=defineEmits<{
  close:[]
  select:[id:number]
  create:[payload:BasePayload]
  update:[payload:BasePayload&{id:number}]
}>()
const name=ref('');const description=ref('')
const visibility=ref<string>('PUBLIC');const ownerGroupId=ref<number|null>(null)
const editingId=ref<number|null>(null)
const editing=computed(()=>editingId.value!==null)
const editingName=computed(()=>props.bases.find(b=>b.id===editingId.value)?.name||'')
/** 用户组可见必须指定组，否则会把知识库变成无人可访问的孤岛。 */
const ready=computed(()=>name.value.trim().length>0&&(visibility.value!=='GROUP'||ownerGroupId.value!==null))
/** 编辑入口按归属裁剪：无权者不显示按钮，避免点了才被后端拒绝。 */
function canEdit(base:KnowledgeBase){
  if(props.canManage)return true
  return props.currentUserId!=null&&base.createdBy===props.currentUserId
}
function reset(){
  name.value='';description.value='';visibility.value='PUBLIC';ownerGroupId.value=null;editingId.value=null
}
/** 回填当前值再编辑，避免改名时被迫重填用途与可见范围。 */
function startEdit(base:KnowledgeBase){
  editingId.value=base.id
  name.value=base.name
  description.value=base.description||''
  visibility.value=base.visibility||'PUBLIC'
  ownerGroupId.value=base.ownerGroupId??null
}
function submit(){
  if(!ready.value)return
  const payload:BasePayload={
    name:name.value.trim(),
    description:description.value.trim(),
    visibility:visibility.value,
    ownerGroupId:visibility.value==='GROUP'?ownerGroupId.value:null,
  }
  if(editingId.value!==null)emit('update',{id:editingId.value,...payload})
  else emit('create',payload)
  reset()
}
watch(()=>props.visible,v=>{if(!v)reset()})
</script>
<style scoped>
.kb-create{display:grid;
grid-template-columns:1fr 1.4fr auto;
gap:8px;
margin-bottom:14px}
.kb-vis{width:170px}
.kb-hint{margin:-6px 0 10px;
font-size:12px;
color:var(--ink-3)}
.kb-list{border:1px solid var(--line-d);
border-radius:10px;
overflow:hidden}
.kb-row{width:100%;
border:0;
border-bottom:1px solid var(--line-d);
background:var(--bg-raised);
padding:11px 12px;
display:flex;
align-items:center;
gap:10px;
text-align:left;
cursor:pointer}
.kb-row:last-child{border-bottom:0}
.kb-row:hover,.kb-row.active{background:var(--bg-sub)}
.kb-icon{font-size:18px}
.kb-copy{display:flex;
flex-direction:column;
min-width:0;
flex:1}
.kb-copy b{font-size:13px;
color:var(--ink-1)}
.kb-copy small,.kb-code{font-size:11px;
color:var(--ink-3)}
.kb-meta{display:flex;
align-items:center;
gap:8px}
.kb-owner{font-size:11px;
color:var(--ink-3);
font-style:normal}
.kb-code{margin-left:auto}
.kb-edit{margin-left:8px;
padding:0}
@media(max-width:650px){.kb-create{grid-template-columns:1fr}
}

</style>
