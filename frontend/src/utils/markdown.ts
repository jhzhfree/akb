import type { QueryCitation } from '../types'

/**
 * 轻量 Markdown 渲染器：只实现答案卡片需要的受控子集
 * （标题 / 列表 / 粗体 / 行内代码 / 引用块 / 代码块 / [证据N] 上标引用），
 * 输入先做 HTML 转义，因此可以安全地用于 v-html。
 */

const escapeHtml = (text:string):string =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const INLINE_PATTERNS:Array<[RegExp, string]> = [
  [/\*\*([^*]+)\*\*/g, '<b>$1</b>'],
  [/`([^`]+)`/g, '<code class="md-code">$1</code>'],
]

/** 行内元素：粗体、行内代码，以及映射到引用列表的 [证据N] 上标。 */
function renderInline(text:string, citations:QueryCitation[]):string {
  let html = escapeHtml(text)
  for (const [pattern, replacement] of INLINE_PATTERNS) html = html.replace(pattern, replacement)
  html = html.replace(/\[证据(\d+)\]/g, (_match, index:string) => {
    const citation = citations.find(item => Number(item.index) === Number(index))
    const title = citation?.title ? ` title="${escapeHtml(citation.title)}"` : ''
    return `<sup class="md-cite"${title}>[${index}]</sup>`
  })
  return html
}

const headingTag = (level:number):string =>
  level <= 1 ? 'h3' : level === 2 ? 'h4' : 'h5'

/**
 * 把答案文本渲染为 HTML 片段。分组规则按行处理：代码块、标题、列表、
 * 引用块依次匹配，其余行合并为段落。
 */
export function renderMarkdown(source:string, citations:QueryCitation[] = []):string {
  const lines = (source || '').split(/\r?\n/)
  const out:string[] = []
  let paragraph:string[] = []
  let list:{type:'ul'|'ol'; items:string[]}|null = null
  let quote:string[] = []
  let code:string[] = []
  let inCode = false

  const flushParagraph = () => {
    if (paragraph.length) {
      out.push(`<p>${paragraph.map(line => renderInline(line, citations)).join('<br/>')}</p>`)
      paragraph = []
    }
  }
  const flushList = () => {
    if (list) {
      out.push(`<${list.type}>${list.items.map(item => `<li>${renderInline(item, citations)}</li>`).join('')}</${list.type}>`)
      list = null
    }
  }
  const flushQuote = () => {
    if (quote.length) {
      out.push(`<blockquote>${renderInline(quote.join(' '), citations)}</blockquote>`)
      quote = []
    }
  }
  const flushAll = () => { flushParagraph(); flushList(); flushQuote() }

  for (const line of lines) {
    if (line.trim().startsWith('```')) {
      if (inCode) {
        out.push(`<pre class="md-pre">${code.join('\n')}</pre>`)
        code = []
        inCode = false
      } else {
        flushAll()
        inCode = true
      }
      continue
    }
    if (inCode) { code.push(escapeHtml(line)); continue }

    const trimmed = line.trim()
    if (!trimmed) { flushAll(); continue }

    const heading = trimmed.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      flushAll()
      out.push(`<${headingTag(heading[1].length)} class="md-heading">${renderInline(heading[2], citations)}</${headingTag(heading[1].length)}>`)
      continue
    }

    const unordered = trimmed.match(/^[-*]\s+(.*)$/)
    if (unordered) {
      flushParagraph(); flushQuote()
      if (!list || list.type !== 'ul') { flushList(); list = {type:'ul', items:[]} }
      list.items.push(unordered[1])
      continue
    }

    const ordered = trimmed.match(/^\d+[.、)]\s+(.*)$/)
    if (ordered) {
      flushParagraph(); flushQuote()
      if (!list || list.type !== 'ol') { flushList(); list = {type:'ol', items:[]} }
      list.items.push(ordered[1])
      continue
    }

    const quoted = trimmed.match(/^>\s?(.*)$/)
    if (quoted) {
      flushParagraph(); flushList()
      quote.push(quoted[1])
      continue
    }

    flushList(); flushQuote()
    paragraph.push(trimmed)
  }
  if (inCode && code.length) out.push(`<pre class="md-pre">${code.join('\n')}</pre>`)
  flushAll()
  return out.join('\n')
}
