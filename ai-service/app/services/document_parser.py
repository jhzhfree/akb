"""Document parsing: turn any supported source into the standard document model.

The parser owns no cleaning rules of its own. Every format normalizer lives in
:mod:`app.services.standard_document` so that markdown, HTML, PDF, DOCX and plain
text all converge on one canonical representation. Callers receive both the modern
``standard`` model and the historical ``text``/``chunks`` keys, where ``chunks`` are
now the cleaned standard units rather than raw text slices.
"""

import hashlib
from pathlib import Path
from typing import Any, Dict

from app.core.config import settings
from app.services.standard_document import (
    SOURCE_FORMATS,
    build_standard_document,
    from_docx,
    from_html,
    from_markdown,
    from_pdf,
    from_text,
    to_legacy_chunks,
)

SUPPORTED_EXTENSIONS = set(SOURCE_FORMATS)


class DocumentParser:
    """Parse supported files into the canonical standard document model."""

    def parse(self, file_path: str, filename: str = "") -> Dict[str, Any]:
        path = Path(file_path)
        ext = path.suffix.lower()
        if ext not in SUPPORTED_EXTENSIONS:
            raise ValueError("暂不支持的文件类型: %s" % ext)

        if ext == ".pdf":
            blocks, page_count = from_pdf(str(path))
        elif ext == ".docx":
            blocks, page_count = from_docx(str(path)), 0
        elif ext in {".html", ".htm"}:
            blocks, page_count = from_html(path.read_text(encoding="utf-8", errors="ignore")), 0
        elif ext in {".md", ".markdown"}:
            blocks, page_count = from_markdown(path.read_text(encoding="utf-8", errors="ignore")), 0
        else:
            blocks, page_count = from_text(path.read_text(encoding="utf-8", errors="ignore")), 0

        name = filename or path.name
        sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
        standard = build_standard_document(name, ext, sha256, blocks, page_count)
        return {
            "filename": name,
            "extension": ext,
            "sha256": sha256,
            "page_count": page_count,
            # Clean text: format markers are gone before anything consumes it.
            "text": standard["text"],
            "chunks": to_legacy_chunks(standard),
            "standard": standard,
        }

    @property
    def chunk_size(self) -> int:
        return int(settings.chunk_size)
