<template>
<div class="lv-mask">
  <div class="lv-card">
    <div class="lv-brand"><span class="lv-logo">◈</span>AI 原生企业知识库平台</div>
    <p class="lv-sub">请先登录，知识库按账号权限开放</p>
    <div class="lv-form">
      <el-input v-model="username" size="large" placeholder="用户名" autocomplete="username" @keyup.enter="submit"/>
      <el-input v-model="password" size="large" type="password" show-password placeholder="密码"
        autocomplete="current-password" @keyup.enter="submit"/>
      <el-button type="primary" size="large" :loading="loading" :disabled="!canSubmit" @click="submit">登录</el-button>
    </div>
    <div v-if="error" class="lv-error">⚠ {{error}}</div>
    <p v-if="hint" class="lv-hint">{{hint}}</p>
  </div>
</div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { login, setToken } from '../api/auth'
import type { AuthSessionResult } from '../types'

defineProps<{ hint?:string }>()
const emit = defineEmits<{ success:[session:AuthSessionResult] }>()

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const canSubmit = computed(() => username.value.trim().length > 0 && password.value.length > 0)

async function submit() {
  if (!canSubmit.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await login(username.value.trim(), password.value)
    if (!response.data?.success) throw new Error(response.data?.message || '登录失败')
    const session = response.data.data
    if (!session?.token) throw new Error('登录未返回会话令牌')
    setToken(session.token)
    emit('success', session)
  } catch (e) {
    error.value = (e as { response?:{ data?:{ message?:string } }; message?:string })
      .response?.data?.message || (e as { message?:string }).message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
<style scoped>
.lv-mask{min-height:100vh;display:flex;align-items:center;justify-content:center;
background:radial-gradient(circle at 20% 20%,#eef2ff,#f8fafc 45%,#eef2ff)}
.lv-card{width:380px;background:#fff;border:1px solid #e5e7eb;border-radius:16px;
padding:28px 26px;box-shadow:0 18px 40px rgba(15,23,42,.08)}
.lv-brand{display:flex;align-items:center;gap:8px;font-size:15px;font-weight:600;color:#0f172a}
.lv-logo{color:#4f46e5}
.lv-sub{margin:6px 0 18px;font-size:12px;color:#64748b}
.lv-form{display:flex;flex-direction:column;gap:10px}
.lv-error{margin-top:12px;font-size:12px;color:#dc2626}
.lv-hint{margin-top:10px;font-size:11px;color:#94a3b8}
</style>
