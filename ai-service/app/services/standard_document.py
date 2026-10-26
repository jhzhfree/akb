"""Canonical source normalization: one standard document model for every input format.

Ingestion used to hand raw file text straight to extraction, so whatever the source
format carried became knowledge: markdown markers (``#``, ``**``, table alignment
rows), HTML leftovers, and PDF running headers all ended up in card titles and claim
subjects. This module introduces the single intermediate representation that sits
between "a file was uploaded" and "knowledge objects exist":

    raw source -> format normalizer -> Block[] -> StandardDocument -> Unit[]

A :class:`Block` is the smallest typed piece of content (heading, paragraph, list
item, table, code, quote) with the heading path it lives under. A ``Unit`` is the
chunk-sized grouping of blocks used for retrieval indexing and card generation.
Nothing that exists only to express layout ever survives this step, so every
downstream consumer—Elasticsearch, the extractor, the UI—sees the same shape no
matter whether the source was a markdown file, a web page, a PDF or a Word document.
"""

import hashlib
import re
from typing import Any

from bs4 import BeautifulSoup
from docx import Document as DocxDocument
from docx.oxml.ns import qn
from docx.table import Table as DocxTable
from docx.text.paragraph import Paragraph as DocxParagraph
from pypdf import PdfReader

from app.core import chunk_policy

# Block types are the vocabulary shared by every normalizer.
BLOCK_HEADING = "HEADING"
BLOCK_PARAGRAPH = "PARAGRAPH"
BLOCK_LIST_ITEM = "LIST_ITEM"
BLOCK_TABLE = "TABLE"
BLOCK_CODE = "CODE"
BLOCK_QUOTE = "QUOTE"

SOURCE_FORMATS = {
    ".md": "MARKDOWN",
    ".markdown": "MARKDOWN",
    ".html": "HTML",
    ".htm": "HTML",
    ".pdf": "PDF",
    ".docx": "DOCX",
    ".txt": "TEXT",
}

_ZERO_WIDTH = re.compile(r"[​‎‏‪-‮﻿]")
_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
# A block is meaningful when it still holds a letter, digit or CJK character.
_MEANINGFUL = re.compile(r"[0-9A-Za-z\u4e00-\u9fff]")
_CJK = re.compile(r"[\u4e00-\u9fff]")
_LATIN = re.compile(r"[A-Za-z]")
_TAG = re.compile(r"<[^>]{1,200}>")
_IMAGE = re.compile(r"!\[([^\]]*)\]\([^)]*\)")
_LINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")
_FENCE = re.compile(r"^\s*(```|~~~)\s*(\S*)")
_ATX_HEADING = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
_SETEXT = re.compile(r"^\s{0,3}(=+|-{2,})\s*$")
_BULLET = re.compile(r"^\s{0,3}(?:[-*+•]|\d+[.)])\s+(.*)$")
_QUOTE = re.compile(r"^\s{0,3}>\s?(.*)$")
_TABLE_SEPARATOR = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$")
_HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)
_EMPHASIS = [
    re.compile(r"\*\*\*(.+?)\*\*\*"),
    re.compile(r"\*\*(.+?)\*\*"),
    re.compile(r"\*(.+?)\*"),
    re.compile(r"__(.+?)__"),
    re.compile(r"_(.+?)_"),
    re.compile(r"~~(.+?)~~"),
]
_INLINE_CODE = re.compile(r"`{1,3}([^`]*)`{1,3}")
_HORIZONTAL_RULE = re.compile(r"^\s{0,3}([-*_])(\s*\1){2,}\s*$")
_FRONT_MATTER = re.compile(r"\A\s*---\s*\n.*?\n\s*---\s*", re.S)
_PAGE_MARKER = re.compile(
    r"^\s*(?:第\s*\d+\s*页(?:\s*/\s*共\s*\d+\s*页)?|page\s*\d+(?:\s*(?:of|/)\s*\d+)?|\d{1,4})\s*$",
    re.I,
)
_REPEATED_SPACES = re.compile(r"[ \t]{2,}")
_BLANK_RUN = re.compile(r"\n{3,}")


def clean_inline(text: str) -> str:
    """Strip inline layout markup and return plain prose."""
    if not text:
        return ""
    value = _ZERO_WIDTH.sub("", text)
    value = _CONTROL_CHARS.sub(" ", value)
    value = _IMAGE.sub(r"\1", value)
    value = _LINK.sub(r"\1", value)
    value = _TAG.sub(" ", value)
    value = _INLINE_CODE.sub(r"\1", value)
    for pattern in _EMPHASIS:
        value = pattern.sub(r"\1", value)
    # Markdown escapes and stray backslashes in front of punctuation carry no meaning.
    value = re.sub(r"\\([\\`*_{}\[\]()#+\-.!|])", r"\1", value)
    value = _REPEATED_SPACES.sub(" ", value)
    return value.strip()


def has_content(text: str) -> bool:
    """Return whether text carries meaning beyond layout or separators.

    ``|---|---|``, ``***`` and ``&nbsp;`` all look non-empty to ``str.strip`` but
    describe formatting, not knowledge. A block is only meaningful when it still
    contains a letter, a digit or a CJK character after inline cleaning.
    """
    return bool(_MEANINGFUL.search(text or ""))


def normalize_whitespace(text: str) -> str:
    """Collapse repeated blanks so every format yields the same line semantics."""
    value = (text or "").replace("\r\n", "\n").replace("\r", "\n")
    value = _REPEATED_SPACES.sub(" ", value)
    value = _BLANK_RUN.sub("\n\n", value)
    return value.strip()


def block(
    block_id: str,
    seq: int,
    block_type: str,
    text: str,
    heading_path: str = "",
    level: int = 0,
    page_no: int | None = None,
    table: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build one canonical block record."""
    return {
        "block_id": block_id,
        "seq": seq,
        "block_type": block_type,
        "text": text,
        "heading_path": heading_path,
        "level": level,
        "page_no": page_no,
        "table": table,
    }


# ---------------------------------------------------------------------------
# Format normalizers: each returns a flat list of blocks in document order.
# ---------------------------------------------------------------------------


def render_table_text(header: list[str], rows: list[list[str]]) -> str:
    """Render a table as prose rows so no pipe separators leak into knowledge objects.

    ``| 市内交通 | 80 元/天 |`` is fine as a table but meaningless as a card title or a
    claim object, so tables are flattened into ``列名: 值`` pairs instead.
    """
    lines: list[str] = []
    for row in rows:
        pairs: list[str] = []
        for index, cell in enumerate(row):
            value = cell.strip()
            if not value:
                continue
            key = header[index].strip() if index < len(header) else ""
            pairs.append("%s: %s" % (key, value) if key and has_content(key) else value)
        line = "；".join(pairs)
        if has_content(line):
            lines.append(line)
    return "\n".join(lines)


_PAGE_COMMENT = re.compile(r"^<!--\s*page\s*=\s*(\d+)\s*-->$")


def from_markdown(raw: str) -> list[dict[str, Any]]:
    """Convert markdown to blocks, dropping fences, rules and table alignment."""
    text = _FRONT_MATTER.sub("", raw or "")
    # 页码是 to_markdown 写成 HTML 注释带进来的，而下面会把注释整个删掉；先按行号记下来，
    # 删注释只清空内容、不动换行，行号对得上，可以在遍历时回放。没有这一步，Markdown 化
    # 之后引用就再也说不出「第几页」了。
    page_marks: dict[int, int] = {}
    for line_index, source_line in enumerate(text.split("\n")):
        mark = _PAGE_COMMENT.match(source_line.strip())
        if mark:
            page_marks[line_index] = int(mark.group(1))
    text = _HTML_COMMENT.sub("", text)
    lines = text.split("\n")
    blocks: list[dict[str, Any]] = []
    heading_stack: list[tuple[int, str]] = []
    paragraph: list[str] = []
    seq = 0
    index = 0
    current_page: int | None = None

    def flush_paragraph() -> None:
        nonlocal paragraph, seq
        if not paragraph:
            return
        joined = clean_inline(" ".join(paragraph))
        paragraph = []
        if has_content(joined):
            seq += 1
            blocks.append(block("B%d" % seq, seq, BLOCK_PARAGRAPH, joined, heading_path(), 0, current_page))

    def heading_path() -> str:
        return " / ".join(title for _, title in heading_stack)

    while index < len(lines):
        if index in page_marks:
            current_page = page_marks[index]
        line = lines[index]
        stripped = line.strip()
        fence = _FENCE.match(line)
        if fence:
            flush_paragraph()
            language = fence.group(2)
            body: list[str] = []
            index += 1
            while index < len(lines) and not _FENCE.match(lines[index]):
                body.append(lines[index])
                index += 1
            index += 1
            code = "\n".join(body).strip()
            if has_content(code):
                seq += 1
                blocks.append(
                    block(
                        "B%d" % seq,
                        seq,
                        BLOCK_CODE,
                        code,
                        heading_path(),
                        table={"language": language or ""},
                    )
                )
            continue

        if not stripped:
            flush_paragraph()
            index += 1
            continue

        if _HORIZONTAL_RULE.match(line):
            flush_paragraph()
            index += 1
            continue

        # A pipe row followed by an alignment separator is a table.
        if "|" in stripped and index + 1 < len(lines) and _TABLE_SEPARATOR.match(lines[index + 1]):
            flush_paragraph()
            table_block, consumed = _parse_markdown_table(lines, index)
            if table_block:
                seq += 1
                item = block(
                    "B%d" % seq, seq, BLOCK_TABLE, table_block["text"], heading_path(), table=table_block["table"]
                )
                blocks.append(item)
            index = consumed
            continue

        heading = _ATX_HEADING.match(line)
        if heading:
            flush_paragraph()
            level = len(heading.group(1))
            title = clean_inline(heading.group(2))
            if has_content(title):
                _push_heading(heading_stack, level, title)
                seq += 1
                blocks.append(
                    block("B%d" % seq, seq, BLOCK_HEADING, title, " / ".join(t for _, t in heading_stack), level)
                )
            index += 1
            continue

        # Setext heading: a text line underlined by === or ---
        if _SETEXT.match(line) and paragraph and has_content(" ".join(paragraph)):
            title = clean_inline(" ".join(paragraph))
            paragraph = []
            level = 1 if line.strip().startswith("=") else 2
            _push_heading(heading_stack, level, title)
            seq += 1
            blocks.append(block("B%d" % seq, seq, BLOCK_HEADING, title, " / ".join(t for _, t in heading_stack), level))
            index += 1
            continue

        quote = _QUOTE.match(line)
        if quote:
            flush_paragraph()
            body_text = clean_inline(quote.group(1))
            if has_content(body_text):
                seq += 1
                blocks.append(block("B%d" % seq, seq, BLOCK_QUOTE, body_text, heading_path()))
            index += 1
            continue

        bullet = _BULLET.match(line)
        if bullet:
            flush_paragraph()
            item_text = clean_inline(bullet.group(1))
            if has_content(item_text):
                seq += 1
                blocks.append(block("B%d" % seq, seq, BLOCK_LIST_ITEM, item_text, heading_path()))
            index += 1
            continue

        cleaned = clean_inline(stripped)
        if has_content(cleaned):
            paragraph.append(cleaned)
        index += 1

    flush_paragraph()
    return blocks


def _push_heading(stack: list[tuple[int, str]], level: int, title: str) -> None:
    while stack and stack[-1][0] >= level:
        stack.pop()
    stack.append((level, title))


def _parse_markdown_table(lines: list[str], start: int) -> tuple[dict[str, Any] | None, int]:
    """Parse a github-style markdown table starting at ``start``."""

    def cells(line: str) -> list[str]:
        trimmed = line.strip().strip("|")
        return [clean_inline(cell) for cell in trimmed.split("|")]

    header = cells(lines[start])
    index = start + 2  # skip the alignment separator row
    rows: list[list[str]] = []
    while index < len(lines) and "|" in lines[index] and lines[index].strip():
        rows.append(cells(lines[index]))
        index += 1
    if not any(has_content(cell) for cell in header) and not any(has_content(cell) for row in rows for cell in row):
        return None, index
    return {
        "text": render_table_text(header, rows),
        "table": {"header": header, "rows": rows},
    }, index


_HTML_BLOCK_TAGS = {"p", "div", "section", "article", "li", "td", "th", "pre", "blockquote"}
_HTML_DROP_TAGS = {"script", "style", "noscript", "svg", "form", "iframe", "nav", "footer"}


def from_html(raw: str) -> list[dict[str, Any]]:
    """Convert HTML to blocks using tag semantics instead of raw text."""
    soup = BeautifulSoup(raw or "", "html.parser")
    for tag in soup(list(_HTML_DROP_TAGS)):
        tag.decompose()
    root = soup.body or soup
    blocks: list[dict[str, Any]] = []
    heading_stack: list[tuple[int, str]] = []
    seq = 0

    def heading_path() -> str:
        return " / ".join(title for _, title in heading_stack)

    def add(block_type: str, text: str, level: int = 0, table: dict[str, Any] | None = None) -> None:
        nonlocal seq
        cleaned = normalize_whitespace(clean_inline(text))
        if not has_content(cleaned):
            return
        seq += 1
        blocks.append(block("B%d" % seq, seq, block_type, cleaned, heading_path(), level, table=table))

    for element in root.find_all(list(_HTML_BLOCK_TAGS | {"h1", "h2", "h3", "h4", "h5", "h6", "table"})):
        name = element.name
        if name == "table":
            header = [clean_inline(th.get_text(" ", strip=True)) for th in element.find_all("th")]
            rows = []
            for row in element.find_all("tr"):
                # Header cells are captured separately, so th-only rows are skipped here.
                cells = [clean_inline(td.get_text(" ", strip=True)) for td in row.find_all("td")]
                if not any(has_content(cell) for cell in cells):
                    continue
                rows.append(cells)
            if not rows:
                continue
            if not header:
                header = rows.pop(0)
            add(BLOCK_TABLE, render_table_text(header, rows), table={"header": header, "rows": rows})
            continue
        if name in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            level = int(name[1])
            title = clean_inline(element.get_text(" ", strip=True))
            if has_content(title):
                _push_heading(heading_stack, level, title)
                seq += 1
                blocks.append(block("B%d" % seq, seq, BLOCK_HEADING, title, heading_path(), level))
            continue
        if name == "li":
            add(BLOCK_LIST_ITEM, element.get_text(" ", strip=True))
            continue
        if name == "pre":
            add(BLOCK_CODE, element.get_text("\n", strip=False))
            continue
        if name == "blockquote":
            add(BLOCK_QUOTE, element.get_text(" ", strip=True))
            continue
        if name in {"td", "th"}:
            continue  # already emitted by the enclosing table
        # Nested containers duplicate their children; only leaf-ish divs/sections emit text.
        if element.find(list(_HTML_BLOCK_TAGS | {"h1", "h2", "h3", "h4", "h5", "h6", "table"})):
            continue
        add(BLOCK_PARAGRAPH, element.get_text(" ", strip=True))
    return blocks


def _pdf_page_lines(pages: list[str]) -> tuple[list[list[str]], set]:
    """Split pages into lines and detect running headers/footers/page numbers."""
    all_lines: list[list[str]] = []
    for page in pages:
        all_lines.append([line.strip() for line in page.split("\n") if line.strip()])
    total = len(all_lines)
    counter: dict[str, int] = {}
    for lines in all_lines:
        # Running headers/footers occupy the very top or bottom of a page.
        for candidate in set(lines[:2] + lines[-2:]):
            normalized = candidate.lower()
            if len(normalized) > 120:
                continue
            counter[normalized] = counter.get(normalized, 0) + 1
    threshold = max(2, int(total * 0.6))
    noise = {key for key, count in counter.items() if count >= threshold}
    return all_lines, noise


_HEADING_LIKE = re.compile(r"^(第[一二三四五六七八九十百千]+[章节条款项]|附件|附录|[0-9０-９]+[.、)）]|[(（][0-9０-９]+[)）]|[一二三四五六七八九十]+[、.])")


def _looks_like_heading(line: str) -> bool:
    """A short labelled line that opens an item instead of continuing a sentence.

    「第五条」「1)」「附件1」这类行自己就是一块；它们不因为短而成段，成段是因为它们
    带着编号标签，看上去就是新条目的开头。
    """
    stripped = line.strip()
    if not stripped or len(stripped) > 30:
        return False
    return bool(_HEADING_LIKE.match(stripped))


def _pdf_pages(path: str) -> tuple[list[str], int]:
    """Extract per-page text, preferring PyMuPDF.

    PyMuPDF returns text in reading order (``sort=True``) and keeps multi-column
    layouts and CJK encodings intact. pypdf emits the content stream in whatever
    order the producer happened to write it, which is what left the policy
    documents cut mid-sentence. pypdf stays as the fallback so a missing optional
    package cannot take the whole parser down with it.
    """
    try:
        import pymupdf
    except ImportError:
        pymupdf = None
    if pymupdf is not None:
        try:
            with pymupdf.open(path) as document:
                pages = [page.get_text("text", sort=True).strip() for page in document]
                return pages, document.page_count
        except Exception:
            pass  # 取不到就落回 pypdf
    reader = PdfReader(path)
    return [(page.extract_text() or "").strip() for page in reader.pages], len(reader.pages)


def from_pdf(path: str) -> tuple[list[dict[str, Any]], int]:
    """Convert a PDF to blocks, removing page numbers and running headers/footers."""
    pages, page_count = _pdf_pages(path)
    page_lines, noise = _pdf_page_lines(pages)
    blocks: list[dict[str, Any]] = []
    seq = 0
    # 上一页末尾没成段的残留。PDF 是分页的，一个句子常常横跨页边界：页尾是前半句、页首是
    # 后半句。逐页 flush 会把后半句变成新块开头，后面卡片标题和断言主语就是这么被劈成
    # 「和市场响应速度」「)硬件授权申请流程」的，所以要带到下一页接着拼。
    carry: list[str] = []
    for page_no, lines in enumerate(page_lines, start=1):
        kept: list[str] = []
        for line in lines:
            if line.lower() in noise:
                continue
            if _PAGE_MARKER.match(line) and not has_content(line.replace("页", "").replace("page", "")):
                continue
            kept.append(_strip_dot_leaders(line))
        # Soft-wrapped PDF lines are re-joined; a line ending in terminal punctuation
        # or a short heading-like line starts a new paragraph.
        paragraph: list[str] = list(carry)
        carry = []
        for line in kept:
            paragraph.append(line)
            # 中文 PDF 的一行常常只是排版折行，不是一句话的结束。实测制度类 PDF 一页里
            # 行长小于 40 的比例是 100%，所以「行长 < 40 即成段」等于把每一行都切成一块，
            # 句子被逐行切碎，后面的切块和断言只能拿到半句话。只有真正以句末标点收尾、或
            # 像编号标题那样的短行才算一段结束。
            ends_paragraph = line.rstrip().endswith(("。", "；", "！", "？", ".", ":", "：")) or _looks_like_heading(line)
            if not ends_paragraph:
                continue
            text = normalize_whitespace(clean_inline(_join_lines(paragraph)))
            paragraph = []
            if has_content(text):
                seq += 1
                blocks.append(block("B%d" % seq, seq, BLOCK_PARAGRAPH, text, "", 0, page_no))
        if paragraph:
            # 段落还没收尾：非最后一页就留到下一页接着拼，最后一页才落块。
            if page_no < len(page_lines):
                carry = paragraph
                continue
            text = normalize_whitespace(clean_inline(_join_lines(paragraph)))
            if has_content(text):
                seq += 1
                blocks.append(block("B%d" % seq, seq, BLOCK_PARAGRAPH, text, "", 0, page_no))
    return blocks, page_count


_DOT_LEADERS = re.compile(r"[.．·・]{3,}")


def _strip_dot_leaders(line: str) -> str:
    """Remove the dotted leader between a table-of-contents entry and its page number.

    ``第一章总则..........8`` still carries the heading, so the row cannot simply be
    dropped — only the run of dots goes, leaving ``第一章总则 8``.
    """
    return _DOT_LEADERS.sub(" ", line).strip()


def to_markdown(blocks: list[dict[str, Any]]) -> str:
    """Serialize canonical blocks back to Markdown.

    Every format converges on this text so that chunking, extraction and citation
    all read one shape, whether the upload was a PDF, a docx or an HTML page.
    Page numbers survive as HTML comments: a citation can still name the page it
    came from, which plain Markdown would otherwise throw away.
    """
    lines: list[str] = []
    current_page: object = None
    for item in blocks:
        page = item.get("page_no")
        if page and page != current_page:
            lines.append("<!-- page=%s -->" % page)
            current_page = page
        block_type = item.get("block_type") or BLOCK_PARAGRAPH
        text = (item.get("text") or "").strip()
        if block_type == BLOCK_HEADING:
            level = max(1, min(6, int(item.get("level") or 1)))
            lines.append("%s %s" % ("#" * level, text))
        elif block_type == BLOCK_TABLE:
            table = item.get("table") or {}
            header = list(table.get("header") or [])
            rows = list(table.get("rows") or [])
            if header:
                lines.append("| " + " | ".join(_md_cell(cell) for cell in header) + " |")
                lines.append("|" + "|".join("---" for _ in header) + "|")
            for row in rows:
                lines.append("| " + " | ".join(_md_cell(cell) for cell in row) + " |")
            if not header and not rows and text:
                lines.append(text)
        elif block_type == BLOCK_CODE:
            lines.extend(["```", text, "```"])
        elif text:
            lines.append(text)
        lines.append("")
    return "\n".join(lines).strip() + "\n"


def _md_cell(value: object) -> str:
    """Escape a cell so it cannot break out of the Markdown table it sits in."""
    return str(value or "").replace("|", "\\|").replace("\n", " ").strip()


def _join_lines(lines: list[str]) -> str:
    """Join lines, removing hyphenation breaks created by PDF layout."""
    text = "\n".join(lines)
    text = re.sub(r"-\n", "", text)
    return re.sub(r"\n", "", text) if _looks_wrapped(lines) else text


def _looks_wrapped(lines: list[str]) -> bool:
    """Treat consecutive short lines as one wrapped paragraph (CJK PDFs)."""
    return len(lines) > 1 and all(len(line) <= 40 for line in lines)


def from_docx(path: str) -> list[dict[str, Any]]:
    """Convert DOCX to blocks, preserving heading levels and tables."""
    document = DocxDocument(path)
    blocks: list[dict[str, Any]] = []
    heading_stack: list[tuple[int, str]] = []
    seq = 0

    def heading_path() -> str:
        return " / ".join(title for _, title in heading_stack)

    def add(block_type: str, text: str, level: int = 0, table: dict[str, Any] | None = None) -> None:
        nonlocal seq
        cleaned = normalize_whitespace(clean_inline(text))
        if not has_content(cleaned):
            return
        seq += 1
        blocks.append(block("B%d" % seq, seq, block_type, cleaned, heading_path(), level, table=table))

    for child in document.element.body.iterchildren():
        if child.tag == qn("w:tbl"):
            table = DocxTable(child, document)
            rows = [[clean_inline(cell.text or "") for cell in row.cells] for row in table.rows]
            rows = [row for row in rows if any(has_content(cell) for cell in row)]
            if not rows:
                continue
            header = rows.pop(0)
            add(BLOCK_TABLE, render_table_text(header, rows), table={"header": header, "rows": rows})
            continue
        if child.tag != qn("w:p"):
            continue
        paragraph = DocxParagraph(child, document)
        text = paragraph.text.strip()
        if not text:
            continue
        style = (paragraph.style.name or "") if paragraph.style is not None else ""
        level = _docx_heading_level(style)
        if level:
            _push_heading(heading_stack, level, clean_inline(text))
            seq += 1
            blocks.append(block("B%d" % seq, seq, BLOCK_HEADING, clean_inline(text), heading_path(), level))
            continue
        if style.lower().startswith("list"):
            add(BLOCK_LIST_ITEM, text)
            continue
        if style.lower().startswith("quote"):
            add(BLOCK_QUOTE, text)
            continue
        add(BLOCK_PARAGRAPH, text)
    return blocks


def _docx_heading_level(style: str) -> int:
    match = re.search(r"(?:heading|标题)\s*(\d)", style, re.I)
    if match:
        return min(6, max(1, int(match.group(1))))
    return 1 if style.lower() in {"title", "标题"} else 0


def from_text(raw: str) -> list[dict[str, Any]]:
    """Convert plain text to paragraphs, dropping separator-only lines."""
    blocks: list[dict[str, Any]] = []
    seq = 0
    for part in re.split(r"\n{2,}", normalize_whitespace(raw or "")):
        cleaned = normalize_whitespace(clean_inline(part))
        if not has_content(cleaned):
            continue
        seq += 1
        blocks.append(block("B%d" % seq, seq, BLOCK_PARAGRAPH, cleaned))
    return blocks


# ---------------------------------------------------------------------------
# Standard document assembly
# ---------------------------------------------------------------------------


def build_sections(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Derive the section outline from heading blocks."""
    sections: list[dict[str, Any]] = []
    for item in blocks:
        if item["block_type"] != BLOCK_HEADING:
            continue
        sections.append(
            {
                "section_id": "S%d" % len(sections),
                "heading": item["text"],
                "level": item.get("level") or 1,
                "heading_path": item.get("heading_path", ""),
                "order": item["seq"],
            }
        )
    return sections


def estimate_tokens(text: str) -> int:
    """Token estimate good enough for chunk budgeting (no tokenizer dependency).

    CJK characters carry far more meaning per character than latin ones, so a
    pure ``len / 4`` estimate under-counts Chinese documents badly and lets a
    "900 token" chunk grow to several thousand real tokens.
    """
    if not text:
        return 0
    cjk = len(_CJK.findall(text))
    rest = max(0, len(text) - cjk)
    return max(1, int(round(cjk * 0.7 + rest / 4.0)))


def _measure(text: str, policy: dict[str, Any]) -> int:
    """Window measurement in the unit declared by the policy."""
    return estimate_tokens(text) if policy.get("measure") == "TOKEN" else len(text)


def _block_spans(blocks: list[dict[str, Any]]) -> dict[str, tuple[int, int]]:
    """Character span of every block inside the joined document text.

    The canonical text is the block texts joined by a blank line, so these offsets
    are exact and a chunk can later report where in the source it starts and ends.
    """
    spans: dict[str, tuple[int, int]] = {}
    cursor = 0
    for item in blocks:
        text = item.get("text") or ""
        spans[item["block_id"]] = (cursor, cursor + len(text))
        cursor += len(text) + 2
    return spans


def _locate(blocks: list[dict[str, Any]], spans: dict[str, tuple[int, int]], offset: int) -> tuple[str, int | None]:
    """Heading path and page number covering a character offset."""
    for item in blocks:
        span = spans.get(item["block_id"])
        if span and span[0] <= offset < max(span[1], span[0] + 1):
            return item.get("heading_path", ""), item.get("page_no")
    for item in blocks:
        span = spans.get(item["block_id"])
        if span and offset < span[0]:
            return item.get("heading_path", ""), item.get("page_no")
    last = blocks[-1] if blocks else {}
    return last.get("heading_path", ""), last.get("page_no")


def _snap_to_boundary(text: str, limit: int) -> int:
    """Move a hard cut back to the last sentence boundary inside the window."""
    if limit >= len(text):
        return len(text)
    window = text[:limit]
    best = 0
    for match in re.finditer(r"[。！？!？；;\n]", window):
        best = match.end()
    if best >= int(limit * 0.6):
        return best
    # 制度文档常见整段没有句末标点（长列表项、跨页残句续进来之后），窗口里根本没有靠后的
    # 边界，按原逻辑就硬切，把下一块开头劈成残句，连带带坏卡片标题和断言主语。宁可少切
    # 一点退到更靠前的边界，连一个边界都没有才硬切。
    return best if best >= int(limit * 0.3) else limit


def _next_cut(text: str, start: int, budget: int, policy: dict[str, Any]) -> int:
    """End offset of the window that starts at ``start`` under the policy budget."""
    if start >= len(text):
        return len(text)
    if policy.get("measure") == "TOKEN":
        consumed = 0
        position = start
        raw = len(text)
        while position < len(text):
            step = min(64, len(text) - position)
            consumed += estimate_tokens(text[position : position + step])
            position += step
            if consumed >= budget:
                raw = position
                break
    else:
        raw = min(len(text), start + budget)
    if policy.get("respectSentence"):
        raw = start + _snap_to_boundary(text[start:raw], raw - start)
    if raw <= start:
        raw = min(len(text), start + max(1, budget // 2))
    return max(raw, start + 1) if raw < len(text) else len(text)


def _overlap_chars(policy: dict[str, Any]) -> int:
    """Overlap expressed in characters; a token overlap is roughly two chars."""
    overlap = int(policy.get("chunkOverlap") or 0)
    return overlap * 2 if policy.get("measure") == "TOKEN" else overlap


def _chunk_type_for(block_type: str) -> str:
    if block_type == BLOCK_TABLE:
        return "TABLE"
    if block_type == BLOCK_CODE:
        return "CODE"
    if block_type == BLOCK_HEADING:
        return "HEADING"
    return "PARAGRAPH"


def _make_unit(
    seq: int,
    content: str,
    heading_path: str,
    page_no: int | None,
    block_type: str,
    block_ids: list[str],
    spans: dict[str, tuple[int, int]],
    policy: dict[str, Any],
    parent_unit_id: str | None = None,
    chunk_type: str | None = None,
    overlap_prefix: str = "",
) -> dict[str, Any]:
    """One retrieval unit carrying every boundary a citation may need to explain."""
    start = spans.get(block_ids[0], (None, None))[0] if block_ids else None
    end = spans.get(block_ids[-1], (None, None))[1] if block_ids else None
    return {
        "unit_id": "U%d" % seq,
        "seq": seq,
        "heading_path": heading_path or "",
        "page_no": page_no,
        "page_end": page_no,
        "block_type": block_type,
        "chunk_type": chunk_type or _chunk_type_for(block_type),
        "block_ids": list(block_ids),
        "content": content,
        "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "char_start": start,
        "char_end": end,
        "token_count": estimate_tokens(content),
        "parent_unit_id": parent_unit_id,
        "overlap_prefix": overlap_prefix,
        "strategy": policy.get("strategy", "SEMANTIC"),
    }


def _unit_text(items: list[dict[str, Any]]) -> str:
    return "\n\n".join(item["text"] for item in items if item.get("text"))


def build_units(
    blocks: list[dict[str, Any]],
    size: int | None = None,
    overlap: int | None = None,
    policy: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """Group blocks into retrieval/extraction units under a chunking policy.

    Tables and code blocks are never merged with prose: mixing them would make the
    resulting unit unreadable for an operator reviewing the source of a card.
    """
    active = chunk_policy.normalize(policy) if policy else chunk_policy.default_policy(size, overlap)
    strategy = active["strategy"]
    if strategy == "FIXED":
        units = _units_fixed(blocks, active)
    elif strategy == "SENTENCE":
        units = _units_sentence(blocks, active)
    elif strategy == "STRUCTURE":
        units = _units_structure(blocks, active)
    elif strategy == "PARENT_CHILD":
        units = _units_parent_child(blocks, active)
    else:
        units = _units_semantic(blocks, active)
    return _finalize(units, blocks, active)


def _units_semantic(blocks: list[dict[str, Any]], policy: dict[str, Any]) -> list[dict[str, Any]]:
    """Accumulate blocks up to the window, cutting on heading changes."""
    window = int(policy["chunkSize"])
    keep_structure = bool(policy.get("keepStructure"))
    units: list[dict[str, Any]] = []
    buffer: list[dict[str, Any]] = []
    tail = ""

    def flush() -> None:
        nonlocal tail
        if not buffer:
            return
        content = _unit_text(buffer)
        if not has_content(content):
            buffer.clear()
            return
        first = buffer[0]
        units.append(
            _make_unit(
                len(units) + 1,
                content,
                first.get("heading_path", ""),
                first.get("page_no"),
                first["block_type"],
                [item["block_id"] for item in buffer],
                {},
                policy,
                overlap_prefix=tail,
            )
        )
        # The next unit carries the tail of this one as context without duplicating it
        # in the indexed text: retrieval gains the bridge, storage stays clean.
        gap = _overlap_chars(policy)
        tail = content[-gap:] if gap else ""
        buffer.clear()

    for item in blocks:
        if keep_structure and item["block_type"] in {BLOCK_TABLE, BLOCK_CODE}:
            flush()
            buffer = [item]
            flush()
            buffer = []
            continue
        projected = _measure(_unit_text(buffer + [item]), policy)
        if buffer and projected > window and (not keep_structure or item["heading_path"] == buffer[-1]["heading_path"]):
            flush()
        buffer.append(item)
        if _measure(_unit_text(buffer), policy) >= window:
            flush()
    flush()
    return _merge_small_units(units, policy)


def _units_sentence(blocks: list[dict[str, Any]], policy: dict[str, Any]) -> list[dict[str, Any]]:
    """Group whole sentences up to the window, so no unit ends mid-sentence."""
    window = int(policy["chunkSize"])
    units: list[dict[str, Any]] = []
    buffer: list[str] = []
    ids: list[str] = []
    meta: dict[str, Any] = {}

    def flush() -> None:
        if not buffer:
            return
        content = " ".join(buffer).strip()
        if not has_content(content):
            buffer.clear()
            ids.clear()
            return
        units.append(
            _make_unit(
                len(units) + 1,
                content,
                meta.get("heading_path", ""),
                meta.get("page_no"),
                meta.get("block_type", BLOCK_PARAGRAPH),
                list(ids),
                {},
                policy,
            )
        )
        buffer.clear()
        ids.clear()

    for item in blocks:
        text = item.get("text") or ""
        if not text.strip():
            continue
        for sentence in re.split(r"(?<=[。！？!？；;])\s*|\n+", text):
            piece = sentence.strip()
            if not piece:
                continue
            if buffer and _measure(" ".join(buffer + [piece]), policy) > window:
                flush()
                meta = {
                    "heading_path": item.get("heading_path", ""),
                    "page_no": item.get("page_no"),
                    "block_type": item["block_type"],
                }
            elif not buffer:
                meta = {
                    "heading_path": item.get("heading_path", ""),
                    "page_no": item.get("page_no"),
                    "block_type": item["block_type"],
                }
            buffer.append(piece)
            if item["block_id"] not in ids:
                ids.append(item["block_id"])
    flush()
    return _merge_small_units(units, policy)


def _units_fixed(blocks: list[dict[str, Any]], policy: dict[str, Any]) -> list[dict[str, Any]]:
    """Sliding window over the cleaned text with a real overlap."""
    spans = _block_spans(blocks)
    text = "\n\n".join(item.get("text") or "" for item in blocks)
    window = int(policy["chunkSize"])
    gap = _overlap_chars(policy)
    units: list[dict[str, Any]] = []
    start = 0
    while start < len(text):
        end = _next_cut(text, start, window, policy)
        piece = text[start:end]
        if has_content(piece):
            heading_path, page_no = _locate(blocks, spans, start)
            block_ids = [
                item["block_id"]
                for item in blocks
                if spans[item["block_id"]][0] < end and spans[item["block_id"]][1] > start
            ]
            units.append(
                _make_unit(len(units) + 1, piece, heading_path, page_no, BLOCK_PARAGRAPH, block_ids, spans, policy)
            )
        if end >= len(text):
            break
        # 回退 overlap 之后要向前对齐到边界，否则下一块从半句话开始。
        start = _align_forward(text, max(end - gap, start + 1), end)
    return units


def _units_structure(blocks: list[dict[str, Any]], policy: dict[str, Any]) -> list[dict[str, Any]]:
    """One unit per heading path; only oversized sections are split."""
    window = int(policy["chunkSize"])
    limit = max(window, int(window * 1.5))
    units: list[dict[str, Any]] = []
    buffer: list[dict[str, Any]] = []
    current_path = None

    def flush() -> None:
        if not buffer:
            return
        content = _unit_text(buffer)
        if not has_content(content):
            buffer.clear()
            return
        first = buffer[0]
        block_ids = [item["block_id"] for item in buffer]
        if _measure(content, policy) <= limit:
            units.append(
                _make_unit(
                    len(units) + 1,
                    content,
                    first.get("heading_path", ""),
                    first.get("page_no"),
                    first["block_type"],
                    block_ids,
                    {},
                    policy,
                )
            )
        else:
            for piece in _hard_split(content, window, policy):
                units.append(
                    _make_unit(
                        len(units) + 1,
                        piece,
                        first.get("heading_path", ""),
                        first.get("page_no"),
                        first["block_type"],
                        block_ids,
                        {},
                        policy,
                    )
                )
        buffer.clear()

    for item in blocks:
        path = item.get("heading_path", "")
        if current_path is not None and path != current_path:
            flush()
        current_path = path
        buffer.append(item)
    flush()
    return units


def _units_parent_child(blocks: list[dict[str, Any]], policy: dict[str, Any]) -> list[dict[str, Any]]:
    """Section-level parent units, each followed by its window-sized children.

    Retrieval matches the precise child; the parent keeps the surrounding context
    that makes an answer readable. Parents are emitted first so persistence can link
    every child to an id that already exists.
    """
    window = int(policy["chunkSize"])
    units: list[dict[str, Any]] = []
    groups: list[tuple[str, list[dict[str, Any]]]] = []
    current_path = None
    for item in blocks:
        path = item.get("heading_path", "") or ""
        if not groups or path != current_path:
            groups.append((path, []))
            current_path = path
        groups[-1][1].append(item)

    def new_unit(
        content: str,
        heading_path: str,
        page_no: int | None,
        block_type: str,
        block_ids: list[str],
        parent_unit_id: str | None = None,
        chunk_type: str | None = None,
    ) -> None:
        units.append(
            _make_unit(
                len(units) + 1,
                content,
                heading_path,
                page_no,
                block_type,
                block_ids,
                {},
                policy,
                parent_unit_id=parent_unit_id,
                chunk_type=chunk_type,
            )
        )

    for _path, group in groups:
        content = _unit_text(group)
        if not has_content(content):
            continue
        block_ids = [item["block_id"] for item in group]
        heading_path = group[0].get("heading_path", "")
        page_no = group[0].get("page_no")
        new_unit(content, heading_path, page_no, BLOCK_HEADING, block_ids, chunk_type="SECTION")
        parent_unit_id = units[-1]["unit_id"]
        if _measure(content, policy) <= window:
            continue
        child_policy = dict(policy)
        child_policy["strategy"] = "SEMANTIC"
        for child in _units_semantic(group, child_policy):
            if not has_content(child["content"]):
                continue
            new_unit(
                child["content"],
                child["heading_path"],
                child["page_no"],
                child["block_type"],
                child["block_ids"],
                parent_unit_id=parent_unit_id,
                chunk_type=child.get("chunk_type"),
            )
    return units


_BOUNDARY = re.compile(r"[。！？!？；;\n]")


def _align_forward(text: str, position: int, limit: int) -> int:
    """Move an overlap start forward to the first boundary so it never opens mid-sentence.

    The tail of a cut is snapped to a boundary, but the next window then steps back by
    the overlap and lands in the middle of a sentence. Card titles and claim subjects
    are read off the head of a chunk, so every chunk but the first opened with a
    fragment — 「期内）」「0 分（不合格）员工」「和市场响应速度」.
    """
    if position <= 0:
        return 0
    match = _BOUNDARY.search(text, position)
    if match and match.end() <= limit:
        return match.end()
    return min(position, limit)


def _hard_split(content: str, window: int, policy: dict[str, Any]) -> list[str]:
    """Split an oversized piece at sentence boundaries when the policy asks for it."""
    pieces: list[str] = []
    start = 0
    gap = _overlap_chars(policy)
    while start < len(content):
        end = _next_cut(content, start, window, policy)
        piece = content[start:end]
        if has_content(piece):
            pieces.append(piece)
        if end >= len(content):
            break
        # 回退 overlap 之后要向前对齐到边界，否则下一块从半句话开始。
        start = _align_forward(content, max(end - gap, start + 1), end)
    return pieces


def _merge_small_units(units: list[dict[str, Any]], policy: dict[str, Any]) -> list[dict[str, Any]]:
    """Fold orphan fragments into a neighbour so no chunk is too thin to answer from."""
    min_size = int(policy.get("minChunkSize") or 0)
    window = int(policy["chunkSize"])
    if min_size <= 0 or len(units) < 2:
        return units
    merged: list[dict[str, Any]] = []
    for unit in units:
        if (
            merged
            and _measure(unit["content"], policy) < min_size
            and unit.get("heading_path") == merged[-1].get("heading_path")
            and _measure(merged[-1]["content"], policy) + _measure(unit["content"], policy) <= window * 1.5
        ):
            previous = merged[-1]
            previous["content"] = previous["content"] + "\n\n" + unit["content"]
            previous["content_hash"] = hashlib.sha256(previous["content"].encode("utf-8")).hexdigest()
            previous["block_ids"] = previous["block_ids"] + unit["block_ids"]
            previous["token_count"] = estimate_tokens(previous["content"])
            continue
        merged.append(unit)
    return merged


def _finalize(
    units: list[dict[str, Any]], blocks: list[dict[str, Any]], policy: dict[str, Any]
) -> list[dict[str, Any]]:
    """Renumber units and fill spans / token counts from the canonical text."""
    spans = _block_spans(blocks)
    result: list[dict[str, Any]] = []
    for index, unit in enumerate(units, start=1):
        block_ids = [bid for bid in unit.get("block_ids", []) if bid in spans]
        unit["unit_id"] = "U%d" % index
        unit["seq"] = index
        unit["block_ids"] = block_ids
        unit["char_start"] = spans[block_ids[0]][0] if block_ids else None
        unit["char_end"] = spans[block_ids[-1]][1] if block_ids else None
        unit["page_end"] = unit.get("page_end") or unit.get("page_no")
        unit["token_count"] = estimate_tokens(unit.get("content", ""))
        unit["strategy"] = policy.get("strategy", "SEMANTIC")
        result.append(unit)
    return result


def infer_title(blocks: list[dict[str, Any]], filename: str) -> str:
    """Prefer the first top-level heading, then any heading, then the file name."""
    for item in blocks:
        if item["block_type"] == BLOCK_HEADING and (item.get("level") or 6) <= 1:
            return item["text"][:200]
    for item in blocks:
        if item["block_type"] == BLOCK_HEADING:
            return item["text"][:200]
    return re.sub(r"\.[A-Za-z0-9]+$", "", filename or "").strip() or "未命名文档"


def detect_language(text: str) -> str:
    """Guess zh/en from the dominant script so analyzers can be chosen per document."""
    sample = text[:4000] or ""
    return "zh" if len(_CJK.findall(sample)) >= len(_LATIN.findall(sample)) else "en"


def build_standard_document(
    filename: str,
    extension: str,
    sha256: str,
    blocks: list[dict[str, Any]],
    page_count: int = 0,
    policy: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Assemble the canonical document model shared by every consumer."""
    text = "\n\n".join(item["text"] for item in blocks if item.get("text"))
    units = build_units(blocks, policy=policy)
    sections = build_sections(blocks)
    return {
        "doc_id": hashlib.sha256(("%s:%s" % (filename, sha256)).encode("utf-8")).hexdigest()[:32],
        "title": infer_title(blocks, filename),
        "filename": filename,
        "source_format": SOURCE_FORMATS.get(extension.lower(), "TEXT"),
        "language": detect_language(text),
        "sha256": sha256,
        "page_count": page_count,
        "text": text,
        "blocks": blocks,
        "sections": sections,
        "units": units,
        "stats": {
            "blocks": len(blocks),
            "sections": len(sections),
            "units": len(units),
            "tables": sum(1 for item in blocks if item["block_type"] == BLOCK_TABLE),
            "chars": len(text),
            "tokens": sum(int(unit.get("token_count") or 0) for unit in units),
            "chunkStrategy": (units[0].get("strategy") if units else (policy or {}).get("strategy", "SEMANTIC")),
            "chunkPolicy": policy or chunk_policy.default_policy(),
        },
    }


def to_legacy_chunks(standard: dict[str, Any]) -> list[dict[str, Any]]:
    """Render units in the historical chunk shape so persistence stays unchanged."""
    return [
        {
            "seq": unit["seq"],
            "unit_id": unit["unit_id"],
            "page_no": unit.get("page_no"),
            "page_end": unit.get("page_end"),
            "char_start": unit.get("char_start"),
            "char_end": unit.get("char_end"),
            "token_count": unit.get("token_count"),
            "heading_path": unit.get("heading_path", ""),
            "content": unit["content"],
            "content_hash": unit["content_hash"],
            "chunk_type": unit.get("chunk_type") or "PARAGRAPH",
            "block_type": unit.get("block_type", ""),
            "parent_unit_id": unit.get("parent_unit_id"),
            "overlap_prefix": unit.get("overlap_prefix", ""),
            "strategy": unit.get("strategy", ""),
        }
        for unit in standard.get("units", [])
    ]
