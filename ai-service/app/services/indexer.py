"""Retrieval-document normalization and fan-out indexing to retrieval views.

The indexer is intentionally stateless. MySQL owns business truth; this module
normalizes authoritative objects into retrieval documents and writes local,
Elasticsearch, Milvus, and graph views. Callers should normally invoke it through
the Outbox worker rather than from a database transaction.
"""

import asyncio
from typing import Any, Dict, List, Optional

from app.core.config import settings
from app.services.embedding import get_embedding_provider
from app.services.graph_provider import get_graph_provider
from app.services.retrieval.external import (
    get_elasticsearch_provider,
    get_milvus_provider,
)
from app.storage.index_store import store


class KnowledgeIndexer:
    """Normalize authoritative objects and synchronize rebuildable indexes."""

    @staticmethod
    def _external_providers():
        """Return external retrieval providers in a stable diagnostic order."""
        return [
            ("elasticsearch", get_elasticsearch_provider()),
            ("milvus", get_milvus_provider()),
        ]

    async def _sync_external(self, docs: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Best-effort fan-out to external retrieval views.

        In ``external_required`` mode an external failure aborts delivery so the
        Outbox event can retry. In fallback mode the error is returned for
        diagnostics while local retrieval remains available.
        """
        if settings.retrieval_mode not in {"auto", "external", "dual"}:
            return {}

        results: Dict[str, Any] = {}
        for name, provider in self._external_providers():
            try:
                results[name] = await provider.upsert(docs)
            except Exception as exc:
                results[name] = {"error": str(exc)}
                if settings.retrieval_external_required:
                    raise
        return results

    async def delete_document(
        self,
        knowledge_base_id: int,
        document_id: int,
    ) -> Dict[str, Any]:
        """Delete one document from all retrieval views."""
        total = store.delete_document(knowledge_base_id, document_id)
        external: Dict[str, Any] = {}
        for name, provider in self._external_providers():
            try:
                external[name] = await provider.delete_document(
                    knowledge_base_id,
                    document_id,
                )
            except Exception as exc:
                external[name] = {"error": str(exc)}
                if settings.retrieval_external_required:
                    raise
        return {"total": total, "external": external}

    async def rebuild_external(self, knowledge_base_id: int) -> Dict[str, Any]:
        """Rebuild external indexes from the local fallback view.

        This path exists for development/recovery. Production schema releases
        should use the authoritative MySQL snapshot rebuild endpoint instead.
        """
        docs = store.load(knowledge_base_id)
        external = await self._sync_external(docs)
        return {
            "knowledgeBaseId": knowledge_base_id,
            "documents": len(docs),
            "external": external,
        }

    async def index_chunks(
        self,
        knowledge_base_id: int,
        document_id: int,
        source_name: str,
        source_uri: str,
        chunks: List[Dict[str, Any]],
        permission_tags: Optional[List[str]] = None,
        index_version: int = 0,
    ) -> Dict[str, Any]:
        """Embed and index authoritative document chunks."""
        contents = [chunk.get("content", "") for chunk in chunks]
        vectors = await get_embedding_provider().embed(contents)
        docs: List[Dict[str, Any]] = []

        for chunk, vector in zip(chunks, vectors):
            chunk_id = (
                chunk.get("content_unit_id")
                or chunk.get("id")
                or "DOC%s_CHUNK%s" % (document_id, chunk.get("seq"))
            )
            docs.append(
                {
                    "id": str(chunk_id),
                    "knowledge_base_id": knowledge_base_id,
                    "document_id": document_id,
                    "content_unit_id": chunk.get("content_unit_id"),
                    "object_type": "CONTENT_UNIT",
                    "title": chunk.get("heading_path") or source_name,
                    "content": chunk.get("content", ""),
                    "source_name": source_name,
                    "source_uri": source_uri,
                    "page_no": chunk.get("page_no"),
                    "heading_path": chunk.get("heading_path", ""),
                    "permission_tags": permission_tags or [],
                    "index_version": int(
                        chunk.get("index_version") or index_version or 0
                    ),
                    "metadata": {"content_hash": chunk.get("content_hash")},
                    "vector": vector,
                }
            )

        total = store.upsert(knowledge_base_id, docs)
        external = await self._sync_external(docs)
        return {
            "indexed": len(docs),
            "total": total,
            "external": external,
        }

    async def index_knowledge_objects(
        self,
        knowledge_base_id: int,
        cards: Optional[List[Dict[str, Any]]] = None,
        claims: Optional[List[Dict[str, Any]]] = None,
        entities: Optional[List[Dict[str, Any]]] = None,
        relations: Optional[List[Dict[str, Any]]] = None,
        index_version: int = 0,
    ) -> Dict[str, Any]:
        """Index cards/claims and synchronize graph entities/relations."""
        rows = self._knowledge_rows(
            knowledge_base_id,
            cards or [],
            claims or [],
            index_version,
        )

        graph = get_graph_provider()
        if not rows:
            graph_result = await asyncio.to_thread(
                graph.upsert_knowledge,
                knowledge_base_id,
                entities,
                relations,
                claims,
                cards,
            )
            return {
                "indexed": 0,
                "total": len(store.load(knowledge_base_id)),
                "graph": graph_result,
            }

        vectors = await get_embedding_provider().embed(
            [row["content"] for row in rows]
        )
        for row, vector in zip(rows, vectors):
            row["vector"] = vector

        total = store.upsert(knowledge_base_id, rows)
        external = await self._sync_external(rows)
        graph_result = await asyncio.to_thread(
            graph.upsert_knowledge,
            knowledge_base_id,
            entities,
            relations,
            claims,
            cards,
        )
        return {
            "indexed": len(rows),
            "total": total,
            "graph": graph_result,
            "external": external,
        }

    def _knowledge_rows(
        self,
        knowledge_base_id: int,
        cards: List[Dict[str, Any]],
        claims: List[Dict[str, Any]],
        index_version: int,
    ) -> List[Dict[str, Any]]:
        """Convert governed cards/claims into the common retrieval schema."""
        rows: List[Dict[str, Any]] = []
        for card in cards:
            rows.append(
                {
                    "id": card.get("knowledge_code") or card.get("id"),
                    "knowledge_base_id": knowledge_base_id,
                    "object_type": "CARD",
                    "title": card.get("title", ""),
                    "content": card.get("content") or card.get("summary", ""),
                    "source_name": "知识卡片",
                    "source_uri": "",
                    "permission_tags": [],
                    "status": card.get("status", "DRAFT"),
                    "index_version": int(
                        card.get("index_version") or index_version or 0
                    ),
                    "metadata": {"confidence": card.get("confidence", 0)},
                }
            )

        for claim in claims:
            content = "%s %s %s" % (
                claim.get("subject", ""),
                claim.get("predicate", ""),
                claim.get("object", claim.get("object_value", "")),
            )
            rows.append(
                {
                    "id": claim.get("claim_code") or claim.get("id"),
                    "knowledge_base_id": knowledge_base_id,
                    "object_type": "CLAIM",
                    "title": "断言：" + claim.get("subject", ""),
                    "content": content,
                    "source_name": "断言",
                    "source_uri": "",
                    "permission_tags": [],
                    "status": claim.get("status", "UNCERTAIN"),
                    "index_version": int(
                        claim.get("index_version") or index_version or 0
                    ),
                    "metadata": {"confidence": claim.get("confidence", 0)},
                }
            )
        return rows

    async def update_object_status(
        self,
        knowledge_base_id: int,
        object_type: str,
        object_id: str,
        status: str,
        index_version: int,
    ) -> Dict[str, Any]:
        """Apply a version-aware status update to every retrieval view."""
        local_updated = store.update_object_status(
            knowledge_base_id,
            object_id,
            status,
            index_version,
        )
        external: Dict[str, Any] = {}
        for name, provider in self._external_providers():
            try:
                external[name] = await provider.update_object_status(
                    knowledge_base_id,
                    object_id,
                    status,
                    index_version,
                )
            except Exception as exc:
                external[name] = {"error": str(exc)}
                if settings.retrieval_external_required:
                    raise

        graph = await asyncio.to_thread(
            get_graph_provider().update_object_status,
            knowledge_base_id,
            object_type,
            object_id,
            status,
            index_version,
        )
        return {
            "objectId": object_id,
            "status": status,
            "indexVersion": index_version,
            "localUpdated": local_updated,
            "external": external,
            "graph": graph,
        }

    async def rebuild_schema(
        self,
        knowledge_base_id: int,
        schema_version: str,
    ) -> Dict[str, Any]:
        """Development helper that rebuilds a schema from the local fallback store."""
        docs = store.load(knowledge_base_id)
        return await self.rebuild_schema_batch(
            knowledge_base_id,
            schema_version,
            docs,
            True,
        )

    async def rebuild_schema_batch(
        self,
        knowledge_base_id: int,
        schema_version: str,
        docs: List[Dict[str, Any]],
        reset: bool = False,
    ) -> Dict[str, Any]:
        """Write one authoritative snapshot page into green physical targets."""
        normalized = [
            self._normalize_snapshot_document(knowledge_base_id, doc)
            for doc in docs or []
        ]
        if normalized:
            vectors = await get_embedding_provider().embed(
                [item.get("content", "") for item in normalized]
            )
            for item, vector in zip(normalized, vectors):
                item["vector"] = vector

        elasticsearch = get_elasticsearch_provider()
        milvus = get_milvus_provider()
        os_target = elasticsearch.schema_target(schema_version)
        qd_target = milvus.schema_target(schema_version)
        if reset:
            await self._reset_schema_targets(
                elasticsearch,
                milvus,
                os_target,
                qd_target,
            )

        os_result = (
            await elasticsearch.upsert(normalized, target=os_target)
            if normalized
            else {"provider": "elasticsearch", "indexed": 0, "target": os_target}
        )
        qd_result = (
            await milvus.upsert(normalized, target=qd_target)
            if normalized
            else {"provider": "milvus", "indexed": 0, "target": qd_target}
        )
        os_result["target"] = os_target
        qd_result["target"] = qd_target
        return {
            "knowledgeBaseId": knowledge_base_id,
            "schemaVersion": schema_version,
            "documents": len(normalized),
            "elasticsearch": os_result,
            "milvus": qd_result,
            "elasticsearchCount": int(os_result.get("indexed") or 0),
            "milvusCount": int(qd_result.get("indexed") or 0),
        }

    @staticmethod
    def _normalize_snapshot_document(
        knowledge_base_id: int,
        doc: Dict[str, Any],
    ) -> Dict[str, Any]:
        """Normalize Java/MySQL snapshot field names into the retrieval schema."""
        item = dict(doc)
        item["knowledge_base_id"] = int(
            item.get("knowledge_base_id")
            or item.get("knowledgeBaseId")
            or knowledge_base_id
        )
        item["id"] = str(
            item.get("id") or item.get("objectId") or item.get("object_id")
        )
        item["object_type"] = item.get("object_type") or item.get("objectType")
        item["document_id"] = item.get("document_id") or item.get("documentId")
        item["content_unit_id"] = (
            item.get("content_unit_id") or item.get("contentUnitId")
        )
        item["source_name"] = item.get("source_name") or item.get("sourceName") or ""
        item["source_uri"] = item.get("source_uri") or item.get("sourceUri") or ""
        item["page_no"] = item.get("page_no") or item.get("pageNo")
        item["heading_path"] = (
            item.get("heading_path") or item.get("headingPath") or ""
        )
        raw_tags = item.get("permission_tags") or item.get("permissionTags") or []
        if isinstance(raw_tags, str):
            stripped = raw_tags.strip()
            if stripped.startswith("["):
                try:
                    import json
                    raw_tags = json.loads(stripped)
                except (TypeError, ValueError):
                    raw_tags = [part.strip() for part in stripped.split(",") if part.strip()]
            else:
                raw_tags = [part.strip() for part in stripped.split(",") if part.strip()]
        item["permission_tags"] = list(raw_tags or [])
        item["metadata"] = {"content_hash": item.get("contentHash")}
        return item

    @staticmethod
    async def _reset_schema_targets(
        elasticsearch,
        milvus,
        os_target: str,
        qd_target: str,
    ) -> None:
        """Reset green physical targets before the first snapshot page."""
        if (await elasticsearch.client.head("/%s" % os_target)).status_code == 200:
            await elasticsearch.client.delete("/%s" % os_target)
        if qd_target in await milvus.list_collections():
            await milvus._post(
                "/v2/vectordb/collections/drop",
                {"collectionName": qd_target},
            )
        await elasticsearch.ensure_index(os_target)
        await milvus.ensure_collection(qd_target)

    async def activate_schema(
        self,
        knowledge_base_id: int,
        schema_version: str,
    ) -> Dict[str, Any]:
        """Atomically activate validated Elasticsearch and Milvus green targets."""
        os_result = await get_elasticsearch_provider().activate_target(schema_version)
        qd_result = await get_milvus_provider().activate_target(schema_version)
        return {
            "knowledgeBaseId": knowledge_base_id,
            "schemaVersion": schema_version,
            "elasticsearch": os_result,
            "milvus": qd_result,
        }

    async def external_stats(self, knowledge_base_id: int) -> Dict[str, Any]:
        """Return object counts for drift diagnostics across retrieval views."""
        results: Dict[str, Any] = {}
        for name, provider in self._external_providers():
            try:
                results[name] = await provider.count(knowledge_base_id)
            except Exception as exc:
                results[name] = {"provider": name, "error": str(exc)}
        results["local"] = {
            "provider": "local",
            "count": len(store.load(knowledge_base_id)),
        }
        return results
