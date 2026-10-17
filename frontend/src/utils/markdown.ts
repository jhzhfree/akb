import type { QueryCitation } from '../types'

/**
 * 轻量 Markdown 渲染器：只实现答案卡片需要的受控子集
 * （标题 / 列表 / 粗体 / 行内代码 / 引用块 / 代码块 / 表格 / 任务列表 / [证据N] 上标引用），
 * 输入先做 HTML 转义，因此可以安全地用于 v-html。
 *
 * ⚠ 一致性约束：这里产出的 HTML 必须与嵌入组件 `public/embed/kos-chat.js` 里的
 * `renderMarkdown` 保持同一套结构和同一套 `.md-*` 类名（h3/h4/h5 + .md-heading、
 * `<b>` 而非 `<strong>`、`<code class="md-code">`、`.md-codeblock > .md-codebar + .md-pre`、
 * `.md-table-wrap > table`、`<li class="md-task"> > .md-check`、`<sup class="md-cite">`）。
 * 否则同一个答案在控制台与嵌入组件里会渲染成两种样子。
 * 改这里请同步改组件，反之亦然。
 */

const escapeHtml = (text:string):string =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** 与组件一致：# → h3，## → h4，其余 → h5。 */
const headingTag = (level:number):string =>
  level <= 1 ? 'h3' : level === 2 ? 'h4' : 'h5'

/** 行内元素：粗体、斜体、删除线、行内代码、图片、链接，以及映射到引用列表的 [证据N] 上标。 */
function renderInline(text:string, citations:QueryCitation[]):string {
  // 行内代码先抽成占位符，避免里面的 * ~ [ ] 被后面的规则吃掉
  const slots:string[] = []
  let html = escapeHtml(text)
    .replace(/`([^`]+)`/g, (_match, code:string) => {
      slots.push(code)
      return `☁${slots.length - 1}☁`
    })
    .replace(/!\[([^\]]*)\]\((https?:\/\/[^)\s]+)\)/g, '<img class="md-img" alt="$1" src="$2" loading="lazy"/>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/(^|[^*\w])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/~~([^~]+)~~/g, '<del>$1</del>')

  // [证据N] 与 [N] 合并成一条规则：分两次替换会把刚生成的 [N] 再包一层 sup
  html = html.replace(/\[\s*(?:证据\s*)?(\d+)\s*\]/g, (match:string, index:string) => {
    const citation = citations.find(item => Number(item.index) === Number(index))
    if (!citation) return match
    const title = citation.title || citation.sourceName || ''
    return `<sup class="md-cite"${title ? ` title="${escapeHtml(title)}"` : ''}>[${index}]</sup>`
  })

  return html.replace(/☁(\d+)☁/g, (_match, slot:string) =>
    `<code class="md-code">${escapeHtml(slots[Number(slot)])}</code>`)
}

/** 表格对齐行 → 每列的 text-align */
function parseAlign(row:string):string[] {
  return row.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map((cell:string) => {
    const value = cell.trim()
    if (/^:-+:$/.test(value)) return 'center'
    if (/^-+:$/.test(value)) return 'right'
    return ''
  })
}

function rowCells(row:string, tag:string, align:string[], citations:QueryCitation[]):string {
  return row.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map((cell:string, idx:number) => {
    const style = align[idx] ? ` style="text-align:${align[idx]}"` : ''
    return `<${tag}${style}>${renderInline(cell.trim(), citations)}</${tag}>`
  }).join('')
}

/** 围栏代码块：与组件同构的外壳 + 语言标签顶栏 + .md-pre 代码体 */
function codeBlock(lang:string, code:string[]):string {
  return `<div class="md-codeblock"><div class="md-codebar"><span class="md-codelang">${
    escapeHtml(lang || 'code')}</span></div><pre class="md-pre">${code.map(escapeHtml).join('\n')}</pre></div>`
}

/**
 * 把答案文本渲染为 HTML 片段。分组规则按行处理：代码块、分隔线、表格、标题、
 * 引用块、列表依次匹配，其余行合并为段落。
 */
export function renderMarkdown(source:string, citations:QueryCitation[] = []):string {
  const lines = (source || '').split(/\r?\n/)
  const out:string[] = []
  let paragraph:string[] = []
  let list:{type:'ul'|'ol'; items:string[]}|null = null
  let quote:string[] = []
  let code:string[] = []
  let codeLang = ''
  let inCode = false

  const flushParagraph = () => {
    if (paragraph.length) {
      out.push(`<p>${paragraph.map(line => renderInline(line, citations)).join('<br/>')}</p>`)
      paragraph = []
    }
  }
  const flushList = () => {
    if (list) {
      // 任务列表项自带 <li class="md-task">，不能再包一层
      const items = list.items.map(item => item.startsWith('<li') ? item : `<li>${item}</li>`).join('')
      out.push(`<${list.type}>${items}</${list.type}>`)
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

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trimmed = line.trim()

    const fence = trimmed.match(/^```\s*([\w+#-]*)\s*$/)
    if (fence) {
      if (inCode) {
        out.push(codeBlock(codeLang, code))
        code = []; codeLang = ''; inCode = false
      } else {
        flushAll()
        inCode = true
        codeLang = fence[1] || ''
      }
      continue
    }
    if (inCode) { code.push(line); continue }

    if (!trimmed) { flushAll(); continue }

    if (/^([-*_])\s*\1\s*\1[\s\-*_]*$/.test(trimmed)) { flushAll(); out.push('<hr/>'); continue }

    if (trimmed.startsWith('|') && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(lines[i + 1])) {
      flushAll()
      const align = parseAlign(lines[i + 1])
      const head = rowCells(lines[i], 'th', align, citations)
      const body:string[] = []
      i += 2
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        body.push(`<tr>${rowCells(lines[i], 'td', align, citations)}</tr>`)
        i++
      }
      i--
      out.push(`<div class="md-table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${body.join('')}</tbody></table></div>`)
      continue
    }

    // 标题：`#` 后允许没有空格（模型原文常写成 `##年假天数`），否则整行会退化成普通段落
    const heading = trimmed.match(/^(#{1,6})\s*(.+)$/)
    if (heading) {
      flushAll()
      const tag = headingTag(heading[1].length)
      out.push(`<${tag} class="md-heading">${renderInline(heading[2], citations)}</${tag}>`)
      continue
    }

    const quoted = trimmed.match(/^>\s?(.*)$/)
    if (quoted) {
      flushParagraph(); flushList()
      quote.push(quoted[1])
      continue
    }

    // 列表：标记后「有空格」照旧；「无空格」也认（模型原文常写 `-旧口径`）。
    // 但 `-09-01` 这种「减号紧跟数字」是日期/负数，不能当列表项，故 tight 里排除数字。
    // 两条正则的捕获组编号不同，这里归一化成 bullet / ordered / body 再用。
    const spaced = trimmed.match(/^(?:([-*+])|(\d{1,3})[.、)])\s+(.*)$/)
    // tight 只认 '-' 和 '+'：'*' 开头的无空格行多半是 `**加粗**` / `*斜体*`，
    // 当成列表项会把它们连人带标记吞进 <li>（渲染成斜体），比不认还糟。
    const tight = spaced ? null : trimmed.match(/^([-+])(?![\s\d])(.*)$/)
    const bullet = spaced ? spaced[1] : tight ? tight[1] : ''
    const ordered = spaced ? spaced[2] : ''
    const body = spaced ? spaced[3] : tight ? tight[2] : ''
    if (bullet || ordered) {
      flushParagraph(); flushQuote()
      const type:'ul'|'ol' = bullet ? 'ul' : 'ol'
      if (!list || list.type !== type) { flushList(); list = { type, items: [] } }
      const task = body.match(/^\[([ xX])\]\s+(.*)$/)
      if (task) {
        const done = task[1].toLowerCase() === 'x'
        list.items.push(`<li class="md-task"><span class="md-check${done ? ' md-checked' : ''}">${
          done ? '\u2713' : ''}</span>${renderInline(task[2], citations)}</li>`)
      } else {
        list.items.push(renderInline(body, citations))
      }
      continue
    }

    flushList(); flushQuote()
    paragraph.push(trimmed)
  }
  if (inCode && code.length) out.push(codeBlock(codeLang, code))
  flushAll()
  return out.join('\n')
}
