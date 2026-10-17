/*!
 * KnowledgeOS Chat Widget — 网站嵌入问答组件
 *
 * 引入方式（一行）：
 *   <script src="https://<host>/embed/kos-chat.js"
 *           data-api-base="https://<host>/api/v1/external"
 *           data-token="kos_et_..."
 *           data-kb="1"></script>
 *
 * 或显式初始化：
 *   KnowledgeOSChat.init({ apiBase, token, knowledgeBaseId, title, theme })
 *
 * 设计要点：
 *  - 零依赖原生 JS，渲染在 Light DOM 里：宿主页面的全局样式会作用到组件内部，
 *    组件自身样式统一带 .kos-* / .md-* 前缀，不污染宿主页面；
 *    主题化走 --kos-external-* CSS 变量（见 docs/EMBED-CHAT-WIDGET.md 步骤 4）；
 *  - 只持有一枚短期嵌入令牌（X-Embed-Token），API Key 不出现在浏览器中；
 *  - 消费后端 SSE 流，实时展示服务端检索过程（改写/召回/融合/重排/生成/引用校验）；
 *  - 答案里的 [证据N] 上标与下方「引用来源」卡片一一对应，点开可看证据原文，
 *    排版与控制台问答页保持一致：同一套信息层次，只是装进了一个 420px 的面板。
 */
(function (root, doc) {
  'use strict';

  var VERSION = '1.3.6';
  /**
   * apiBase 的写法在各接入方手里并不统一：控制台示例与文档给的是
   * https://<host>/api/v1/external，也有只写到 https://<host> 的。
   * 这里统一归一化成 .../api/v1/external —— 否则 apiBase 已经带了
   * /api/v1/external 时会被再拼一遍，请求落到 .../api/v1/external/api/v1/external/stream/query，
   * 表现就是组件回复“服务器返回异常：HTTP 404”。
   */
  var EXTERNAL_ROOT = '/api/v1/external';
  function resolveApiBase(apiBase) {
    var base = String(apiBase || '').replace(/\/+$/, '');
    if (/\/api\/v1\/external$/i.test(base)) {
      return base;
    }
    if (/\/api\/v1$/i.test(base)) {
      return base + '/external';
    }
    if (/\/api$/i.test(base)) {
      return base + '/v1/external';
    }
    return base + EXTERNAL_ROOT;
  }
  function resolveStreamUrl(apiBase) {
    return resolveApiBase(apiBase) + '/stream/query';
  }
  /** 证据原文走同一个外部契约：同一枚令牌、同一套审计，不额外开口子。 */
  function resolveEvidenceUrl(apiBase, code, knowledgeBaseId) {
    var url = resolveApiBase(apiBase) + '/evidence/' + encodeURIComponent(code);
    if (knowledgeBaseId) {
      url += '?knowledgeBaseId=' + encodeURIComponent(knowledgeBaseId);
    }
    return url;
  }

  var STAGE_META = [
    { key: 'rewrite', label: '查询改写' },
    { key: 'route', label: '意图路由' },
    { key: 'recall', label: '多通道召回' },
    { key: 'acl_filter', label: '权限过滤' },
    { key: 'fusion', label: '结果融合' },
    { key: 'rerank', label: '重排序' },
    { key: 'context', label: '上下文装配' },
    { key: 'llm', label: '答案生成' },
    { key: 'citation', label: '引用校验' }
  ];

  /**
   * 结论来源：查到的和推出来的在界面上长得一样时，没人知道该不该追问一句“依据呢”。
   * 与控制台「结论来源」徽标同一语义，只是文案压进一行 tooltip。
   */
  var CONCLUSION_HINT = {
    GRAPH_TRAVERSAL: '沿图谱关系遍历得出，原文未直接写出',
    DERIVED: '由衍生规则推导，可展开基础断言核对',
    RETRIEVED: '来自资料原文检索',
    RETRIEVED_KNOWLEDGE: '来自资料原文检索'
  };

  var GATE_LABEL = { PASS: '通过', PARTIAL: '部分通过', BLOCKED: '拦截', BYPASSED: '未启用' };

  /** 门控返回的是策略枚举，直接显示给终端用户没有任何意义。 */
  var GATE_REASON_LABEL = {
    ANSWER_EVIDENCE_GATE_BLOCKED: '结论缺少可精确匹配的证据，已整段阻止输出',
    ANSWER_EVIDENCE_GATE_PARTIAL_REDACTION: '部分结论缺少可精确匹配的证据，已删除对应内容',
    STRUCTURED_ANSWER_GATE_BLOCKED: '结构化校验未通过，已阻止输出',
    STRUCTURED_ANSWER_GATE_PARTIAL_REDACTION: '部分结论未通过结构化校验，已删除对应内容',
    PARTIAL_CLAIM_SUPPORT: '部分结论的证据支撑不足',
    EXACT_EVIDENCE_MISSING: '缺少与原文精确匹配的证据',
    CONTRADICTED_BY_EVIDENCE: '结论与现有知识冲突',
    LOW_CONFIDENCE: '整体置信度不足'
  };

  function attrOf(name, fallback) {
    var script = doc.currentScript || doc.querySelector('script[src*="kos-chat.js"]');
    if (!script) {
      return fallback;
    }
    var value = script.getAttribute(name);
    return value === null || value === '' ? fallback : value;
  }

  function toNumber(value, fallback) {
    var parsed = parseInt(value, 10);
    return isNaN(parsed) ? fallback : parsed;
  }

  function defaults() {
    return {
      apiBase: attrOf('data-api-base', ''),
      token: attrOf('data-token', ''),
      apiKey: attrOf('data-api-key', ''),
      knowledgeBaseId: attrOf('data-kb', ''),
      title: attrOf('data-title', '知识库助手'),
      subtitle: attrOf('data-subtitle', '回答可追溯到引用来源'),
      welcome: attrOf('data-welcome', '你好，我可以基于知识库回答你的问题，答案会带上引用来源，可展开查看证据原文。'),
      placeholder: attrOf('data-placeholder', '输入你的问题，回车发送'),
      primary: attrOf('data-primary', '#2f6bff'),
      position: attrOf('data-position', 'right'),
      topK: toNumber(attrOf('data-top-k', ''), 10),
      answerFormat: attrOf('data-answer-format', 'markdown'),
      /* content 决定答案正文如何从 SSE 里取：
         final   = 检索过程用 SSE，答案在 done 里整体拿到（与控制台阻塞式 /api/knowledge/query 同源）
         stream  = 逐字推 delta（边收边渲染的是模型原文，没有引用上标与「引用原文」小节） */
      content: attrOf('data-content', 'final'),
      /* minScore：最低相关度阈值（0~1）。空 = 不传 = 不过滤（与历史一致）。 */
      minScore: attrOf('data-min-score', ''),
      showTrace: attrOf('data-show-trace', 'true') !== 'false',
      showCitations: attrOf('data-show-citations', 'true') !== 'false',
      sampleQuestions: (attrOf('data-samples', '') || '').split('|').filter(Boolean)
    };
  }

  function extend(target, source) {
    for (var key in source) {
      if (Object.prototype.hasOwnProperty.call(source, key) && source[key] !== undefined && source[key] !== null && source[key] !== '') {
        target[key] = source[key];
      }
    }
    return target;
  }

  function escapeHtml(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  /** 对象取值：同一含义在不同接口里字段名不一致，按顺序取第一个非空值。 */
  function pick(source, keys) {
    if (!source) {
      return '';
    }
    for (var i = 0; i < keys.length; i++) {
      var value = source[keys[i]];
      if (value !== undefined && value !== null && value !== '') {
        return value;
      }
    }
    return '';
  }

  /** 事件委托用的最近祖先匹配，避免依赖 Element.closest 的老实现差异。 */
  function closest(node, selector) {
    while (node && node.nodeType === 1) {
      if (node.matches && node.matches(selector)) {
        return node;
      }
      node = node.parentNode;
    }
    return null;
  }

  function formatDuration(ms) {
    var value = Number(ms || 0);
    return value >= 1000 ? (value / 1000).toFixed(1) + 's' : Math.round(value) + 'ms';
  }

  /**
   * 服务端在 markdown 答案尾部追加的「### 引用原文」小节，会被下面的引用卡片取代；
   * 两份引用清单比没有更糟，所以确认尾部确实只有引用条目时才把它摘掉。
   */
  var REF_HEADING = /^ {0,3}#{1,6}\s*.*(引用原文|引用来源|参考来源|参考资料|参考文献|证据原文|引用与出处|引用)\s*$/;
  var REF_ITEM = /^\s*(\[\s*(证据)?\s*\d+\s*\]|>|\uff08|\u6765\u6e90\uff1a)/;
  function stripReferenceSection(answer) {
    var text = String(answer || '');
    var lines = text.split(/\r?\n/);
    var start = -1;
    for (var i = 0; i < lines.length; i++) {
      if (REF_HEADING.test(lines[i])) {
        start = i;
      }
    }
    if (start < 0) {
      return text;
    }
    var body = lines.slice(start + 1).filter(function (line) { return line.trim(); });
    if (!body.length) {
      return text;
    }
    var hits = body.filter(function (line) { return REF_ITEM.test(line); }).length;
    if (hits / body.length < 0.7) {
      return text;
    }
    return lines.slice(0, start).join('\n').replace(/\s+$/, '');
  }

  /**
   * 极简 Markdown：先转义再还原少量标记，保证不会执行宿主注入的 HTML。
   * 行内的 [证据N] 渲染成上标，并带上 data-cite 供点击定位到引用卡片。
   */
  /**
   * 行内标记：先转义再还原少量标签，宿主注入的 <script> 只会变成文本。
   * 引用锚点支持 [证据N] 与 [N]（后者仅在确实存在对应编号时转换，避免把普通序号变成引用）。
   */
  function renderInline(line, refMap) {
    var slots = [];
    var html = String(line)
      .replace(/`([^`]+)`/g, function (_match, code) {
        slots.push(code);
        return '☁' + (slots.length - 1) + '☁';
      })
      .replace(/!\[([^\]]*)\]\((https?:\/\/[^)\s]+)\)/g, '<img class="md-img" alt="$1" src="$2" loading="lazy"/>')
      .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
      /* 用 <b> 而不是 <strong>：与控制台 utils/markdown.ts 的输出保持同一个标签，
         两边共用一套 .md-answer 规则时才不会出现「一边加粗看起来更重」。 */
      .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
      .replace(/(^|[^*\w])\*([^*\n]+)\*/g, '$1<em>$2</em>')
      .replace(/~~([^~]+)~~/g, '<del>$1</del>');

    // [证据N] 与 [N] 合并成一条规则：分两次替换会把刚生成的 [N] 再包一层 sup。
    html = html.replace(/\[\s*(?:证据\s*)?(\d+)\s*\]/g, function (match, index) {
      var found = refMap[Number(index)];
      if (!found) {
        return match;
      }
      var title = found.title || found.sourceName || '';
      return '<sup class="md-cite" data-cite="' + index + '"' +
        (title ? ' title="' + escapeHtml(title) + '"' : '') + '>[' + index + ']</sup>';
    });

    return html.replace(/☁(\d+)☁/g, function (_match, slot) {
      return '<code class="md-code">' + escapeHtml(slots[Number(slot)]) + '</code>';
    });
  }

  /**
   * Markdown → HTML：块级用 DOM 构建（免疫标签注入），行内走 renderInline。
   * 支持标题、有序/无序列表（含缩进嵌套与任务列表）、表格（含对齐）、围栏代码块（带语言标签）、
   * 引用块、分隔线、图片、删除线与引用上标锚点。
   */
  /** 编号 → 引用对象：答案里的 [N] 只有命中真实引用时才渲染成上标。 */
  function refMapOf(citations) {
    var map = {};
    (citations || []).forEach(function (item) {
      if (item && item.index != null) {
        map[Number(item.index)] = item;
      }
    });
    return map;
  }

  function renderMarkdown(text, citations) {
    var refMap = refMapOf(citations);
    var lines = String(text == null ? '' : text).split(/\r?\n/);
    var out = doc.createElement('div');
    out.className = 'md-answer';

    var paragraph = null;
    var quote = null;
    var listStack = [];

    var emit = function (node) { out.appendChild(node); return node; };

    var flushParagraph = function () {
      if (paragraph && paragraph.lines.length) {
        var el = doc.createElement('p');
        el.innerHTML = renderInline(escapeHtml(paragraph.lines.join('\n')), refMap).replace(/\n/g, '<br/>');
        emit(el);
      }
      paragraph = null;
    };
    var flushQuote = function () {
      if (quote && quote.lines.length) {
        var el = doc.createElement('blockquote');
        el.innerHTML = renderInline(escapeHtml(quote.lines.join('\n')), refMap).replace(/\n/g, '<br/>');
        emit(el);
      }
      quote = null;
    };
    var flushLists = function () { listStack = []; };
    var flushAll = function () {
      flushParagraph();
      flushQuote();
      flushLists();
    };

    /** 列表按缩进建栈：更深就挂在上一个 <li> 下，同级同类续接，变浅则回退。 */
    var openList = function (tag, indent) {
      while (listStack.length && listStack[listStack.length - 1].indent > indent) {
        listStack.pop();
      }
      if (listStack.length && listStack[listStack.length - 1].indent === indent) {
        if (listStack[listStack.length - 1].tag === tag) {
          // 同级同类：续接同一个列表，否则 1. 2. 会被截断成两个重新编号的列表。
          return listStack[listStack.length - 1].el;
        }
        listStack.pop();
      }
      var el = doc.createElement(tag);
      if (!listStack.length) {
        listStack.push({ tag: tag, indent: indent, el: el });
        return emit(el);
      }
      var host = listStack[listStack.length - 1].el;
      var last = host.lastElementChild;
      if (!last || last.tagName !== 'LI') {
        last = doc.createElement('li');
        host.appendChild(last);
      }
      last.appendChild(el);
      listStack.push({ tag: tag, indent: indent, el: el });
      return el;
    };

    var alignments = [];
    var parseAlign = function (row) {
      return row.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map(function (cell) {
        var value = cell.trim();
        if (/^:-+:$/.test(value)) { return 'center'; }
        if (/^-+:$/.test(value)) { return 'right'; }
        return '';
      });
    };
    var cellsOf = function (row, tag) {
      return row.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map(function (cell, idx) {
        var style = alignments[idx] ? ' style="text-align:' + alignments[idx] + '"' : '';
        return '<' + tag + style + '>' + renderInline(escapeHtml(cell.trim()), refMap) + '</' + tag + '>';
      }).join('');
    };

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var trimmed = line.trim();
      if (!trimmed) {
        flushAll();
        continue;
      }
      var fence = trimmed.match(/^```\s*([\w+#-]*)\s*$/);
      if (fence) {
        flushAll();
        var code = [];
        i++;
        while (i < lines.length && lines[i].trim().indexOf('```') !== 0) {
          code.push(lines[i]);
          i++;
        }
        var block = doc.createElement('div');
        block.className = 'md-codeblock';
        var bar = doc.createElement('div');
        bar.className = 'md-codebar';
        bar.innerHTML = '<span class="md-codelang">' + escapeHtml(fence[1] || 'code') + '</span>' +
          '<button type="button" class="md-action" data-act="copy-code">复制</button>';
        var pre = doc.createElement('pre');
        pre.className = 'md-pre';
        pre.textContent = code.join('\n');
        block.appendChild(bar);
        block.appendChild(pre);
        emit(block);
        continue;
      }
      if (/^([-*_])\s*\1\s*\1[\s\-*_]*$/.test(trimmed)) {
        flushAll();
        emit(doc.createElement('hr'));
        continue;
      }
      if (trimmed.charAt(0) === '|' && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(lines[i + 1])) {
        flushAll();
        alignments = parseAlign(lines[i + 1]);
        var wrap = doc.createElement('div');
        wrap.className = 'md-table-wrap';
        var table = doc.createElement('table');
        var thead = doc.createElement('thead');
        thead.innerHTML = '<tr>' + cellsOf(lines[i], 'th') + '</tr>';
        var tbody = doc.createElement('tbody');
        i += 2;
        while (i < lines.length && lines[i].trim().charAt(0) === '|') {
          var tr = doc.createElement('tr');
          tr.innerHTML = cellsOf(lines[i], 'td');
          tbody.appendChild(tr);
          i++;
        }
        i--;
        table.appendChild(thead);
        table.appendChild(tbody);
        wrap.appendChild(table);
        emit(wrap);
        continue;
      }
      /* `#` 后允许没有空格（模型原文常写成 `##年假天数`），与控制台 utils/markdown.ts 同规则 */
      var heading = trimmed.match(/^(#{1,6})\s*(.+)$/);
      if (heading) {
        flushAll();
        // 与控制台 headingTag() 同级映射：# → h3，## → h4，其余 → h5。
        // 两边必须一致，否则同一份答案在应用里和组件里标题字号不同。
        var level = heading[1].length <= 1 ? 3 : heading[1].length === 2 ? 4 : 5;
        var h = doc.createElement('h' + level);
        h.className = 'md-heading';
        h.innerHTML = renderInline(escapeHtml(heading[2]), refMap);
        emit(h);
        continue;
      }
      var quoted = trimmed.match(/^>\s?(.*)$/);
      if (quoted) {
        flushParagraph();
        flushLists();
        if (!quote) {
          quote = { lines: [] };
        }
        quote.lines.push(quoted[1]);
        continue;
      }
      /* 标记后「有空格」照旧，「无空格」也认（模型原文常写 `-旧口径`）；
         `-09-01` 这种「减号紧跟数字」是日期/负数，排除掉不当列表项。与控制台同规则。
         两条正则捕获组编号不同，归一化成 indent / isOrdered / body 再用。 */
      var item = line.match(/^(\s*)(?:([-*+])|(\d{1,3})[.、)])\s+(.*)$/);
      /* tight 只认 '-' 和 '+'：'*' 开头的无空格行多半是 `**加粗**` / `*斜体*`，
         当成列表项会把它们连标记一起吞进 <li>；与控制台 utils/markdown.ts 同规则。 */
      var tight = item ? null : line.match(/^(\s*)([-+])(?![\s\d])(.*)$/);
      var matched = item || tight;
      if (matched) {
        flushParagraph();
        flushQuote();
        var indent = Math.floor(matched[1].replace(/\t/g, '  ').length / 2);
        var isOrdered = item ? !!item[3] : false;
        var list = openList(isOrdered ? 'ol' : 'ul', indent);
        var li = doc.createElement('li');
        var body = item ? item[4] : tight[3];
        var task = body.match(/^\[([ xX])\]\s+(.*)$/);
        if (task) {
          var done = task[1].toLowerCase() === 'x';
          li.className = 'md-task';
          li.innerHTML = '<span class="md-check' + (done ? ' md-checked' : '') + '">' +
            (done ? '\u2713' : '') + '</span>' + renderInline(escapeHtml(task[2]), refMap);
        } else {
          li.innerHTML = renderInline(escapeHtml(body), refMap);
        }
        list.appendChild(li);
        continue;
      }
      flushQuote();
      flushLists();
      if (!paragraph) {
        paragraph = { lines: [] };
      }
      paragraph.lines.push(trimmed);
    }
    flushAll();
    return out.innerHTML;
  }

  /* ---- 服务端 HTML 白名单清洗：组件跑在别人页面里，返回的 HTML 一律不可信 ---- */
  var HTML_ALLOWED = {
    P: 1, BR: 1, DIV: 1, SPAN: 1, SECTION: 1, ARTICLE: 1, ASIDE: 1, MAIN: 1, HEADER: 1, FOOTER: 1,
    H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, UL: 1, OL: 1, LI: 1, DL: 1, DT: 1, DD: 1,
    BLOCKQUOTE: 1, PRE: 1, CODE: 1, EM: 1, STRONG: 1, B: 1, I: 1, U: 1, S: 1, DEL: 1, INS: 1,
    SUP: 1, SUB: 1, SMALL: 1, MARK: 1, A: 1, IMG: 1, FIGURE: 1, FIGCAPTION: 1,
    TABLE: 1, THEAD: 1, TBODY: 1, TFOOT: 1, TR: 1, TH: 1, TD: 1, CAPTION: 1, COL: 1, COLGROUP: 1,
    HR: 1, ABBR: 1, TIME: 1
  };
  var HTML_DROPPED = {
    SCRIPT: 1, STYLE: 1, IFRAME: 1, FRAME: 1, FRAMESET: 1, OBJECT: 1, EMBED: 1, LINK: 1, META: 1,
    BASE: 1, FORM: 1, INPUT: 1, BUTTON: 1, TEXTAREA: 1, SELECT: 1, OPTION: 1, SVG: 1, MATH: 1, TEMPLATE: 1
  };
  var HTML_ATTRS = {
    'class': 1, id: 1, title: 1, alt: 1, href: 1, src: 1, target: 1, rel: 1,
    colspan: 1, rowspan: 1, align: 1, width: 1, height: 1, 'data-cite': 1, 'data-index': 1
  };
  var SAFE_URL = /^(https?:\/\/|mailto:|#|\/|\.\/|\.\.\/)/i;

  function sanitizeHtml(html, refMap) {
    var box = doc.createElement('div');
    box.innerHTML = String(html == null ? '' : html);
    (function walk(parent) {
      var node = parent.firstChild;
      while (node) {
        var next = node.nextSibling;
        if (node.nodeType === 1) {
          var tag = node.tagName;
          if (HTML_DROPPED[tag]) {
            parent.removeChild(node);
          } else {
            var attrs = node.attributes;
            for (var i = attrs.length - 1; i >= 0; i--) {
              var name = attrs[i].name.toLowerCase();
              if (name.indexOf('on') === 0 || name === 'style' || !HTML_ATTRS[name]) {
                node.removeAttribute(attrs[i].name);
                continue;
              }
              if ((name === 'href' || name === 'src') &&
                  !SAFE_URL.test(String(node.getAttribute(name) || '').trim())) {
                node.removeAttribute(attrs[i].name);
              }
            }
            if (tag === 'A') {
              node.setAttribute('target', '_blank');
              node.setAttribute('rel', 'noopener noreferrer');
            }
            walk(node);
            if (!HTML_ALLOWED[tag]) {
              var frag = doc.createDocumentFragment();
              while (node.firstChild) {
                frag.appendChild(node.firstChild);
              }
              parent.replaceChild(frag, node);
            }
          }
        } else if (node.nodeType === 8) {
          parent.removeChild(node);
        } else if (node.nodeType === 3 && refMap && node.nodeValue.indexOf('[') >= 0) {
          // 服务端 HTML 里的 [N] 只是文本：命中引用编号就升级成可点击上标。
          var text = node.nodeValue;
          var pattern = /\[\s*(?:证据\s*)?(\d+)\s*\]/g;
          var frag = doc.createDocumentFragment();
          var cursor = 0;
          var hit = pattern.exec(text);
          while (hit) {
            if (refMap[Number(hit[1])]) {
              if (hit.index > cursor) {
                frag.appendChild(doc.createTextNode(text.slice(cursor, hit.index)));
              }
              var sup = doc.createElement('sup');
              sup.className = 'md-cite';
              sup.setAttribute('data-cite', hit[1]);
              sup.textContent = '[' + hit[1] + ']';
              frag.appendChild(sup);
              cursor = hit.index + hit[0].length;
            }
            hit = pattern.exec(text);
          }
          if (cursor) {
            if (cursor < text.length) {
              frag.appendChild(doc.createTextNode(text.slice(cursor)));
            }
            parent.replaceChild(frag, node);
          }
        }
        node = next;
      }
    })(box);
    return box.innerHTML;
  }

  /** SSE 帧解析：按空行切帧，累积多行 data。 */
  function createSseParser(onEvent) {
    var buffer = '';
    return function push(chunk) {
      buffer += chunk;
      var index;
      while ((index = buffer.indexOf('\n\n')) !== -1) {
        var frame = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        var name = 'message';
        var dataLines = [];
        frame.split(/\r?\n/).forEach(function (line) {
          if (line.charAt(0) === ':' || line.indexOf('heartbeat') === 0) {
            return;
          }
          if (line.indexOf('event:') === 0) {
            name = line.slice(6).trim();
          } else if (line.indexOf('data:') === 0) {
            /* SSE 规范：data: 后若紧跟一个空格，去掉它，其余内容原样保留。
               用 trim() 会把行首缩进一起吃掉，Markdown 的嵌套列表、代码块缩进随之丢失。 */
            dataLines.push(line.slice(5).replace(/^ /, ''));
          }
        });
        if (!dataLines.length) {
          continue;
        }
        var raw = dataLines.join('\n');
        var payload = raw;
        try {
          payload = JSON.parse(raw);
        } catch (error) {
          // 非 JSON 的 delta 直接按文本处理
        }
        onEvent(name, payload);
      }
    };
  }

  function Widget(options) {
    this.options = extend(defaults(), options || {});
    this.open = false;
    this.busy = false;
    this.controller = null;
    if (this.options.apiKey && !this.options.token) {
      console.warn('[KnowledgeOSChat] 不建议在页面中直接配置 apiKey，请改用短期嵌入令牌 token。');
    }
  }

  Widget.prototype.mount = function () {
    if (!this.options.apiBase) {
      console.error('[KnowledgeOSChat] 缺少 apiBase，组件未挂载。');
      return this;
    }
    var host = doc.createElement('div');
    host.className = 'kos-widget-host';
    // 用 Light DOM 渲染：外部应用已有的全局样式（button/.card/.btn-primary/Tailwind 等）
    // 能直接作用到组件内部元素；组件 .kos-* / .md-* 前缀防止污染外部。
    // 如需完全隔离，可在调用方用 `<div class="kos-widget-host" style="all: revert">` 包裹。
    this.shadow = host;
    this.shadow.appendChild(this.buildStyles());
    this.shadow.appendChild(this.buildMarkup());
    doc.body.appendChild(host);
    this.bind();
    return this;
  };

  /**
   * 给组件 CSS 加作用域：所有选择器前面补 `.kos-host`。
   *
   * 组件渲染在 Light DOM 里，样式是一条全局 <style>——不加限定的话 `.md-answer {…}`
   * 会命中宿主页面上任何同名元素。控制台自己就有 `.md-answer` / `.md-cite`，
   * 结果就是组件把宿主的答案排版改坏，宿主的颜色变量又在组件里读不到，两边一起错。
   * 加了作用域后组件样式只作用于自己的 DOM 子树。
   */
  function scopeSelectors(text) {
    return text.split(',').map(function (sel) {
      var s = sel.trim();
      if (!s) { return s; }
      // .kos-host 自身及其组合（.kos-host .x）不再重复加前缀
      if (s === '.kos-host' || s.indexOf('.kos-host ') === 0 || s.indexOf('.kos-host.') === 0) {
        return s;
      }
      return '.kos-host ' + s;
    }).join(', ');
  }

  function scopeCss(css) {
    var out = '';
    var buf = '';
    var depth = 0;
    var scopeStack = [];
    for (var i = 0; i < css.length; i++) {
      var ch = css.charAt(i);
      if (ch === '{') {
        var head = buf.trim();
        buf = '';
        if (/^@/.test(head)) {
          // @media / @supports 内部的选择器要加作用域；@keyframes 内部是百分比关键帧，不能加
          scopeStack.push(/^@(?:-\w+-)?(?:media|supports|document|layer|container)\b/.test(head));
          out += head + '{';
        } else {
          var need = depth === 0 || scopeStack[scopeStack.length - 1] === true;
          out += (need ? scopeSelectors(head) : head) + '{';
          scopeStack.push(false);
        }
        depth++;
        continue;
      }
      if (ch === '}') {
        out += buf;
        buf = '';
        scopeStack.pop();
        depth--;
        out += '}';
        continue;
      }
      buf += ch;
    }
    return out + buf;
  }

  Widget.prototype.buildStyles = function () {
    var style = doc.createElement('style');
    var side = this.options.position === 'left' ? 'left: 24px;' : 'right: 24px;';
    style.textContent = scopeCss([
      /* 主题变量：三级 fallback —— 外部显式覆盖 → 宿主应用主题 → 组件自带默认。
         嵌进 KnowledgeOS 控制台时 `--ink-*` / `--brand-*` 已存在，组件自动跟随深/浅主题；
         嵌到第三方站点时这些变量不存在，退回组件自带配色，不会裸奔。
         与控制台 theme.css 的对应关系见 docs/EMBED-CHAT-WIDGET.md「样式一致性」。 */
      '.kos-host {',
      '  --kos-primary: var(--kos-external-primary, var(--brand-600, #2f6bff));',
      '  --kos-primary-dark: var(--kos-external-primary-dark, var(--brand-700, #1d4ed8));',
      '  --kos-primary-soft: var(--kos-external-primary-soft, var(--accent-bg, #eff6ff));',
      '  --kos-accent: var(--kos-external-accent, var(--accent-text, #1d4ed8));',
      '  --kos-ink-1: var(--kos-external-ink-1, var(--ink-1, #0f172a));',
      '  --kos-ink-2: var(--kos-external-ink-2, var(--ink-2, #334155));',
      '  --kos-ink-3: var(--kos-external-ink-3, var(--ink-3, #64748b));',
      '  --kos-ink-4: var(--kos-external-ink-4, var(--ink-3, #94a3b8));',
      '  --kos-line: var(--kos-external-line, var(--line, #e5e7eb));',
      '  --kos-line-soft: var(--kos-external-line-soft, var(--line-soft, #f1f5f9));',
      '  --kos-bg: var(--kos-external-bg, var(--bg-sunken, #f8fafc));',
      '  --kos-bg-sub: var(--kos-external-bg-sub, var(--bg-sub, #f1f5f9));',
      '  --kos-bg-sunken: var(--kos-external-bg-sunken, var(--bg-sunken, #eef1f8));',
      '  --kos-surface: var(--kos-external-surface, var(--bg-raised, #fff));',
      '  --kos-info-bg: var(--kos-external-info-bg, var(--info-bg, #eff6ff));',
      /* 状态三件套：与控制台 .kb-state-* 同源（字色 / 底色 / 边框），深浅主题各自成对 */
      '  --kos-ok: var(--ok, #047857); --kos-ok-bg: var(--ok-bg, #ecfdf5); --kos-ok-bd: var(--ok-bd, #a7f3d0);',
      '  --kos-warn: var(--warn, #b45309); --kos-warn-bg: var(--warn-bg, #fffbeb); --kos-warn-bd: var(--warn-bd, #fde68a);',
      '  --kos-danger: var(--danger, #b91c1c); --kos-danger-bg: var(--danger-bg, #fef2f2); --kos-danger-bd: var(--danger-bd, #fecaca);',
      '  --kos-info: var(--info, #1d4ed8); --kos-info-bd: var(--info-bd, #bfdbfe);',
      '  --kos-neutral-fg: var(--neutral-fg, #334155); --kos-neutral-bg: var(--neutral-bg, #f1f5f9);',
      '  --kos-radius: var(--kos-external-radius, 14px);',
      '  font-family: var(--kos-external-font, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif);',
      '  color: var(--kos-ink-1); font-size: 14px; line-height: 1.6; }',
      '.kos-launcher { position: fixed; bottom: 28px; ' + side + ' z-index: 2147483000; width: 56px; height: 56px;',
      '  border-radius: 28px; border: none;',
      '  background: linear-gradient(135deg, var(--kos-primary), var(--kos-primary-dark)); color: #fff;',
      '  font-size: 24px; line-height: 56px; text-align: center; cursor: pointer; padding: 0;',
      '  box-shadow: 0 10px 28px rgba(15, 23, 42, .28); transition: transform .18s ease, box-shadow .18s ease; }',
      '.kos-launcher:hover { transform: translateY(-2px); box-shadow: 0 14px 32px rgba(15, 23, 42, .34); }',
      '.kos-launcher:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }',
      '.kos-panel { position: fixed; bottom: 96px; ' + side + ' z-index: 2147483001; width: 420px;',
      '  max-width: calc(100vw - 32px); height: 640px; max-height: calc(100vh - 140px);',
      '  display: flex; flex-direction: column; overflow: hidden; background: var(--kos-surface);',
      '  border: 1px solid var(--kos-line); border-radius: 18px;',
      '  box-shadow: 0 24px 64px rgba(15, 23, 42, .22); opacity: 0; transform: translateY(12px) scale(.98);',
      '  pointer-events: none; transition: opacity .18s ease, transform .18s ease; }',
      '.kos-panel.kos-open { opacity: 1; transform: none; pointer-events: auto; }',
      '.kos-header { display: flex; align-items: center; gap: 10px; padding: 14px 16px; color: #fff;',
      '  background: linear-gradient(135deg, var(--kos-primary), var(--kos-primary-dark)); }',
      '.kos-header .kos-brand { width: 32px; height: 32px; flex: none; display: grid; place-items: center;',
      '  border-radius: 10px; background: rgba(255, 255, 255, .18); font-size: 16px; }',
      '.kos-header .kos-titles { min-width: 0; flex: 1; }',
      '.kos-header strong { display: block; font-size: 15px; font-weight: 600; letter-spacing: .2px; }',
      '.kos-header small { display: block; font-size: 11.5px; opacity: .85; }',
      '.kos-icon-btn { background: rgba(255, 255, 255, .14); border: none; color: #fff; width: 28px; height: 28px;',
      '  border-radius: 8px; font-size: 14px; line-height: 1; cursor: pointer; flex: none; }',
      '.kos-icon-btn:hover { background: rgba(255, 255, 255, .26); }',
      '.kos-body { flex: 1; overflow-y: auto; padding: 14px; background: var(--kos-bg); scroll-behavior: smooth; }',
      '.kos-body::-webkit-scrollbar { width: 8px; }',
      '.kos-body::-webkit-scrollbar-thumb { background: var(--kos-line); border-radius: 4px; }',
      '.kos-body::-webkit-scrollbar-thumb:hover { background: var(--kos-ink-4); }',
      '.kos-msg { margin-bottom: 14px; display: flex; flex-direction: column; }',
      '.kos-msg.kos-user { align-items: flex-end; }',
      '.kos-user .kos-bubble { max-width: 82%; padding: 9px 13px; border-radius: 14px 14px 4px 14px;',
      '  background: linear-gradient(135deg, var(--kos-primary), var(--kos-primary-dark)); color: #fff;',
      '  font-size: 14px; line-height: 1.65; word-break: break-word; box-shadow: 0 6px 16px rgba(47, 107, 255, .18); }',
      '.kos-card { background: var(--kos-surface); border: 1px solid var(--kos-line); border-radius: var(--kos-radius);',
      '  padding: 12px 14px; box-shadow: 0 1px 2px rgba(15, 23, 42, .04); }',
      '.kos-welcome { display: flex; gap: 10px; }',
      '.kos-welcome .kos-brand-mark { width: 30px; height: 30px; flex: none; display: grid; place-items: center;',
      '  border-radius: 10px; background: var(--kos-primary-soft); color: var(--kos-primary); font-size: 15px; }',
      '.kos-welcome p { margin: 0; font-size: 13.5px; color: var(--kos-ink-2); line-height: 1.7; }',
      /* 欢迎区的示例问题按钮 */
      '.kos-samples { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }',
      '.kos-samples button { border: 1px solid var(--kos-line); background: var(--kos-surface); color: var(--kos-ink-2);',
      '  border-radius: 999px; padding: 5px 11px; font-size: 12.5px; cursor: pointer; transition: all .15s ease;',
      '  font-family: inherit; }',
      '.kos-samples button:hover { border-color: var(--kos-primary); color: var(--kos-primary); background: var(--kos-primary-soft); }',
      /* 答案排版：与控制台 utils/markdown.ts 产出的结构 + QueryPanel.vue 的 .md-answer 规则
         逐条对齐（padding / 字号 / 行高 / 间距 / 配色全部同值），改这里记得同步那边。 */
      '.md-answer { padding: .25rem 1rem .25rem 0; font-size: 14px; line-height: 1.8;',
      '  color: var(--kos-ink-1); word-break: break-word; }',
      '.md-answer-body { margin: 0; }',
      '.md-answer-body:empty::before { content: "…"; color: var(--kos-ink-4); }',
      '.md-answer p { margin: .5rem 0; }',
      '.md-answer p:first-child { margin-top: 0; } .md-answer p:last-child { margin-bottom: 0; }',
      '.md-answer h1, .md-answer h2, .md-answer h3, .md-answer h4, .md-answer h5, .md-answer h6 {',
      '  margin: .9rem 0 .4rem; font-weight: 600; color: var(--kos-ink-1); }',
      '.md-answer h1, .md-answer h2 { font-size: 15px; }',
      '.md-answer h3 { font-size: 14.5px; }',
      '.md-answer h4 { font-size: 14px; }',
      '.md-answer h5, .md-answer h6 { font-size: 13.5px; }',
      '.md-answer h1:first-child, .md-answer h2:first-child, .md-answer h3:first-child,',
      '.md-answer h4:first-child, .md-answer h5:first-child, .md-answer h6:first-child { margin-top: .2rem; }',
      '.md-answer b, .md-answer strong { font-weight: 600; }',
      '.md-answer ul, .md-answer ol { margin: .4rem 0; padding-left: 1.4rem; }',
      '.md-answer li { margin: .2rem 0; }',
      '.md-answer blockquote { margin: .5rem 0; border-left: 3px solid var(--kos-line-soft);',
      '  padding: .1rem 0 .1rem .75rem; color: var(--kos-ink-2); }',
      '.md-answer code, .md-answer .md-code { border-radius: 4px; background: var(--kos-bg-sub);',
      '  padding: .1rem .3rem; font-family: ui-monospace, monospace; font-size: 12px; }',
      '.md-answer .md-pre { margin: .5rem 0; border-radius: 8px; background: var(--kos-bg-sunken);',
      '  color: var(--kos-ink-2); padding: .75rem 1rem; overflow: auto; font-size: 12px; line-height: 1.6; }',
      '.md-answer .md-pre code { font-family: ui-monospace, monospace; }',
      '.md-answer a { color: var(--kos-accent); }',
      '.md-answer a:hover { text-decoration: underline; }',
      '.md-answer em { font-style: italic; }',
      '.md-answer del { opacity: .65; }',
      '.md-answer hr { border: none; border-top: 1px solid var(--kos-line-soft); margin: .8rem 0; }',
      '.md-answer table { border-collapse: collapse; font-size: 13px; width: 100%; margin: .5rem 0; }',
      '.md-answer th, .md-answer td { border: 1px solid var(--kos-line); padding: 5px 9px; text-align: left; }',
      '.md-answer th { background: var(--kos-bg-sub); font-weight: 600; }',
      '.md-answer .md-table-wrap { overflow-x: auto; margin: .5rem 0; }',
      '.md-answer .md-task { list-style: none; position: relative; padding-left: 1.4rem; margin-left: -1.4rem; }',
      '.md-answer .md-check { display: inline-block; width: 12px; height: 12px; line-height: 11px; text-align: center;',
      '  border: 1.5px solid var(--kos-ink-3); border-radius: 3px; margin-right: 4px; color: var(--kos-ok);',
      '  font-size: 10px; font-weight: 700; vertical-align: -1px; }',
      '.md-answer .md-check.md-checked { background: var(--kos-ok); border-color: var(--kos-ok); color: #fff; }',
      '.md-answer img.md-img { max-width: 100%; height: auto; border-radius: 6px; margin: .4rem 0; }',
      '.md-answer .md-cite { margin-left: 2px; color: var(--kos-accent); font-weight: 600;',
      '  cursor: help; padding: 0 1px; border-radius: 3px; }',
      '.md-answer .md-cite:hover { background: var(--kos-info-bg); }',
      /* 服务端自带的引用小节仍按 .answer-citations 渲染，与 chips 互为补充 */
      '.md-answer .answer-citations { margin-top: 1rem; border-top: 1px solid var(--kos-line-soft); padding-top: .75rem; }',
      '.md-answer .answer-citations h3 { margin: 0 0 .4rem; font-size: 12px; font-weight: 600; color: var(--kos-ink-2); }',
      '.md-answer .answer-citations ol { margin: 0; padding-left: 1.2rem; list-style: decimal; }',
      '.md-answer .answer-citation { margin: .35rem 0; font-size: 13px; }',
      '.md-answer .answer-citation-head { display: flex; flex-wrap: wrap; gap: .4rem; align-items: baseline; }',
      '.md-answer .answer-citation-source { margin-left: auto; font-size: 11px; color: var(--kos-ink-3); }',
      '.md-answer .answer-citation-excerpt { margin: .2rem 0 .5rem; border-left: 3px solid var(--kos-line-soft);',
      '  padding: .1rem 0 .1rem .75rem; color: var(--kos-ink-2); font-size: 12.5px; line-height: 1.7; }',
      /* 代码块：顶栏（语言 + 复制）是组件的增强，代码体底色与控制台 .md-pre 同为 --bg-sunken */
      '.md-answer .md-codeblock { margin: .5rem 0; border-radius: 8px; overflow: hidden;',
      '  border: 1px solid var(--kos-line); background: var(--kos-bg-sunken); }',
      '.md-answer .md-codebar { display: flex; align-items: center; padding: 4px 10px;',
      '  background: var(--kos-bg-sub); border-bottom: 1px solid var(--kos-line); }',
      '.md-answer .md-codeblock .md-codelang { font-size: 11px; color: var(--kos-ink-3);',
      '  font-family: ui-monospace, monospace; }',
      '.md-answer .md-codeblock .md-action { margin-left: auto; }',
      '.md-answer .md-codeblock .md-pre { margin: 0; border-radius: 0; background: var(--kos-bg-sunken);',
      '  color: var(--kos-ink-2); padding: .75rem 1rem; font-size: 12px; line-height: 1.6; }',
      /* HTML 源降级 / 纯文本 / 流式光标 / 错误与检索中 */
      '.md-source { margin: 0; padding: 10px 12px; border-radius: 8px; background: var(--kos-bg);',
      '  color: var(--kos-ink-2); font-family: Menlo, Consolas, monospace; font-size: 12.5px; line-height: 1.65;',
      '  white-space: pre-wrap; word-break: break-word; }',
      '.md-text { margin: 0; font-size: 14px; line-height: 1.8; color: var(--kos-ink-1);',
      '  white-space: pre-wrap; word-break: break-word; font-family: inherit; }',
      '.md-typing { color: var(--kos-ink-4); font-size: 13px; }',
      '.md-error { color: var(--kos-danger); font-size: 13px; }',
      '.md-caret { display: inline-block; width: 7px; height: 15px; vertical-align: -2px;',
      '  background: var(--kos-ink-2); animation: md-blink 1s steps(2) infinite; }',
      '@keyframes md-blink { 50% { opacity: 0; } }',
      /* 元信息胶囊：与控制台 .kb-badge / .kb-state-* 同形态（rounded-md 6px、11px、font-medium）
         与同配色（三件套都取自 theme.css，不在组件里写死浅色） */
      '.md-meta { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 10px; padding-top: 10px;',
      '  border-top: 1px solid var(--kos-line-soft); }',
      '.md-badge { display: inline-flex; align-items: center; gap: 3px; padding: 2px 6px;',
      '  border-radius: 6px; font-size: 11px; font-weight: 500; border: 1px solid transparent;',
      '  background: var(--kos-neutral-bg); color: var(--kos-neutral-fg); }',
      '.md-badge.md-tone-ok { background: var(--kos-ok-bg); color: var(--kos-ok); border-color: var(--kos-ok-bd); }',
      '.md-badge.md-tone-info { background: var(--kos-info-bg); color: var(--kos-info); border-color: var(--kos-info-bd); }',
      '.md-badge.md-tone-warn { background: var(--kos-warn-bg); color: var(--kos-warn); border-color: var(--kos-warn-bd); }',
      '.md-badge.md-tone-danger { background: var(--kos-danger-bg); color: var(--kos-danger); border-color: var(--kos-danger-bd); }',
      /* 引用来源：与控制台「引用来源」同一形态 —— [N] 标题 的朴素 accent 文字按钮，
         点击在本卡片内联展开详情（窄面板里比抽屉更实用），hover 与控制台同：浅底 + 下划线 */
      '.md-cites { margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--kos-line-soft); }',
      '.md-cites-head { font-size: 11px; font-weight: 600; color: var(--kos-ink-3);',
      '  margin: 0 0 6px; text-transform: uppercase; letter-spacing: .05em; }',
      '.md-cite-list { display: flex; flex-wrap: wrap; gap: 2px 6px; }',
      '.md-cite-chip { border: none; background: none; color: var(--kos-accent);',
      '  padding: 2px 6px; border-radius: 4px; font-size: 12px; font-family: inherit;',
      '  cursor: pointer; transition: background .12s ease; }',
      '.md-cite-chip:hover { background: var(--kos-info-bg); text-decoration: underline; }',
      '.md-cite-chip.md-selected { background: var(--kos-primary-soft); text-decoration: underline; }',
      '.md-cite-detail { margin-top: 8px; padding: 9px 11px; border: 1px solid var(--kos-line);',
      '  border-radius: 8px; background: var(--kos-bg-sub); font-size: 12.5px; color: var(--kos-ink-2); }',
      '.md-cite-detail-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }',
      '.md-cite-detail-head .md-cite-idx { display: inline-block; min-width: 18px; height: 18px; line-height: 18px;',
      '  text-align: center; border-radius: 6px; background: var(--kos-info-bg); color: var(--kos-info);',
      '  font-size: 11px; font-weight: 600; }',
      '.md-cite-detail-head .md-cite-title { font-weight: 600; color: var(--kos-ink-1); font-size: 13px;',
      '  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }',
      '.md-cite-detail-head .md-cite-close { border: none; background: none; color: var(--kos-ink-3);',
      '  font-size: 16px; line-height: 1; cursor: pointer; padding: 0 2px; }',
      '.md-cite-detail dl { display: grid; grid-template-columns: max-content 1fr; gap: 2px 10px;',
      '  margin: 6px 0 0; font-size: 12px; }',
      '.md-cite-detail dt { color: var(--kos-ink-4); }',
      '.md-cite-detail dd { color: var(--kos-ink-2); margin: 0; }',
      '.md-cite-detail .md-cite-evidence { margin-top: 8px; }',
      '.md-cite-detail .md-cite-excerpt { margin: 6px 0 0; padding: 4px 0 4px 8px;',
      '  border-left: 2px solid var(--kos-line-soft); color: var(--kos-ink-2); font-size: 12.5px; line-height: 1.7; }',
      /* 操作区：与控制台 .kb-btn-outline 同调（bg-raised 底 / line-soft 边 / ink-1 字） */
      '.md-actions { display: flex; align-items: center; gap: 6px; margin-top: 10px; padding-top: 9px;',
      '  border-top: 1px solid var(--kos-line-soft); }',
      '.md-action { border: 1px solid var(--kos-line-soft); background: var(--kos-surface);',
      '  color: var(--kos-ink-1); padding: 2px 10px; border-radius: 6px; font-size: 11.5px;',
      '  font-family: inherit; cursor: pointer; transition: all .12s ease; }',
      '.md-action:hover { border-color: var(--kos-primary); color: var(--kos-accent);',
      '  background: var(--kos-bg-sub); }',
      /* 空结果：与控制台 .kb-state-danger 同语义（三件套取自 theme.css） */
      '.md-empty { margin: 0; padding: 10px 12px; border-radius: 8px;',
      '  background: var(--kos-danger-bg); border-left: 3px solid var(--kos-danger); }',
      '.md-empty-title { margin: 0 0 4px; font-size: 13.5px; font-weight: 600;',
      '  color: var(--kos-danger); line-height: 1.6; }',
      '.md-empty-facts { margin: 0 0 0 16px; padding: 0; font-size: 12px;',
      '  color: var(--kos-ink-2); line-height: 1.75; }',
      '.md-empty-hint { margin: 6px 0 0; font-size: 12px; color: var(--kos-ink-3); }',
      /* 检索过程：生成中展开，结束后收起——过程是用来解释等待的，不是答案的一部分 */
      '.kos-trace { border: 1px solid var(--kos-line); border-radius: 8px; background: var(--kos-surface);',
      '  margin-bottom: 10px; overflow: hidden; }',
      '.kos-trace > summary { list-style: none; cursor: pointer; padding: 8px 12px; font-size: 12.5px;',
      '  color: var(--kos-ink-2); user-select: none; display: flex; align-items: center; gap: 6px; }',
      '.kos-trace > summary::-webkit-details-marker { display: none; }',
      '.kos-stages { display: flex; flex-direction: column; gap: 5px; padding: 0 12px 10px; }',
      '.kos-stage { display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--kos-ink-3); }',
      '.kos-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--kos-line);',
      '  transition: background .2s ease; }',
      '.kos-stage.kos-running .kos-dot { background: var(--kos-primary); animation: kos-pulse 1s ease-in-out infinite; }',
      '.kos-stage.kos-done .kos-dot { background: var(--kos-ok); }',
      '.kos-latency { margin-left: auto; font-family: Menlo, Consolas, monospace; font-size: 11px; color: var(--kos-ink-4); }',
      '@keyframes kos-pulse { 50% { opacity: .25; } }',
      /* 输入区 */
      '.kos-footer { display: flex; flex-direction: column; gap: 6px; padding: 10px 12px;',
      '  border-top: 1px solid var(--kos-line-soft); background: var(--kos-surface); }',
      '.kos-footer textarea { width: 100%; box-sizing: border-box; resize: none; min-height: 44px; max-height: 140px;',
      '  border: 1px solid var(--kos-line); border-radius: 12px; padding: 9px 11px; font-size: 14px; font-family: inherit; line-height: 1.5;',
      '  outline: none; color: var(--kos-ink-1); background: var(--kos-surface); transition: border-color .15s ease; }',
      '.kos-footer textarea:focus { border-color: var(--kos-primary); box-shadow: 0 0 0 3px var(--kos-primary-soft); }',
      '.kos-composer { display: flex; align-items: flex-end; gap: 8px; }',
      '.kos-send { flex: none; border: none;',
      '  background: linear-gradient(135deg, var(--kos-primary), var(--kos-primary-dark)); color: #fff;',
      '  font-size: 14px; font-family: inherit; cursor: pointer; transition: opacity .15s ease;',
      '  border-radius: 10px; padding: 9px 18px; }',
      '.kos-send:disabled { opacity: .55; cursor: not-allowed; }',
      '.kos-send:hover:not(:disabled) { opacity: .92; }',
      /* 生成中：发送按钮切成「停止」语义 */
      '.kos-send.kos-stop { background: var(--kos-danger); }',
      '.kos-footnote { margin: 6px 2px 0; font-size: 11px; color: var(--kos-ink-4); }',
      /* 证据原文抽屉：贴在面板内部，从右侧滑入 */
      '.kos-drawer { position: absolute; top: 0; bottom: 0; right: 0; left: 0; z-index: 5;',
      '  display: flex; flex-direction: column; background: var(--kos-surface);',
      '  transform: translateX(100%); transition: transform .2s ease; pointer-events: none; }',
      '.kos-drawer.kos-open { transform: none; pointer-events: auto; }',
      '.kos-drawer-head { display: flex; align-items: center; gap: 8px; padding: 12px 14px;',
      '  border-bottom: 1px solid var(--kos-line-soft); }',
      '.kos-drawer-head strong { flex: 1; font-size: 14px; font-weight: 600; color: var(--kos-ink-1); }',
      '.kos-drawer-head .kos-icon-btn { background: var(--kos-line-soft); color: var(--kos-ink-2); }',
      '.kos-drawer-body { flex: 1; overflow-y: auto; padding: 12px 14px; font-size: 13px;',
      '  color: var(--kos-ink-2); line-height: 1.75; }',
      '.kos-drawer-body dl { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; margin: 0 0 12px; }',
      '.kos-drawer-body dt { color: var(--kos-ink-4); }',
      '.kos-drawer-body dd { margin: 0; color: var(--kos-ink-2); word-break: break-word; }',
      '.kos-drawer-body blockquote { margin: 0; padding: 8px 0 8px 12px;',
      '  border-left: 3px solid var(--kos-line); color: var(--kos-ink-2); }',
      '.kos-drawer-body .kos-grid4 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 12px; }',
      '.kos-drawer-body .kos-grid4 small { display: block; font-size: 11px; color: var(--kos-ink-4); }',
      '.kos-drawer-body .kos-grid4 span { display: block; font-size: 12.5px; color: var(--kos-ink-1);',
      '  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }',
      '.kos-section-title { margin: 0 0 6px; font-size: 12px; font-weight: 600; color: var(--kos-ink-3); }',
      '.kos-content { font-size: 12.5px; line-height: 1.8; color: var(--kos-ink-2);',
      '  white-space: pre-wrap; word-break: break-word; }',
      '.kos-content mark { background: #fef08a; color: inherit; padding: 0 1px; border-radius: 2px; }',
      '.kos-quote { margin: 0; padding: 8px 0 8px 12px; border-left: 3px solid var(--kos-line);',
      '  color: var(--kos-ink-2); font-size: 12.5px; line-height: 1.75; }',
      '.kos-placeholder { padding: 14px; font-size: 12.5px; color: var(--kos-ink-4); text-align: center; }',
      '@media (max-width: 480px) {',
      '  .kos-panel { width: 100vw; height: 100vh; max-height: 100vh; bottom: 0; left: 0; right: 0; border-radius: 0; }',
      '}'
    ].join('\n'));
    return style;
  };

  Widget.prototype.buildMarkup = function () {
    var host = doc.createElement('div');
    host.className = 'kos-host';
    // data-primary 走外部变量入口，让外部应用可以在 :root 进一步覆盖（fallback 链保留默认色）
    if (this.options.primary) {
      host.style.setProperty('--kos-external-primary', this.options.primary);
    }
    host.innerHTML = [
      '<button class="kos-launcher" type="button" aria-label="打开' + escapeHtml(this.options.title) + '">💬</button>',
      '<div class="kos-panel" role="dialog" aria-label="' + escapeHtml(this.options.title) + '">',
      '  <div class="kos-header">',
      '    <span class="kos-brand">💬</span>',
      '    <span class="kos-titles"><strong>' + escapeHtml(this.options.title) + '</strong>',
      '      <small>' + escapeHtml(this.options.subtitle) + '</small></span>',
      '    <button class="kos-icon-btn" type="button" data-role="clear" title="清空会话" aria-label="清空会话">⟲</button>',
      '    <button class="kos-icon-btn" type="button" data-role="close" title="收起" aria-label="收起">✕</button>',
      '  </div>',
      '  <div class="kos-body" data-role="body"></div>',
      '  <div class="kos-footer">',
      '    <div class="kos-composer">',
      '      <textarea data-role="input" rows="1" placeholder="' + escapeHtml(this.options.placeholder) + '"></textarea>',
      '      <button class="kos-send" type="button" data-role="send">发送</button>',
      '    </div>',
      '    <p class="kos-footnote">回车发送 · Shift + 回车换行 · 回答附引用来源</p>',
      '  </div>',
      '  <div class="kos-drawer" data-role="drawer">',
      '    <div class="kos-drawer-head">',
      '      <button class="kos-icon-btn" type="button" data-role="drawer-back" aria-label="返回">←</button>',
      '      <strong>证据原文</strong>',
      '      <span class="md-badge" data-role="drawer-status" style="display:none"></span>',
      '    </div>',
      '    <div class="kos-drawer-body" data-role="drawer-body"></div>',
      '  </div>',
      '</div>'
    ].join('');
    return host;
  };

  Widget.prototype.bind = function () {
    var self = this;
    var shadow = this.shadow;
    this.launcher = shadow.querySelector('.kos-launcher');
    this.panel = shadow.querySelector('.kos-panel');
    this.bodyEl = shadow.querySelector('[data-role="body"]');
    this.input = shadow.querySelector('[data-role="input"]');
    this.sendBtn = shadow.querySelector('[data-role="send"]');
    this.drawer = shadow.querySelector('[data-role="drawer"]');
    this.drawerBody = shadow.querySelector('[data-role="drawer-body"]');
    this.drawerStatus = shadow.querySelector('[data-role="drawer-status"]');

    this.launcher.addEventListener('click', function () {
      self.toggle();
    });
    shadow.querySelector('[data-role="close"]').addEventListener('click', function () {
      self.toggle(false);
    });
    shadow.querySelector('[data-role="clear"]').addEventListener('click', function () {
      self.clear();
    });
    this.sendBtn.addEventListener('click', function () {
      if (self.busy) {
        self.stop();
      } else {
        self.submit();
      }
    });
    this.input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        self.submit();
      }
    });
    this.input.addEventListener('input', function () {
      self.input.style.height = 'auto';
      self.input.style.height = Math.min(self.input.scrollHeight, 120) + 'px';
    });
    shadow.querySelector('[data-role="drawer-back"]').addEventListener('click', function () {
      self.closeDrawer();
    });
    // 答案区与引用区都是流式重建的，用委托避免每次重渲染都要重新挂监听。
    this.bodyEl.addEventListener('click', function (event) {
      self.onBodyClick(event);
    });
    doc.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && self.open) {
        if (self.drawer.classList.contains('kos-open')) {
          self.closeDrawer();
        } else {
          self.toggle(false);
        }
      }
    });
    this.renderWelcome();
  };

  Widget.prototype.toggle = function (force) {
    this.open = force === undefined ? !this.open : force;
    this.panel.classList.toggle('kos-open', this.open);
    if (this.open) {
      this.input.focus();
    }
  };

  Widget.prototype.clear = function () {
    if (this.busy) {
      this.stop();
    }
    this.bodyEl.innerHTML = '';
    this.renderWelcome();
  };

  Widget.prototype.renderWelcome = function () {
    if (this.options.welcome) {
      var item = doc.createElement('div');
      item.className = 'kos-msg kos-bot';
      item.innerHTML = '<div class="kos-card kos-welcome"><span class="kos-brand-mark">💬</span>' +
        '<p>' + escapeHtml(this.options.welcome) + '</p></div>';
      this.bodyEl.appendChild(item);
    }
    if (this.options.sampleQuestions.length) {
      this.renderSamples();
    }
    this.bodyEl.scrollTop = this.bodyEl.scrollHeight;
  };

  Widget.prototype.renderSamples = function () {
    var self = this;
    var box = doc.createElement('div');
    box.className = 'kos-samples';
    this.options.sampleQuestions.forEach(function (question) {
      var button = doc.createElement('button');
      button.type = 'button';
      button.textContent = question;
      button.addEventListener('click', function () {
        self.input.value = question;
        self.submit();
      });
      box.appendChild(button);
    });
    this.bodyEl.appendChild(box);
  };

  Widget.prototype.scrollToEnd = function () {
    this.bodyEl.scrollTop = this.bodyEl.scrollHeight;
  };

  /** 结论来源、置信度、耗时、通道：一句话说清这个答案是怎么来的。 */
  /** 结论来源、置信度、耗时、通道：一句话说清这个答案是怎么来的。 */
  Widget.prototype.renderMeta = function (card, payload) {
    if (!payload) {
      return;
    }
    if (card && card.metaEl) {
      var chips = [];
      var conclusion = payload.conclusionSource || {};
      var conclusionType = String(conclusion.type || '');
      if (conclusionType) {
        var tone = conclusionType === 'GRAPH_TRAVERSAL' ? 'info' : (conclusionType === 'DERIVED' ? 'warn' : 'ok');
        chips.push('<span class="md-badge kos-tone-' + tone + '" title="' +
          escapeHtml(CONCLUSION_HINT[conclusionType] || '') + '">结论来源 · ' +
          escapeHtml(conclusion.label || conclusionType) + '</span>');
      }
      if (payload.questionTypeLabel) {
        chips.push('<span class="md-badge">' + escapeHtml(payload.questionTypeLabel) + '</span>');
      }
      if (typeof payload.confidence === 'number' && payload.confidence > 0) {
        chips.push('<span class="md-badge" title="检索强度与断言支撑的综合估计">置信度 ' +
          Math.round(payload.confidence * 100) + '%</span>');
      }
      var channels = payload.channels || payload.strategies || [];
      if (channels.length) {
        chips.push('<span class="md-badge" title="召回通道">通道 ' + escapeHtml(channels.join(' + ')) + '</span>');
      }
      if (payload.reranked) {
        chips.push('<span class="md-badge md-tone-ok">已重排序</span>');
      }
      var gate = payload.answerQualification && payload.answerQualification.gateStatus;
      if (gate && gate !== 'BYPASSED') {
        var gateTone = gate === 'PASS' ? 'ok' : (gate === 'PARTIAL' ? 'warn' : 'danger');
        chips.push('<span class="md-badge kos-tone-' + gateTone + '">门控 ' +
          escapeHtml(GATE_LABEL[gate] || gate) + '</span>');
      }
      var duration = payload.durationMs || payload.elapsedMs;
      if (duration) {
        chips.push('<span class="md-badge">' + formatDuration(duration) + '</span>');
      }
      if (payload.model) {
        chips.push('<span class="md-badge">' + escapeHtml(payload.model) + '</span>');
      }
      if (chips.length) {
        card.metaEl.innerHTML = chips.join('');
        card.metaEl.style.display = '';
      }
    }
  };

  /**
   * 查询策略：答案旁的胶囊只给结论，这里给出过程——问题怎么被改写、走了哪些通道、
   * 有没有重排、门控怎么判的、结论从哪来。可追溯的答案是"能解释"的，不只是"有出处"。
   */

  /** 单张引用卡：序号 + 标题 + 属性 chips + 相关度 + 摘录 + 属性明细 + 操作。 */

  /** chip 悬浮显示的元数据：出处 / 页 / 通道 / 相关度，与控制台 chip title 一致。 */
  function citationTitle(citation) {
    var parts = [citation.sourceName || '—'];
    if (citation.pageNo) parts.push('P' + citation.pageNo);
    if (citation.channel) parts.push(citation.channel);
    if (typeof citation.score === 'number') parts.push(citation.score.toFixed(3));
    return parts.join(' · ');
  }

  /** 单条引用的属性表：服务控制台 .answer-citations 那种「属性 + 摘录」结构。 */
  function citationDetail(citation) {
    var rows = [
      ['文档', pick(citation, ['sourceName', 'documentTitle'])],
      ['页码', pick(citation, ['pageNo', 'page'])],
      ['章节', pick(citation, ['sectionPath', 'section', 'sectionTitle'])],
      ['通道', pick(citation, ['channel'])],
      ['相关度', typeof citation.score === 'number' ? citation.score.toFixed(3) : ''],
      ['权威度', pick(citation, ['authority', 'authorityLevel'])],
      ['时效', pick(citation, ['freshness', 'effectiveStatus', 'status'])],
      ['证据号', pick(citation, ['evidenceCode'])],
      ['内容单元', pick(citation, ['contentUnitCode', 'contentUnitId', 'objectId', 'id'])],
      ['对象类型', pick(citation, ['objectType'])]
    ];
    var dl = rows
      .filter(function (row) { return row[1] !== '' && row[1] != null; })
      .map(function (row) { return '<dt>' + escapeHtml(row[0]) + '</dt><dd>' + escapeHtml(String(row[1])) + '</dd>'; })
      .join('');
    var excerpt = pick(citation, ['excerpt', 'content', 'quoteText', 'text']);
    var excerptHtml = excerpt
      ? '<blockquote class="md-cite-excerpt">' + escapeHtml(String(excerpt)) + '</blockquote>'
      : '';
    // 证据原文入口：只有拿到证据号才给（否则抽屉无从查询）
    var evidenceCode = pick(citation, ['evidenceCode', 'evidenceId']);
    var evidenceHtml = evidenceCode
      ? '<button type="button" class="md-action md-cite-evidence" data-evidence="' + escapeHtml(String(evidenceCode)) +
        '" data-cite-index="' + escapeHtml(String(citation.index != null ? citation.index : '')) + '">查看证据原文 →</button>'
      : '';
    return (dl ? '<dl>' + dl + '</dl>' : '') + excerptHtml + evidenceHtml;
  }

  /** 引用来源：chips 入口 + 选中态高亮 + 下方内联详情区，与控制台 open-citation 抽屉同意图。 */
  Widget.prototype.renderCitations = function (card, list) {
    if (!card || !card.citeEl || !this.options.showCitations) {
      return;
    }
    var items = (list || []).filter(function (item) { return item && typeof item === 'object'; });
    var box = card.citeEl;
    card.citations = items;
    if (!items.length) {
      box.style.display = 'none';
      box.innerHTML = '';
      return;
    }
    var chipsHtml = items.map(function (citation) {
      var idx = citation.index != null ? citation.index : 0;
      var title = pick(citation, ['title', 'sourceName', 'documentTitle', 'objectId']) || '证据';
      return '<button type="button" class="md-cite-chip" data-act="cite-show" data-cite-index="' +
        escapeHtml(String(idx)) + '" title="' + escapeHtml(citationTitle(citation)) + '">[' +
        escapeHtml(String(idx)) + '] ' + escapeHtml(title) + '</button>';
    }).join('');
    box.innerHTML =
      '<p class="md-cites-head">引用来源 · ' + items.length + ' 条</p>' +
      '<div class="md-cite-list">' + chipsHtml + '</div>' +
      '<div class="md-cite-detail" hidden></div>';
    box.style.display = '';
  };

  Widget.prototype.onBodyClick = function (event) {
    var target = event.target;
    var actionBtn = closest(target, '[data-act]');
    if (actionBtn) {
      var act = actionBtn.getAttribute('data-act');
      if (act === 'copy' || act === 'copy-md' || act === 'copy-html' || act === 'copy-cite') {
        this.copyText(actionBtn.getAttribute('data-copy'), actionBtn);
        return;
      }
      if (act === 'copy-code') {
        var block = closest(actionBtn, '.md-codeblock');
        var pre = block ? block.querySelector('.md-pre') : null;
        if (pre) {
          this.copyText(pre.textContent, actionBtn);
        }
        return;
      }
      if (act === 'cite-show') {
        this.showCitationDetail(actionBtn);
        return;
      }
      if (act === 'cite-close') {
        this.hideCitationDetail(actionBtn);
        return;
      }
    }
    var evidenceBtn = closest(target, '[data-evidence]');
    if (evidenceBtn) {
      this.openEvidence(evidenceBtn.getAttribute('data-evidence'), evidenceBtn.getAttribute('data-cite-index'));
      return;
    }
  };

  /** 点 chip：把当前选中的引用展开到引用区下方；同 chip 再点收起。 */
  Widget.prototype.showCitationDetail = function (chip) {
    var card = this.findCard(chip);
    if (!card) return;
    var idx = chip.getAttribute('data-cite-index');
    var citation = (card.citations || []).find(function (item) {
      return String(item.index) === String(idx);
    });
    if (!citation) return;
    var box = card.citeEl.querySelector('.md-cite-detail');
    if (!box) return;
    var alreadyShown = chip.classList.contains('md-selected') && !box.hidden;
    card.citeEl.querySelectorAll('.md-cite-chip').forEach(function (n) { n.classList.remove('md-selected'); });
    if (alreadyShown) {
      box.hidden = true;
      box.innerHTML = '';
      return;
    }
    chip.classList.add('md-selected');
    var title = pick(citation, ['title', 'sourceName', 'documentTitle', 'objectId']) || '证据';
    box.innerHTML =
      '<div class="md-cite-detail-head">' +
      '<span class="md-cite-idx">' + escapeHtml(String(citation.index != null ? citation.index : '·')) + '</span>' +
      '<span class="md-cite-title">' + escapeHtml(title) + '</span>' +
      '<button type="button" class="md-cite-close" data-act="cite-close" aria-label="关闭">×</button>' +
      '</div>' + citationDetail(citation);
    box.hidden = false;
  };

  Widget.prototype.hideCitationDetail = function (btn) {
    var card = this.findCard(btn);
    if (!card) return;
    var box = card.citeEl.querySelector('.md-cite-detail');
    if (box) { box.hidden = true; box.innerHTML = ''; }
    card.citeEl.querySelectorAll('.md-cite-chip').forEach(function (n) { n.classList.remove('md-selected'); });
  };

  Widget.prototype.findCard = function (node) {
    var host = closest(node, '.kos-msg');
    return host && host.kosCard;
  };

  /** 点上标 → 滚动到对应引用卡并短暂高亮：答案里的“依据”要能一步看到出处。 */

  Widget.prototype.copyText = function (text, button) {
    var done = function () {
      if (!button) {
        return;
      }
      var old = button.textContent;
      button.textContent = '已复制';
      setTimeout(function () {
        button.textContent = old;
      }, 1400);
    };
    if (root.navigator && root.navigator.clipboard && root.navigator.clipboard.writeText) {
      root.navigator.clipboard.writeText(text || '').then(done, done);
      return;
    }
    try {
      var area = doc.createElement('textarea');
      area.value = text || '';
      doc.body.appendChild(area);
      area.select();
      doc.execCommand('copy');
      doc.body.removeChild(area);
      done();
    } catch (error) {
      done();
    }
  };

  Widget.prototype.authHeaders = function () {
    var headers = {};
    if (this.options.token) {
      headers['X-Embed-Token'] = this.options.token;
    } else if (this.options.apiKey) {
      headers['X-API-Key'] = this.options.apiKey;
    }
    return headers;
  };

  /** 证据抽屉：优先走外部 /evidence/{code} 拿权威原文，拿不到就退回引用里带的摘录。 */
  Widget.prototype.openEvidence = function (code, citeIndex) {
    var self = this;
    // 兜底摘录：从当前选中 chip 反查 card 上的引用数据（证据接口不可读时用它）
    var fallbackQuote = '';
    var active = this.shadow.querySelector('.md-cite-chip.md-selected');
    if (active) {
      var card = this.findCard(active);
      var citations = (card && card.citations) || [];
      var hit = citations.filter(function (item) {
        return String(item.index) === String(citeIndex);
      })[0];
      if (hit) {
        fallbackQuote = hit.excerpt || hit.quote || hit.snippet || '';
      }
    }
    this.drawerStatus.style.display = 'none';
    this.drawerBody.innerHTML = '<div class="kos-placeholder">正在定位原始证据…</div>';
    this.drawer.classList.add('kos-open');

    var url = resolveEvidenceUrl(this.options.apiBase, code, this.options.knowledgeBaseId);
    fetch(url, { method: 'GET', headers: this.authHeaders() })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        return response.json();
      })
      .then(function (json) {
        self.renderEvidence(json && json.data ? json.data : json, fallbackQuote);
      })
      .catch(function (error) {
        self.renderEvidence(null, fallbackQuote, error && error.message ? error.message : '');
      });
  };

  Widget.prototype.closeDrawer = function () {
    this.drawer.classList.remove('kos-open');
  };

  /**
   * 证据详情的字段在不同接口里落在不同层级（object / evidence[] / 扁平），
   * 这里统一按“先 object、再 evidence[0]、最后顶层”的顺序取，缺什么就不显示什么。
   */
  Widget.prototype.renderEvidence = function (detail, fallbackQuote, errorText) {
    var object = null;
    if (detail) {
      if (detail.object && typeof detail.object === 'object') {
        object = detail.object;
      } else if (detail.evidence && detail.evidence.length && typeof detail.evidence[0] === 'object') {
        object = detail.evidence[0];
      } else {
        object = detail;
      }
    }
    if (!object || !Object.keys(object).length) {
      this.drawerBody.innerHTML = '<div class="kos-card kos-welcome">' +
        '<p>' + escapeHtml(errorText ? '证据原文暂不可读（' + errorText + '）' : '未找到证据原文') + '</p>' +
        (fallbackQuote ? '<blockquote class="kos-quote" style="margin-top:8px">' + escapeHtml(fallbackQuote) + '</blockquote>' : '') +
        '</div>';
      return;
    }
    var sourceName = pick(object, ['sourceName', 'sourceCode', 'sourceUri']);
    var documentTitle = pick(object, ['documentTitle', 'documentName', 'title']);
    var pageNo = pick(object, ['pageNo']);
    var unitCode = pick(object, ['contentUnitCode', 'contentUnitId', 'unitCode']);
    var headingPath = pick(object, ['headingPath', 'sectionPath', 'heading']);
    var content = pick(object, ['content', 'text', 'rawText']);
    var quoteText = pick(object, ['quoteText', 'excerpt']) || fallbackQuote || '';
    var authority = pick(object, ['authorityScore']);
    var freshness = pick(object, ['freshnessScore']);
    var hash = pick(object, ['contentHash', 'evidenceHash']);
    var status = pick(object, ['evidenceStatus', 'status']);

    if (status) {
      this.drawerStatus.style.display = '';
      this.drawerStatus.textContent = String(status);
    }

    var html = '<section class="kos-card" style="margin-bottom:12px"><div class="kos-grid4">' +
      '<div><small>来源</small><span title="' + escapeHtml(sourceName) + '">' + escapeHtml(sourceName || '—') + '</span></div>' +
      '<div><small>文档</small><span title="' + escapeHtml(documentTitle) + '">' + escapeHtml(documentTitle || '—') + '</span></div>' +
      '<div><small>页码</small><span>' + escapeHtml(pageNo ? 'P' + pageNo : '—') + '</span></div>' +
      '<div><small>内容单元</small><span title="' + escapeHtml(unitCode) + '">' + escapeHtml(unitCode || '—') + '</span></div>' +
      '</div>' +
      (headingPath ? '<div class="md-badge md-tone-info" style="margin-top:10px">章节：' + escapeHtml(headingPath) + '</div>' : '') +
      '</section>';

    if (content) {
      html += '<section class="kos-card" style="margin-bottom:12px;padding-bottom:12px">' +
        '<p class="kos-section-title">内容单元原文</p>' +
        '<div class="kos-content">' + this.highlightQuote(content, quoteText) + '</div></section>';
    }
    if (quoteText) {
      html += '<section class="kos-card" style="margin-bottom:12px"><p class="kos-section-title">引用片段</p>' +
        '<blockquote class="kos-quote">' + escapeHtml(quoteText) + '</blockquote></section>';
    }
    if (!content && !quoteText) {
      html += '<div class="kos-placeholder">该证据未返回原文内容</div>';
    }
    var badges = '';
    if (authority !== '') {
      badges += '<span class="md-badge">Authority ' + escapeHtml(Number(authority).toFixed(2)) + '</span>';
    }
    if (freshness !== '') {
      badges += '<span class="md-badge">Freshness ' + escapeHtml(Number(freshness).toFixed(2)) + '</span>';
    }
    if (hash) {
      badges += '<span class="md-badge">HASH ' + escapeHtml(String(hash).slice(0, 12)) + '</span>';
    }
    if (badges) {
      html += '<div style="display:flex;flex-wrap:wrap;gap:6px">' + badges + '</div>';
    }
    this.drawerBody.innerHTML = html;
  };

  /** 原文里高亮被引用的一段：谁都能看到“这句话确实出自这里”。 */
  Widget.prototype.highlightQuote = function (content, quote) {
    var body = escapeHtml(String(content || ''));
    var needle = escapeHtml(String(quote || '')).trim();
    if (!needle) {
      return body;
    }
    var at = body.indexOf(needle);
    if (at < 0) {
      return body;
    }
    return body.slice(0, at) + '<mark>' + needle + '</mark>' + body.slice(at + needle.length);
  };

  Widget.prototype.setComposerBusy = function (busy) {
    this.sendBtn.textContent = busy ? '停止' : '发送';
    this.sendBtn.classList.toggle('kos-stop', busy);
  };

  Widget.prototype.stop = function () {
    if (this.controller) {
      try {
        this.controller.abort();
      } catch (error) {
        // 已结束的流再 abort 没有意义
      }
    }
  };

  Widget.prototype.submit = function () {
    var question = (this.input.value || '').trim();
    if (!question || this.busy) {
      return;
    }
    this.input.value = '';
    this.input.style.height = 'auto';
    var item = doc.createElement('div');
    item.className = 'kos-msg kos-user';
    var bubble = doc.createElement('div');
    bubble.className = 'kos-bubble';
    bubble.textContent = question;
    item.appendChild(bubble);
    this.bodyEl.appendChild(item);
    this.scrollToEnd();
    this.ask(question);
  };

  /** 一张助手卡片容纳：检索过程 / 答案 / 元信息 / 引用来源 / 操作。 */
  /* ---- 空结果：没有可验证内容时，说清发生了什么，而不是给一段空文本 ---- */
  var NO_ANSWER_TEXT = '现有知识不足以形成全部可验证的结论；未通过证据完整性与忠实度校验的内容已阻止输出。';
  var NO_ANSWER_PREFIX = '现有知识不足以';

  /** 三种情况都算"没有可展示的知识内容"：空答案、服务端的弃答话术、门控判定为阻断。 */
  function isNoAnswer(payload, answer) {
    var text = String(answer == null ? '' : answer).trim();
    if (!text) {
      return true;
    }
    if (text.indexOf(NO_ANSWER_PREFIX) === 0) {
      return true;
    }
    var qualification = payload && payload.answerQualification ? payload.answerQualification : null;
    var gate = qualification ? String(qualification.gateStatus || '') : '';
    return gate === 'BLOCKED' || gate === 'FAIL';
  }

  /** 默认视图跟着服务端返回格式走：要 HTML 就给 HTML，其余一律 Markdown。 */

  /** 渲染答案正文：HTML 源半截先降级为纯文本，done 时再整段落成富文本。 */
  Widget.prototype.renderAnswer = function (card, streaming) {
    var answer = card.answer || '';
    var html;
    if (card.answerFormat === 'html') {
      html = streaming
        ? '<pre class="md-source">' + escapeHtml(answer.replace(/<[^>]*>/g, '')) + '</pre>'
        : sanitizeHtml(answer, refMapOf(card.citations));
    } else if (card.answerFormat === 'text') {
      html = '<pre class="md-text">' + escapeHtml(answer) + '</pre>';
    } else {
      html = renderMarkdown(answer, card.citations);
    }
    card.answerBody.innerHTML = html + (streaming ? '<span class="md-caret"></span>' : '');
  };

  /** 空结果卡：给出结论性说明 + 这次检索到底发生了什么 + 下一步建议。 */
  Widget.prototype.renderNoAnswer = function (card, payload) {
    var answer = String(card.answer || '').trim();
    var headline = answer.indexOf(NO_ANSWER_PREFIX) === 0 ? answer : NO_ANSWER_TEXT;
    var facts = [];
    var channels = (payload && (payload.channels || payload.strategies)) || [];
    if (channels.length) {
      facts.push('已检索 ' + channels.length + ' 个通道，未取得可验证的证据');
    }
    if (payload && payload.contextChars === 0) {
      facts.push('进入生成的上下文为空');
    }
    var qualification = (payload && payload.answerQualification) || {};
    var gate = String(qualification.gateStatus || '');
    if (gate && gate !== 'BYPASSED') {
      facts.push('质量门控：' + (GATE_LABEL[gate] || gate));
    }
    var reasons = []
      .concat(qualification.reason ? [qualification.reason] : [])
      .concat(qualification.decisionReasons || [])
      .concat(Object.keys(qualification.blockReasons || {}))
      .map(function (key) {
        return GATE_REASON_LABEL[key] || key;
      })
      .filter(function (label, index, list) {
        return label && list.indexOf(label) === index;
      });
    if (reasons.length) {
      facts.push('阻断原因：' + reasons.join('；'));
    }
    if (payload && typeof payload.confidence === 'number' && payload.confidence > 0) {
      facts.push('置信度 ' + Math.round(payload.confidence * 100) + '%');
    }
    var duration = payload && (payload.durationMs || payload.elapsedMs);
    if (duration) {
      facts.push('耗时 ' + formatDuration(duration));
    }
    card.answerBody.innerHTML =
      '<div class="md-empty">' +
      '<p class="md-empty-title">' + escapeHtml(headline) + '</p>' +
      (facts.length
        ? '<ul class="md-empty-facts">' + facts.map(function (fact) {
          return '<li>' + escapeHtml(fact) + '</li>';
        }).join('') + '</ul>'
        : '') +
      '<p class="md-empty-hint">可尝试缩小问题范围，或换用更贴近文档表述的措辞。</p>' +
      '</div>';
  };

  /** 操作区：同一份答案按三种形态复制，别人要什么格式就给什么格式。 */
  Widget.prototype.renderActions = function (card, stopped) {
    if (!card.answer) {
      return;
    }
    var source = card.answer;
    var plain = stripReferenceSection(source.replace(/<[^>]*>/g, ''));
    var markdown = card.answerFormat === 'html'
      ? stripReferenceSection(source.replace(/<[^>]*>/g, ''))
      : stripReferenceSection(source);
    var html = card.answerFormat === 'html'
      ? sanitizeHtml(source, refMapOf(card.citations))
      : renderMarkdown(stripReferenceSection(source), card.citations);
    card.actionsEl.style.display = '';
    card.actionsEl.innerHTML =
      '<button class="md-action" type="button" data-act="copy" data-copy="' + escapeHtml(plain) + '">复制纯文本</button>' +
      '<button class="md-action" type="button" data-act="copy-md" data-copy="' + escapeHtml(markdown) + '">复制 Markdown</button>' +
      '<button class="md-action" type="button" data-act="copy-html" data-copy="' + escapeHtml(html) + '">复制 HTML</button>';
  };

  /** 一张助手卡片：检索过程 → 答案（上：正文 / 下：引用来源）→ 元信息 → 操作。 */
  Widget.prototype.createAssistantCard = function () {
    var item = doc.createElement('div');
    item.className = 'kos-msg kos-bot';
    var card = doc.createElement('div');
    card.className = 'kos-card';
    var answerEl = doc.createElement('div');
    answerEl.className = 'kos-answer md-answer';
    var answerBody = doc.createElement('div');
    answerBody.className = 'md-answer-body';
    answerEl.appendChild(answerBody);
    var metaEl = doc.createElement('div');
    metaEl.className = 'kos-meta md-meta';
    metaEl.style.display = 'none';
    var citeEl = doc.createElement('div');
    citeEl.className = 'kos-cites md-cites';
    citeEl.style.display = 'none';
    var actionsEl = doc.createElement('div');
    actionsEl.className = 'kos-actions md-actions';
    actionsEl.style.display = 'none';
    card.appendChild(answerEl);
    card.appendChild(metaEl);
    card.appendChild(citeEl);
    card.appendChild(actionsEl);
    item.appendChild(card);
    this.bodyEl.appendChild(item);
    var handle = {
      item: item, card: card, answerEl: answerEl, answerBody: answerBody,
      metaEl: metaEl, citeEl: citeEl, actionsEl: actionsEl,
      question: '', answer: '', answerFormat: 'markdown', citations: []
    };
    item.kosCard = handle;
    return handle;
  };

  Widget.prototype.ask = function (question) {
    var self = this;
    this.busy = true;
    this.setComposerBusy(true);

    var card = this.createAssistantCard();
    card.question = question;
    var answer = '';
    var answerFormat = this.options.answerFormat;
    card.answerFormat = answerFormat;
    var citations = [];
    var traceEl = null;
    var stageEls = {};
    var renderHandle = null;
    var finished = false;

    card.answerBody.innerHTML = '<span class="md-typing">正在检索知识库…</span>';

    /** 每个 delta 都整段重渲染会让面板卡住，合并到下一帧再刷。 */
    var paint = function () {
      // 流已结束还留着一次待执行的重绘，它会用未加工的原文盖掉 finish 的结果
      // （引用章节没摘掉、光标也留在末尾），所以结束后一律不再排帧。
      if (finished || renderHandle) {
        return;
      }
      renderHandle = root.requestAnimationFrame(function () {
        renderHandle = null;
        card.answer = answer;
        card.citations = citations;
        self.renderAnswer(card, true);
        self.scrollToEnd();
      });
    };

    var renderTrace = function (stages) {
      if (!self.options.showTrace) {
        return;
      }
      traceEl = doc.createElement('details');
      traceEl.className = 'kos-trace';
      traceEl.open = true;
      var summary = doc.createElement('summary');
      summary.textContent = '检索过程';
      traceEl.appendChild(summary);
      var list = doc.createElement('div');
      list.className = 'kos-stages';
      stages.forEach(function (stage) {
        var row = doc.createElement('div');
        row.className = 'kos-stage';
        row.innerHTML = '<span class="kos-dot"></span><span>' + escapeHtml(stage.label || stage.key) + '</span>' +
          '<span class="kos-latency"></span>';
        list.appendChild(row);
        stageEls[stage.key] = row;
      });
      traceEl.appendChild(list);
      card.card.insertBefore(traceEl, card.answerEl);
    };

    var updateStage = function (payload) {
      if (!traceEl) {
        return;
      }
      var row = stageEls[payload.key];
      if (!row) {
        return;
      }
      row.classList.remove('kos-running');
      row.classList.add(payload.status === 'done' ? 'kos-done' : 'kos-running');
      if (payload.status === 'done') {
        var latency = row.querySelector('.kos-latency');
        if (latency && payload.latencyMs !== undefined && payload.latencyMs !== null) {
          latency.textContent = payload.latencyMs + ' ms';
        }
        if (payload.detail) {
          row.title = String(payload.detail);
        }
      }
    };

    var finish = function (stopped) {
      finished = true;
      if (renderHandle && root.cancelAnimationFrame) {
        root.cancelAnimationFrame(renderHandle);
        renderHandle = null;
      }
      self.busy = false;
      self.setComposerBusy(false);
      if (traceEl) {
        traceEl.open = false;
        var summary = traceEl.querySelector('summary');
        if (summary) {
          var duration = Number(lastPayload && (lastPayload.durationMs || lastPayload.elapsedMs) || 0);
          summary.textContent = duration ? '检索过程 · ' + formatDuration(duration) : '检索过程';
        }
      }
      card.answer = answer;
      card.citations = citations;
      if (isNoAnswer(lastPayload, answer)) {
        // 没有可验证内容时给结论性说明，引用区与格式切换都不再出现。
        self.renderNoAnswer(card, lastPayload || {});
      } else {

        self.renderAnswer(card, false);
        self.renderCitations(card, citations);
        self.renderActions(card);
      }
      self.scrollToEnd();
    };

    var fail = function (message) {
      finished = true;
      if (renderHandle && root.cancelAnimationFrame) {
        root.cancelAnimationFrame(renderHandle);
        renderHandle = null;
      }
      card.answerBody.innerHTML = '<span class="md-error">' + escapeHtml(message) + '</span>';
      self.busy = false;
      self.setComposerBusy(false);
      self.scrollToEnd();
    };

    var lastPayload = null;

    var url = resolveStreamUrl(this.options.apiBase) +
      '?question=' + encodeURIComponent(question) +
      '&topK=' + encodeURIComponent(this.options.topK) +
      '&answerFormat=' + encodeURIComponent(this.options.answerFormat) +
      '&content=' + encodeURIComponent(this.options.content) +
      /* 只有显式配了才带：不传即不过滤，与历史一致 */
      (this.options.minScore !== '' && this.options.minScore !== null && this.options.minScore !== undefined
        ? '&minScore=' + encodeURIComponent(this.options.minScore) : '') +
      (this.options.knowledgeBaseId ? '&knowledgeBaseId=' + encodeURIComponent(this.options.knowledgeBaseId) : '');

    this.controller = typeof AbortController === 'function' ? new AbortController() : null;

    fetch(url, { method: 'GET', headers: this.authHeaders(), signal: this.controller ? this.controller.signal : undefined })
      .then(function (response) {
        if (response.status === 401 || response.status === 403) {
          fail('凭证无效或已过期（HTTP ' + response.status + '），请重新签发嵌入令牌。');
          return null;
        }
        if (!response.ok || !response.body) {
          fail('服务返回异常：HTTP ' + response.status);
          return null;
        }
        var reader = response.body.getReader();
        var decoder = new TextDecoder('utf-8');
        var push = createSseParser(function (name, payload) {
          if (name === 'open') {
            renderTrace(Array.isArray(payload.stages) ? payload.stages : STAGE_META);
            // 服务端实际生效的交付方式：未识别时退回 stream，组件自己的渲染依赖这个值，悄悄退回排版会变
            if (payload.content && payload.content !== self.options.content && root.console && root.console.warn) {
              root.console.warn('[KnowledgeOSChat] content 退回：请求 ' + self.options.content +
                '，实际 ' + payload.content + '（取值未识别会退回 stream）');
            }
          } else if (name === 'stage') {
            updateStage(payload);
          } else if (name === 'delta') {
            // content=final：SSE 只负责检索过程；切片是未加工的模型原文，边收边渲染会和控制台不一样
            if (self.options.content !== 'final') {
              answer += typeof payload === 'string' ? payload : (payload.text || '');
              paint();
            }
          } else if (name === 'citation') {
            citations = payload.citations || [];
            card.citations = citations;
            self.renderCitations(card, citations);
          } else if (name === 'done') {
            lastPayload = payload || {};
            if (lastPayload.answerFormat && lastPayload.answerFormat !== answerFormat) {
              /* 服务端实际生效的格式与请求的不一致：取值没被识别时它会退回 default，
                 而 default 没有任何格式加工（引用原文章节、编号上标都不会有），
                 排版自然和控制台不一样。排查「显示不一致」先看这条告警。 */
              if (root.console && root.console.warn) {
                root.console.warn('[KnowledgeOSChat] answerFormat 退回：请求 ' + answerFormat +
                  '，实际 ' + lastPayload.answerFormat + '（取值未识别会退回 default）');
              }
              answerFormat = lastPayload.answerFormat;
            }
            /* 服务端的 answer 才是权威版本：它等于模型原文 + 请求格式的加工
               （markdown 追加「引用原文」章节、text 剥掉标记、html 转成标签）。
               流式 delta 只有模型原文，拿它渲染就会丢掉这层加工 —— 表现为嵌入组件里
               「一整段无结构文本、没有引用原文区」，而控制台读 done 的 answer 所以正常。
               所以这里必须无条件替换，不能只在「没收到任何 delta」时才兜底。 */
            if (lastPayload.answer) {
              answer = String(lastPayload.answer);
            }
            if (!isNoAnswer(lastPayload, answer)) {
              self.renderMeta(card, lastPayload);
            }
            finish(false);
          } else if (name === 'error') {
            fail(typeof payload === 'string' ? payload : (payload.message || '检索失败'));
          }
        });
        var read = function () {
          return reader.read().then(function (result) {
            if (result.done) {
              finish(false);
              return;
            }
            push(decoder.decode(result.value, { stream: true }));
            return read();
          });
        };
        return read();
      })
      .catch(function (error) {
        if (error && error.name === 'AbortError') {
          finish(true);
          return;
        }
        fail('网络或服务异常：' + (error && error.message ? error.message : '未知错误'));
      });
  };

  function init(options) {
    return new Widget(options).mount();
  }

  root.KnowledgeOSChat = {
    version: VERSION,
    init: init,
    stages: STAGE_META
  };

  // 未显式调用 init 时，按 script 上的 data-* 自动挂载
  if (doc.currentScript && doc.currentScript.getAttribute('data-auto') !== 'false') {
    var run = function () {
      if (defaults().apiBase) {
        init({});
      }
    };
    if (doc.readyState === 'loading') {
      doc.addEventListener('DOMContentLoaded', run);
    } else {
      run();
    }
  }
})(window, document);
