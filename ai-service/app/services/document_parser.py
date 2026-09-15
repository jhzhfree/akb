"""Document parsing and chunk-normalization pipeline for supported source formats."""

import hashlib
import re
from pathlib import Path
from typing import Dict, List, Any
from bs4 import BeautifulSoup
from pypdf import PdfReader
from docx import Document as DocxDocument
from app.core.config import settings

SUPPORTED_EXTENSIONS = {".pdf", ".docx", ".md", ".markdown", ".html", ".htm", ".txt"}


class DocumentParser:
    """Parse supported files and produce semantically useful content chunks."""
    def parse(self, file_path: str, filename: str = "") -> Dict[str, Any]:
        path = Path(file_path)
        ext = path.suffix.lower()
        if ext not in SUPPORTED_EXTENSIONS:
            raise ValueError("暂不支持的文件类型: %s" % ext)
        if ext == ".pdf":
            pages = self._pdf(path)
        elif ext == ".docx":
            pages = self._docx(path)
        elif ext in {".html", ".htm"}:
            pages = self._html(path)
        else:
            pages = self._text(path)
        chunks = self._chunk_pages(pages)
        full_text = "\n\n".join(p["text"] for p in pages)
        return {
            "filename": filename or path.name,
            "extension": ext,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "page_count": len(pages),
            "text": full_text,
            "chunks": chunks,
        }

    def _pdf(self, path: Path):
        reader = PdfReader(str(path))
        result = []
        for i, page in enumerate(reader.pages):
            text = (page.extract_text() or "").strip()
            if text:
                result.append({"page_no": i + 1, "heading_path": "", "text": text})
        return result

    def _docx(self, path: Path):
        doc = DocxDocument(str(path))
        blocks = []
        heading = []
        for p in doc.paragraphs:
            text = p.text.strip()
            if not text:
                continue
            style = (p.style.name or "") if p.style else ""
            if style.lower().startswith("heading"):
                heading = [text]
                continue
            blocks.append({"page_no": None, "heading_path": " / ".join(heading), "text": text})
        return blocks

    def _html(self, path: Path):
        soup = BeautifulSoup(path.read_text(encoding="utf-8", errors="ignore"), "html.parser")
        for tag in soup(["script", "style", "noscript"]):
            tag.decompose()
        text = "\n".join(x.strip() for x in soup.get_text("\n").splitlines() if x.strip())
        return [{"page_no": None, "heading_path": "", "text": text}]

    def _text(self, path: Path):
        return [{"page_no": None, "heading_path": "", "text": path.read_text(encoding="utf-8", errors="ignore")}]

    def _chunk_pages(self, pages: List[Dict[str, Any]]):
        chunks = []
        seq = 0
        for page in pages:
            text = self._normalize(page["text"])
            for part in self._semantic_chunks(text):
                seq += 1
                chunks.append({
                    "seq": seq,
                    "page_no": page.get("page_no"),
                    "heading_path": page.get("heading_path", ""),
                    "content": part,
                    "content_hash": hashlib.sha256(part.encode("utf-8")).hexdigest(),
                })
        return chunks

    def _normalize(self, text):
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        text = re.sub(r"[ \t]+", " ", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()

    def _semantic_chunks(self, text):
        size = settings.chunk_size
        overlap = settings.chunk_overlap
        paragraphs = [p.strip() for p in re.split(r"\n{2,}", text) if p.strip()]
        if not paragraphs:
            return []
        out = []
        buf = ""
        for p in paragraphs:
            if len(buf) + len(p) + 2 <= size:
                buf = (buf + "\n\n" + p).strip()
                continue
            if buf:
                out.append(buf)
            if len(p) <= size:
                buf = p
            else:
                start = 0
                while start < len(p):
                    end = min(start + size, len(p))
                    out.append(p[start:end])
                    if end == len(p):
                        break
                    start = max(end - overlap, start + 1)
                buf = ""
        if buf:
            out.append(buf)
        return out
