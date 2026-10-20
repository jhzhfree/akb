"""Milvus dense-vector retrieval provider.

Uses Milvus REST v2 so the AI service remains dependency-light. MySQL is the
authoritative store; Milvus collections are rebuildable retrieval projections.
Scalar ACL/status filters are applied before ANN Top-K.
"""

import logging
from collections.abc import Iterable
from functools import lru_cache
from typing import Any

import httpx

from app.core.config import settings
from app.core.contracts import retrievable_statuses, statusless_objects_retrievable
from app.services.retrieval.provider_common import candidate

logger = logging.getLogger(__name__)


class MilvusProvider:
    """Dense-vector provider backed by Milvus REST v2."""

    def __init__(self) -> None:
        headers = {"Authorization": "Bearer %s" % settings.milvus_token} if settings.milvus_token else {}
        self.client = httpx.AsyncClient(
            base_url=settings.milvus_url,
            headers=headers,
            timeout=settings.retrieval_timeout_seconds,
        )
        self.collection = settings.milvus_collection
        self.read_alias = settings.milvus_read_alias
        self._active_read_collection: str | None = None

    @property
    def enabled(self) -> bool:
        """Read the switch on every use, so a console edit needs no restart."""
        return settings.milvus_enabled

    async def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
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

    async def list_collections(self) -> list[str]:
        payload = await self._post("/v2/vectordb/collections/list", {})
        return list(payload.get("data") or [])

    # Object ids are "knowledge_base_id:object_id"; 256 leaves room if a deployment
    # prefixes them further, and VarChar primaries need the cap up front.
    PRIMARY_MAX_LENGTH = 256

    @staticmethod
    def _create_body(name: str, dimension: int) -> dict[str, Any]:
        """Schema form understood by Milvus 3.x: explicit fields with typed params."""
        return {
            "collectionName": name,
            "schema": {
                "autoID": False,
                "enableDynamicField": True,
                "fields": [
                    {
                        "fieldName": "id",
                        "dataType": "VarChar",
                        "isPrimary": True,
                        "elementTypeParams": {"max_length": str(MilvusProvider.PRIMARY_MAX_LENGTH)},
                    },
                    {
                        "fieldName": "vector",
                        "dataType": "FloatVector",
                        "elementTypeParams": {"dim": str(dimension)},
                    },
                ],
            },
            "indexParams": [
                {"fieldName": "vector", "indexName": "vector_idx", "metricType": "COSINE"},
            ],
        }

    @staticmethod
    def _legacy_create_body(name: str, dimension: int) -> dict[str, Any]:
        """Flat quick-create form used by Milvus 2.x REST."""
        return {
            "collectionName": name,
            "dimension": dimension,
            "metricType": "COSINE",
            "primaryFieldName": "id",
            "vectorFieldName": "vector",
            "idType": "VarChar",
            "autoID": False,
            "enableDynamicField": True,
        }

    async def _collection_dimension(self, name: str) -> int | None:
        """Read the vector width of an existing collection, if it can be described."""
        try:
            payload = await self._post(
                "/v2/vectordb/collections/describe", {"collectionName": name}
            )
        except Exception as exc:
            logger.debug("Milvus describe failed for %s: %s", name, exc)
            return None
        for field in (payload.get("data") or {}).get("fields") or []:
            if field.get("name") != "vector":
                continue
            for param in field.get("params") or []:
                if param.get("key") == "dim":
                    try:
                        return int(param.get("value"))
                    except (TypeError, ValueError):
                        return None
        return None

    async def _dynamic_field_enabled(self, name: str) -> bool:
        """Whether the collection accepts fields it does not declare.

        Milvus 3.x reports this on describe, and it is the difference between an upsert that
        works and one that fails with code 1804 for every batch.
        """
        try:
            payload = await self._post(
                "/v2/vectordb/collections/describe", {"collectionName": name}
            )
        except Exception as exc:
            logger.debug("Milvus describe failed for %s: %s", name, exc)
            return False
        return bool((payload.get("data") or {}).get("enableDynamicField"))

    async def ensure_collection(self, target: str | None = None, dim: int | None = None) -> None:
        """Create the collection, tolerating both REST dialects.

        Milvus 3.x rejects the flat form outright (``idType`` makes it reply
        ``strconv.ParseInt: parsing "<nil>"``), while 2.x does not know the schema
        form, so the schema form is tried first and the flat one is the fallback.

        ``dim`` comes from the vectors actually being written: the configured
        embedding dimension is only a default, and a collection created at the
        wrong width rejects every insert with "size N doesn't equal dimension M".
        """
        name = target or self.collection
        dimension = int(dim or settings.embedding_dim)
        if name in await self.list_collections():
            # An existing collection keeps its original width; saying so beats the
            # opaque "size N doesn't equal dimension M" Milvus returns on insert.
            existing = await self._collection_dimension(name)
            if existing and existing != dimension:
                raise RuntimeError(
                    "Milvus collection %s was created with dimension %d but the current "
                    "embeddings are %d wide; drop the collection or re-index it before "
                    "writing." % (name, existing, dimension)
                )
            # Upserts carry fields outside the declared schema — body text, title, permission
            # tags — and Milvus 3.x answers any such insert with code 1804 when the collection
            # was created without dynamic fields. A collection left over from an older
            # deployment matches on dimension and so would never be caught above, yet fails
            # every single write. Collections are rebuildable projections of the authoritative
            # store, so recreating one beats leaving it permanently broken.
            if await self._dynamic_field_enabled(name):
                return
            logger.warning(
                "Milvus collection %s has dynamic fields disabled; recreating it so writes "
                "outside the declared schema are accepted",
                name,
            )
            await self._post("/v2/vectordb/collections/drop", {"collectionName": name})
        try:
            await self._post("/v2/vectordb/collections/create", self._create_body(name, dimension))
        except Exception:
            await self._post("/v2/vectordb/collections/create", self._legacy_create_body(name, dimension))

    def schema_target(self, schema_version: str, knowledge_base_id: int | None = None) -> str:
        """Physical collection of one schema release.

        The knowledge base has to be part of the name: a blue/green release is per library,
        and a shared name makes the second library's rebuild drop the first library's target
        (or, worse, point the shared read alias at a collection holding only one library).
        """
        if knowledge_base_id is None:
            return "%s__%s" % (self.collection, schema_version.lower())
        return "%s__kb%s__%s" % (self.collection, int(knowledge_base_id), schema_version.lower())

    async def upsert(self, docs: list[dict[str, Any]], target: str | None = None) -> dict[str, Any]:
        vector_docs = [doc for doc in docs if doc.get("vector")]
        if not self.enabled or not vector_docs:
            return {"provider": "milvus", "indexed": 0, "skipped": True}
        name = target or self.collection
        await self.ensure_collection(name, dim=len(vector_docs[0]["vector"]))
        total = 0
        batch_size = max(1, settings.retrieval_batch_size)
        for start in range(0, len(vector_docs), batch_size):
            rows = []
            for doc in vector_docs[start : start + batch_size]:
                row = {key: value for key, value in doc.items() if key != "vector"}
                # Dynamic fields are stored in Milvus JSON metadata. A sentinel makes
                # public visibility explicit and avoids ambiguous empty-array filters.
                row["permission_tags"] = list(doc.get("permission_tags") or ["__public__"])
                # A chunk has no governance state of its own, and leaving the field out is not
                # the same as leaving it empty: Milvus evaluates a filter against a missing
                # dynamic field to false, so the status clause in _filter_expr would drop every
                # chunk and vector recall would return nothing for every library. Chunks are
                # readable the moment they are parsed.
                row["status"] = str(doc.get("status") or "EFFECTIVE")
                row["id"] = "%s:%s" % (doc.get("knowledge_base_id"), doc.get("id"))
                row["object_id"] = str(doc.get("id"))
                row["vector"] = doc["vector"]
                rows.append(row)
            await self._post("/v2/vectordb/entities/upsert", {"collectionName": name, "data": rows})
            total += len(rows)
        return {"provider": "milvus", "indexed": total}

    async def rebuild_target(self, docs: list[dict[str, Any]], schema_version: str,
                             knowledge_base_id: int | None = None) -> dict[str, Any]:
        target = self.schema_target(schema_version, knowledge_base_id)
        if target in await self.list_collections():
            await self._post("/v2/vectordb/collections/drop", {"collectionName": target})
        dimension = len(next((doc["vector"] for doc in docs if doc.get("vector")), [])) or None
        await self.ensure_collection(target, dim=dimension)
        result = await self.upsert(docs, target)
        result["target"] = target
        return result

    async def target_manifest(self, knowledge_base_id: int, schema_version: str):
        """Calculate deterministic ACL/content manifest for one physical collection."""
        import hashlib
        import json

        collection = self.schema_target(schema_version, knowledge_base_id)
        result = await self._post(
            "/v2/vectordb/entities/query",
            {
                "collectionName": collection,
                "filter": "knowledge_base_id == %d" % int(knowledge_base_id),
                "outputFields": [
                    "object_id",
                    "permission_tags",
                    "object_key",
                    "content_hash",
                    "status",
                    "generation_id",
                ],
                "limit": 10000,
            },
        )
        rows = result.get("data") or []
        digest = hashlib.sha256()
        for row in sorted(rows, key=lambda item: str(item.get("object_key") or "")):
            tags = [tag for tag in (row.get("permission_tags") or []) if tag != "__public__"]
            canonical = {
                "objectKey": row.get("object_key"),
                "contentHash": row.get("content_hash") or "",
                "status": row.get("status"),
                "permissionTags": sorted(tags),
            }
            digest.update((json.dumps(canonical, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        generations = sorted({str(row.get("generation_id")) for row in rows if row.get("generation_id")})
        return {
            "provider": "milvus",
            "manifestHash": digest.hexdigest(),
            "count": len(rows),
            "generations": generations,
        }

    async def qualify_acl_target(self, knowledge_base_id: int, schema_version: str, permission_tags, admin=False):
        """Scan green Milvus scalar ACL fields without ANN ranking."""
        collection = self.schema_target(schema_version, knowledge_base_id)
        result = await self._post(
            "/v2/vectordb/entities/query",
            {
                "collectionName": collection,
                "filter": "knowledge_base_id == %d" % int(knowledge_base_id),
                "outputFields": ["id", "object_id", "permission_tags"],
                "limit": 10000,
            },
        )
        rows = result.get("data") or []
        caller = set(permission_tags or [])
        return [
            str(row.get("object_id") or row.get("id"))
            for row in rows
            if admin or not (row.get("permission_tags") or []) or caller.intersection(row.get("permission_tags") or [])
        ]

    def _read_alias_for(self, knowledge_base_id: int) -> str:
        """Read alias of one library.

        A release is per library, so the read side has to be per library too: a single shared
        alias can only ever point at one physical collection, and the last activation would
        then decide which library is readable and which ones recall nothing.
        """
        return "%s__kb%s" % (self.read_alias, int(knowledge_base_id))

    async def activate_target(self, schema_version: str, knowledge_base_id: int | None = None) -> dict[str, Any]:
        target = self.schema_target(schema_version, knowledge_base_id)
        if target not in await self.list_collections():
            raise RuntimeError("Milvus target does not exist: %s" % target)
        alias = self._read_alias_for(knowledge_base_id) if knowledge_base_id is not None else self.read_alias
        try:
            await self._post(
                "/v2/vectordb/aliases/create",
                {
                    "collectionName": target,
                    "aliasName": alias,
                },
            )
        except RuntimeError:
            # Alter is atomic from the caller perspective; create is used for first activation.
            await self._post(
                "/v2/vectordb/aliases/alter",
                {
                    "collectionName": target,
                    "aliasName": alias,
                },
            )
        self._active_read_collection = alias
        return {"provider": "milvus", "alias": alias, "target": target, "activated": True}

    async def read_collection(self, knowledge_base_id: int | None = None) -> str:
        """Resolve the active read alias, falling back to the base collection.

        The alias only exists once a blue/green generation has been activated.
        Reading through it unconditionally would make every count and query fail
        with "can't find collection" on a deployment that never activated one —
        the same reason Elasticsearch checks the alias before using it.
        """
        if knowledge_base_id is not None:
            alias = self._read_alias_for(knowledge_base_id)
            try:
                await self._post("/v2/vectordb/aliases/describe", {"aliasName": alias})
                return alias
            except Exception as exc:
                logger.debug("Milvus per-kb alias %s unavailable: %s", alias, exc)
                return self.collection
        if self._active_read_collection:
            return self._active_read_collection
        target = self.collection
        if self.read_alias:
            try:
                payload = await self._post("/v2/vectordb/aliases/list", {})
                if self.read_alias in (payload.get("data") or []):
                    target = self.read_alias
            except Exception as exc:
                logger.debug("Milvus alias lookup failed: %s", exc)
        self._active_read_collection = target
        return target

    def _filter_expr(self, kb: int, tags: Iterable[str] | None, object_type: str | None) -> str:
        clauses = ["knowledge_base_id == %d" % kb]
        if object_type:
            safe_type = str(object_type).replace('"', '"')
            clauses.append('object_type == "%s"' % safe_type)
        recallable = ", ".join('"%s"' % item for item in sorted(retrievable_statuses()))
        status_clause = "status in [%s]" % recallable
        if statusless_objects_retrievable():
            status_clause = "(%s or status == \"\")" % status_clause
        clauses.append(status_clause)
        # tags is None: caller is unrestricted (platform admin / auth off) → no ACL clause,
        # otherwise restricted knowledge (e.g. the ops knowledge base) would never be recalled.
        if tags is not None:
            values = ["__public__"] + [str(tag).replace('"', '"') for tag in tags]
            quoted = ", ".join('"%s"' % value for value in values)
            # permission_tags is a dynamic JSON-array field, therefore JSON_CONTAINS_ANY
            # is the correct Milvus scalar-filter operator.
            clauses.append("json_contains_any(permission_tags, [%s])" % quoted)
        return " and ".join(clauses)

    async def search(
        self,
        kb: int,
        vector: list[float],
        top_k: int,
        permission_tags: Iterable[str] | None = None,
        object_type: str | None = None,
    ) -> list[dict[str, Any]]:
        body = {
            "collectionName": await self.read_collection(kb),
            "data": [vector],
            "annsField": "vector",
            "limit": top_k,
            "filter": self._filter_expr(kb, permission_tags, object_type),
            "outputFields": ["*"],
        }
        payload = await self._post("/v2/vectordb/entities/search", body)
        hits = payload.get("data") or []
        return [candidate(hit.get("entity") or hit, "vector", hit.get("distance") or 0.0, "milvus") for hit in hits]

    async def delete_document(self, kb: int, document_id: int) -> dict[str, Any]:
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        expr = "knowledge_base_id == %d and document_id == %d" % (kb, document_id)
        await self._post(
            "/v2/vectordb/entities/delete",
            {
                "collectionName": await self.read_collection(),
                "filter": expr,
            },
        )
        return {"deleted": "submitted"}

    async def delete_knowledge_base(self, kb: int) -> dict[str, Any]:
        """Delete every vector of one knowledge base, used by a knowledge-base purge."""
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        await self._post(
            "/v2/vectordb/entities/delete",
            {
                "collectionName": await self.read_collection(),
                "filter": "knowledge_base_id == %d" % kb,
            },
        )
        return {"provider": "milvus", "deleted": "submitted"}

    async def update_object_status(self, kb: int, object_id: str, status: str, index_version: int) -> dict[str, Any]:
        # Milvus upsert is used because scalar partial-update semantics vary by release.
        # Worker per-aggregate ordering remains the stale-write guard.
        return {
            "provider": "milvus",
            "updated": 0,
            "requiresReindex": True,
            "objectId": object_id,
            "status": status,
            "indexVersion": index_version,
        }

    async def update_object_acl(self, kb: int, object_id: str, permission_tags, index_version: int) -> dict[str, Any]:
        return {
            "provider": "milvus",
            "updated": 0,
            "requiresReindex": True,
            "objectId": object_id,
            "permissionTags": list(permission_tags or []),
            "indexVersion": index_version,
        }

    async def count(self, kb: int) -> dict[str, Any]:
        # A switched-off engine must not be dialled: the drift view would otherwise
        # report a connection error for something that was disabled on purpose.
        if not self.enabled:
            return {"provider": "milvus", "count": 0, "skipped": True}
        payload = await self._post(
            "/v2/vectordb/entities/query",
            {
                "collectionName": await self.read_collection(),
                "filter": "knowledge_base_id == %d" % kb,
                "outputFields": ["count(*)"],
            },
        )
        data = payload.get("data") or []
        count = int((data[0] if data else {}).get("count(*)", 0))
        return {"provider": "milvus", "count": count}

    async def stats(self) -> dict[str, Any]:
        return {
            "provider": "milvus",
            "available": await self.available(),
            "url": settings.milvus_url,
            "collection": self.collection,
            "readAlias": self.read_alias,
            "activeRead": self._active_read_collection,
        }


@lru_cache(maxsize=1)
def get_milvus_provider() -> MilvusProvider:
    """Return the process-wide Milvus provider."""
    return MilvusProvider()
