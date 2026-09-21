<template>
<div class="lv-mask">
  <div class="lv-card">
    <div class="lv-brand">
      <KosBrandMark :size="42"/>
      <div class="lv-brand-copy">
        <b>智能知识平台</b>
        <small>Knowledge Lifecycle OS</small>
      </div>
    </div>
    <h1 class="lv-title">欢迎回来</h1>
    <p class="lv-sub">请先登录，知识库按账号权限开放</p>
    <div class="lv-form">
      <el-input v-model="username" size="large" placeholder="用户名" autocomplete="username" @keyup.enter="submit"/>
      <el-input v-model="password" size="large" type="password" show-password placeholder="密码"
        autocomplete="current-password" @keyup.enter="submit"/>
      <el-button type="primary" size="large" class="lv-submit" :loading="loading" :disabled="!canSubmit" @click="submit">登录</el-button>
    </div>
    <div v-if="error" class="lv-error">⚠ {{error}}</div>
    <p v-if="hint" class="lv-hint">{{hint}}</p>
    <p class="lv-foot">AI 原生 · 知识生命周期治理</p>
  </div>
</div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { login, setToken } from '../api/auth'
import type { AuthSessionResult } from '../types'
import KosBrandMark from './KosBrandMark.vue'

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
/* 登录页是全站唯一无框架页：背景用品牌色双光斑，突出中间的卡片 */
.lv-mask{
  min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;
  background:
    radial-gradient(1000px 520px at 12% -8%,rgba(91,139,247,.28),transparent 60%),
    radial-gradient(760px 460px at 105% 110%,rgba(20,184,166,.22),transparent 62%),
    linear-gradient(180deg,#111a3c,#172554 45%,#0f172a);
}
.lv-card{
  width:400px;max-width:100%;padding:30px 28px 22px;border-radius:20px;
  background:#fff;box-shadow:0 24px 60px rgba(8,15,40,.32);
  border:1px solid rgba(255,255,255,.6);
}
.lv-brand{display:flex;align-items:center;gap:12px}
.lv-brand-copy{display:flex;flex-direction:column;line-height:1.2}
.lv-brand-copy b{font-size:16px;font-weight:700;color:#0f172a;letter-spacing:.3px}
.lv-brand-copy small{font-size:9.5px;font-weight:600;letter-spacing:.16em;color:#8591a9;text-transform:uppercase}
.lv-title{margin:18px 0 0;font-size:20px;font-weight:700;letter-spacing:-.01em;color:#0f172a}
.lv-sub{margin:6px 0 18px;font-size:12px;color:#5b667a}
.lv-form{display:flex;flex-direction:column;gap:11px}
.lv-submit{width:100%;margin-top:4px;height:40px;font-weight:600;letter-spacing:.06em}
.lv-error{
  margin-top:13px;padding:8px 11px;border-radius:10px;font-size:12px;color:#b91c1c;
  background:#fef2f2;border:1px solid #fbd5d5;
}
.lv-hint{margin-top:12px;font-size:11px;line-height:1.6;color:#8591a9}
.lv-foot{
  margin:20px 0 0;padding-top:14px;border-top:1px solid #eef2f7;
  font-size:10.5px;letter-spacing:.12em;color:#b0b8c9;text-align:center;
}
</style>
