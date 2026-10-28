<template>
  <el-dialog :model-value="visible" title="个人访问令牌" width="min(760px, calc(100vw - 32px))"
    destroy-on-close @update:model-value="$emit('update:visible',$event)">
    <div class="mb-4 flex items-start justify-between gap-4">
      <p class="max-w-xl text-[13px] leading-6 text-ink-500">
        PAT 代表你的 AKB 用户身份访问检索接口。新令牌默认只读，最长有效期 365 天；完整令牌只在创建时显示一次，到期前用「轮换」换发新的。
      </p>
      <el-button type="primary" @click="openCreate">创建令牌</el-button>
    </div>

    <el-alert v-if="createdSecret" type="warning" :closable="false" show-icon class="mb-4">
      <template #title>请立即复制并妥善保存。关闭此窗口后，系统无法再次显示完整令牌。</template>
      <div class="mt-2 flex gap-2">
        <el-input :model-value="createdSecret" readonly/>
        <el-button @click="copySecret">复制</el-button>
      </div>
    </el-alert>

    <el-table v-loading="loading" :data="tokens" row-key="id" empty-text="还没有个人访问令牌">
      <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip/>
      <el-table-column prop="keyPrefix" label="令牌前缀" min-width="150"/>
      <el-table-column label="权限范围" min-width="180">
        <template #default="{row}">{{scopeLabel(row.scopes)}}</template>
      </el-table-column>
      <el-table-column label="状态" width="95">
        <template #default="{row}"><el-tag :type="row.status==='ACTIVE'?'success':'info'">{{row.status==='ACTIVE'?'有效':'已撤销'}}</el-tag></template>
      </el-table-column>
      <el-table-column label="到期时间" min-width="160">
        <template #default="{row}">{{formatTime(row.expiresAt)}}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{row}">
          <el-button v-if="row.status==='ACTIVE'" link type="primary" @click="openRotate(row)">轮换</el-button>
          <el-button v-if="row.status==='ACTIVE'" link type="danger" @click="revoke(row)">撤销</el-button>
          <span v-else class="text-ink-400">—</span>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="$emit('update:visible',false)">关闭</el-button>
    </template>

    <el-dialog v-model="createVisible" title="创建个人访问令牌" width="min(480px, calc(100vw - 32px))" append-to-body>
      <el-form label-position="top">
        <el-form-item label="令牌名称" required>
          <el-input v-model="form.name" maxlength="128" show-word-limit placeholder="例如：本地开发脚本"/>
        </el-form-item>
        <el-form-item label="到期日期" required>
          <el-date-picker v-model="form.expiresAt" type="date" value-format="YYYY-MM-DD"
            :disabled-date="disableDate" class="w-full" placeholder="选择到期日期"/>
        </el-form-item>
        <el-form-item label="权限范围">
          <el-checkbox-group v-model="form.scopes" class="flex flex-wrap gap-x-4 gap-y-1">
            <el-checkbox v-for="scope in allowedScopes" :key="scope.value" :value="scope.value">{{scope.label}}</el-checkbox>
          </el-checkbox-group>
          <p class="mt-1 text-[12px] text-ink-500">Memory 与写入权限不在自助 PAT 范围内，需单独申请审批。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rotateVisible" title="轮换个人访问令牌" width="min(480px, calc(100vw - 32px))" append-to-body>
      <p class="mb-3 text-[13px] leading-6 text-ink-500">
        轮换会换发一枚新明文并立即撤销旧令牌，<b>你的用户身份与权限范围都不变</b>。
        这是令牌到期前该做的事——到期时间必须重选，不能靠改日期延长生命周期。
      </p>
      <el-form label-position="top">
        <el-form-item :label="`轮换「${rotateTarget?.name || ''}」的新到期日期`" required>
          <el-date-picker v-model="rotateExpiresAt" type="date" value-format="YYYY-MM-DD"
            :disabled-date="disableDate" class="w-full" placeholder="选择新的到期日期"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rotateVisible=false">取消</el-button>
        <el-button type="primary" :loading="rotateSaving" @click="confirmRotate">确认轮换</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createPersonalAccessToken, listPersonalAccessTokens, revokePersonalAccessToken,
  rotatePersonalAccessToken,
  type CreatedPersonalAccessToken, type PersonalAccessToken } from '../api/auth'

const props=defineProps<{visible:boolean}>()
defineEmits<{(e:'update:visible',value:boolean):void}>()
const tokens=ref<PersonalAccessToken[]>([])
const loading=ref(false)
const saving=ref(false)
const createVisible=ref(false)
const createdSecret=ref('')
const allowedScopes=[
  {value:'SEARCH',label:'检索'},
  {value:'QUERY',label:'问答'},
  {value:'LIST',label:'列表'},
  {value:'READ_OBJECT',label:'读取知识对象'},
]
const form=reactive({name:'',expiresAt:'',scopes:allowedScopes.map(item=>item.value)})
const minDate=computed(()=>{
  const date=new Date()
  date.setHours(0,0,0,0)
  date.setDate(date.getDate()+1)
  return date
})
const maxDate=computed(()=>{
  const date=new Date()
  date.setHours(23,59,59,999)
  date.setDate(date.getDate()+364)
  return date
})

watch(()=>props.visible,(value)=>{if(value)void load();else createdSecret.value=''})

async function load(){
  if(!props.visible)return
  loading.value=true
  try{tokens.value=(await listPersonalAccessTokens()).data.data||[]}
  catch{ElMessage.error('个人访问令牌列表加载失败')}
  finally{loading.value=false}
}
const rotateVisible=ref(false)
const rotateTarget=ref<PersonalAccessToken|null>(null)
const rotateExpiresAt=ref('')
const rotateSaving=ref(false)

function openRotate(token:PersonalAccessToken){
  createdSecret.value=''
  rotateTarget.value=token
  // 不预填原到期：轮换要重新选一个日子，顺手沿用会让人以为「改了下日期」。
  rotateExpiresAt.value=''
  rotateVisible.value=true
}
/** 旧令牌在轮换成功的同一刻失效，所以新明文必须立刻给到使用者。 */
async function confirmRotate(){
  const token=rotateTarget.value
  if(!token)return
  if(!rotateExpiresAt.value){ElMessage.warning('请选择新的到期日期');return}
  rotateSaving.value=true
  try{
    const result=(await rotatePersonalAccessToken(token.id,rotateExpiresAt.value)).data.data as CreatedPersonalAccessToken
    createdSecret.value=result.secret
    rotateVisible.value=false
    ElMessage.success('已轮换，旧令牌同时失效')
    await load()
  }catch(error){
    const message=(error as {response?:{data?:{message?:string}}})?.response?.data?.message
    ElMessage.error(message||'个人访问令牌轮换失败')
  }finally{rotateSaving.value=false}
}
function openCreate(){
  createdSecret.value=''
  form.name=''
  form.expiresAt=''
  form.scopes=allowedScopes.map(item=>item.value)
  createVisible.value=true
}
function disableDate(date:Date){return date<minDate.value||date>maxDate.value}
async function create(){
  if(!form.name.trim()){ElMessage.warning('请填写令牌名称');return}
  if(!form.expiresAt){ElMessage.warning('请选择到期日期');return}
  if(!form.scopes.length){ElMessage.warning('至少选择一个权限范围');return}
  saving.value=true
  try{
    const result=(await createPersonalAccessToken({name:form.name.trim(),expiresAt:form.expiresAt,scopes:form.scopes})).data.data as CreatedPersonalAccessToken
    createdSecret.value=result.secret
    createVisible.value=false
    ElMessage.success('个人访问令牌已创建')
    await load()
  }catch(error){
    const message=(error as {response?:{data?:{message?:string}}})?.response?.data?.message
    ElMessage.error(message||'个人访问令牌创建失败')
  }finally{saving.value=false}
}
async function revoke(token:PersonalAccessToken){
  try{
    await ElMessageBox.confirm(`撤销“${token.name}”后，使用该令牌的客户端将立即无法访问。`, '撤销个人访问令牌', {type:'warning',confirmButtonText:'撤销',cancelButtonText:'取消'})
    await revokePersonalAccessToken(token.id)
    ElMessage.success('令牌已撤销')
    await load()
  }catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error('令牌撤销失败')}
}
async function copySecret(){
  try{await navigator.clipboard.writeText(createdSecret.value);ElMessage.success('已复制令牌')}
  catch{ElMessage.warning('无法访问剪贴板，请手动复制令牌')}
}
function scopeLabel(value:string){
  const labels:Record<string,string>={SEARCH:'检索',QUERY:'问答',LIST:'列表',READ_OBJECT:'读取知识对象'}
  return String(value||'').split(',').map(item=>labels[item]||item).join('、')
}
function formatTime(value?:string){return value?value.replace('T',' ').slice(0,16):'—'}
</script>
