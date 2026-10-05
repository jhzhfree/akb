"""Answer delivery formats.

One answer, three shapes callers actually asked for: a chat card wants Markdown, an integration
pasting into a ticket wants plain prose without footnote clutter, and a document export wants
HTML that carries the quoted source text next to the sentence it supports — otherwise the reader
has to resolve ``[证据N]`` somewhere else, or more likely, not at all.

Rendering happens after every gate has run: the evidence and entailment gates score the answer the
model produced, and appending a reference section first would let that section's own words look
like claims the answer made.

Everything here escapes before it emits a tag, so the HTML shape is safe to inject as-is.
"""
import html
import re

DEFAULT = "default"
MARKDOWN = "markdown"
TEXT = "text"
HTML = "html"
FORMATS = (DEFAULT, MARKDOWN, TEXT, HTML)

#: Excerpt length per citation. Long enough to be quotable, short enough that five citations do
#: not bury the answer they are supposed to support.
EXCERPT_CHARS = 240

#: Headings that start a section the plain-text shape must drop.
_EVIDENCE_HEADING = re.compile(r"^\s{0,3}#{1,6}\s*.*(引用|证据|来源|参考|reference|citation)", re.IGNORECASE)
_ANY_HEADING = re.compile(r"^\s{0,3}#{1,6}\s+")
_HEADING_MARK = re.compile(r"^\s{0,3}#{1,6}\s*")
_BULLET = re.compile(r"^\s*([-*+]|\d+[.)])\s+")
_QUOTE_MARK = re.compile(r"^\s*>\s?")
_RULE = re.compile(r"^\s*([-*_])\s*\1\s*\1[\s\-*_]*$")
_CITE_MARK = re.compile(r"\[证据\s*\d+\]|\[\d+\]")


def normalize(value):
    """Coerce a requested format into one of :data:`FORMATS`; anything unrecognised is default.

    Accepting rather than rejecting keeps an old caller (or a typo) on today's behaviour instead
    of turning a working question into a 422.
    """
    raw = str(value or "").strip().lower()
    return raw if raw in FORMATS else DEFAULT


def excerpt(content, limit=EXCERPT_CHARS):
    """One quotable line from a chunk: collapsed whitespace, cut on a character boundary."""
    text = re.sub(r"\s+", " ", str(content or "")).strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "…"


def render(answer, citations, answer_format):
    """Return the answer in the requested shape; ``citations`` carry the quoted source text."""
    fmt = normalize(answer_format)
    text = str(answer or "")
    refs = [c for c in (citations or []) if isinstance(c, dict)]
    if fmt == TEXT:
        return _plain(text)
    if fmt == MARKDOWN:
        return _markdown(text, refs)
    if fmt == HTML:
        return _html(text, refs)
    return text


def _plain(text):
    """Prose only: drop the evidence sections, then strip every Markdown mark."""
    lines = []
    for line in _strip_evidence_sections(text).splitlines():
        line = _HEADING_MARK.sub("", line)
        line = _BULLET.sub("", line)
        line = _QUOTE_MARK.sub("", line)
        line = re.sub(r"\*\*([^*]+)\*\*", r"\1", line)
        line = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"\1", line)
        line = re.sub(r"`([^`]*)`", r"\1", line)
        line = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", line)
        line = _CITE_MARK.sub("", line)
        if _RULE.match(line):
            continue
        lines.append(line.rstrip())
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def _strip_evidence_sections(text):
    """Cut everything from a reference/evidence heading until the next unrelated heading."""
    kept = []
    skipping = False
    for line in text.splitlines():
        if _EVIDENCE_HEADING.match(line):
            skipping = True
            continue
        if skipping and _ANY_HEADING.match(line):
            skipping = False
        if not skipping:
            kept.append(line)
    return "\n".join(kept)


def _markdown(text, refs):
    """Markdown as generated, plus a quoted-source section the answer can be checked against."""
    body = text.strip()
    # 模型自己已经写了引用章节就别再拼一个：两份引用清单比没有更糟。
    if not refs or any(_EVIDENCE_HEADING.match(line) for line in body.splitlines()):
        return body
    blocks = ["", "---", "", "### 引用原文", ""]
    for citation in refs:
        blocks.append("[%s] %s" % (citation.get("index") or "·", citation.get("title") or "证据"))
        source = citation.get("sourceName") or citation.get("sourceUri") or ""
        if source:
            blocks.append("（来源：%s）" % source)
        quote = excerpt(citation.get("excerpt") or citation.get("content") or "")
        if quote:
            blocks.append("> " + quote)
        blocks.append("")
    return body + "\n" + "\n".join(blocks).rstrip() + "\n"


def _html(text, refs):
    """HTML for the answer, with the quoted source text carried underneath it."""
    body = _render_markdown(text)
    if not refs:
        return body
    items = []
    for citation in refs:
        title = html.escape(str(citation.get("title") or "证据"))
        source = html.escape(str(citation.get("sourceName") or citation.get("sourceUri") or "知识库"))
        quote = html.escape(excerpt(citation.get("excerpt") or citation.get("content") or ""))
        block = ['<li class="answer-citation">']
        block.append('<div class="answer-citation-head">[%s] %s<span class="answer-citation-source">%s</span></div>'
                     % (html.escape(str(citation.get("index") or "·")), title, source))
        if quote:
            block.append('<blockquote class="answer-citation-excerpt">%s</blockquote>' % quote)
        block.append("</li>")
        items.append("".join(block))
    return '%s<section class="answer-citations"><h3>引用原文</h3><ol>%s</ol></section>' % (body, "".join(items))


def _inline(text):
    """Escape first, then emit only the inline tags the answer card styles."""
    safe = html.escape(text)
    safe = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", safe)
    safe = re.sub(r"`([^`]+)`", r'<code class="md-code">\1</code>', safe)
    return _CITE_MARK.sub(lambda m: '<sup class="md-cite">%s</sup>' % html.escape(m.group(0)), safe)


def _render_markdown(source):
    """The controlled Markdown subset answers actually use: headings, lists, quotes, code, prose."""
    out = []
    paragraph = []
    items = []
    ordered = False
    quote = []
    code = []
    in_code = False

    def flush_paragraph():
        if paragraph:
            out.append("<p>%s</p>" % "<br/>".join(_inline(line) for line in paragraph))
            paragraph.clear()

    def flush_list():
        if items:
            tag = "ol" if ordered else "ul"
            out.append("<%s>%s</%s>" % (tag, "".join("<li>%s</li>" % _inline(i) for i in items), tag))
            items.clear()

    def flush_quote():
        if quote:
            out.append("<blockquote>%s</blockquote>" % _inline(" ".join(quote)))
            quote.clear()

    for line in (source or "").splitlines():
        if line.lstrip().startswith("```"):
            if in_code:
                out.append("<pre><code>%s</code></pre>" % html.escape("\n".join(code)))
                code.clear()
            in_code = not in_code
            continue
        if in_code:
            code.append(line)
            continue
        stripped = line.strip()
        if not stripped or _RULE.match(line):
            flush_paragraph()
            flush_list()
            flush_quote()
            continue
        heading = _ANY_HEADING.match(line)
        if heading:
            flush_paragraph()
            flush_list()
            flush_quote()
            level = len(line.strip().split(" ", 1)[0])
            tag = "h3" if level <= 2 else "h4"
            out.append('<%s class="md-heading">%s</%s>' % (tag, _inline(_HEADING_MARK.sub("", line).strip()), tag))
            continue
        bullet = _BULLET.match(line)
        if bullet:
            flush_paragraph()
            flush_quote()
            ordered = bool(re.match(r"\d+[.)]", bullet.group(1)))
            items.append(_BULLET.sub("", line).strip())
            continue
        if line.lstrip().startswith(">"):
            flush_paragraph()
            flush_list()
            quote.append(_QUOTE_MARK.sub("", line).strip())
            continue
        flush_list()
        flush_quote()
        paragraph.append(line.strip())
    if in_code and code:
        out.append("<pre><code>%s</code></pre>" % html.escape("\n".join(code)))
    flush_paragraph()
    flush_list()
    flush_quote()
    return "".join(out)
