"""External retrieval providers.

Elasticsearch full-text provider adapts Elasticsearch to the internal retrieval contract used
by :mod:`app.services.retrieval.hybrid`.  Both providers deliberately expose a
small API surface so QueryEngine does not depend on vendor-specific clients.

Design rules:
- MySQL remains authoritative. These engines are rebuildable retrieval views.
- Permission and publication-state filters are pushed down before Top-K.
- Object identifiers are deterministic so repeated deliveries are idempotent.
- Blue/green schema releases write to physical targets and switch read aliases.
"""

import hashlib
import json
import logging
from collections.abc import Iterable
from functools import lru_cache
from typing import Any

import httpx

from app.core.config import settings
from app.core.contracts import retrievable_statuses, statusless_objects_retrievable

logger = logging.getLogger(__name__)

SEARCH_METADATA_FIELDS = [
    "object_type",
    "document_id",
    "content_unit_id",
    "source_name",
    "source_uri",
    "page_no",
    "heading_path",
    "status",
    "permission_tags",
    "generation_id",
    "evidence_code",
    "evidence_hash",
    "source_version",
    "content_hash",
]


def _status_filter() -> dict[str, Any]:
    """Build the configured status whitelist, preserving legacy rows in permissive mode."""
    should = [{"terms": {"status": sorted(retrievable_statuses())}}]
    if statusless_objects_retrievable():
        should.append({"bool": {"must_not": [{"exists": {"field": "status"}}]}})
    return {
        "bool": {
            "should": should,
            "minimum_should_match": 1,
        }
    }


def _permission_filter(tags: Iterable[str] | None) -> dict[str, Any] | None:
    """Build the Elasticsearch ACL filter, or None when the caller is unrestricted.

    ``None`` means there is no ACL identity to enforce (platform admin or auth off):
    every object is visible, so no ACL clause is applied at all. An empty list means a
    known identity that holds no tag, which may only see public objects. Missing
    ``permission_tags`` means the object is public; restricted objects are visible
    only when at least one required tag intersects the caller tags.
    """
    if tags is None:
        return None
    should = [{"bool": {"must_not": [{"exists": {"field": "permission_tags"}}]}}]
    values = list(tags or [])
    if values:
        should.append({"terms": {"permission_tags": values}})
    return {"bool": {"should": should, "minimum_should_match": 1}}


def _candidate(source: dict[str, Any], channel: str, score: float, provider: str) -> dict[str, Any]:
    """Convert provider-specific hits to the internal candidate shape."""
    metadata = {key: source.get(key) for key in SEARCH_METADATA_FIELDS}
    metadata["retrieval_provider"] = provider
    return {
        "id": str(source.get("object_id")),
        "channel": channel,
        "title": source.get("title", ""),
        "content": source.get("content", ""),
        "score": float(score or 0.0),
        "metadata": metadata,
    }


class ElasticsearchProvider:
    """BM25/full-text provider backed by Elasticsearch.

    The provider owns one reusable ``httpx.AsyncClient`` per process. Physical
    indices are versioned for blue/green rebuilds; normal reads go through a
    stable alias whenever one has been activated.
    """

    def __init__(self) -> None:
        auth = None
        if settings.elasticsearch_username:
            auth = (settings.elasticsearch_username, settings.elasticsearch_password)
        self.client = httpx.AsyncClient(
            base_url=settings.elasticsearch_url,
            auth=auth,
            verify=settings.elasticsearch_verify_ssl,
            timeout=settings.retrieval_timeout_seconds,
        )
        self.index = settings.elasticsearch_index
        self.read_alias = settings.elasticsearch_read_alias
        self._active_read_index: str | None = None

    @property
    def enabled(self) -> bool:
        """Read the switch on every use, so a console edit needs no restart.

        The url and credentials are baked into the HTTP client, so changing those
        rebuilds the singleton instead; only the on/off switch is read live.
        """
        return settings.elasticsearch_enabled

    async def available(self) -> bool:
        """Return whether Elasticsearch responds without a server-side failure."""
        if not self.enabled:
            return False
        try:
            response = await self.client.get("/")
            return response.status_code < 500
        except Exception as exc:  # availability checks must never break fallback mode
            logger.debug("Elasticsearch availability check failed: %s", exc)
            return False

    async def ensure_index(self, index_name: str | None = None) -> None:
        """Create a physical index when it does not already exist."""
        target = index_name or self.index
        response = await self.client.head("/%s" % target)
        if response.status_code == 200:
            return

        mapping = {
            "settings": {
                "index": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                }
            },
            "mappings": {
                "properties": {
                    "knowledge_base_id": {"type": "long"},
                    "object_id": {"type": "keyword"},
                    "object_type": {"type": "keyword"},
                    "document_id": {"type": "long"},
                    "content_unit_id": {"type": "long"},
                    "title": {
                        "type": "text",
                        "analyzer": settings.elasticsearch_analyzer,
                        "fields": {
                            "keyword": {
                                "type": "keyword",
                                "ignore_above": 256,
                            }
                        },
                    },
                    "content": {"type": "text", "analyzer": settings.elasticsearch_analyzer},
                    "source_name": {"type": "keyword"},
                    "source_uri": {"type": "keyword", "ignore_above": 2048},
                    "page_no": {"type": "integer"},
                    "heading_path": {"type": "text"},
                    "status": {"type": "keyword"},
                    "index_version": {"type": "long"},
                    "permission_tags": {"type": "keyword"},
                    "generation_id": {"type": "keyword"},
                    "object_key": {"type": "keyword"},
                    "content_hash": {"type": "keyword"},
                    # Metadata is retained for diagnostics but excluded from mapping growth.
                    "metadata": {"type": "object", "enabled": False},
                }
            },
        }
        response = await self.client.put("/%s" % target, json=mapping)
        response.raise_for_status()

    async def upsert(
        self,
        docs: list[dict[str, Any]],
        target: str | None = None,
    ) -> dict[str, Any]:
        """Idempotently bulk-upsert retrieval documents."""
        if not self.enabled or not docs:
            return {"provider": "elasticsearch", "indexed": 0, "skipped": True}

        write_target = target or self.index
        await self.ensure_index(write_target)
        lines: list[str] = []
        for doc in docs:
            identity = "%s:%s" % (doc.get("knowledge_base_id"), doc.get("id"))
            document_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()
            lines.append(
                json.dumps(
                    {"index": {"_index": write_target, "_id": document_id}},
                    ensure_ascii=False,
                )
            )
            body = {key: value for key, value in doc.items() if key != "vector"}
            # ACL invariant: a missing permission_tags field means public. Elasticsearch
            # indexes an empty array as an existing source field, so remove it explicitly.
            if not body.get("permission_tags"):
                body.pop("permission_tags", None)
            body["object_id"] = str(doc.get("id"))
            lines.append(json.dumps(body, ensure_ascii=False))

        # ``wait_for`` rather than ``false``: a blue/green rebuild is followed by ACL
        # qualification and alias activation, and both read the target immediately. With
        # ``false`` those reads can legitimately see zero documents and reject a valid
        # generation — the one-second refresh window is not worth that ambiguity.
        response = await self.client.post(
            "/_bulk?refresh=wait_for",
            content=("\n".join(lines) + "\n").encode("utf-8"),
            headers={"Content-Type": "application/x-ndjson"},
        )
        response.raise_for_status()
        payload = response.json()
        errors = [item for item in payload.get("items", []) if item.get("index", {}).get("error")]
        if errors:
            raise RuntimeError("Elasticsearch bulk indexing failed for %s documents" % len(errors))
        return {"provider": "elasticsearch", "indexed": len(docs)}

    def schema_target(self, schema_version: str, knowledge_base_id: int | None = None) -> str:
        """Return the physical index name for one schema release.

        The knowledge base is part of the name because a release belongs to one library: with a
        shared name, rebuilding a second library drops the first library's green index and the
        shared read alias ends up covering a single library.
        """
        if knowledge_base_id is None:
            return "%s__%s" % (settings.elasticsearch_index, schema_version.lower())
        return "%s__kb%s__%s" % (settings.elasticsearch_index, int(knowledge_base_id), schema_version.lower())

    # ------------------------------------------------------------------
    # Standard document store: normalized source units that card generation reads.
    # ------------------------------------------------------------------

    async def ensure_standard_index(self) -> None:
        """Create the standard-document index when it does not already exist."""
        target = settings.standard_document_index
        if (await self.client.head("/%s" % target)).status_code == 200:
            return
        mapping = {
            "settings": {"index": {"number_of_shards": 1, "number_of_replicas": 0}},
            "mappings": {
                "properties": {
                    "knowledge_base_id": {"type": "long"},
                    "document_id": {"type": "long"},
                    "source_id": {"type": "long"},
                    "unit_id": {"type": "keyword"},
                    "doc_id": {"type": "keyword"},
                    "seq": {"type": "integer"},
                    "object_type": {"type": "keyword"},
                    "title": {"type": "text", "analyzer": settings.elasticsearch_analyzer},
                    "heading_path": {"type": "text", "analyzer": settings.elasticsearch_analyzer},
                    "content": {"type": "text", "analyzer": settings.elasticsearch_analyzer},
                    "block_type": {"type": "keyword"},
                    "source_format": {"type": "keyword"},
                    "source_name": {"type": "keyword"},
                    "source_uri": {"type": "keyword"},
                    "page_no": {"type": "integer"},
                    "permission_tags": {"type": "keyword"},
                    "content_hash": {"type": "keyword"},
                    "status": {"type": "keyword"},
                    "index_version": {"type": "long"},
                    "metadata": {"type": "object", "enabled": False},
                }
            },
        }
        response = await self.client.put("/%s" % target, json=mapping)
        response.raise_for_status()

    @staticmethod
    def _standard_doc(**kwargs: Any) -> dict[str, Any]:
        return {key: value for key, value in kwargs.items() if value is not None}

    async def upsert_standard(
        self,
        kb: int,
        document_id: int,
        units: list[dict[str, Any]],
        source_name: str = "",
        source_uri: str = "",
        doc_id: str = "",
        source_format: str = "",
        title: str = "",
        permission_tags: list[str] | None = None,
        source_id: int | None = None,
        index_version: int = 0,
    ) -> dict[str, Any]:
        """Write normalized source units into the standard-document index."""
        if not self.enabled or not units:
            return {"provider": "elasticsearch", "standardIndexed": 0, "skipped": True}
        await self.ensure_standard_index()
        target = settings.standard_document_index
        lines: list[str] = []
        for unit in units:
            identity = "%s:%s:%s" % (kb, document_id, unit.get("unit_id") or unit.get("seq"))
            document_key = hashlib.sha256(identity.encode("utf-8")).hexdigest()
            lines.append(json.dumps({"index": {"_index": target, "_id": document_key}}, ensure_ascii=False))
            body = self._standard_doc(
                knowledge_base_id=kb,
                document_id=document_id,
                source_id=source_id,
                unit_id=str(unit.get("unit_id") or unit.get("seq")),
                doc_id=doc_id,
                seq=int(unit.get("seq") or 0),
                object_type="STANDARD_UNIT",
                title=title,
                heading_path=unit.get("heading_path", ""),
                content=unit.get("content", ""),
                block_type=unit.get("block_type", ""),
                source_format=source_format,
                source_name=source_name,
                source_uri=source_uri,
                page_no=unit.get("page_no"),
                content_hash=unit.get("content_hash", ""),
                status="PARSED",
                index_version=int(index_version or 0),
                permission_tags=permission_tags or [],
                metadata={"block_ids": unit.get("block_ids", [])},
            )
            if not body.get("permission_tags"):
                body.pop("permission_tags", None)
            lines.append(json.dumps(body, ensure_ascii=False))

        response = await self.client.post(
            "/_bulk?refresh=true",
            content=("\n".join(lines) + "\n").encode("utf-8"),
            headers={"Content-Type": "application/x-ndjson"},
        )
        response.raise_for_status()
        errors = [item for item in response.json().get("items", []) if item.get("index", {}).get("error")]
        if errors:
            raise RuntimeError("Elasticsearch standard-document indexing failed for %s units" % len(errors))
        return {"provider": "elasticsearch", "standardIndexed": len(units), "target": target}

    async def search_standard(
        self,
        kb: int,
        query: str,
        top_k: int = 20,
        permission_tags: Iterable[str] | None = None,
        document_id: int | None = None,
    ) -> list[dict[str, Any]]:
        """Full-text search over normalized source units."""
        if not self.enabled:
            return []
        filters: list[dict[str, Any]] = [{"term": {"knowledge_base_id": kb}}]
        acl = _permission_filter(permission_tags)
        if acl:
            filters.append(acl)
        if document_id is not None:
            filters.append({"term": {"document_id": document_id}})
        body = {
            "size": top_k,
            "query": {
                "bool": {
                    "must": [
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["title^2", "heading_path^1.5", "content"],
                                "type": "best_fields",
                            }
                        }
                    ],
                    "filter": filters,
                }
            },
        }
        response = await self.client.post("/%s/_search" % settings.standard_document_index, json=body)
        if response.status_code == 404:
            return []
        response.raise_for_status()
        hits = []
        for hit in response.json().get("hits", {}).get("hits", []):
            source = hit.get("_source", {})
            candidate = _candidate(source, "standard", hit.get("_score") or 0.0, "elasticsearch")
            # Standard units are addressed by unit_id within a document, so the shared
            # object_id field is absent: fall back to the unit identity for citation.
            candidate["id"] = str(source.get("unit_id") or hit.get("_id"))
            candidate["metadata"]["document_id"] = source.get("document_id")
            candidate["metadata"]["unit_id"] = source.get("unit_id")
            candidate["metadata"]["block_type"] = source.get("block_type")
            hits.append(candidate)
        return hits

    async def get_standard_units(self, kb: int, document_id: int) -> list[dict[str, Any]]:
        """Return every normalized unit of one document, ordered by sequence."""
        if not self.enabled:
            return []
        body = {
            "size": 10000,
            "query": {
                "bool": {
                    "filter": [
                        {"term": {"knowledge_base_id": kb}},
                        {"term": {"document_id": document_id}},
                    ]
                }
            },
            "sort": [{"seq": {"order": "asc"}}],
        }
        response = await self.client.post("/%s/_search" % settings.standard_document_index, json=body)
        if response.status_code == 404:
            return []
        response.raise_for_status()
        return [hit.get("_source", {}) for hit in response.json().get("hits", {}).get("hits", [])]

    async def delete_standard_document(self, kb: int, document_id: int) -> dict[str, Any]:
        """Remove normalized units of one document."""
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        response = await self.client.post(
            "/%s/_delete_by_query?refresh=true" % settings.standard_document_index,
            json={
                "query": {
                    "bool": {
                        "filter": [
                            {"term": {"knowledge_base_id": kb}},
                            {"term": {"document_id": document_id}},
                        ]
                    }
                }
            },
        )
        if response.status_code == 404:
            return {"deleted": 0}
        response.raise_for_status()
        return {"deleted": response.json().get("deleted", 0)}

    async def delete_standard_knowledge_base(self, kb: int) -> dict[str, Any]:
        """Remove every normalized unit of one knowledge base, used by a knowledge-base purge."""
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        response = await self.client.post(
            "/%s/_delete_by_query?refresh=true" % settings.standard_document_index,
            json={"query": {"bool": {"filter": [{"term": {"knowledge_base_id": kb}}]}}},
        )
        if response.status_code == 404:
            return {"deleted": 0}
        response.raise_for_status()
        return {"provider": "elasticsearch", "deleted": response.json().get("deleted", 0)}

    async def rebuild_target(
        self,
        docs: list[dict[str, Any]],
        schema_version: str,
        knowledge_base_id: int | None = None,
    ) -> dict[str, Any]:
        """Reset and populate a green index for a schema release."""
        target = self.schema_target(schema_version, knowledge_base_id)
        head = await self.client.head("/%s" % target)
        if head.status_code == 200:
            await self.client.delete("/%s" % target)
        result = await self.upsert(docs, target=target)
        result["target"] = target
        return result

    async def acl_target_rows(self, knowledge_base_id: int, schema_version=None):
        """Return green-target ACL metadata for machine qualification only."""
        index = self.schema_target(schema_version, knowledge_base_id) if schema_version else self.read_alias
        body = {
            "size": 10000,
            "query": {"bool": {"filter": [{"term": {"knowledge_base_id": knowledge_base_id}}]}},
            "_source": ["object_id", "permission_tags", "object_key", "content_hash", "status", "generation_id"],
        }
        response = await self.client.post("/%s/_search" % index, json=body)
        response.raise_for_status()
        return [
            {
                "id": str((hit.get("_source") or {}).get("object_id") or hit.get("_id")),
                "permission_tags": (hit.get("_source") or {}).get("permission_tags") or [],
                "object_key": (hit.get("_source") or {}).get("object_key"),
                "content_hash": (hit.get("_source") or {}).get("content_hash") or "",
                "status": (hit.get("_source") or {}).get("status"),
                "generation_id": (hit.get("_source") or {}).get("generation_id"),
            }
            for hit in response.json().get("hits", {}).get("hits", [])
        ]

    async def target_manifest(self, knowledge_base_id: int, schema_version: str):
        """Calculate deterministic ACL/content manifest for one physical green index."""
        import hashlib
        import json

        rows = await self.acl_target_rows(knowledge_base_id, schema_version)
        digest = hashlib.sha256()
        for row in sorted(rows, key=lambda item: str(item.get("object_key") or "")):
            canonical = {
                "objectKey": row.get("object_key"),
                "contentHash": row.get("content_hash") or "",
                "status": row.get("status"),
                "permissionTags": sorted(row.get("permission_tags") or []),
            }
            digest.update((json.dumps(canonical, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        generations = sorted({str(row.get("generation_id")) for row in rows if row.get("generation_id")})
        return {
            "provider": "elasticsearch",
            "manifestHash": digest.hexdigest(),
            "count": len(rows),
            "generations": generations,
        }

    async def qualify_acl_target(self, knowledge_base_id: int, schema_version: str, permission_tags, admin=False):
        rows = await self.acl_target_rows(knowledge_base_id, schema_version)
        if admin:
            return [str(row["id"]) for row in rows]
        caller = set(permission_tags or [])
        return [
            str(row["id"]) for row in rows if not row["permission_tags"] or caller.intersection(row["permission_tags"])
        ]

    def _read_alias_for(self, knowledge_base_id: int) -> str:
        """Read alias of one library: a release is per library, so the read side is too."""
        return "%s__kb%s" % (self.read_alias, int(knowledge_base_id))

    async def activate_target(self, schema_version: str, knowledge_base_id: int | None = None) -> dict[str, Any]:
        """Atomically move the read alias to a validated physical index."""
        target = self.schema_target(schema_version, knowledge_base_id)
        if (await self.client.head("/%s" % target)).status_code != 200:
            raise RuntimeError("Elasticsearch target does not exist: %s" % target)

        alias = self._read_alias_for(knowledge_base_id) if knowledge_base_id is not None else self.read_alias
        actions: list[dict[str, Any]] = []
        aliases = await self.client.get("/_alias/%s" % alias)
        if aliases.status_code == 200:
            for old in aliases.json():
                actions.append({"remove": {"index": old, "alias": alias}})
        actions.append({"add": {"index": target, "alias": alias}})

        response = await self.client.post("/_aliases", json={"actions": actions})
        response.raise_for_status()
        self._active_read_index = alias
        return {
            "provider": "elasticsearch",
            "alias": alias,
            "target": target,
            "activated": True,
        }

    async def read_index(self, knowledge_base_id: int | None = None) -> str:
        """Resolve and cache the active read alias, falling back to base index."""
        if knowledge_base_id is not None:
            alias = self._read_alias_for(knowledge_base_id)
            aliases = await self.client.get("/_alias/%s" % alias)
            return alias if aliases.status_code == 200 else self.index
        if self._active_read_index:
            return self._active_read_index
        aliases = await self.client.get("/_alias/%s" % self.read_alias)
        self._active_read_index = self.read_alias if aliases.status_code == 200 else self.index
        return self._active_read_index

    async def delete_document(self, kb: int, document_id: int) -> dict[str, Any]:
        """Delete all indexed objects belonging to one authoritative document."""
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        query = {
            "query": {
                "bool": {
                    "filter": [
                        {"term": {"knowledge_base_id": kb}},
                        {"term": {"document_id": document_id}},
                    ]
                }
            }
        }
        target = await self.read_index()
        response = await self.client.post(
            "/%s/_delete_by_query?refresh=true" % target,
            json=query,
        )
        if response.status_code == 404:
            return {"deleted": 0}
        response.raise_for_status()
        return {"deleted": response.json().get("deleted", 0)}

    async def delete_knowledge_base(self, kb: int) -> dict[str, Any]:
        """Delete every index generation of one knowledge base, used by a purge.

        A blue/green release leaves the previous generation's physical index behind the
        alias, so clearing only the active one strands the older generation. The next
        rebuild then answers from two generations at once and retrieval refuses to mix
        them. A purge means the whole library is going away, so every generation of it
        is dropped outright — including when there is nothing to delete, because the
        generation that causes the mismatch is exactly the one no longer active.
        """
        if not self.enabled:
            return {"deleted": 0, "skipped": True}
        removed = await self._delete_knowledge_indices(kb)
        await self._drop_read_alias(kb)
        return {"provider": "elasticsearch", "deletedIndices": removed}

    async def _delete_knowledge_indices(self, kb: int) -> list[str]:
        """Drop every index belonging to one library, whichever generation it is."""
        marker = "__kb%s" % int(kb)
        listing = await self.client.get("/_cat/indices?format=json")
        if listing.status_code != 200:
            return []
        removed: list[str] = []
        for row in listing.json() or []:
            name = str(row.get("index") or "")
            if not name or marker not in name:
                continue
            # 只动本服务自己的索引前缀，避免误删同一集群里别的应用的索引。
            if not name.startswith((self.index, self.read_alias)):
                continue
            response = await self.client.delete("/%s" % name)
            if response.status_code in (200, 404):
                removed.append(name)
        return removed

    async def _drop_read_alias(self, kb: int) -> None:
        """Detach the per-library read alias so it cannot resolve to a dropped index."""
        alias = self._read_alias_for(kb)
        aliases = await self.client.get("/_alias/%s" % alias)
        if aliases.status_code != 200:
            return
        await self.client.post(
            "/_aliases", json={"actions": [{"remove": {"index": "*", "alias": alias}}]}
        )

    async def update_object_status(
        self,
        kb: int,
        object_id: str,
        status: str,
        index_version: int,
    ) -> dict[str, Any]:
        """Update publication state only when the event version is not stale."""
        if not self.enabled:
            return {"provider": "elasticsearch", "updated": 0, "skipped": True}

        identity = "%s:%s" % (kb, object_id)
        document_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()
        script = {
            "script": {
                "lang": "painless",
                "source": (
                    "if (ctx._source.index_version == null || "
                    "params.v >= ctx._source.index_version) { "
                    "ctx._source.status=params.s; "
                    "ctx._source.index_version=params.v; }"
                ),
                "params": {"s": status, "v": int(index_version)},
            }
        }
        target = await self.read_index()
        response = await self.client.post(
            "/%s/_update/%s" % (target, document_id),
            json=script,
        )
        if response.status_code == 404:
            return {"provider": "elasticsearch", "updated": 0, "missing": True}
        response.raise_for_status()
        return {"provider": "elasticsearch", "updated": 1}

    async def update_object_acl(
        self,
        kb: int,
        object_id: str,
        permission_tags,
        index_version: int,
    ) -> dict[str, Any]:
        if not self.enabled:
            return {"provider": "elasticsearch", "updated": 0, "skipped": True}
        identity = "%s:%s" % (kb, object_id)
        document_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()
        source = (
            "if (ctx._source.index_version == null || params.v >= ctx._source.index_version) { "
            "ctx._source.permission_tags=params.t; ctx._source.index_version=params.v; }"
        )
        script = {
            "script": {
                "lang": "painless",
                "source": source,
                "params": {"t": list(permission_tags or []), "v": int(index_version)},
            }
        }
        path = "/%s/_update/%s" % (await self.read_index(), document_id)
        response = await self.client.post(path, json=script)
        if response.status_code == 404:
            return {"provider": "elasticsearch", "updated": 0, "missing": True}
        response.raise_for_status()
        return {"provider": "elasticsearch", "updated": 1}

    async def count(self, kb: int) -> dict[str, Any]:
        """Return the exact number of indexed objects for one knowledge base."""
        if not self.enabled:
            return {"provider": "elasticsearch", "count": 0, "skipped": True}
        response = await self.client.post(
            "/%s/_count" % (await self.read_index()),
            json={"query": {"term": {"knowledge_base_id": kb}}},
        )
        if response.status_code == 404:
            return {"provider": "elasticsearch", "count": 0}
        response.raise_for_status()
        return {
            "provider": "elasticsearch",
            "count": int(response.json().get("count") or 0),
        }

    async def search(
        self,
        kb: int,
        query: str,
        top_k: int,
        permission_tags: Iterable[str] | None = None,
        object_type: str | None = None,
    ) -> list[dict[str, Any]]:
        """Run ACL-aware BM25/full-text retrieval."""
        filters: list[dict[str, Any]] = [{"term": {"knowledge_base_id": kb}}, _status_filter()]
        acl = _permission_filter(permission_tags)
        if acl:
            filters.append(acl)
        if object_type:
            filters.append({"term": {"object_type": object_type}})

        body = {
            "size": top_k,
            "_source": {"excludes": ["metadata"]},
            "query": {
                "bool": {
                    "must": [
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["title^2", "content", "heading_path^1.2"],
                                "type": "best_fields",
                            }
                        }
                    ],
                    "filter": filters,
                }
            },
        }
        response = await self.client.post(
            "/%s/_search" % (await self.read_index(kb)),
            json=body,
        )
        response.raise_for_status()

        channel = "bm25" if not object_type else object_type.lower()
        return [
            _candidate(
                hit.get("_source", {}),
                channel,
                hit.get("_score") or 0.0,
                "elasticsearch",
            )
            for hit in response.json().get("hits", {}).get("hits", [])
        ]

    async def stats(self) -> dict[str, Any]:
        """Expose provider configuration and current availability for diagnostics."""
        return {
            "provider": "elasticsearch",
            "available": await self.available(),
            "url": settings.elasticsearch_url,
            "index": self.index,
            "readAlias": self.read_alias,
            "activeRead": self._active_read_index,
        }


@lru_cache(maxsize=1)
def get_elasticsearch_provider() -> ElasticsearchProvider:
    """Return the process-wide Elasticsearch provider."""
    return ElasticsearchProvider()
