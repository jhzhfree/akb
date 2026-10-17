#!/usr/bin/env node
/**
 * 答案排版一致性校验。
 *
 * 「控制台里检索结果」和「嵌入组件里检索结果」必须是同一套样式。
 * 但组件 public/embed/kos-chat.js 不参与构建、无法 import src/answer.css，
 * 只能把规则抄一份进 buildStyles()。抄就会漂移 —— 这个脚本就是防漂移的：
 *
 *   解析 src/answer.css 与 kos-chat.js 里 .md-* / .md-text 的规则，
 *   把组件侧的 --kos-* 变量映射回应用的主题变量后逐条比对声明，有差异就非零退出。
 *
 * 用法：node scripts/check-answer-style.mjs
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const answerCss = join(root, 'src/answer.css')
const widgetJs = join(root, 'public/embed/kos-chat.js')

/** 组件侧变量 → 应用侧变量（与 kos-chat.js 里 .kos-host 的映射表一一对应） */
const VAR_MAP = {
  '--kos-ink-1': '--ink-1',
  '--kos-ink-2': '--ink-2',
  '--kos-ink-3': '--ink-3',
  '--kos-ink-4': '--ink-3',
  '--kos-line': '--line',
  '--kos-line-soft': '--line-soft',
  '--kos-bg': '--bg-sunken',
  '--kos-bg-sub': '--bg-sub',
  '--kos-bg-sunken': '--bg-sunken',
  '--kos-surface': '--bg-raised',
  '--kos-accent': '--accent-text',
  '--kos-info-bg': '--info-bg',
  '--kos-ok': '--ok',
  '--kos-primary': '--brand-600',
  '--kos-primary-dark': '--brand-700',
  '--kos-primary-soft': '--accent-bg',
}

/** 只比对答案排版相关的选择器，组件自己的 .kos-* 外壳不参与。
    显式带上 .answer-citation：那是服务端 html 格式自带的引用原文小节
    （.answer-citations / -head / -source / -excerpt），同样要求两边同值。 */
const isAnswerSelector = (s) =>
  /(^|[\s,>])(\.md-|\.answer-citation|\.text-answer)/.test(s) || s.startsWith('.md-text')

/** 组件侧规则带 .kos-host 作用域前缀（防止污染宿主页面），比对前去掉 */
const stripScope = (s) => s.replace(/(^|,\s*)\.kos-host\s+/g, '$1').trim()

function normalizeVars(text) {
  return text.replace(/--kos-[a-z0-9-]+/g, (name) => VAR_MAP[name] || name)
}

/** 数值写法归一化：.5rem 与 0.5rem 是同一个值，不应算差异 */
function normalizeNumbers(text) {
  return text
    .replace(/(^|[\s(,])\.(\d)/g, '$10.$2')   // .5rem → 0.5rem
    .replace(/-\.(\d)/g, '-0.$1')             // -.5rem → -0.5rem
    .replace(/\s+/g, ' ')
    .trim()
}

/** 极简 CSS 规则解析：够用即可（这两份都是我们自己控制的、不含 @media 嵌套的规则） */
function parseRules(css) {
  const rules = new Map()
  const cleaned = css.replace(/\/\*[\s\S]*?\*\//g, ' ')
  const re = /([^{}]+)\{([^{}]*)\}/g
  let m
  while ((m = re.exec(cleaned))) {
    const selectors = m[1].split(',').map((s) => s.trim().replace(/\s+/g, ' ')).filter(Boolean)
    const decls = new Map()
    m[2]
      .split(';')
      .map((d) => d.trim())
      .filter(Boolean)
      .forEach((d) => {
        const at = d.indexOf(':')
        if (at < 0) return
        const prop = d.slice(0, at).trim()
        const value = normalizeNumbers(normalizeVars(d.slice(at + 1).trim()))
        decls.set(prop, value)
      })
    selectors.filter(isAnswerSelector).forEach((sel) => {
      const key = normalizeNumbers(normalizeVars(stripScope(sel)))
      if (!rules.has(key)) rules.set(key, new Map())
      decls.forEach((v, p) => rules.get(key).set(p, v))
    })
  }
  return rules
}

/** 从 kos-chat.js 的 buildStyles() 里还原 CSS 文本 */
function extractWidgetCss(src) {
  const start = src.indexOf('Widget.prototype.buildStyles = function () {')
  if (start < 0) throw new Error('找不到 buildStyles')
  const end = src.indexOf('\n  };', start)
  const block = src.slice(start, end)
  let css = ''
  const re = /'((?:[^'\\]|\\.)*)'/g
  let m
  while ((m = re.exec(block))) {
    css += m[1].replace(/\\'/g, "'").replace(/\\\\/g, '\\') + '\n'
  }
  // 去掉 JS 里的 /* */ 注释行（它们在字符串数组外面，正则不捕获，这里兜底）
  return css
}

const app = parseRules(readFileSync(answerCss, 'utf8'))
const widget = parseRules(extractWidgetCss(readFileSync(widgetJs, 'utf8')))

/** 差异白名单：组件侧刻意增强、应用侧没有对应形态的规则 */
const IGNORE_SELECTORS = new Set([
  '.md-answer .md-codeblock .md-action', // 组件的「复制」按钮，应用侧没有
])

const problems = []
const compared = []

app.forEach((appDecls, sel) => {
  if (IGNORE_SELECTORS.has(sel)) return
  const widgetDecls = widget.get(sel)
  if (!widgetDecls) {
    problems.push(`组件缺少规则: ${sel}`)
    return
  }
  appDecls.forEach((appValue, prop) => {
    const widgetValue = widgetDecls.get(prop)
    compared.push(`${sel} { ${prop} }`)
    if (widgetValue === undefined) {
      problems.push(`${sel} { ${prop} } —— 组件缺少该声明（应用: ${appValue}）`)
      return
    }
    if (widgetValue !== appValue) {
      problems.push(`${sel} { ${prop} }\n    应用: ${appValue}\n    组件: ${widgetValue}`)
    }
  })
})

console.log(`answer.css 规则: ${app.size}  组件侧规则: ${widget.size}`)
console.log(`比对声明: ${compared.length}`)
if (problems.length) {
  console.log(`\n✗ 发现 ${problems.length} 处不一致：`)
  problems.forEach((p) => console.log('  - ' + p))
  process.exit(1)
}
console.log('\n✓ 嵌入组件与控制台的答案排版规则一致')
