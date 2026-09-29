import { computed, ref, watch } from 'vue'

/**
 * 主题：深浅两套 + 跟随系统。
 * 主题只体现在 <html data-theme> 上，配色全部由 theme.css 的变量提供，
 * 组件不感知当前主题，避免把「是深色吗」的判断散落到几十个组件里。
 */
export type ThemeMode = 'dark' | 'light' | 'system'

const STORAGE_KEY = 'kos.theme'
const CYCLE: ThemeMode[] = ['dark', 'light', 'system']

const mediaQuery = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: light)')
  : null

function readStored(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === 'dark' || raw === 'light' || raw === 'system') return raw
  } catch { /* 隐私模式下 localStorage 不可用：回落默认深色 */ }
  return 'dark'
}

const mode = ref<ThemeMode>(readStored())
const systemLight = ref(!!mediaQuery?.matches)
/** 实际生效的主题：跟随系统时按系统偏好解析。 */
const resolved = computed<'dark' | 'light'>(() =>
  mode.value === 'system' ? (systemLight.value ? 'light' : 'dark') : mode.value)

function apply(theme: 'dark' | 'light') {
  const root = document.documentElement
  root.dataset.theme = theme
  root.style.colorScheme = theme
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme === 'dark' ? '#172554' : '#ffffff')
}

watch(resolved, apply, { immediate: true })
mediaQuery?.addEventListener('change', e => { systemLight.value = e.matches })

export function setTheme(next: ThemeMode) {
  mode.value = next
  try { localStorage.setItem(STORAGE_KEY, next) } catch { /* 忽略：内存内仍生效 */ }
}

/** 顶栏按钮用：深色 → 浅色 → 跟随系统 循环，避免为一个开关做下拉菜单。 */
function cycleTheme() {
  setTheme(CYCLE[(CYCLE.indexOf(mode.value) + 1) % CYCLE.length])
}

export function useTheme() {
  const icon = computed(() => mode.value === 'light' ? '☀' : mode.value === 'system' ? '◐' : '☾')
  const label = computed(() => {
    if (mode.value === 'light') return '浅色主题：点击改为跟随系统'
    if (mode.value === 'system') return `跟随系统（当前${resolved.value === 'light' ? '浅色' : '深色'}）：点击改为深色`
    return '深色主题：点击改为浅色'
  })
  return { mode, resolved, icon, label, setTheme, cycleTheme }
}
