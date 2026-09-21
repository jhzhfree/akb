"""Published knowledge schema vocabulary used by extraction.

The platform owns the dictionary of knowledge, entity and relation types; the
extraction side used to own a hard-coded tuple of relation labels instead. Once a
knowledge base publishes its schema, extraction reads that published vocabulary, so
"which relation types are allowed" is answered by configuration rather than by a
code deployment. Unpublished knowledge bases keep the built-in vocabulary.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

from app.core.config import DATA_DIR

SCHEMA_DIR = Path(DATA_DIR) / "schema"

# Fallback vocabulary, identical to the tuple extraction always had.
DEFAULT_RELATION_VOCABULARY: tuple[str, ...] = (
    "RELATED_TO",
    "PART_OF",
    "DEPENDS_ON",
    "USES",
    "CAUSES",
    "BELONGS_TO",
    "LOCATED_IN",
    "DESCRIBES",
    "CONTAINS",
    "MANAGES",
)
DEFAULT_ENTITY_TYPES: tuple[str, ...] = ("TERM", "PRODUCT", "SYSTEM", "METRIC", "ORG", "ROLE")

_LOCK = threading.Lock()
_CACHE: dict[int, dict[str, Any]] = {}


def apply_schema(payload: dict[str, Any]) -> dict[str, Any]:
    """Persist a published schema and refresh the in-memory vocabulary.

    Accepts either ``{"knowledgeBaseId": n, "types": [...]}`` from the backend or a
    pre-shaped ``{"relationTypes": [...], "entityTypes": [...]}`` payload, so the
    endpoint stays usable from tests and from the console alike.
    """
    try:
        knowledge_base_id = int(payload.get("knowledgeBaseId") or 0)
    except (TypeError, ValueError):
        knowledge_base_id = 0

    types = payload.get("types")
    relation_types: list[str] = []
    entity_types: list[str] = []
    knowledge_types: list[str] = []
    if isinstance(types, list):
        for item in types:
            if not isinstance(item, dict):
                continue
            kind = str(item.get("kind") or "").upper()
            code = str(item.get("typeCode") or item.get("entity_type") or "").strip().upper()
            status = str(item.get("status") or "ACTIVE").upper()
            if not code or status == "DEPRECATED":
                continue
            if kind == "RELATION_TYPE":
                relation_types.append(code)
            elif kind == "ENTITY_TYPE":
                entity_types.append(code)
            elif kind == "KNOWLEDGE_TYPE":
                knowledge_types.append(code)

    # Explicit overrides win over the type list, and defaults keep the vocabulary
    # usable when a schema only customises one half.
    relation_types = _clean(payload.get("relationTypes")) or relation_types or list(DEFAULT_RELATION_VOCABULARY)
    entity_types = _clean(payload.get("entityTypes")) or entity_types or list(DEFAULT_ENTITY_TYPES)

    schema = {
        "knowledgeBaseId": knowledge_base_id,
        "version": str(payload.get("version") or ""),
        "relationTypes": relation_types,
        "entityTypes": entity_types,
        "knowledgeTypes": knowledge_types,
    }
    with _LOCK:
        _CACHE[knowledge_base_id] = schema
    try:
        SCHEMA_DIR.mkdir(parents=True, exist_ok=True)
        (SCHEMA_DIR / f"kb_{knowledge_base_id}.json").write_text(
            json.dumps(schema, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except OSError:
        # The cached copy is enough for this process; persistence is best effort.
        pass
    return schema


def load_schema(knowledge_base_id: int | None) -> dict[str, Any] | None:
    """Read a published schema, from cache first and disk second."""
    try:
        key = int(knowledge_base_id or 0)
    except (TypeError, ValueError):
        return None
    with _LOCK:
        cached = _CACHE.get(key)
    if cached is not None:
        return cached
    path = SCHEMA_DIR / f"kb_{key}.json"
    if not path.exists():
        return None
    try:
        schema = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    with _LOCK:
        _CACHE[key] = schema
    return schema


def relation_vocabulary(knowledge_base_id: int | None = None) -> tuple[str, ...]:
    """Relation labels extraction may emit for one knowledge base."""
    schema = load_schema(knowledge_base_id)
    if not schema:
        return DEFAULT_RELATION_VOCABULARY
    values = _clean(schema.get("relationTypes"))
    return tuple(values) if values else DEFAULT_RELATION_VOCABULARY


def entity_types(knowledge_base_id: int | None = None) -> tuple[str, ...]:
    """Entity types extraction may emit for one knowledge base."""
    schema = load_schema(knowledge_base_id)
    if not schema:
        return DEFAULT_ENTITY_TYPES
    values = _clean(schema.get("entityTypes"))
    return tuple(values) if values else DEFAULT_ENTITY_TYPES


def clear_cache() -> None:
    with _LOCK:
        _CACHE.clear()


def _clean(values: Any) -> list[str]:
    if not isinstance(values, list):
        return []
    cleaned = []
    for value in values:
        text = str(value or "").strip().upper()
        if text and text not in cleaned:
            cleaned.append(text)
    return cleaned
