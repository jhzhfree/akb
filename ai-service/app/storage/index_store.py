"""Thread-safe local JSON retrieval store used as development, recovery, and fallback infrastructure."""

import json
import threading
from pathlib import Path
from typing import Any, Dict, List, Tuple

from app.core.config import INDEX_DIR


class IndexStore:
    """Small persistent index with an in-process immutable snapshot cache.

    V1.1 still uses JSON as the local fallback store. Re-reading and parsing the
    complete file on every query is unnecessarily expensive, so the store keeps
    one snapshot per knowledge base and invalidates it after each write.
    """

    def __init__(self):
        self._lock = threading.RLock()
        self._cache: Dict[str, Tuple[int, List[Dict[str, Any]]]] = {}
        self._generation: Dict[str, int] = {}

    def _key(self, kb: Any) -> str:
        return str(kb)

    def _path(self, kb: Any) -> Path:
        return INDEX_DIR / ("kb_%s.json" % kb)

    def generation(self, kb: Any) -> int:
        with self._lock:
            return self._generation.get(self._key(kb), 0)

    def load(self, kb: Any) -> List[Dict[str, Any]]:
        key = self._key(kb)
        path = self._path(kb)
        if not path.exists():
            return []

        with self._lock:
            generation = self._generation.get(key, 0)
            cached = self._cache.get(key)
            if cached and cached[0] == generation:
                return cached[1]

            try:
                docs = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError, TypeError):
                docs = []
            self._cache[key] = (generation, docs)
            return docs

    def save(self, kb: Any, docs: List[Dict[str, Any]]) -> None:
        key = self._key(kb)
        path = self._path(kb)
        tmp = path.with_suffix(".tmp")
        path.parent.mkdir(parents=True, exist_ok=True)

        payload = json.dumps(docs, ensure_ascii=False, separators=(",", ":"))
        with self._lock:
            tmp.write_text(payload, encoding="utf-8")
            tmp.replace(path)
            generation = self._generation.get(key, 0) + 1
            self._generation[key] = generation
            self._cache[key] = (generation, docs)

    def upsert(self, kb: Any, new_docs: List[Dict[str, Any]]) -> int:
        docs = self.load(kb)
        by_id = {str(item["id"]): item for item in docs}
        for item in new_docs:
            by_id[str(item["id"])] = item
        merged = list(by_id.values())
        self.save(kb, merged)
        return len(merged)

    def delete_document(self, kb: Any, document_id: Any) -> int:
        docs = [
            item
            for item in self.load(kb)
            if str(item.get("document_id")) != str(document_id)
        ]
        self.save(kb, docs)
        return len(docs)

    def delete_knowledge_base(self, kb: Any) -> int:
        """Drop every retrieval document of one knowledge base.

        A knowledge-base purge removes the whole fallback view instead of filtering
        document by document: the file is the fallback view, so deleting it is both
        faster and guarantees no orphan object survives.
        """
        removed = len(self.load(kb))
        key = self._key(kb)
        path = self._path(kb)
        with self._lock:
            self._cache.pop(key, None)
            self._generation[key] = self._generation.get(key, 0) + 1
            try:
                if path.exists():
                    path.unlink()
            except OSError:
                # A locked or already removed file only leaves an empty view behind.
                pass
        return removed

    def update_object_status(
        self, kb: Any, object_id: Any, status: str, index_version: int
    ) -> bool:
        docs = self.load(kb)
        changed = False
        for item in docs:
            if str(item.get("id")) != str(object_id):
                continue
            current = int(item.get("index_version") or 0)
            if int(index_version) < current:
                return False
            item["status"] = status
            item["index_version"] = int(index_version)
            changed = True
            break
        if changed:
            self.save(kb, docs)
        return changed

    def update_object_acl(self, kb: Any, object_id: Any, permission_tags, index_version: int) -> bool:
        docs=self.load(kb); changed=False
        for item in docs:
            if str(item.get("id")) != str(object_id): continue
            if int(index_version) < int(item.get("index_version") or 0): return False
            item["permission_tags"]=list(permission_tags or []); item["index_version"]=int(index_version); changed=True; break
        if changed: self.save(kb,docs)
        return changed


store = IndexStore()
