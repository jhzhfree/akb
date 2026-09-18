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


def build_units(
    blocks: List[Dict[str, Any]],
    size: Optional[int] = None,
    overlap: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Group blocks into retrieval/extraction units that fit the configured window.

    Tables and code blocks are never merged with prose: mixing them would make the
    resulting unit unreadable for an operator reviewing the source of a card.
    """
    window = int(size or settings.chunk_size)
    margin = int(overlap or settings.chunk_overlap)
    units: List[Dict[str, Any]] = []
    buffer: List[Dict[str, Any]] = []

    def flush() -> None:
        if not buffer:
            return
        content = _unit_text(buffer)
        if not has_content(content):
            buffer.clear()
            return
        first = buffer[0]
        units.append({
            "unit_id": "U%d" % (len(units) + 1),
            "seq": len(units) + 1,
            "heading_path": first.get("heading_path", ""),
            "page_no": first.get("page_no"),
            "block_type": first["block_type"],
            "block_ids": [item["block_id"] for item in buffer],
            "content": content,
            "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        })
        buffer.clear()

    for item in blocks:
        if item["block_type"] in {BLOCK_TABLE, BLOCK_CODE}:
            flush()
            buffer = [item]
            flush()
            buffer = []
            continue
        projected = len(_unit_text(buffer + [item]))
        if buffer and projected > window and item["heading_path"] == buffer[-1]["heading_path"]:
            flush()
        buffer.append(item)
        if len(_unit_text(buffer)) >= window:
            flush()
    flush()
    return _split_oversized(units, window, margin)


def _unit_text(items: List[Dict[str, Any]]) -> str:
    return "\n\n".join(item["text"] for item in items if item.get("text"))


def _split_oversized(
    units: List[Dict[str, Any]], window: int, margin: int
) -> List[Dict[str, Any]]:
    """Hard-split any unit still above the window and renumber the result."""
    if margin >= window:
        margin = max(0, window // 4)
    result: List[Dict[str, Any]] = []
    for unit in units:
        content = unit["content"]
        if len(content) <= window:
            result.append(unit)
            continue
        start = 0
        while start < len(content):
            piece = content[start:start + window]
            if has_content(piece):
                result.append({
                    "unit_id": "U%d" % (len(result) + 1),
                    "seq": len(result) + 1,
                    "heading_path": unit["heading_path"],
                    "page_no": unit["page_no"],
                    "block_type": unit["block_type"],
                    "block_ids": unit["block_ids"],
                    "content": piece,
                    "content_hash": hashlib.sha256(piece.encode("utf-8")).hexdigest(),
                })
            if start + window >= len(content):
                break
            start = max(start + window - margin, start + 1)
    for index, unit in enumerate(result, start=1):
        unit["unit_id"] = "U%d" % index
        unit["seq"] = index
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
) -> Dict[str, Any]:
    """Assemble the canonical document model shared by every consumer."""
    text = "\n\n".join(item["text"] for item in blocks if item.get("text"))
    units = build_units(blocks)
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
        },
    }


def to_legacy_chunks(standard: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Render units in the historical chunk shape so persistence stays unchanged."""
    return [{
        "seq": unit["seq"],
        "page_no": unit.get("page_no"),
        "heading_path": unit.get("heading_path", ""),
        "content": unit["content"],
        "content_hash": unit["content_hash"],
        "unit_id": unit["unit_id"],
        "block_type": unit.get("block_type", ""),
    } for unit in standard.get("units", [])]
