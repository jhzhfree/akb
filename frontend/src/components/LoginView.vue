<template>
<!-- 登录页是全站唯一的无框架页：左右分栏（左品牌 / 右表单）对齐原型 login.html。
     mode=admin 为管理员控制台入口，mode=user 为普通用户登录；两页共用这一套结构与样式，
     差异只在标题、副标题、第三方登录区与底部互相切换的入口。 -->
<div class="lv-page">
  <section class="lv-brand" aria-label="产品介绍">
    <div class="lv-brand-top">
      <KosBrandMark :size="40" tone="onBrand" label="智能知识平台"/>
      <span class="lv-brand-copy">
        <b>智能知识平台</b>
        <small>Knowledge OS</small>
      </span>
    </div>
    <div class="lv-brand-body">
      <h1>让团队的知识，随时随地被 AI 调用</h1>
      <p>统一沉淀产品、研发、运营与客服知识，构建可检索、可共享、可被智能问答触达的团队知识中枢。</p>
      <ul class="lv-points">
        <li><span class="lv-check">✓</span>集中管理文档与知识库，告别信息孤岛</li>
        <li><span class="lv-check">✓</span>全文检索 + AI 问答，知识秒级触达</li>
        <li><span class="lv-check">✓</span>精细的角色权限控制，安全可控</li>
      </ul>
    </div>
    <div class="lv-brand-foot">© 2026 智能知识平台 · Knowledge OS</div>
  </section>

  <section class="lv-panel" aria-label="登录">
    <div class="lv-card">
      <div class="lv-head">
        <span v-if="mode==='admin'" class="lv-badge">⚿ 管理员控制台</span>
        <h2>{{mode==='admin'?'管理员登录':'欢迎回来'}}</h2>
        <p>{{mode==='admin'?'使用管理员账号登录平台控制台':'登录以访问你的知识库'}}</p>
      </div>

      <div v-if="error" class="lv-error" role="alert">
        <span aria-hidden="true">⚠️</span><span>{{error}}</span>
      </div>

      <form class="lv-form" @submit.prevent="submit">
        <label class="lv-field">
          <span class="lv-label">{{mode==='admin'?'管理员账号':'用户名 / 邮箱'}}</span>
          <input v-model="username" class="lv-input" type="text" autocomplete="username"
            :placeholder="mode==='admin'?'admin':'请输入用户名或邮箱'" @keyup.enter="submit"/>
        </label>
        <label class="lv-field">
          <span class="lv-label">密码</span>
          <input v-model="password" class="lv-input" type="password" autocomplete="current-password"
            placeholder="请输入密码" @keyup.enter="submit"/>
        </label>
        <div class="lv-row">
          <label class="lv-remember"><input v-model="remember" type="checkbox"/>记住账号</label>
          <button type="button" class="lv-link" @click="forgot">忘记密码？</button>
        </div>
        <button type="submit" class="lv-submit" :disabled="!canSubmit||loading">
          {{loading?'登录中…':'登 录'}}
        </button>
      </form>

      <template v-if="mode==='user'">
        <div class="lv-divider"><span>或使用以下方式</span></div>
        <div class="lv-third">
          <button type="button" class="lv-third-btn" @click="notReady('企业微信登录')">企业微信登录</button>
          <button type="button" class="lv-third-btn" @click="notReady('SSO 单点登录')">SSO 单点登录</button>
        </div>
      </template>
      <p v-else class="lv-note">管理员通道仅使用账号密码登录，第三方登录不对管理员开放。</p>

      <div class="lv-foot">
        <template v-if="mode==='admin'">
          不是管理员？<button type="button" class="lv-link" @click="switchMode('user')">使用普通用户登录 →</button>
        </template>
        <template v-else>
          需要管理控制台？<button type="button" class="lv-link" @click="switchMode('admin')">管理员入口 →</button>
        </template>
      </div>
      <p v-if="hint" class="lv-hint">{{hint}}</p>
    </div>
  </section>
</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import KosBrandMark from './KosBrandMark.vue'
import { login, setToken } from '../api/auth'
import type { AuthSessionResult } from '../types'

/** admin：管理员控制台登录页；user：普通用户登录页。 */
export type LoginMode = 'admin' | 'user'

defineProps<{ hint?: string; mode?: LoginMode }>()
const emit = defineEmits<{
  success: [session: AuthSessionResult]
  'switch-mode': [mode: LoginMode]
}>()

const REMEMBER_KEY = 'kos.loginAccount'

const username = ref('')
const password = ref('')
const remember = ref(true)
const loading = ref(false)
const error = ref('')
const canSubmit = computed(() => username.value.trim().length > 0 && password.value.length > 0)

onMounted(() => {
  const saved = localStorage.getItem(REMEMBER_KEY)
  if (saved) username.value = saved
})

function switchMode(mode: LoginMode) {
  error.value = ''
  emit('switch-mode', mode)
}
function forgot() {
  ElMessage.info('请联系平台管理员重置密码')
}
function notReady(name: string) {
  ElMessage.info(`${name} 暂未接入，请先用账号密码登录`)
}

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
    if (remember.value) localStorage.setItem(REMEMBER_KEY, username.value.trim())
    else localStorage.removeItem(REMEMBER_KEY)
    emit('success', session)
  } catch (e) {
    error.value = (e as { response?: { data?: { message?: string } }; message?: string })
      .response?.data?.message || (e as { message?: string }).message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 登录页不跟随主题骨架：品牌区沿用原型蓝紫渐变，表单区用主题表面色，
   这样浅色下是原型观感，深色下也不会刺眼。 */
.lv-page{
  display:grid;grid-template-columns:1.05fr .95fr;min-height:100dvh;background:var(--bg-base);
}
.lv-brand{
  position:relative;overflow:hidden;
  display:flex;flex-direction:column;padding:46px 52px;color:#fff;
  background:linear-gradient(160deg,#5b5cff 0%,#6f5cff 46%,#8a5cff 100%);
}
/* 品牌区柔光：两团光斑压在大面积渐变上，避免整块色面发平 */
.lv-brand::before,.lv-brand::after{content:'';position:absolute;border-radius:50%;pointer-events:none}
.lv-brand::before{
  width:440px;height:440px;top:-160px;right:-140px;
  background:radial-gradient(circle,rgba(255,255,255,.18),rgba(255,255,255,0) 70%);
}
.lv-brand::after{
  width:360px;height:360px;bottom:-170px;left:-120px;
  background:radial-gradient(circle,rgba(24,184,200,.3),rgba(24,184,200,0) 70%);
}
.lv-brand-top{position:relative;z-index:1;display:flex;align-items:center;gap:12px}
.lv-brand-copy{display:flex;flex-direction:column;line-height:1.15}
.lv-brand-copy b{font-size:16px;font-weight:700;letter-spacing:.02em}
.lv-brand-copy small{
  margin-top:3px;font-size:10px;font-weight:600;letter-spacing:.18em;
  text-transform:uppercase;color:rgba(255,255,255,.76);
}
.lv-brand-body{position:relative;z-index:1;margin-top:auto;margin-bottom:auto;max-width:460px}
.lv-brand-body h1{margin:0;font-size:30px;line-height:1.35;font-weight:700;letter-spacing:-.01em}
.lv-brand-body p{margin:14px 0 0;font-size:13.5px;line-height:1.8;color:rgba(255,255,255,.86)}
.lv-points{margin:22px 0 0;padding:0;list-style:none;display:flex;flex-direction:column;gap:11px}
.lv-points li{display:flex;align-items:center;gap:9px;font-size:13px;color:rgba(255,255,255,.94)}
.lv-check{
  width:18px;height:18px;border-radius:50%;display:grid;place-items:center;flex:none;
  background:rgba(255,255,255,.22);font-size:11px;
}
.lv-brand-foot{position:relative;z-index:1;font-size:11.5px;color:rgba(255,255,255,.72)}

.lv-panel{display:grid;place-items:center;padding:40px 32px}
.lv-card{
  width:400px;max-width:100%;padding:30px 30px 24px;border-radius:14px;
  background:var(--bg-raised);border:1px solid var(--line-d);box-shadow:var(--sd-2);
}
.lv-head{margin-bottom:20px}
.lv-badge{
  display:inline-flex;align-items:center;gap:5px;margin-bottom:12px;padding:3px 10px;border-radius:999px;
  font-size:11px;font-weight:600;color:var(--accent-text);
  background:color-mix(in srgb,var(--brand-500) 12%,transparent);
  border:1px solid color-mix(in srgb,var(--brand-500) 34%,transparent);
}
.lv-head h2{margin:0;font-size:22px;font-weight:700;color:var(--ink-900);letter-spacing:-.01em}
.lv-head p{margin:7px 0 0;font-size:12.5px;color:var(--ink-500)}
.lv-error{
  display:flex;align-items:flex-start;gap:8px;margin-bottom:16px;padding:9px 12px;border-radius:10px;
  font-size:12px;line-height:1.6;color:var(--danger);background:var(--danger-bg);
  border:1px solid color-mix(in srgb,var(--danger) 40%,transparent);
}
.lv-form{display:flex;flex-direction:column;gap:14px}
.lv-field{display:flex;flex-direction:column;gap:6px}
.lv-label{font-size:12px;font-weight:600;color:var(--ink-700)}
.lv-input{
  height:40px;padding:0 12px;border-radius:8px;font-size:13px;font-family:inherit;
  color:var(--ink-900);background:var(--bg-base);border:1px solid var(--line);
  outline:none;transition:border-color .16s var(--ease),box-shadow .16s var(--ease);
}
.lv-input::placeholder{color:var(--ink-400)}
.lv-input:focus{border-color:var(--brand-500);box-shadow:var(--ring)}
.lv-row{display:flex;align-items:center;justify-content:space-between;font-size:12px}
.lv-remember{display:flex;align-items:center;gap:6px;color:var(--ink-600);cursor:pointer}
.lv-link{background:none;border:0;padding:0;font:inherit;font-size:12px;font-weight:600;color:var(--accent-text);cursor:pointer}
.lv-submit{
  height:42px;margin-top:4px;border:0;border-radius:8px;cursor:pointer;
  font-family:inherit;font-size:14px;font-weight:600;letter-spacing:.14em;color:#fff;
  background:linear-gradient(180deg,var(--brand-500),var(--brand-600));
  box-shadow:0 2px 8px color-mix(in srgb,var(--brand-600) 30%,transparent);
  transition:opacity .16s var(--ease),transform .16s var(--ease);
}
.lv-submit:hover{opacity:.94}
.lv-submit:active{transform:translateY(1px)}
.lv-submit:disabled{opacity:.55;cursor:not-allowed}
.lv-divider{display:flex;align-items:center;gap:12px;margin:20px 0 14px;font-size:11.5px;color:var(--ink-400)}
.lv-divider::before,.lv-divider::after{content:'';flex:1;height:1px;background:var(--line-d)}
.lv-third{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.lv-third-btn{
  height:36px;border-radius:8px;cursor:pointer;font-family:inherit;font-size:12.5px;
  background:var(--bg-base);border:1px solid var(--line);color:var(--ink-700);
  transition:background .16s var(--ease),border-color .16s var(--ease);
}
.lv-third-btn:hover{background:var(--bg-sub);border-color:var(--line-soft)}
.lv-note{margin:16px 0 0;font-size:11.5px;line-height:1.7;color:var(--ink-400)}
.lv-foot{
  margin-top:20px;padding-top:16px;border-top:1px solid var(--line-d);
  font-size:12px;color:var(--ink-500);text-align:center;
}
.lv-hint{margin:12px 0 0;font-size:11px;line-height:1.7;color:var(--ink-400);text-align:center}

@media(max-width:900px){
  .lv-page{grid-template-columns:1fr}
  .lv-brand{padding:28px 24px}
  .lv-brand-body{margin:20px 0}
  .lv-brand-body h1{font-size:24px}
}
</style>
