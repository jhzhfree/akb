"""Milvus dense-vector retrieval provider.

Uses Milvus REST v2 so the AI service remains dependency-light. MySQL is the
authoritative store; Milvus collections are rebuildable retrieval projections.
Scalar ACL/status filters are applied before ANN Top-K.
"""
import logging
from functools import lru_cache
from typing import Any, Dict, Iterable, List, Optional
import httpx
from app.core.config import settings
from app.services.retrieval.provider_common import candidate

logger = logging.getLogger(__name__)

class MilvusProvider:
    """Dense-vector provider backed by Milvus REST v2."""

    def __init__(self) -> None:
        self.enabled = settings.milvus_enabled
        headers = {"Authorization": "Bearer %s" % settings.milvus_token} if settings.milvus_token else {}
        self.client = httpx.AsyncClient(
            base_url=settings.milvus_url, headers=headers,
            timeout=settings.retrieval_timeout_seconds,
        )
        self.collection = settings.milvus_collection
        self.read_alias = settings.milvus_read_alias
        self._active_read_collection: Optional[str] = None

    async def _post(self, path: str, body: Dict[str, Any]) -> Dict[str, Any]:
        response = await self.client.post(path, json=body)
        response.raise_for_status()
        payload = response.json()
        if int(payload.get("code", 0)) != 0:
            raise RuntimeError("Milvus REST error: %s" % payload)
        return payload

    async def available(self) -> bool:
        if not self.enabled:
            return False
        try:
            await self._post("/v2/vectordb/collections/list", {})
            return True
        except Exception as exc:
            logger.debug("Milvus availability check failed: %s", exc)
            return False

    async def list_collections(self) -> List[str]:
        payload = await self._post("/v2/vectordb/collections/list", {})
        return list(payload.get("data") or [])

    async def ensure_collection(self, target: Optional[str] = None) -> None:
        name = target or self.collection
        if name in await self.list_collections():
            return
        body = {
            "collectionName": name,
            "dimension": settings.embedding_dim,
            "metricType": "COSINE",
            "primaryFieldName": "id",
            "vectorFieldName": "vector",
            "idType": "VarChar",
            "autoID": False,
            "enableDynamicField": True,
        }
        await self._post("/v2/vectordb/collections/create", body)

    def schema_target(self, schema_version: str) -> str:
        return "%s__%s" % (self.collection, schema_version.lower())

    async def upsert(self, docs: List[Dict[str, Any]], target: Optional[str] = None) -> Dict[str, Any]:
        vector_docs = [doc for doc in docs if doc.get("vector")]
        if not self.enabled or not vector_docs:
            return {"provider": "milvus", "indexed": 0, "skipped": True}
        name = target or self.collection
        await self.ensure_collection(name)
        total = 0
        batch_size = max(1, settings.retrieval_batch_size)
        for start in range(0, len(vector_docs), batch_size):
            rows = []
            for doc in vector_docs[start:start + batch_size]:
                row = {key: value for key, value in doc.items() if key != "vector"}
                # Dynamic fields are stored in Milvus JSON metadata. A sentinel makes
                # public visibility explicit and avoids ambiguous empty-array filters.
                row["permission_tags"] = list(doc.get("permission_tags") or ["__public__"])
                row["id"] = "%s:%s" % (doc.get("knowledge_base_id"), doc.get("id"))
                row["object_id"] = str(doc.get("id"))
                row["vector"] = doc["vector"]
                rows.append(row)
            await self._post("/v2/vectordb/entities/upsert", {"collectionName": name, "data": rows})
            total += len(rows)
        return {"provider": "milvus", "indexed": total}

    async def rebuild_target(self, docs: List[Dict[str, Any]], schema_version: str) -> Dict[str, Any]:
        target = self.schema_target(schema_version)
        if target in await self.list_collections():
            await self._post("/v2/vectordb/collections/drop", {"collectionName": target})
        await self.ensure_collection(target)
        result = await self.upsert(docs, target)
        result["target"] = target
        return result


    async def target_manifest(self, knowledge_base_id: int, schema_version: str):
        """Calculate deterministic ACL/content manifest for one physical collection."""
        import hashlib
        import json
        collection = self.schema_target(schema_version)
        result = await self._post("/v2/vectordb/entities/query", {
            "collectionName": collection,
            "filter": "knowledge_base_id == %d" % int(knowledge_base_id),
            "outputFields": ["object_id", "permission_tags", "object_key",
                             "content_hash", "status", "generation_id"],
            "limit": 10000,
        })
        rows = result.get("data") or []
        digest = hashlib.sha256()
        for row in sorted(rows, key=lambda item: str(item.get("object_key") or "")):
            tags = [tag for tag in (row.get("permission_tags") or []) if tag != "__public__"]
            canonical = {"objectKey": row.get("object_key"),
                         "contentHash": row.get("content_hash") or "",
                         "status": row.get("status"), "permissionTags": sorted(tags)}
            digest.update((json.dumps(canonical, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        generations = sorted({str(row.get("generation_id")) for row in rows if row.get("generation_id")})
        return {"provider": "milvus", "manifestHash": digest.hexdigest(),
                "count": len(rows), "generations": generations}

    async def qualify_acl_target(self, knowledge_base_id: int, schema_version: str,
                                 permission_tags, admin=False):
        """Scan green Milvus scalar ACL fields without ANN ranking."""
        collection = self.schema_target(schema_version)
        result = await self._post("/v2/vectordb/entities/query", {
            "collectionName": collection,
            "filter": "knowledge_base_id == %d" % int(knowledge_base_id),
            "outputFields": ["id", "object_id", "permission_tags"], "limit": 10000})
        rows = result.get("data") or []
        caller = set(permission_tags or [])
        return [str(row.get("object_id") or row.get("id")) for row in rows if admin or
                not (row.get("permission_tags") or []) or
                caller.intersection(row.get("permission_tags") or [])]

    async def activate_target(self, schema_version: str) -> Dict[str, Any]:
        target = self.schema_target(schema_version)
        if target not in await self.list_collections():
            raise RuntimeError("Milvus target does not exist: %s" % target)
        aliases = await self._post("/v2/vectordb/aliases/list", {"collectionName": target})
        # Alter is atomic from the caller perspective; create is used for first activation.
        all_aliases = aliases.get("data") or []
        if any(item.get("aliasName") == self.read_alias for item in all_aliases if isinstance(item, dict)):
            await self._post("/v2/vectordb/aliases/alter", {
                "collectionName": target, "aliasName": self.read_alias,
            })
        else:
            try:
                await self._post("/v2/vectordb/aliases/create", {
                    "collectionName": target, "aliasName": self.read_alias,
                })
            except RuntimeError:
                await self._post("/v2/vectordb/aliases/alter", {
                    "collectionName": target, "aliasName": self.read_alias,
                })
        self._active_read_collection = self.read_alias
        return {"provider": "milvus", "alias": self.read_alias, "target": target, "activated": True}

    async def read_collection(self) -> str:
        return self._active_read_collection or self.read_alias or self.collection

    def _filter_expr(self, kb: int, tags: Optional[Iterable[str]],
                     object_type: Optional[str]) -> str:
        clauses = ["knowledge_base_id == %d" % kb]
        if object_type:
            safe_type = str(object_type).replace('"', '\"')
            clauses.append('object_type == "%s"' % safe_type)
        clauses.append('(status in ["APPROVED", "EFFECTIVE"] or status == "")')
        values = ["__public__"] + [str(tag).replace('"', '\"') for tag in (tags or [])]
        quoted = ", ".join('"%s"' % value for value in values)
        # permission_tags is a dynamic JSON-array field, therefore JSON_CONTAINS_ANY
        # is the correct Milvus scalar-filter operator.
        clauses.append('json_contains_any(permission_tags, [%s])' % quoted)
        return " and ".join(clauses)

    async def search(self, kb: int, vector: List[float], top_k: int,
                     permission_tags: Optional[Iterable[str]] = None,
                     object_type: Optional[str] = None) -> List[Dict[str, Any]]:
        body = {
            "collectionName": await self.read_collection(),
            "data": [vector], "annsField": "vector", "limit": top_k,
            "filter": self._filter_expr(kb, permission_tags, object_type),
            "outputFields": ["*"],
        }
        payload = await self._post("/v2/vectordb/entities/search", body)
        hits = payload.get("data") or []
        return [candidate(hit.get("entity") or hit, "vector", hit.get("distance") or 0.0, "milvus") for hit in hits]

    async def delete_document(self, kb: int, document_id: int) -> Dict[str, Any]:
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        expr = "knowledge_base_id == %d and document_id == %d" % (kb, document_id)
        await self._post("/v2/vectordb/entities/delete", {
            "collectionName": await self.read_collection(), "filter": expr,
        })
        return {"deleted": "submitted"}

    async def delete_knowledge_base(self, kb: int) -> Dict[str, Any]:
        """Delete every vector of one knowledge base, used by a knowledge-base purge."""
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        await self._post("/v2/vectordb/entities/delete", {
            "collectionName": await self.read_collection(),
            "filter": "knowledge_base_id == %d" % kb,
        })
        return {"provider": "milvus", "deleted": "submitted"}

    async def update_object_status(self, kb: int, object_id: str, status: str,
                                   index_version: int) -> Dict[str, Any]:
        # Milvus upsert is used because scalar partial-update semantics vary by release.
        # Worker per-aggregate ordering remains the stale-write guard.
        return {"provider": "milvus", "updated": 0, "requiresReindex": True,
                "objectId": object_id, "status": status, "indexVersion": index_version}

    async def update_object_acl(self, kb: int, object_id: str, permission_tags, index_version: int) -> Dict[str, Any]:
        return {
            "provider": "milvus",
            "updated": 0,
            "requiresReindex": True,
            "objectId": object_id,
            "permissionTags": list(permission_tags or []),
            "indexVersion": index_version,
        }

    async def count(self, kb: int) -> Dict[str, Any]:
        payload = await self._post("/v2/vectordb/entities/query", {
            "collectionName": await self.read_collection(),
            "filter": "knowledge_base_id == %d" % kb,
            "outputFields": ["count(*)"],
        })
        data = payload.get("data") or []
        count = int((data[0] if data else {}).get("count(*)", 0))
        return {"provider": "milvus", "count": count}

    async def stats(self) -> Dict[str, Any]:
        return {"provider": "milvus", "available": await self.available(),
                "url": settings.milvus_url, "collection": self.collection,
                "readAlias": self.read_alias, "activeRead": self._active_read_collection}

@lru_cache(maxsize=1)
def get_milvus_provider() -> MilvusProvider:
    """Return the process-wide Milvus provider."""
    return MilvusProvider()
