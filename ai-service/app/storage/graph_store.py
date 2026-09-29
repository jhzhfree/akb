"""Local authoritative-graph store used when no graph engine is deployed.

The authoritative graph lives in MySQL and is mirrored into Neo4j when one is configured.
Without a graph engine the graph recall channel used to fall back to plain claim search, which
is not traversal: "which customers does CVE-2026-1234 affect" needs the edges, not the sentences
that happen to share a word with the question. This store keeps the edges the platform already
extracted, so traversal works regardless of which engine is installed.

It is a retrieval view, never a source of truth: it is rebuilt from the authoritative tables on
every index event and may be dropped without losing knowledge.
"""

import json
import threading
from pathlib import Path
from typing import Any

from app.core.config import INDEX_DIR


class GraphStore:
    """Per-knowledge-base entity/relation snapshot, cached like the lexical index."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._cache: dict[str, tuple[int, dict[str, Any]]] = {}
        self._generation: dict[str, int] = {}

    def _key(self, kb: Any) -> str:
        return str(kb)

    def _path(self, kb: Any) -> Path:
        return INDEX_DIR / ("kb_%s_graph.json" % kb)

    def generation(self, kb: Any) -> int:
        with self._lock:
            return self._generation.get(self._key(kb), 0)

    def load(self, kb: Any) -> dict[str, Any]:
        """Return ``{"entities": {code: {...}}, "relations": [...]}``."""
        key = self._key(kb)
        path = self._path(kb)
        if not path.exists():
            return {"entities": {}, "relations": []}
        with self._lock:
            cached = self._cache.get(key)
            if cached and cached[0] == self._generation.get(key, 0):
                return cached[1]
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError, TypeError):
                payload = {}
            graph = {
                "entities": payload.get("entities") or {},
                "relations": payload.get("relations") or [],
            }
            self._cache[key] = (self._generation.get(key, 0), graph)
            return graph

    def upsert(self, kb: Any, entities: list[dict[str, Any]] | None, relations: list[dict[str, Any]] | None) -> int:
        """Merge one batch of entities/relations into the snapshot."""
        entities = entities or []
        relations = relations or []
        if not entities and not relations:
            return len(self.load(kb).get("relations") or [])
        graph = self.load(kb)
        merged_entities = dict(graph.get("entities") or {})
        for entity in entities:
            if not isinstance(entity, dict):
                continue
            code = str(
                entity.get("entity_code") or entity.get("entityCode") or entity.get("code") or ""
            ).strip()
            name = str(entity.get("canonical_name") or entity.get("name") or "").strip()
            if not code or not name:
                continue
            merged_entities[code] = {
                "name": name,
                "entityType": str(entity.get("entity_type") or entity.get("type") or "TERM"),
                "permissionTags": self._tags(entity.get("permission_tags") or entity.get("permissionTags")),
            }
        merged_relations = {
            self._relation_key(item): self._relation_row(item) for item in (graph.get("relations") or [])
        }
        for relation in relations:
            if not isinstance(relation, dict):
                continue
            row = self._relation_row(relation)
            if not row:
                continue
            merged_relations[self._relation_key(relation)] = row
        payload = {
            "entities": merged_entities,
            "relations": list(merged_relations.values()),
        }
        self._write(kb, payload)
        return len(payload["relations"])

    def delete_knowledge_base(self, kb: Any) -> int:
        """Drop the graph view of one knowledge base."""
        removed = len(self.load(kb).get("relations") or [])
        key = self._key(kb)
        path = self._path(kb)
        with self._lock:
            self._cache.pop(key, None)
            self._generation[key] = self._generation.get(key, 0) + 1
            try:
                if path.exists():
                    path.unlink()
            except OSError:
                pass
        return removed

    def _write(self, kb: Any, payload: dict[str, Any]) -> None:
        key = self._key(kb)
        path = self._path(kb)
        tmp = path.with_suffix(".tmp")
        path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock:
            tmp.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            tmp.replace(path)
            self._generation[key] = self._generation.get(key, 0) + 1
            self._cache[key] = (self._generation[key], payload)

    @staticmethod
    def _relation_key(relation: dict[str, Any]) -> str:
        return "%s|%s|%s" % (
            GraphStore._endpoint(relation, "source"),
            str(relation.get("relation_type") or relation.get("relationType") or "RELATED_TO"),
            GraphStore._endpoint(relation, "target"),
        )

    @staticmethod
    def _relation_row(relation: dict[str, Any]) -> dict[str, Any]:
        source = GraphStore._endpoint(relation, "source")
        target = GraphStore._endpoint(relation, "target")
        if not source or not target or source == target:
            return {}
        return {
            "source": source,
            "target": target,
            "relationType": str(relation.get("relation_type") or relation.get("relationType") or "RELATED_TO"),
            "confidence": float(relation.get("confidence") or 0.5),
            "permissionTags": GraphStore._tags(relation.get("permission_tags") or relation.get("permissionTags")),
        }

    @staticmethod
    def _tags(value: Any) -> list[str]:
        """Normalize ACL tags, which arrive either as a list or as a JSON-encoded string.

        An empty list arriving as the string ``"[]"`` is not an empty tag set when read
        character by character — every edge in the graph would look restricted, and graph
        recall would silently return nothing.
        """
        if isinstance(value, str):
            text = value.strip()
            if not text:
                return []
            try:
                parsed = json.loads(text)
            except ValueError:
                return [text]
            if isinstance(parsed, list):
                return [str(item) for item in parsed if str(item)]
            return [text] if text else []
        return [str(item) for item in (value or []) if str(item)]

    @staticmethod
    def _endpoint(relation: dict[str, Any], side: str) -> str:
        for key in (
            "%s_entity_code" % side,
            "%sEntityCode" % side,
            side,
            "subject" if side == "source" else "object",
        ):
            value = relation.get(key)
            if value:
                return str(value).strip()
        return ""


graph_store = GraphStore()
