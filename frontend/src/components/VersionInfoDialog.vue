<template>
  <el-dialog
    :model-value="visible" title="版本信息" width="440px" append-to-body
    @update:model-value="(v:boolean)=>$emit('update:visible',v)">
    <div class="space-y-3">
      <div class="rounded-lg bg-[color:var(--bg-sub)] px-3 py-2.5">
        <div class="flex items-center justify-between gap-2">
          <b class="text-sm text-ink-900">智能知识平台</b>
          <span class="kb-badge kb-state-info">{{ productVersion }}</span>
        </div>
        <p class="kb-muted mt-1">Knowledge Lifecycle OS · 产品版本（信息架构基线）</p>
      </div>

      <div class="space-y-1.5 text-[12px]">
        <div class="flex items-center justify-between gap-3">
          <span class="text-ink-500">服务版本</span>
          <b class="text-ink-800">{{ loading ? '读取中…' : (serviceVersion || '—') }}</b>
        </div>
        <div class="flex items-center justify-between gap-3">
          <span class="text-ink-500">运行环境</span>
          <b class="text-ink-800">{{ loading ? '读取中…' : (environment || '—') }}</b>
        </div>
        <div class="flex items-center justify-between gap-3">
          <span class="text-ink-500">核对时间</span>
          <b class="text-ink-800">{{ checkedAt || '—' }}</b>
        </div>
      </div>

      <p class="kb-muted">
        升级由部署方通过镜像版本完成（拉取新镜像后重启服务），这里只做版本核对，
        不会自动下载或安装更新。
      </p>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <button class="kb-btn-outline" :disabled="loading" @click="check">
          {{ loading ? '检查中…' : '检查更新' }}
        </button>
        <button class="kb-btn-primary" @click="$emit('update:visible',false)">关闭</button>
      </div>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getEnvironment, unwrap } from '../api/knowledge'

const props = defineProps<{ visible:boolean; productVersion:string }>()
defineEmits<{ 'update:visible':[value:boolean] }>()

const loading = ref(false)
const serviceVersion = ref('')
const environment = ref('')
const checkedAt = ref('')

/** 打开弹窗即核对一次：版本属于「看的时候要对」的信息，不缓存上次结果。 */
watch(() => props.visible, v => { if (v) void check() })

async function check() {
  loading.value = true
  try {
    const raw = unwrap(await getEnvironment(), null as Record<string, unknown>|null) || {}
    serviceVersion.value = String(raw.version || '')
    environment.value = String(raw.environment || raw.profile || '')
    checkedAt.value = new Date().toLocaleString()
    ElMessage.success('版本信息已刷新')
  } catch {
    // 环境接口不可用时保留上一轮结果，弹窗里已有「—」兜底，不再弹错打断查看。
    checkedAt.value = new Date().toLocaleString()
  } finally {
    loading.value = false
  }
}
</script>
