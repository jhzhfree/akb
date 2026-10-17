"""Retrieval-document normalization and fan-out indexing to retrieval views.

The indexer is intentionally stateless. MySQL owns business truth; this module
normalizes authoritative objects into retrieval documents and writes local,
Elasticsearch, Milvus, and graph views. Callers should normally invoke it through
the Outbox worker rather than from a database transaction.
"""

import asyncio
from typing import Any

from app.core.config import settings
from app.services.embedding import get_embedding_provider
from app.services.graph_provider import get_graph_provider
from app.services.retrieval.external import (
    get_elasticsearch_provider,
    get_milvus_provider,
)
from app.storage.graph_store import graph_store
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

    async def _sync_external(self, docs: list[dict[str, Any]]) -> dict[str, Any]:
        """Best-effort fan-out to external retrieval views.

        In ``external_required`` mode an external failure aborts delivery so the
        Outbox event can retry. In fallback mode the error is returned for
        diagnostics while local retrieval remains available.
        """
        if settings.retrieval_mode not in {"auto", "external", "dual"}:
            return {}

        results: dict[str, Any] = {}
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
    ) -> dict[str, Any]:
        """Delete one document from all retrieval views.

        The normalized standard units are the write-side source for card generation, so
        they are removed with the same call: leaving them behind would let a deleted
        document keep feeding extraction and retrieval.
        """
        total = store.delete_document(knowledge_base_id, document_id)
        external: dict[str, Any] = {}
        try:
            external["standard"] = await get_elasticsearch_provider().delete_standard_document(
                knowledge_base_id, document_id
            )
        except Exception as exc:
            external["standard"] = {"error": str(exc)}
            if settings.retrieval_external_required:
                raise
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

    async def purge_knowledge_base(self, knowledge_base_id: int) -> dict[str, Any]:
        """Delete one knowledge base from every retrieval view.

        A console purge empties the authoritative tables, so leaving the retrieval
        views populated would keep answering questions with knowledge that no longer
        exists. Standard units are removed with them: they are the write-side source
        for extraction, so a surviving unit would resurrect the deleted knowledge on
        the next run.
        """
        removed = store.delete_knowledge_base(knowledge_base_id)
        external: dict[str, Any] = {}
        try:
            external["standard"] = await get_elasticsearch_provider().delete_standard_knowledge_base(knowledge_base_id)
        except Exception as exc:
            external["standard"] = {"error": str(exc)}
            if settings.retrieval_external_required:
                raise
        for name, provider in self._external_providers():
            try:
                external[name] = await provider.delete_knowledge_base(knowledge_base_id)
            except Exception as exc:
                external[name] = {"error": str(exc)}
                if settings.retrieval_external_required:
                    raise
        graph = get_graph_provider()
        try:
            graph_result = await asyncio.to_thread(graph.delete_knowledge_base, knowledge_base_id)
        except Exception as exc:
            graph_result = {"error": str(exc)}
        return {
            "knowledgeBaseId": knowledge_base_id,
            "localRemoved": removed,
            "external": external,
            "graph": graph_result,
        }

    async def rebuild_external(self, knowledge_base_id: int) -> dict[str, Any]:
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
        chunks: list[dict[str, Any]],
        permission_tags: list[str] | None = None,
        index_version: int = 0,
    ) -> dict[str, Any]:
        """Embed and index authoritative document chunks."""
        contents = [chunk.get("content", "") for chunk in chunks]
        vectors = await get_embedding_provider().embed(contents)
        docs: list[dict[str, Any]] = []

        for chunk, vector in zip(chunks, vectors, strict=True):
            chunk_id = (
                chunk.get("content_unit_id") or chunk.get("id") or "DOC%s_CHUNK%s" % (document_id, chunk.get("seq"))
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
                    "evidence_code": chunk.get("evidence_code"),
                    "evidence_hash": chunk.get("evidence_hash") or chunk.get("content_hash"),
                    "source_version": chunk.get("source_version") or 1,
                    "content_hash": chunk.get("content_hash"),
                    "index_version": int(chunk.get("index_version") or index_version or 0),
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

    async def index_standard_document(
        self,
        knowledge_base_id: int,
        document_id: int,
        units: list[dict[str, Any]],
        source_name: str = "",
        source_uri: str = "",
        permission_tags: list[str] | None = None,
        index_version: int = 0,
        standard: dict[str, Any] | None = None,
        source_id: int | None = None,
    ) -> dict[str, Any]:
        """Persist the normalized standard document as the basis for card generation.

        Standard units are the single source of truth for downstream extraction: they
        are already free of format noise, so anything derived from them inherits that
        cleanliness instead of re-learning the same cleaning rules.
        """
        provider = get_elasticsearch_provider()
        result = await provider.upsert_standard(
            knowledge_base_id,
            document_id,
            units,
            source_name=source_name,
            source_uri=source_uri,
            doc_id=(standard or {}).get("doc_id", ""),
            source_format=(standard or {}).get("source_format", ""),
            title=(standard or {}).get("title", ""),
            permission_tags=permission_tags,
            source_id=source_id,
            index_version=index_version,
        )
        return {
            "knowledgeBaseId": knowledge_base_id,
            "documentId": document_id,
            "units": len(units),
            "elasticsearch": result,
        }

    async def read_standard_units(
        self,
        knowledge_base_id: int,
        document_id: int,
    ) -> dict[str, Any]:
        """Read back the normalized units stored for one document."""
        provider = get_elasticsearch_provider()
        units = await provider.get_standard_units(knowledge_base_id, document_id)
        return {
            "knowledgeBaseId": knowledge_base_id,
            "documentId": document_id,
            "units": units,
            "count": len(units),
        }

    async def search_standard_units(
        self,
        knowledge_base_id: int,
        query: str,
        top_k: int = 20,
        permission_tags: list[str] | None = None,
        document_id: int | None = None,
    ) -> dict[str, Any]:
        """Search the standard-document store."""
        provider = get_elasticsearch_provider()
        hits = await provider.search_standard(knowledge_base_id, query, top_k, permission_tags, document_id)
        return {
            "knowledgeBaseId": knowledge_base_id,
            "query": query,
            "hits": hits,
            "count": len(hits),
        }

    async def index_knowledge_objects(
        self,
        knowledge_base_id: int,
        cards: list[dict[str, Any]] | None = None,
        claims: list[dict[str, Any]] | None = None,
        entities: list[dict[str, Any]] | None = None,
        relations: list[dict[str, Any]] | None = None,
        index_version: int = 0,
    ) -> dict[str, Any]:
        """Index cards/claims and synchronize graph entities/relations."""
        rows = self._knowledge_rows(
            knowledge_base_id,
            cards or [],
            claims or [],
            index_version,
        )

        graph = get_graph_provider()
        # The local graph view is what graph traversal walks when no graph engine is deployed;
        # writing it here keeps it aligned with the authoritative graph without a second call.
        graph_view = graph_store.upsert(knowledge_base_id, entities, relations)
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
                "graphView": graph_view,
            }

        vectors = await get_embedding_provider().embed([row["content"] for row in rows])
        for row, vector in zip(rows, vectors, strict=True):
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
            "graphView": graph_view,
            "external": external,
        }

    @staticmethod
    def _permission_tags(item: dict[str, Any]) -> list[str]:
        raw = item.get("permission_tags") or item.get("permissionTags") or []
        if isinstance(raw, str):
            stripped = raw.strip()
            if stripped.startswith("["):
                try:
                    import json

                    raw = json.loads(stripped)
                except (TypeError, ValueError):
                    raw = []
            else:
                raw = stripped.split(",")
        return [str(value).strip() for value in raw if str(value).strip()]

    def _knowledge_rows(
        self,
        knowledge_base_id: int,
        cards: list[dict[str, Any]],
        claims: list[dict[str, Any]],
        index_version: int,
    ) -> list[dict[str, Any]]:
        """Convert governed cards/claims into the common retrieval schema."""
        rows: list[dict[str, Any]] = []
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
                    "permission_tags": self._permission_tags(card),
                    "status": card.get("status", "DRAFT"),
                    # Evidence anchor carried by every indexed object: without it the answer gate
                    # has to treat any sentence citing this card as unsupported (see phase 15 gate).
                    "content_hash": card.get("content_hash") or card.get("contentHash") or "",
                    "evidence_code": card.get("evidence_code") or card.get("evidenceCode"),
                    "evidence_hash": (
                        card.get("evidence_hash")
                        or card.get("evidenceHash")
                        or card.get("content_hash")
                        or card.get("contentHash")
                    ),
                    "index_version": int(card.get("index_version") or index_version or 0),
                    "metadata": {
                        "confidence": card.get("confidence", 0),
                        # Quality signals used by fusion scoring (design V1.1 §8.3).
                        "authority_score": card.get("authority_score"),
                        "updated_at": card.get("updated_at"),
                        "evidence_count": card.get("evidence_count", 0),
                    },
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
                    "permission_tags": self._permission_tags(claim),
                    "status": claim.get("status", "UNCERTAIN"),
                    "content_hash": claim.get("content_hash") or claim.get("contentHash") or "",
                    "evidence_code": claim.get("evidence_code") or claim.get("evidenceCode"),
                    "evidence_hash": (
                        claim.get("evidence_hash")
                        or claim.get("evidenceHash")
                        or claim.get("content_hash")
                        or claim.get("contentHash")
                    ),
                    "index_version": int(claim.get("index_version") or index_version or 0),
                    "metadata": {
                        "confidence": claim.get("confidence", 0),
                        "authority_score": claim.get("authority_score"),
                        "updated_at": claim.get("updated_at"),
                        "evidence_count": claim.get("evidence_count", 0),
                    },
                }
            )
        return rows

    async def update_object_acl(
        self,
        knowledge_base_id: int,
        object_type: str,
        object_id: str,
        permission_tags,
        index_version: int,
        source_cascade: bool = False,
    ) -> dict[str, Any]:
        if source_cascade:
            return {
                "knowledgeBaseId": knowledge_base_id,
                "objectType": object_type,
                "objectId": object_id,
                "requiresAuthoritativeRebuild": True,
                "reason": "SOURCE_ACL_CASCADE",
            }
        local_updated = store.update_object_acl(knowledge_base_id, object_id, permission_tags, index_version)
        external = {}
        for name, provider in self._external_providers():
            try:
                external[name] = await provider.update_object_acl(
                    knowledge_base_id, object_id, permission_tags, index_version
                )
            except Exception as exc:
                external[name] = {"error": str(exc)}
                if settings.retrieval_external_required:
                    raise
        graph = await asyncio.to_thread(
            get_graph_provider().update_object_acl,
            knowledge_base_id,
            object_type,
            object_id,
            permission_tags,
            index_version,
        )
        return {
            "knowledgeBaseId": knowledge_base_id,
            "objectId": object_id,
            "localUpdated": local_updated,
            "external": external,
            "graph": graph,
            "requiresAuthoritativeRebuild": bool((external.get("milvus") or {}).get("requiresReindex")),
        }

    async def update_object_status(
        self,
        knowledge_base_id: int,
        object_type: str,
        object_id: str,
        status: str,
        index_version: int,
    ) -> dict[str, Any]:
        """Apply a version-aware status update to every retrieval view."""
        local_updated = store.update_object_status(
            knowledge_base_id,
            object_id,
            status,
            index_version,
        )
        external: dict[str, Any] = {}
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

    async def rebuild_authoritative_graph(
        self, knowledge_base_id: int, generation_id: str, rows, evidence_bindings=None
    ):
        """Build graph and immutable evidence bindings in the same green generation."""
        graph = get_graph_provider()
        if not graph.available():
            return {"provider": "neo4j", "available": False, "generationId": generation_id}
        result = await asyncio.to_thread(graph.rebuild_authoritative_graph, knowledge_base_id, generation_id, rows)
        if hasattr(graph, "rebuild_graph_evidence"):
            evidence = await asyncio.to_thread(
                graph.rebuild_graph_evidence, knowledge_base_id, generation_id, evidence_bindings or []
            )
            result["evidenceBinding"] = evidence
        return result

    async def rebuild_schema(
        self,
        knowledge_base_id: int,
        schema_version: str,
    ) -> dict[str, Any]:
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
        docs: list[dict[str, Any]],
        reset: bool = False,
    ) -> dict[str, Any]:
        """Write one authoritative snapshot page into green physical targets."""
        normalized = [self._normalize_snapshot_document(knowledge_base_id, doc) for doc in docs or []]
        for item in normalized:
            item["generation_id"] = schema_version
        if normalized:
            vectors = await get_embedding_provider().embed([item.get("content", "") for item in normalized])
            for item, vector in zip(normalized, vectors, strict=True):
                item["vector"] = vector

        elasticsearch = get_elasticsearch_provider()
        milvus = get_milvus_provider()
        os_target = elasticsearch.schema_target(schema_version)
        qd_target = milvus.schema_target(schema_version)
        graph = get_graph_provider()
        if reset:
            # The width has to come from a real vector, not from settings.embedding_dim. When
            # the configured width and the embedding model disagree, the collection is created
            # at the configured width and every insert afterwards is rejected — which surfaces
            # as a 500 on this endpoint and "Index schema rebuild failed" in the backend.
            width = len(next((item["vector"] for item in normalized if item.get("vector")), [])) or None
            await self._reset_schema_targets(
                elasticsearch,
                milvus,
                os_target,
                qd_target,
                width,
            )
            if graph.available():
                await asyncio.to_thread(graph.reset_generation, knowledge_base_id, schema_version)

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
        graph_result = (
            await asyncio.to_thread(graph.upsert_generation, knowledge_base_id, schema_version, normalized)
            if normalized and graph.available()
            else {"provider": "neo4j", "indexed": 0, "available": graph.available()}
        )
        os_result["target"] = os_target
        qd_result["target"] = qd_target
        return {
            "knowledgeBaseId": knowledge_base_id,
            "schemaVersion": schema_version,
            "documents": len(normalized),
            "elasticsearch": os_result,
            "milvus": qd_result,
            "neo4j": graph_result,
            "elasticsearchCount": int(os_result.get("indexed") or 0),
            "neo4jCount": int(graph_result.get("indexed") or 0),
            "milvusCount": int(qd_result.get("indexed") or 0),
        }

    @staticmethod
    def _normalize_snapshot_document(
        knowledge_base_id: int,
        doc: dict[str, Any],
    ) -> dict[str, Any]:
        """Normalize Java/MySQL snapshot field names into the retrieval schema."""
        item = dict(doc)
        item["knowledge_base_id"] = int(
            item.get("knowledge_base_id") or item.get("knowledgeBaseId") or knowledge_base_id
        )
        item["id"] = str(item.get("id") or item.get("objectId") or item.get("object_id"))
        item["object_type"] = item.get("object_type") or item.get("objectType")
        item["document_id"] = item.get("document_id") or item.get("documentId")
        item["content_unit_id"] = item.get("content_unit_id") or item.get("contentUnitId")
        item["source_name"] = item.get("source_name") or item.get("sourceName") or ""
        item["source_uri"] = item.get("source_uri") or item.get("sourceUri") or ""
        item["page_no"] = item.get("page_no") or item.get("pageNo")
        item["heading_path"] = item.get("heading_path") or item.get("headingPath") or ""
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
        # Evidence anchor survives the snapshot projection: retrieval metadata is read from these
        # snake_case fields, while the Java payload uses camelCase names.
        item["evidence_code"] = item.get("evidence_code") or item.get("evidenceCode") or None
        item["evidence_hash"] = item.get("evidence_hash") or item.get("evidenceHash") or item.get("content_hash") or ""
        item["content_hash"] = item.get("contentHash") or item.get("content_hash") or ""
        item["object_key"] = item.get("objectKey") or ("%s:%s" % (item.get("object_type"), item.get("id")))
        item["metadata"] = {"content_hash": item.get("content_hash")}
        return item

    @staticmethod
    async def _reset_schema_targets(
        elasticsearch,
        milvus,
        os_target: str,
        qd_target: str,
        dim: int | None = None,
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
        await milvus.ensure_collection(qd_target, dim=dim)

    async def activate_schema(
        self,
        knowledge_base_id: int,
        schema_version: str,
    ) -> dict[str, Any]:
        """Atomically activate validated Elasticsearch and Milvus green targets."""
        os_result = await get_elasticsearch_provider().activate_target(schema_version)
        qd_result = await get_milvus_provider().activate_target(schema_version)
        graph = get_graph_provider()
        graph_result = (
            await asyncio.to_thread(graph.activate_generation, knowledge_base_id, schema_version)
            if graph.available()
            else {"provider": "neo4j", "available": False}
        )
        return {
            "knowledgeBaseId": knowledge_base_id,
            "schemaVersion": schema_version,
            "elasticsearch": os_result,
            "milvus": qd_result,
            "neo4j": graph_result,
        }

    async def qualify_schema_acl(
        self,
        knowledge_base_id: int,
        schema_version: str,
        expected_manifest_hash: str = "",
        expected_manifest_count: int = 0,
        expected_graph_manifest_hash: str = "",
        expected_graph_entity_count: int = 0,
        expected_graph_relation_count: int = 0,
    ) -> dict[str, Any]:
        """Run deterministic persona leakage checks against green ES/Milvus targets.

        This gate never activates aliases. It queries physical green targets directly,
        so qualification cannot accidentally validate the currently active generation.
        """
        es = get_elasticsearch_provider()
        mv = get_milvus_provider()
        rows = await self._acl_qualification_rows(knowledge_base_id, schema_version)
        es_manifest = await es.target_manifest(knowledge_base_id, schema_version)
        mv_manifest = await mv.target_manifest(knowledge_base_id, schema_version)
        graph = get_graph_provider()
        graph_manifest = (
            await asyncio.to_thread(graph.target_manifest, knowledge_base_id, schema_version)
            if graph.available()
            else {"provider": "neo4j", "available": False, "manifestHash": "", "count": 0}
        )
        graph_data_manifest = (
            await asyncio.to_thread(graph.authoritative_graph_manifest, knowledge_base_id, schema_version)
            if graph.available() and hasattr(graph, "authoritative_graph_manifest")
            else {
                "provider": "neo4j-graph",
                "available": False,
                "manifestHash": "",
                "entityCount": 0,
                "relationCount": 0,
            }
        )
        manifest_failures = []
        if expected_graph_manifest_hash and graph_data_manifest.get("manifestHash") != expected_graph_manifest_hash:
            manifest_failures.append({"provider": "neo4j-graph", "reason": "GRAPH_MANIFEST_HASH_MISMATCH"})
        if expected_graph_entity_count and int(graph_data_manifest.get("entityCount") or 0) != int(
            expected_graph_entity_count
        ):
            manifest_failures.append({"provider": "neo4j-graph", "reason": "GRAPH_ENTITY_COUNT_MISMATCH"})
        if expected_graph_relation_count and int(graph_data_manifest.get("relationCount") or 0) != int(
            expected_graph_relation_count
        ):
            manifest_failures.append({"provider": "neo4j-graph", "reason": "GRAPH_RELATION_COUNT_MISMATCH"})
        for manifest in (es_manifest, mv_manifest, graph_manifest):
            if expected_manifest_hash and manifest.get("manifestHash") != expected_manifest_hash:
                manifest_failures.append({"provider": manifest.get("provider"), "reason": "MANIFEST_HASH_MISMATCH"})
            if expected_manifest_count and int(manifest.get("count") or 0) != int(expected_manifest_count):
                manifest_failures.append({"provider": manifest.get("provider"), "reason": "MANIFEST_COUNT_MISMATCH"})
        personas = [
            ("anonymous", []),
            ("team-a", ["team-a"]),
            ("team-b", ["team-b"]),
            ("admin", ["__admin__"]),
        ]
        failures = list(manifest_failures)
        evidence = []
        for name, tags in personas:
            allowed = self._acl_allowed_ids(rows, tags, name == "admin")
            providers = [("elasticsearch", es), ("milvus", mv)]
            for provider_name, provider in providers:
                visible = await provider.qualify_acl_target(
                    knowledge_base_id, schema_version, tags, admin=name == "admin"
                )
                leaked = sorted(set(visible) - allowed)
                missing = sorted(allowed - set(visible))
                evidence.append(
                    {
                        "persona": name,
                        "provider": provider_name,
                        "visible": sorted(visible),
                        "allowed": sorted(allowed),
                        "leaked": leaked,
                        "missing": missing,
                    }
                )
                if leaked:
                    failures.append({"persona": name, "provider": provider_name, "leaked": leaked})
            if graph.available():
                visible = await asyncio.to_thread(
                    graph.qualify_acl_target, knowledge_base_id, schema_version, tags, name == "admin"
                )
                leaked = sorted(set(visible) - allowed)
                missing = sorted(allowed - set(visible))
                evidence.append(
                    {
                        "persona": name,
                        "provider": "neo4j",
                        "visible": sorted(visible),
                        "allowed": sorted(allowed),
                        "leaked": leaked,
                        "missing": missing,
                    }
                )
                if leaked:
                    failures.append({"persona": name, "provider": "neo4j", "leaked": leaked})
        return {
            "qualified": not failures,
            "schemaVersion": schema_version,
            "expectedManifestHash": expected_manifest_hash,
            "expectedManifestCount": expected_manifest_count,
            "engineManifests": [es_manifest, mv_manifest, graph_manifest],
            "authoritativeGraphManifest": graph_data_manifest,
            "personas": [x[0] for x in personas],
            "failures": failures,
            "evidence": evidence,
            "generationConsistency": "ES_MILVUS_NEO4J_PINNED",
            "graph": "GENERATION_SCOPED_GOVERNED_PROJECTION",
        }

    async def _acl_qualification_rows(self, knowledge_base_id: int, schema_version: str):
        """Load ACL metadata from the green Elasticsearch target for expected-set calculation."""
        return await get_elasticsearch_provider().acl_target_rows(knowledge_base_id, schema_version)

    @staticmethod
    def _acl_allowed_ids(rows, tags, admin=False):
        if admin:
            return {str(row.get("id")) for row in rows}
        caller = set(tags or [])
        allowed = set()
        for row in rows:
            required = set(row.get("permission_tags") or [])
            if not required or required.intersection(caller):
                allowed.add(str(row.get("id")))
        return allowed

    async def external_stats(self, knowledge_base_id: int) -> dict[str, Any]:
        """Return object counts for drift diagnostics across retrieval views."""
        results: dict[str, Any] = {}
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
