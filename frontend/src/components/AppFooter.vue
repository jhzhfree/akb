<template>
  <!-- 版本与授权信息统一收在主内容底部：侧栏底部只留用户与系统配置入口，
       版本不再占用侧栏那一行的位置，也不需要在多个页面各自重复展示。 -->
  <footer class="kos-footer">
    <div class="kos-footer-left">
      <span class="kos-footer-brand">智能知识平台</span>
      <span class="kos-footer-ver">{{productVersion}}</span>
      <span v-if="environment" class="kos-footer-env">{{environment}}</span>
    </div>
    <div class="kos-footer-right">
      <span class="kos-footer-note">企业自部署 · 版本与授权由部署镜像管理</span>
      <button class="kos-footer-link" :title="`版本信息 · ${productVersion}`" @click="visible=true">
        版本信息
      </button>
    </div>
    <VersionInfoDialog v-model:visible="visible" :product-version="productVersion"/>
  </footer>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import VersionInfoDialog from './VersionInfoDialog.vue'

defineProps<{
  /** 产品版本（信息架构基线），服务版本在弹窗里实时核对 */
  productVersion:string
  /** 运行环境与后端服务版本，来自 /api/knowledge/env；未取到时整段省略 */
  environment?:string
}>()

const visible = ref(false)
</script>
