<template>
<!-- 登录页是全站唯一的无框架页：左右分栏（左品牌 / 右表单）对齐原型 login.html。
     平台已不再区分用户视图与管理视图——登录就是登录，进入后能看到什么由角色权限决定，
     不再有「管理员登录」这个独立入口。 -->
<div class="lv-page">
  <section class="lv-brand" aria-label="产品介绍">
    <!-- 品牌底图：深蓝渐变 + 细网格 + 知识网络装饰，配色取自 logo（深蓝底 / 亮蓝点缀）。
         用 CSS + SVG 分层而不是位图：任意屏宽都不糊，也不用额外下载背景图。 -->
    <div class="lv-brand-art" aria-hidden="true">
      <svg class="lv-brand-net" viewBox="0 0 640 900" preserveAspectRatio="xMidYMid slice">
        <g class="lv-net-lines">
          <path d="M-20 620 L120 560 L250 640 L360 540 L500 596 L660 520" />
          <path d="M120 560 L190 430 L320 392 L430 470 L500 596" />
          <path d="M250 640 L300 780 L450 828 L600 760" />
          <path d="M190 430 L120 300 L240 236 L360 300 L430 470" />
          <path d="M360 300 L470 200 L600 244" />
          <path d="M240 236 L200 120 L320 78" />
        </g>
        <g class="lv-net-nodes">
          <circle cx="120" cy="560" r="5" /><circle cx="250" cy="640" r="4" />
          <circle cx="360" cy="540" r="5" /><circle cx="500" cy="596" r="4" />
          <circle cx="190" cy="430" r="4" /><circle cx="320" cy="392" r="6" />
          <circle cx="430" cy="470" r="4" /><circle cx="120" cy="300" r="4" />
          <circle cx="240" cy="236" r="5" /><circle cx="360" cy="300" r="4" />
          <circle cx="470" cy="200" r="4" /><circle cx="600" cy="244" r="3" />
          <circle cx="300" cy="780" r="4" /><circle cx="450" cy="828" r="3" />
          <circle cx="200" cy="120" r="3" /><circle cx="320" cy="78" r="4" />
        </g>
      </svg>
    </div>

    <div class="lv-brand-top">
      <KosBrandLogo :size="38" tone="onDark"/>
    </div>
    <div class="lv-brand-body">
      <h1>让知识随时被 AI 调用</h1>
      <p>统一沉淀产品、研发、运营与客服知识，构建可检索、可共享、可追溯的团队知识中枢；文档、表格与工单一处接入，供团队与 AI 随时调用。</p>
      <ul class="lv-points">
        <li><span class="lv-check">✓</span>集中管理文档与知识库，告别信息孤岛</li>
        <li><span class="lv-check">✓</span>全文检索 + AI 问答，知识秒级触达</li>
        <li><span class="lv-check">✓</span>精细的角色权限控制，安全可控</li>
      </ul>
    </div>
    <div class="lv-brand-foot">© 2026 智能知识库平台 · AI KNOWLEDGE BASE</div>
  </section>

  <section class="lv-panel" aria-label="登录">
    <div class="lv-card">
      <div class="lv-head">
        <h2>欢迎回来</h2>
        <p>登录以访问你的知识库</p>
      </div>

      <div v-if="error" class="lv-error" role="alert">
        <span aria-hidden="true">⚠️</span><span>{{error}}</span>
      </div>

      <form class="lv-form" @submit.prevent="submit">
        <label class="lv-field">
          <span class="lv-label">用户名 / 邮箱</span>
          <input v-model="username" class="lv-input" type="text" autocomplete="username"
            placeholder="请输入用户名或邮箱" @keyup.enter="submit"/>
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

      <p v-if="hint" class="lv-hint">{{hint}}</p>
    </div>
  </section>
</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import KosBrandLogo from './KosBrandLogo.vue'
import { login, setToken } from '../api/auth'
import type { AuthSessionResult } from '../types'

defineProps<{ hint?: string }>()
const emit = defineEmits<{ success: [session: AuthSessionResult] }>()

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

function forgot() {
  ElMessage.info('请联系平台管理员重置密码')
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
/* 登录页不跟随主题骨架：品牌区改用 logo 的深蓝主色（#071227 → #12306b），
   表单区仍用主题表面色——浅色主题下呈现品牌观感，深色主题下也不会刺眼。 */
.lv-page{
  display:grid;grid-template-columns:1.05fr .95fr;min-height:100dvh;background:var(--bg-base);
}
.lv-brand{
  position:relative;overflow:hidden;
  display:flex;flex-direction:column;padding:46px 52px;color:#fff;
  background:
    radial-gradient(120% 78% at 88% 6%,rgba(47,107,255,.5),rgba(47,107,255,0) 62%),
    radial-gradient(96% 62% at 4% 100%,rgba(24,184,200,.24),rgba(24,184,200,0) 64%),
    linear-gradient(158deg,#071227 0%,#0b1d3e 44%,#12306b 100%);
}
/* 细网格：给整块深色底一点结构感；用径向遮罩四周渐隐，避免出现硬边 */
.lv-brand::before{
  content:'';position:absolute;inset:0;pointer-events:none;opacity:.55;
  background-image:
    linear-gradient(rgba(255,255,255,.05) 1px,transparent 1px),
    linear-gradient(90deg,rgba(255,255,255,.05) 1px,transparent 1px);
  background-size:46px 46px,46px 46px;
  mask-image:radial-gradient(120% 92% at 50% 38%,#000 28%,transparent 84%);
  -webkit-mask-image:radial-gradient(120% 92% at 50% 38%,#000 28%,transparent 84%);
}
/* 左上柔光：把 logo 与标题区“点亮”，与右下角的知识网络形成对角呼应 */
.lv-brand::after{
  content:'';position:absolute;width:460px;height:460px;top:-190px;left:-150px;border-radius:50%;pointer-events:none;
  background:radial-gradient(circle,rgba(255,255,255,.13),rgba(255,255,255,0) 70%);
}
/* 知识网络装饰：铺满品牌区、沉在文案之下（节点 + 连线表达“知识被 AI 调用”） */
.lv-brand-art{position:absolute;inset:0;pointer-events:none;z-index:0}
.lv-brand-net{display:block;width:100%;height:100%}
.lv-net-lines path{fill:none;stroke:rgba(122,168,255,.34);stroke-width:1.1}
.lv-net-nodes circle{fill:rgba(147,197,253,.7)}
.lv-net-nodes circle:nth-child(6){fill:#60a5fa}
.lv-net-nodes circle:nth-child(9){fill:#60a5fa}
.lv-brand-top{position:relative;z-index:1;display:flex;align-items:center;gap:12px}
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
.lv-hint{margin:12px 0 0;font-size:11px;line-height:1.7;color:var(--ink-400);text-align:center}

@media(max-width:900px){
  .lv-page{grid-template-columns:1fr}
  .lv-brand{padding:28px 24px}
  .lv-brand-body{margin:20px 0}
  .lv-brand-body h1{font-size:24px}
}
</style>
