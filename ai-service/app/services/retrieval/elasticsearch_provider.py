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
from functools import lru_cache
from typing import Any, Dict, Iterable, List, Optional

import httpx

from app.core.config import settings

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
]


def _status_filter() -> Dict[str, Any]:
    """Allow raw chunks or formally published knowledge objects."""
    return {
        "bool": {
            "should": [
                {"terms": {"status": ["APPROVED", "EFFECTIVE"]}},
                {"bool": {"must_not": [{"exists": {"field": "status"}}]}},
            ],
            "minimum_should_match": 1,
        }
    }


def _permission_filter(tags: Optional[Iterable[str]]) -> Dict[str, Any]:
    """Build the Elasticsearch ACL filter.

    Missing ``permission_tags`` means the object is public. Restricted objects
    are visible only when at least one required tag intersects the caller tags.
    """
    should = [{"bool": {"must_not": [{"exists": {"field": "permission_tags"}}]}}]
    values = list(tags or [])
    if values:
        should.append({"terms": {"permission_tags": values}})
    return {"bool": {"should": should, "minimum_should_match": 1}}


def _candidate(source: Dict[str, Any], channel: str, score: float, provider: str) -> Dict[str, Any]:
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
        self.enabled = settings.elasticsearch_enabled
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
        self._active_read_index: Optional[str] = None

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

    async def ensure_index(self, index_name: Optional[str] = None) -> None:
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
                    # Metadata is retained for diagnostics but excluded from mapping growth.
                    "metadata": {"type": "object", "enabled": False},
                }
            },
        }
        response = await self.client.put("/%s" % target, json=mapping)
        response.raise_for_status()

    async def upsert(
        self,
        docs: List[Dict[str, Any]],
        target: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Idempotently bulk-upsert retrieval documents."""
        if not self.enabled or not docs:
            return {"provider": "elasticsearch", "indexed": 0, "skipped": True}

        write_target = target or self.index
        await self.ensure_index(write_target)
        lines: List[str] = []
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

        response = await self.client.post(
            "/_bulk?refresh=false",
            content=("\n".join(lines) + "\n").encode("utf-8"),
            headers={"Content-Type": "application/x-ndjson"},
        )
        response.raise_for_status()
        payload = response.json()
        errors = [
            item
            for item in payload.get("items", [])
            if item.get("index", {}).get("error")
        ]
        if errors:
            raise RuntimeError(
                "Elasticsearch bulk indexing failed for %s documents" % len(errors)
            )
        return {"provider": "elasticsearch", "indexed": len(docs)}

    def schema_target(self, schema_version: str) -> str:
        """Return the physical index name for one schema release."""
        return "%s__%s" % (settings.elasticsearch_index, schema_version.lower())

    async def rebuild_target(
        self,
        docs: List[Dict[str, Any]],
        schema_version: str,
    ) -> Dict[str, Any]:
        """Reset and populate a green index for a schema release."""
        target = self.schema_target(schema_version)
        head = await self.client.head("/%s" % target)
        if head.status_code == 200:
            await self.client.delete("/%s" % target)
        result = await self.upsert(docs, target=target)
        result["target"] = target
        return result

    async def activate_target(self, schema_version: str) -> Dict[str, Any]:
        """Atomically move the read alias to a validated physical index."""
        target = self.schema_target(schema_version)
        if (await self.client.head("/%s" % target)).status_code != 200:
            raise RuntimeError("Elasticsearch target does not exist: %s" % target)

        actions: List[Dict[str, Any]] = []
        aliases = await self.client.get("/_alias/%s" % self.read_alias)
        if aliases.status_code == 200:
            for old in aliases.json().keys():
                actions.append({"remove": {"index": old, "alias": self.read_alias}})
        actions.append({"add": {"index": target, "alias": self.read_alias}})

        response = await self.client.post("/_aliases", json={"actions": actions})
        response.raise_for_status()
        self._active_read_index = self.read_alias
        return {
            "provider": "elasticsearch",
            "alias": self.read_alias,
            "target": target,
            "activated": True,
        }

    async def read_index(self) -> str:
        """Resolve and cache the active read alias, falling back to base index."""
        if self._active_read_index:
            return self._active_read_index
        aliases = await self.client.get("/_alias/%s" % self.read_alias)
        self._active_read_index = (
            self.read_alias if aliases.status_code == 200 else self.index
        )
        return self._active_read_index

    async def delete_document(self, kb: int, document_id: int) -> Dict[str, Any]:
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

    async def update_object_status(
        self,
        kb: int,
        object_id: str,
        status: str,
        index_version: int,
    ) -> Dict[str, Any]:
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

    async def count(self, kb: int) -> Dict[str, Any]:
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
        permission_tags: Optional[Iterable[str]] = None,
        object_type: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """Run ACL-aware BM25/full-text retrieval."""
        filters: List[Dict[str, Any]] = [
            {"term": {"knowledge_base_id": kb}},
            _permission_filter(permission_tags),
            _status_filter(),
        ]
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
            "/%s/_search" % (await self.read_index()),
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

    async def stats(self) -> Dict[str, Any]:
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
