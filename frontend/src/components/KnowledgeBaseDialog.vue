<template>
<el-dialog :model-value="visible" title="知识库管理" width="680px" @close="$emit('close')">
  <div class="kb-create">
    <el-input v-model="name" placeholder="知识库名称"/>
    <el-input v-model="description" placeholder="用途 / 描述"/>
    <el-select v-model="visibility" class="kb-vis" placeholder="可见范围">
      <el-option v-for="item in VISIBILITIES" :key="item" :value="item" :label="visibilityLabel(item)"/>
    </el-select>
    <el-select v-model="ownerGroupId" class="kb-vis" :disabled="visibility!=='GROUP'" clearable
      placeholder="用户组（仅用户组可见时需要）">
      <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
    </el-select>
    <el-button type="primary" :disabled="!canCreate" @click="create">新建知识库</el-button>
  </div>
  <div class="kb-list">
    <button v-for="base in bases" :key="base.id" class="kb-row" :class="{active:base.id===activeId}" @click="$emit('select',base.id)">
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
    </button>
  </div>
</el-dialog>
</template>
<script setup lang="ts">
import{computed,ref}from'vue'
import type{KnowledgeBase,UserGroupRow}from'../types'
import{visibilityLabel}from'../types'
const VISIBILITIES=['PUBLIC','PRIVATE','GROUP'] as const
defineProps<{visible:boolean;bases:KnowledgeBase[];activeId:number;groups:UserGroupRow[]}>()
const emit=defineEmits<{
  close:[]
  select:[id:number]
  create:[payload:{name:string;description:string;visibility:string;ownerGroupId:number|null}]
}>()
const name=ref('');const description=ref('')
const visibility=ref<string>('PUBLIC');const ownerGroupId=ref<number|null>(null)
/** 用户组可见必须指定组，否则会把知识库变成无人可访问的孤岛。 */
const canCreate=computed(()=>name.value.trim().length>0&&(visibility.value!=='GROUP'||ownerGroupId.value!==null))
function create(){
  if(!canCreate.value)return
  emit('create',{
    name:name.value.trim(),
    description:description.value.trim(),
    visibility:visibility.value,
    ownerGroupId:visibility.value==='GROUP'?ownerGroupId.value:null,
  })
  name.value='';description.value='';visibility.value='PUBLIC';ownerGroupId.value=null
}
</script>
<style scoped>
.kb-create{display:grid;
grid-template-columns:1fr 1.4fr auto;
gap:8px;
margin-bottom:14px}
.kb-vis{width:170px}
.kb-list{border:1px solid #e5e7eb;
border-radius:10px;
overflow:hidden}
.kb-row{width:100%;
border:0;
border-bottom:1px solid #f1f5f9;
background:#fff;
padding:11px 12px;
display:flex;
align-items:center;
gap:10px;
text-align:left;
cursor:pointer}
.kb-row:last-child{border-bottom:0}
.kb-row:hover,.kb-row.active{background:#f5f7ff}
.kb-icon{font-size:18px}
.kb-copy{display:flex;
flex-direction:column;
min-width:0;
flex:1}
.kb-copy b{font-size:13px;
color:#1e293b}
.kb-copy small,.kb-code{font-size:11px;
color:#94a3b8}
.kb-meta{display:flex;
align-items:center;
gap:8px}
.kb-owner{font-size:11px;
color:#64748b;
font-style:normal}
.kb-code{margin-left:auto}
@media(max-width:650px){.kb-create{grid-template-columns:1fr}
}

</style>
