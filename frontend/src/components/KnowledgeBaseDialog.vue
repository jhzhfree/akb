<template>
<el-dialog :model-value="visible" :title="editing?'编辑知识库':'知识库管理'" width="min(1040px,94vw)" top="4vh"
  class="kb-dialog" @close="$emit('close')">
  <div class="kb-body">
    <!-- 基础信息：与场景配置同屏，建库一次填完，不必先建再找设置页。 -->
    <section class="kb-block">
      <h4 class="kb-title">基础信息</h4>
      <div class="kb-grid kb-grid-4">
        <label class="kb-field kb-span-2">
          <span>名称</span>
          <el-input v-model="name" :placeholder="editing?'新名称':'如：运维知识库'"/>
        </label>
        <label class="kb-field kb-span-2">
          <span>用途 / 描述</span>
          <el-input v-model="description" :placeholder="editing?'新用途 / 描述':'如：故障处置与巡检规程'"/>
        </label>
        <label class="kb-field">
          <span>可见范围</span>
          <el-select v-model="visibility" placeholder="可见范围">
            <el-option v-for="item in VISIBILITIES" :key="item" :value="item" :label="visibilityLabel(item)"/>
          </el-select>
        </label>
        <label class="kb-field">
          <span>用户组（仅用户组可见时需要）</span>
          <el-select v-model="ownerGroupId" :disabled="visibility!=='GROUP'" clearable placeholder="用户组">
            <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
          </el-select>
        </label>
      </div>
    </section>

    <!-- 场景模板：给出推荐默认值，选完下面三段自动带出，不必从零调参。 -->
    <section class="kb-block">
      <h4 class="kb-title">场景模板<em>决定生成、衍生与查询的默认策略</em></h4>
      <div class="kb-templates">
        <button v-for="item in templates" :key="item.code" type="button" class="kb-tpl"
          :class="{active:scenario===item.code}" @click="pickTemplate(item.code)">
          <b>{{item.label}}</b>
          <small>{{item.summary}}</small>
          <span class="kb-tpl-docs">{{(item.docTypes||[]).join(' / ')}}</span>
        </button>
      </div>
    </section>

    <section class="kb-block">
      <h4 class="kb-title">生成配置<em>文档如何切分与抽取</em></h4>
      <div class="kb-grid kb-grid-4">
        <label class="kb-field">
          <span>切片策略</span>
          <el-select v-model="profile.generation.chunk.strategy">
            <el-option v-for="s in chunkStrategies" :key="s.name" :value="s.name" :label="s.label"/>
          </el-select>
        </label>
        <label class="kb-field">
          <span>窗口大小</span>
          <el-input-number v-model="profile.generation.chunk.chunkSize" :min="range('chunkSize').min"
            :max="range('chunkSize').max" :step="50"/>
        </label>
        <label class="kb-field">
          <span>重叠长度</span>
          <el-input-number v-model="profile.generation.chunk.chunkOverlap" :min="range('chunkOverlap').min"
            :max="range('chunkOverlap').max" :step="10"/>
        </label>
        <label class="kb-field">
          <span>最小片段</span>
          <el-input-number v-model="profile.generation.chunk.minChunkSize" :min="range('minChunkSize').min"
            :max="range('minChunkSize').max" :step="10"/>
        </label>
      </div>
      <p class="kb-note">{{strategyHint}}</p>
      <div class="kb-switches">
        <el-checkbox v-model="profile.generation.chunk.keepStructure">保留章节结构</el-checkbox>
        <el-checkbox v-model="profile.generation.chunk.respectSentence">切分落在句末</el-checkbox>
        <el-checkbox v-model="profile.generation.chunk.parentChild">父子分块</el-checkbox>
        <el-checkbox v-model="profile.generation.extract.claims">抽取断言</el-checkbox>
        <el-checkbox v-model="profile.generation.extract.entityResolution">实体消歧</el-checkbox>
        <el-checkbox v-model="profile.generation.extract.conflictDetection">冲突检测</el-checkbox>
      </div>
    </section>

    <section class="kb-block">
      <h4 class="kb-title">衍生配置<em>从已有内容推导出什么</em></h4>
      <div class="kb-switches">
        <el-checkbox v-model="profile.derivation.graphEnabled">构建并使用知识图谱</el-checkbox>
      </div>
      <p class="kb-note">关闭后不再抽取实体关系，检索也不走图谱通道，适合以条款、流程为主的知识库。</p>
      <div class="kb-presets">
        <el-checkbox v-for="p in derivationPresets" :key="p.code" :model-value="hasPreset(p.code)"
          :disabled="!profile.derivation.graphEnabled && p.code!=='CLAIM_SENTENCE' && p.code!=='AI_CONCLUSION'"
          @change="(v:unknown)=>togglePreset(p.code,!!v)">{{p.label}}</el-checkbox>
      </div>
    </section>

    <section class="kb-block">
      <h4 class="kb-title">查询配置<em>默认检索策略与答案形态</em></h4>
      <div class="kb-field kb-block-inline">
        <span>默认召回通道（按勾选顺序优先）</span>
        <div class="kb-channels">
          <el-checkbox v-for="c in channelVocabulary" :key="c.name" :model-value="hasChannel(c.name)"
            @change="(v:unknown)=>toggleChannel(c.name,!!v)">{{c.label}}</el-checkbox>
        </div>
      </div>
      <div class="kb-weights">
        <div v-for="c in channelVocabulary" :key="c.name" class="kb-weight" :class="{off:!channel(c.name).enabled}">
          <span>{{c.label}}</span>
          <el-slider v-model="channel(c.name).weight" :min="range('weight').min" :max="range('weight').max"
            :step="0.05" :disabled="!channel(c.name).enabled"/>
          <b>{{channel(c.name).weight.toFixed(2)}}</b>
        </div>
      </div>
      <div class="kb-grid kb-grid-4">
        <label class="kb-field">
          <span>召回倍数</span>
          <el-input-number v-model="profile.query.recallMultiplier" :min="range('recallMultiplier').min"
            :max="range('recallMultiplier').max"/>
        </label>
        <label class="kb-field">
          <span>最少候选数</span>
          <el-input-number v-model="profile.query.minRecallCandidates" :min="range('minRecallCandidates').min"
            :max="range('minRecallCandidates').max" :step="2"/>
        </label>
        <label class="kb-field">
          <span>融合 RRF K</span>
          <el-input-number v-model="profile.query.rrfK" :min="range('rrfK').min" :max="range('rrfK').max" :step="10"/>
        </label>
        <label class="kb-field">
          <span>默认答案格式</span>
          <el-select v-model="profile.query.answerFormat">
            <el-option v-for="f in answerFormats" :key="f.name" :value="f.name" :label="f.label"/>
          </el-select>
        </label>
        <label class="kb-field">
          <span>最少原文证据</span>
          <el-input-number v-model="profile.query.minChunkEvidence" :min="range('minChunkEvidence').min"
            :max="range('minChunkEvidence').max"/>
        </label>
        <label class="kb-field">
          <span>上下文上限（字符）</span>
          <el-input-number v-model="profile.query.contextMaxChars" :min="range('contextMaxChars').min"
            :max="range('contextMaxChars').max" :step="500"/>
        </label>
        <label class="kb-field">
          <span>精排条数（0 为全部）</span>
          <el-input-number v-model="profile.query.rerankTopN" :min="range('rerankTopN').min"
            :max="range('rerankTopN').max" :step="5" :disabled="!profile.query.rerankEnabled"/>
        </label>
      </div>
      <div class="kb-switches">
        <el-checkbox v-model="profile.query.rerankEnabled">启用精排</el-checkbox>
        <el-checkbox v-model="profile.query.qualitySignalsEnabled">质量信号加权</el-checkbox>
        <el-checkbox v-model="profile.query.chunkBoostEnabled">原文片段加权</el-checkbox>
      </div>
    </section>

    <div class="kb-actions">
      <el-button v-if="editing" @click="reset">取消</el-button>
      <el-button v-if="editing" @click="restoreDefaults">恢复模板默认值</el-button>
      <el-button v-if="editing" type="primary" :disabled="!ready" @click="submit">保存修改</el-button>
      <el-button v-else type="primary" :disabled="!ready" @click="submit">新建知识库</el-button>
    </div>

    <p v-if="editing" class="kb-hint">正在编辑「{{editingName}}」，保存后立即对该知识库生效（已入库文档需重新接入才会重切）。</p>

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
  </div>
</el-dialog>
</template>
<script setup lang="ts">
import{computed,ref,watch}from'vue'
import type{KnowledgeBase,UserGroupRow}from'../types'
import type{KbScenarioProfile,KbScenarioSchema,KbScenarioTemplate}from'../types/knowledge'
import{visibilityLabel}from'../types'
import{getKbScenario,saveKbScenario,resetKbScenario}from'../api/knowledge/base'
const VISIBILITIES=['PUBLIC','PRIVATE','GROUP'] as const
type BasePayload={
  name:string;description:string;visibility:string;ownerGroupId:number|null
  scenario:string;profile:KbScenarioProfile|null
}
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
const scenario=ref('GENERAL')
const schema=ref<KbScenarioSchema|null>(null)
/** 当前编辑的档案：未加载成功时用一个综合模板兜底，保证表单不会空白。 */
const profile=ref<KbScenarioProfile>(emptyProfile())
const dirty=ref(false)
/** 用户组可见必须指定组，否则会把知识库变成无人可访问的孤岛。 */
const ready=computed(()=>name.value.trim().length>0&&(visibility.value!=='GROUP'||ownerGroupId.value!==null))
const templates=computed<KbScenarioTemplate[]>(()=>schema.value?.scenarios||[])
const channelVocabulary=computed(()=>schema.value?.channels||[])
const answerFormats=computed(()=>schema.value?.answerFormats||[])
const chunkStrategies=computed(()=>schema.value?.chunk?.strategies||[])
const derivationPresets=computed(()=>schema.value?.derivation?.presets||[])
const strategyHint=computed(()=>chunkStrategies.value.find(s=>s.name===profile.value.generation.chunk.strategy)?.description||'')

function emptyProfile():KbScenarioProfile{
  return{
    generation:{chunk:{strategy:'SEMANTIC',chunkSize:900,chunkOverlap:120,minChunkSize:120,measure:'CHAR',
      keepStructure:true,respectSentence:true,parentChild:false},
      extract:{claims:true,entityResolution:true,conflictDetection:true}},
    derivation:{graphEnabled:true,presets:[]},
    query:{defaultStrategies:['card','vector','bm25','claim'],channels:{},recallMultiplier:3,minRecallCandidates:20,
      rerankEnabled:true,rerankTopN:0,rrfK:60,qualitySignalsEnabled:true,chunkBoostEnabled:true,
      answerFormat:'default',minChunkEvidence:3,contextMaxChars:8000},
  }
}
/** 取值边界来自后端 schema：前端不再维护第二份范围定义。 */
function range(key:string){
  const item=schema.value?.ranges?.[key]
  return{min:item?.min??0,max:item?.max??1000}
}
function channel(name:string){
  const channels=profile.value.query.channels as Record<string,{enabled:boolean;weight:number}>
  if(!channels[name])channels[name]={enabled:true,weight:1}
  return channels[name]
}
function hasChannel(name:string){return profile.value.query.defaultStrategies.includes(name)}
/** 勾选顺序即优先级：数组顺序就是融合前的召回顺序。 */
function toggleChannel(name:string,on:boolean){
  const list=profile.value.query.defaultStrategies.filter(v=>v!==name)
  if(on)list.push(name)
  profile.value.query.defaultStrategies=list
  channel(name).enabled=on
  dirty.value=true
}
function hasPreset(code:string){return profile.value.derivation.presets.includes(code)}
function togglePreset(code:string,on:boolean){
  const list=profile.value.derivation.presets.filter(v=>v!==code)
  if(on)list.push(code)
  profile.value.derivation.presets=list
  dirty.value=true
}
/** 选模板即采用它的默认值：这是模板存在的意义，避免用户从零调参。 */
function pickTemplate(code:string){
  scenario.value=code
  const template=templates.value.find(item=>item.code===code)
  if(template)profile.value=cloneProfile(template.default)
  dirty.value=true
}
function restoreTemplate(){
  const template=templates.value.find(item=>item.code===scenario.value)
  if(template)profile.value=cloneProfile(template.default)
  dirty.value=true
}
function cloneProfile(source:KbScenarioProfile):KbScenarioProfile{
  return JSON.parse(JSON.stringify(source)) as KbScenarioProfile
}
async function loadSchema(kb:number|null){
  try{
    const data=(await getKbScenario(kb??0)).data?.data
    if(!data)return
    schema.value=data.schema||null
    scenario.value=data.scenario||'GENERAL'
    profile.value=cloneProfile(data.profile||emptyProfile())
  }catch{
    // 字典取不到也要能建库：用内置兜底值，下拉为空时用户仍可输入名称直接创建。
    schema.value=null
  }
}
/** 编辑入口按归属裁剪：无权者不显示按钮，避免点了才被后端拒绝。 */
function canEdit(base:KnowledgeBase){
  if(props.canManage)return true
  return props.currentUserId!=null&&base.createdBy===props.currentUserId
}
function reset(){
  name.value='';description.value='';visibility.value='PUBLIC';ownerGroupId.value=null
  editingId.value=null;dirty.value=false
}
async function startEdit(base:KnowledgeBase){
  editingId.value=base.id
  name.value=base.name
  description.value=base.description||''
  visibility.value=base.visibility||'PUBLIC'
  ownerGroupId.value=base.ownerGroupId??null
  dirty.value=false
  await loadSchema(base.id)
}
async function submit(){
  if(!ready.value)return
  const payload:BasePayload={
    name:name.value.trim(),
    description:description.value.trim(),
    visibility:visibility.value,
    ownerGroupId:visibility.value==='GROUP'?ownerGroupId.value:null,
    scenario:scenario.value,
    profile:profile.value,
  }
  if(editingId.value!==null){
    const id=editingId.value
    // 场景配置与基础信息一起落库，否则界面上改了检索策略却没生效。
    try{
      await saveKbScenario(id,{scenario:scenario.value,profile:profile.value})
    }catch(e){void e}
    emit('update',{id,...payload})
  }else{
    emit('create',payload)
  }
  reset()
}
async function restoreDefaults(){
  if(editingId.value===null)return
  try{
    const data=(await resetKbScenario(editingId.value)).data?.data as Record<string,unknown>|undefined
    if(data?.profile)profile.value=cloneProfile(data.profile as KbScenarioProfile)
    if(typeof data?.scenario==='string')scenario.value=data.scenario
  }catch{
    restoreTemplate()
  }
}
watch(()=>props.visible,async v=>{
  if(v){await loadSchema(null);dirty.value=false}
  else reset()
})
</script>
<style scoped>
.kb-body{max-height:76vh;
overflow:auto;
padding-right:4px}
.kb-block{border:1px solid var(--line-d);
border-radius:10px;
padding:12px 14px;
margin-bottom:12px;
background:var(--bg-raised)}
.kb-title{margin:0 0 10px;
font-size:13px;
color:var(--ink-1);
display:flex;
align-items:baseline;
gap:8px}
.kb-title em{font-size:11px;
font-style:normal;
color:var(--ink-3)}
.kb-grid{display:grid;
gap:10px}
.kb-grid-4{grid-template-columns:repeat(4,minmax(0,1fr))}
.kb-span-2{grid-column:span 2}
.kb-field{display:flex;
flex-direction:column;
gap:4px;
min-width:0}
.kb-field>span{font-size:11px;
color:var(--ink-3)}
.kb-block-inline{margin-bottom:10px}
.kb-templates{display:grid;
grid-template-columns:repeat(auto-fit,minmax(190px,1fr));
gap:8px}
.kb-tpl{display:flex;
flex-direction:column;
gap:4px;
text-align:left;
padding:10px;
border:1px solid var(--line-d);
border-radius:10px;
background:var(--bg-sub);
cursor:pointer}
.kb-tpl:hover{border-color:var(--brand,#3b82f6)}
.kb-tpl.active{border-color:var(--brand,#3b82f6);
background:var(--bg-raised);
box-shadow:0 0 0 1px var(--brand,#3b82f6) inset}
.kb-tpl b{font-size:12px;
color:var(--ink-1)}
.kb-tpl small{font-size:11px;
color:var(--ink-2);
line-height:1.5}
.kb-tpl-docs{font-size:10px;
color:var(--ink-3)}
.kb-switches{display:flex;
flex-wrap:wrap;
gap:14px;
margin-top:8px}
.kb-presets{display:grid;
grid-template-columns:repeat(auto-fit,minmax(200px,1fr));
gap:6px;
margin-top:8px}
.kb-channels{display:flex;
flex-wrap:wrap;
gap:12px}
.kb-weights{display:grid;
grid-template-columns:repeat(auto-fit,minmax(220px,1fr));
gap:6px 16px;
margin:10px 0}
.kb-weight{display:grid;
grid-template-columns:96px 1fr 44px;
align-items:center;
gap:8px}
.kb-weight span{font-size:11px;
color:var(--ink-2)}
.kb-weight b{font-size:11px;
color:var(--ink-3);
text-align:right}
.kb-weight.off{opacity:.45}
.kb-note{margin:6px 0 0;
font-size:11px;
color:var(--ink-3);
line-height:1.6}
.kb-actions{display:flex;
justify-content:flex-end;
gap:8px;
margin-bottom:10px}
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
@media(max-width:820px){.kb-grid-4{grid-template-columns:repeat(2,minmax(0,1fr))}
.kb-span-2{grid-column:span 2}
}
</style>
