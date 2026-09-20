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
from typing import Any, Dict, List, Optional, Tuple

from bs4 import BeautifulSoup
from docx import Document as DocxDocument
from docx.oxml.ns import qn
from docx.table import Table as DocxTable
from docx.text.paragraph import Paragraph as DocxParagraph
from pypdf import PdfReader

from app.core import chunk_policy
from app.core.config import settings

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
    page_no: Optional[int] = None,
    table: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
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


def render_table_text(header: List[str], rows: List[List[str]]) -> str:
    """Render a table as prose rows so no pipe separators leak into knowledge objects.

    ``| 市内交通 | 80 元/天 |`` is fine as a table but meaningless as a card title or a
    claim object, so tables are flattened into ``列名: 值`` pairs instead.
    """
    lines: List[str] = []
    for row in rows:
        pairs: List[str] = []
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


def from_markdown(raw: str) -> List[Dict[str, Any]]:
    """Convert markdown to blocks, dropping fences, rules and table alignment."""
    text = _FRONT_MATTER.sub("", raw or "")
    text = _HTML_COMMENT.sub("", text)
    lines = text.split("\n")
    blocks: List[Dict[str, Any]] = []
    heading_stack: List[Tuple[int, str]] = []
    paragraph: List[str] = []
    seq = 0
    index = 0

    def flush_paragraph() -> None:
        nonlocal paragraph, seq
        if not paragraph:
            return
        joined = clean_inline(" ".join(paragraph))
        paragraph = []
        if has_content(joined):
            seq += 1
            blocks.append(block("B%d" % seq, seq, BLOCK_PARAGRAPH, joined, heading_path()))
    def heading_path() -> str:
        return " / ".join(title for _, title in heading_stack)

    while index < len(lines):
        line = lines[index]
        stripped = line.strip()
        fence = _FENCE.match(line)
        if fence:
            flush_paragraph()
            language = fence.group(2)
            body: List[str] = []
            index += 1
            while index < len(lines) and not _FENCE.match(lines[index]):
                body.append(lines[index])
                index += 1
            index += 1
            code = "\n".join(body).strip()
            if has_content(code):
                seq += 1
                blocks.append(block(
                    "B%d" % seq, seq, BLOCK_CODE, code, heading_path(),
                    table={"language": language or ""},
                ))
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
                item = block("B%d" % seq, seq, BLOCK_TABLE, table_block["text"],
                             heading_path(), table=table_block["table"])
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
                blocks.append(block("B%d" % seq, seq, BLOCK_HEADING, title,
                                    " / ".join(t for _, t in heading_stack), level))
            index += 1
            continue

        # Setext heading: a text line underlined by === or ---
        if _SETEXT.match(line) and paragraph and has_content(" ".join(paragraph)):
            title = clean_inline(" ".join(paragraph))
            paragraph = []
            level = 1 if line.strip().startswith("=") else 2
            _push_heading(heading_stack, level, title)
            seq += 1
            blocks.append(block("B%d" % seq, seq, BLOCK_HEADING, title,
                                " / ".join(t for _, t in heading_stack), level))
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


def _push_heading(stack: List[Tuple[int, str]], level: int, title: str) -> None:
    while stack and stack[-1][0] >= level:
        stack.pop()
    stack.append((level, title))


def _parse_markdown_table(lines: List[str], start: int) -> Tuple[Optional[Dict[str, Any]], int]:
    """Parse a github-style markdown table starting at ``start``."""
    def cells(line: str) -> List[str]:
        trimmed = line.strip().strip("|")
        return [clean_inline(cell) for cell in trimmed.split("|")]

    header = cells(lines[start])
    index = start + 2  # skip the alignment separator row
    rows: List[List[str]] = []
    while index < len(lines) and "|" in lines[index] and lines[index].strip():
        rows.append(cells(lines[index]))
        index += 1
    if not any(has_content(cell) for cell in header) and not any(
            has_content(cell) for row in rows for cell in row):
        return None, index
    return {
        "text": render_table_text(header, rows),
        "table": {"header": header, "rows": rows},
    }, index


_HTML_BLOCK_TAGS = {"p", "div", "section", "article", "li", "td", "th", "pre", "blockquote"}
_HTML_DROP_TAGS = {"script", "style", "noscript", "svg", "form", "iframe", "nav", "footer"}


def from_html(raw: str) -> List[Dict[str, Any]]:
    """Convert HTML to blocks using tag semantics instead of raw text."""
    soup = BeautifulSoup(raw or "", "html.parser")
    for tag in soup(list(_HTML_DROP_TAGS)):
        tag.decompose()
    root = soup.body or soup
    blocks: List[Dict[str, Any]] = []
    heading_stack: List[Tuple[int, str]] = []
    seq = 0

    def heading_path() -> str:
        return " / ".join(title for _, title in heading_stack)

    def add(block_type: str, text: str, level: int = 0,
            table: Optional[Dict[str, Any]] = None) -> None:
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
            add(BLOCK_TABLE, render_table_text(header, rows),
                table={"header": header, "rows": rows})
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


def _pdf_page_lines(pages: List[str]) -> Tuple[List[List[str]], set]:
    """Split pages into lines and detect running headers/footers/page numbers."""
    all_lines: List[List[str]] = []
    for page in pages:
        all_lines.append([line.strip() for line in page.split("\n") if line.strip()])
    total = len(all_lines)
    counter: Dict[str, int] = {}
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


def from_pdf(path: str) -> Tuple[List[Dict[str, Any]], int]:
    """Convert a PDF to blocks, removing page numbers and running headers/footers."""
    reader = PdfReader(path)
    pages = [(page.extract_text() or "").strip() for page in reader.pages]
    page_lines, noise = _pdf_page_lines(pages)
    blocks: List[Dict[str, Any]] = []
    seq = 0
    for page_no, lines in enumerate(page_lines, start=1):
        kept: List[str] = []
        for line in lines:
            if line.lower() in noise:
                continue
            if _PAGE_MARKER.match(line) and not has_content(line.replace("页", "").replace("page", "")):
                continue
            kept.append(line)
        # Soft-wrapped PDF lines are re-joined; a line ending in terminal punctuation
        # or a short heading-like line starts a new paragraph.
        paragraph: List[str] = []
        for line in kept:
            paragraph.append(line)
            ends_paragraph = (
                len(line) < 40
                or line.rstrip().endswith(("。", "；", "！", "？", ".", ":", "："))
            )
            if not ends_paragraph:
                continue
            text = normalize_whitespace(clean_inline(_join_lines(paragraph)))
            paragraph = []
            if has_content(text):
                seq += 1
                blocks.append(block("B%d" % seq, seq, BLOCK_PARAGRAPH, text, "", 0, page_no))
        if paragraph:
            text = normalize_whitespace(clean_inline(_join_lines(paragraph)))
            if has_content(text):
                seq += 1
                blocks.append(block("B%d" % seq, seq, BLOCK_PARAGRAPH, text, "", 0, page_no))
    return blocks, len(pages)


def _join_lines(lines: List[str]) -> str:
    """Join lines, removing hyphenation breaks created by PDF layout."""
    text = "\n".join(lines)
    text = re.sub(r"-\n", "", text)
    return re.sub(r"\n", "", text) if _looks_wrapped(lines) else text


def _looks_wrapped(lines: List[str]) -> bool:
    """Treat consecutive short lines as one wrapped paragraph (CJK PDFs)."""
    return len(lines) > 1 and all(len(line) <= 40 for line in lines)


def from_docx(path: str) -> List[Dict[str, Any]]:
    """Convert DOCX to blocks, preserving heading levels and tables."""
    document = DocxDocument(path)
    blocks: List[Dict[str, Any]] = []
    heading_stack: List[Tuple[int, str]] = []
    seq = 0

    def heading_path() -> str:
        return " / ".join(title for _, title in heading_stack)

    def add(block_type: str, text: str, level: int = 0,
            table: Optional[Dict[str, Any]] = None) -> None:
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
            add(BLOCK_TABLE, render_table_text(header, rows),
                table={"header": header, "rows": rows})
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
            blocks.append(block("B%d" % seq, seq, BLOCK_HEADING, clean_inline(text),
                                heading_path(), level))
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


def from_text(raw: str) -> List[Dict[str, Any]]:
    """Convert plain text to paragraphs, dropping separator-only lines."""
    blocks: List[Dict[str, Any]] = []
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


def build_sections(blocks: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Derive the section outline from heading blocks."""
    sections: List[Dict[str, Any]] = []
    for item in blocks:
        if item["block_type"] != BLOCK_HEADING:
            continue
        sections.append({
            "section_id": "S%d" % len(sections),
            "heading": item["text"],
            "level": item.get("level") or 1,
            "heading_path": item.get("heading_path", ""),
            "order": item["seq"],
        })
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


def _measure(text: str, policy: Dict[str, Any]) -> int:
    """Window measurement in the unit declared by the policy."""
    return estimate_tokens(text) if policy.get("measure") == "TOKEN" else len(text)


def _block_spans(blocks: List[Dict[str, Any]]) -> Dict[str, Tuple[int, int]]:
    """Character span of every block inside the joined document text.

    The canonical text is the block texts joined by a blank line, so these offsets
    are exact and a chunk can later report where in the source it starts and ends.
    """
    spans: Dict[str, Tuple[int, int]] = {}
    cursor = 0
    for item in blocks:
        text = item.get("text") or ""
        spans[item["block_id"]] = (cursor, cursor + len(text))
        cursor += len(text) + 2
    return spans


def _locate(blocks: List[Dict[str, Any]], spans: Dict[str, Tuple[int, int]], offset: int
            ) -> Tuple[str, Optional[int]]:
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
    # Only honour the boundary when it does not throw away most of the window.
    return best if best >= int(limit * 0.6) else limit


def _next_cut(text: str, start: int, budget: int, policy: Dict[str, Any]) -> int:
    """End offset of the window that starts at ``start`` under the policy budget."""
    if start >= len(text):
        return len(text)
    if policy.get("measure") == "TOKEN":
        consumed = 0
        position = start
        raw = len(text)
        while position < len(text):
            step = min(64, len(text) - position)
            consumed += estimate_tokens(text[position:position + step])
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


def _overlap_chars(policy: Dict[str, Any]) -> int:
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


def _make_unit(seq: int, content: str, heading_path: str, page_no: Optional[int],
               block_type: str, block_ids: List[str], spans: Dict[str, Tuple[int, int]],
               policy: Dict[str, Any], parent_unit_id: Optional[str] = None,
               chunk_type: Optional[str] = None, overlap_prefix: str = "") -> Dict[str, Any]:
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


def _unit_text(items: List[Dict[str, Any]]) -> str:
    return "\n\n".join(item["text"] for item in items if item.get("text"))


def build_units(
    blocks: List[Dict[str, Any]],
    size: Optional[int] = None,
    overlap: Optional[int] = None,
    policy: Optional[Dict[str, Any]] = None,
) -> List[Dict[str, Any]]:
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


def _units_semantic(blocks: List[Dict[str, Any]], policy: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Accumulate blocks up to the window, cutting on heading changes."""
    window = int(policy["chunkSize"])
    keep_structure = bool(policy.get("keepStructure"))
    units: List[Dict[str, Any]] = []
    buffer: List[Dict[str, Any]] = []
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
        units.append(_make_unit(
            len(units) + 1, content, first.get("heading_path", ""), first.get("page_no"),
            first["block_type"], [item["block_id"] for item in buffer], {},
            policy, overlap_prefix=tail))
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
        if buffer and projected > window and (
                not keep_structure or item["heading_path"] == buffer[-1]["heading_path"]):
            flush()
        buffer.append(item)
        if _measure(_unit_text(buffer), policy) >= window:
            flush()
    flush()
    return _merge_small_units(units, policy)


def _units_sentence(blocks: List[Dict[str, Any]], policy: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Group whole sentences up to the window, so no unit ends mid-sentence."""
    window = int(policy["chunkSize"])
    units: List[Dict[str, Any]] = []
    buffer: List[str] = []
    ids: List[str] = []
    meta: Dict[str, Any] = {}

    def flush() -> None:
        if not buffer:
            return
        content = " ".join(buffer).strip()
        if not has_content(content):
            buffer.clear()
            ids.clear()
            return
        units.append(_make_unit(
            len(units) + 1, content, meta.get("heading_path", ""), meta.get("page_no"),
            meta.get("block_type", BLOCK_PARAGRAPH), list(ids), {}, policy))
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
                meta = {"heading_path": item.get("heading_path", ""),
                        "page_no": item.get("page_no"),
                        "block_type": item["block_type"]}
            elif not buffer:
                meta = {"heading_path": item.get("heading_path", ""),
                        "page_no": item.get("page_no"),
                        "block_type": item["block_type"]}
            buffer.append(piece)
            if item["block_id"] not in ids:
                ids.append(item["block_id"])
    flush()
    return _merge_small_units(units, policy)


def _units_fixed(blocks: List[Dict[str, Any]], policy: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Sliding window over the cleaned text with a real overlap."""
    spans = _block_spans(blocks)
    text = "\n\n".join(item.get("text") or "" for item in blocks)
    window = int(policy["chunkSize"])
    gap = _overlap_chars(policy)
    units: List[Dict[str, Any]] = []
    start = 0
    while start < len(text):
        end = _next_cut(text, start, window, policy)
        piece = text[start:end]
        if has_content(piece):
            heading_path, page_no = _locate(blocks, spans, start)
            block_ids = [item["block_id"] for item in blocks
                         if spans[item["block_id"]][0] < end and spans[item["block_id"]][1] > start]
            units.append(_make_unit(
                len(units) + 1, piece, heading_path, page_no, BLOCK_PARAGRAPH,
                block_ids, spans, policy))
        if end >= len(text):
            break
        start = max(end - gap, start + 1)
    return units


def _units_structure(blocks: List[Dict[str, Any]], policy: Dict[str, Any]) -> List[Dict[str, Any]]:
    """One unit per heading path; only oversized sections are split."""
    window = int(policy["chunkSize"])
    limit = max(window, int(window * 1.5))
    units: List[Dict[str, Any]] = []
    buffer: List[Dict[str, Any]] = []
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
            units.append(_make_unit(
                len(units) + 1, content, first.get("heading_path", ""), first.get("page_no"),
                first["block_type"], block_ids, {}, policy))
        else:
            for piece in _hard_split(content, window, policy):
                units.append(_make_unit(
                    len(units) + 1, piece, first.get("heading_path", ""), first.get("page_no"),
                    first["block_type"], block_ids, {}, policy))
        buffer.clear()

    for item in blocks:
        path = item.get("heading_path", "")
        if current_path is not None and path != current_path:
            flush()
        current_path = path
        buffer.append(item)
    flush()
    return units


def _units_parent_child(blocks: List[Dict[str, Any]], policy: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Section-level parent units, each followed by its window-sized children.

    Retrieval matches the precise child; the parent keeps the surrounding context
    that makes an answer readable. Parents are emitted first so persistence can link
    every child to an id that already exists.
    """
    window = int(policy["chunkSize"])
    units: List[Dict[str, Any]] = []
    groups: List[Tuple[str, List[Dict[str, Any]]]] = []
    current_path = None
    for item in blocks:
        path = item.get("heading_path", "") or ""
        if not groups or path != current_path:
            groups.append((path, []))
            current_path = path
        groups[-1][1].append(item)

    def new_unit(content: str, heading_path: str, page_no: Optional[int], block_type: str,
                 block_ids: List[str], parent_unit_id: Optional[str] = None,
                 chunk_type: Optional[str] = None) -> None:
        units.append(_make_unit(len(units) + 1, content, heading_path, page_no, block_type,
                                block_ids, {}, policy, parent_unit_id=parent_unit_id,
                                chunk_type=chunk_type))

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
            new_unit(child["content"], child["heading_path"], child["page_no"],
                     child["block_type"], child["block_ids"], parent_unit_id=parent_unit_id,
                     chunk_type=child.get("chunk_type"))
    return units


def _hard_split(content: str, window: int, policy: Dict[str, Any]) -> List[str]:
    """Split an oversized piece at sentence boundaries when the policy asks for it."""
    pieces: List[str] = []
    start = 0
    gap = _overlap_chars(policy)
    while start < len(content):
        end = _next_cut(content, start, window, policy)
        piece = content[start:end]
        if has_content(piece):
            pieces.append(piece)
        if end >= len(content):
            break
        start = max(end - gap, start + 1)
    return pieces


def _merge_small_units(units: List[Dict[str, Any]], policy: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Fold orphan fragments into a neighbour so no chunk is too thin to answer from."""
    min_size = int(policy.get("minChunkSize") or 0)
    window = int(policy["chunkSize"])
    if min_size <= 0 or len(units) < 2:
        return units
    merged: List[Dict[str, Any]] = []
    for unit in units:
        if merged and _measure(unit["content"], policy) < min_size \
                and unit.get("heading_path") == merged[-1].get("heading_path") \
                and _measure(merged[-1]["content"], policy) + _measure(
                    unit["content"], policy) <= window * 1.5:
            previous = merged[-1]
            previous["content"] = previous["content"] + "\n\n" + unit["content"]
            previous["content_hash"] = hashlib.sha256(
                previous["content"].encode("utf-8")).hexdigest()
            previous["block_ids"] = previous["block_ids"] + unit["block_ids"]
            previous["token_count"] = estimate_tokens(previous["content"])
            continue
        merged.append(unit)
    return merged


def _finalize(units: List[Dict[str, Any]], blocks: List[Dict[str, Any]],
              policy: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Renumber units and fill spans / token counts from the canonical text."""
    spans = _block_spans(blocks)
    result: List[Dict[str, Any]] = []
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


def infer_title(blocks: List[Dict[str, Any]], filename: str) -> str:
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
    blocks: List[Dict[str, Any]],
    page_count: int = 0,
    policy: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
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
            "chunkStrategy": (units[0].get("strategy") if units
                              else (policy or {}).get("strategy", "SEMANTIC")),
            "chunkPolicy": policy or chunk_policy.default_policy(),
        },
    }


def to_legacy_chunks(standard: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Render units in the historical chunk shape so persistence stays unchanged."""
    return [{
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
    } for unit in standard.get("units", [])]
