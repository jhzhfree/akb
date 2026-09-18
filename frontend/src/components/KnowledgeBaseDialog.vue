<template>
<el-dialog :model-value="visible" title="知识库管理" width="620px" @close="$emit('close')">
  <div class="kb-create">
    <el-input v-model="name" placeholder="知识库名称"/>
    <el-input v-model="description" placeholder="用途 / 描述"/>
    <el-button type="primary" :disabled="!name.trim()" @click="create">新建知识库</el-button>
  </div>
  <div class="kb-list">
    <button v-for="base in bases" :key="base.id" class="kb-row" :class="{active:base.id===activeId}" @click="$emit('select',base.id)">
      <span class="kb-icon">📚</span>
<span class="kb-copy">
<b>{{base.name}}</b>
<small>{{base.description||base.baseCode}}</small>
</span>
      <el-tag v-if="base.id===activeId" size="small">当前</el-tag>
      <span class="kb-code">{{base.baseCode}}</span>
    </button>
  </div>
</el-dialog>
</template>
<script setup lang="ts">
import{ref}from'vue'
import type{KnowledgeBase}from'../types'
defineProps<{visible:boolean;bases:KnowledgeBase[];activeId:number}>()
const emit=defineEmits<{close:[];select:[id:number];create:[name:string,description:string]}>()
const name=ref('');const description=ref('')
function create(){if(!name.value.trim())return;emit('create',name.value.trim(),description.value.trim());name.value='';description.value=''}
</script>
<style scoped>
.kb-create{display:grid;
grid-template-columns:1fr 1.4fr auto;
gap:8px;
margin-bottom:14px}
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
.kb-code{margin-left:auto}
@media(max-width:650px){.kb-create{grid-template-columns:1fr}
}

</style>
