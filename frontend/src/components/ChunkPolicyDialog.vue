<template>
  <!-- 分块策略入口：文档中心拖拽区右上角的按钮打开这里。
       策略按知识库保存，接入时下发给解析器，因此对后续导入的文档与连接器同步的数据一律生效。 -->
  <el-dialog :model-value="visible" title="文本分块策略" width="760px" destroy-on-close @close="emit('close')">
    <p class="policy-lead">
      按知识库配置切分方式：保存后对新接入的资料生效——包括这里上传的文档，以及连接器同步进来的数据。
      已解析完成的文档不会自动重切，需要重新接入才会按新策略切分。
    </p>
    <div v-if="loading" class="py-4 text-center text-sm text-ink-500">正在读取分块策略...</div>
    <div v-else class="space-y-3">
      <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <label class="block">
          <span class="mb-1 block text-[11px] text-ink-500">切分算法</span>
          <select v-model="policy.strategy" class="kb-input w-full">
            <option v-for="item in strategies" :key="item.name" :value="item.name">{{ item.label }}</option>
          </select>
        </label>
        <label class="block">
          <span class="mb-1 block text-[11px] text-ink-500">
            窗口大小（{{ policy.measure==='TOKEN'?'Token':'字符' }}）
          </span>
          <input v-model.number="policy.chunkSize" type="number" class="kb-input w-full"
            :min="ranges.chunkSize?.min ?? 200" :max="ranges.chunkSize?.max ?? 4000" />
        </label>
        <label class="block">
          <span class="mb-1 block text-[11px] text-ink-500">重叠</span>
          <input v-model.number="policy.chunkOverlap" type="number" class="kb-input w-full"
            :min="ranges.chunkOverlap?.min ?? 0" :max="ranges.chunkOverlap?.max ?? 1000" />
        </label>
        <label class="block">
          <span class="mb-1 block text-[11px] text-ink-500">最小块（过小则并入相邻块）</span>
          <input v-model.number="policy.minChunkSize" type="number" class="kb-input w-full"
            :min="ranges.minChunkSize?.min ?? 0" :max="ranges.minChunkSize?.max ?? 1000" />
        </label>
      </div>
      <div class="flex flex-wrap items-center gap-6">
        <label class="flex items-center gap-2 text-[12px] text-ink-600">
          <span>窗口度量</span>
          <select v-model="policy.measure" class="kb-input w-32">
            <option v-for="item in measures" :key="item.name" :value="item.name">{{ item.label }}</option>
          </select>
        </label>
        <el-checkbox v-model="policy.keepStructure" label="保留结构（表格 / 代码独立成块）" />
        <el-checkbox v-model="policy.respectSentence" label="句末断句" />
        <el-checkbox v-model="policy.parentChild" label="父子分块（章节父块 + 窗口子块）" />
      </div>
      <p class="kb-muted">{{ strategyDescription }}</p>
      <div class="flex flex-wrap items-center gap-2">
        <span class="kb-muted">
          {{ state.customized ? '已自定义' : '内置默认' }}
          <template v-if="state.customized">
            · 最近由 {{ state.updatedBy || '—' }} 于 {{ state.updatedTime || '—' }} 修改
          </template>
        </span>
      </div>
    </div>
    <template #footer>
      <div class="flex items-center justify-between gap-2">
        <button class="kb-btn-outline" :disabled="loading||saving" @click="resetPolicy">恢复默认</button>
        <div class="flex items-center gap-2">
          <button class="kb-btn-outline" @click="emit('close')">取消</button>
          <button class="kb-btn" :disabled="loading||saving" @click="savePolicy">保存策略</button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ChunkPolicy, ChunkPolicyState } from '../types'
import { getChunkPolicy, resetChunkPolicy, saveChunkPolicy, unwrap } from '../api/knowledge'
import { errorMessage as message, notifyError, notifySuccess } from '../utils/feedback'

const props = defineProps<{ visible:boolean; knowledgeBaseId:number }>()
const emit = defineEmits<{ close:[]; saved:[policy:ChunkPolicy] }>()

const state = ref<ChunkPolicyState>({ policy: {} })
const policy = ref<ChunkPolicy>({})
const loading = ref(false)
const saving = ref(false)

const strategies = computed(() => state.value.schema?.strategies || [])
const measures = computed(() => state.value.schema?.measures || [])
const ranges = computed(() => state.value.schema?.ranges || {})
const strategyDescription = computed(() =>
  strategies.value.find(item => item.name === policy.value.strategy)?.description || '')

async function load(){
  loading.value = true
  try{
    state.value = unwrap(await getChunkPolicy(props.knowledgeBaseId), { policy: {} })
    policy.value = { ...(state.value.policy || {}) }
  }catch(e){
    notifyError(message(e))
  }finally{
    loading.value = false
  }
}
async function savePolicy(){
  saving.value = true
  try{
    state.value = unwrap(await saveChunkPolicy(props.knowledgeBaseId, policy.value), { policy: {} })
    policy.value = { ...(state.value.policy || {}) }
    notifySuccess('分块策略已保存，对新接入的文档与连接器数据生效')
    emit('saved', { ...policy.value })
  }catch(e){
    notifyError(message(e))
  }finally{
    saving.value = false
  }
}
async function resetPolicy(){
  saving.value = true
  try{
    state.value = unwrap(await resetChunkPolicy(props.knowledgeBaseId), { policy: {} })
    policy.value = { ...(state.value.policy || {}) }
    notifySuccess('已恢复内置默认分块策略')
  }catch(e){
    notifyError(message(e))
  }finally{
    saving.value = false
  }
}
/** 每次打开都重读：策略可能被别的管理员改过，弹窗里不该显示过期值。 */
watch(() => props.visible, v => { if(v) void load() }, { immediate:true })
watch(() => props.knowledgeBaseId, () => { if(props.visible) void load() })
</script>
<style scoped>
.policy-lead{margin:-4px 0 12px;font-size:12px;line-height:1.7;color:var(--ink-3)}
</style>
