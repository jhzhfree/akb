"""Graph-provider abstraction and Neo4j implementation.

Graph storage is a rebuildable retrieval view. Business lifecycle state remains
in MySQL. The graph provider therefore favors idempotent ``MERGE`` operations,
version-aware status updates, and graceful degradation when Neo4j is disabled.
"""

import time
from abc import ABC, abstractmethod
from functools import lru_cache
from typing import Any, Dict, List, Optional

from app.core.config import settings


class GraphProvider(ABC):
    """Abstract graph retrieval/write contract used by indexing and querying."""

    @abstractmethod
    def available(self) -> bool:
        """Return whether the graph backend is available for online use."""
        raise NotImplementedError

    @abstractmethod
    def upsert_knowledge(
        self,
        knowledge_base_id: int,
        entities=None,
        relations=None,
        claims=None,
        cards=None,
    ) -> Dict[str, Any]:
        """Idempotently synchronize knowledge objects into the graph view."""
        raise NotImplementedError

    @abstractmethod
    def search(
        self,
        knowledge_base_id: int,
        query: str,
        top_k: int = 10,
    ) -> List[Dict[str, Any]]:
        """Search graph objects relevant to a natural-language query."""
        raise NotImplementedError

    @abstractmethod
    def neighborhood(
        self,
        knowledge_base_id: int,
        object_code: str,
        depth: int = 2,
        limit: int = 50,
    ) -> Dict[str, Any]:
        """Return a bounded graph neighborhood for one knowledge object."""
        raise NotImplementedError

    @abstractmethod
    def update_object_status(
        self,
        knowledge_base_id: int,
        object_type: str,
        object_code: str,
        status: str,
        index_version: int,
    ) -> Dict[str, Any]:
        """Apply a lifecycle state change if its index version is not stale."""
        raise NotImplementedError


class NullGraphProvider(GraphProvider):
    """No-op graph provider used when graph integration is disabled."""

    def available(self) -> bool:
        return False

    def upsert_knowledge(
        self,
        knowledge_base_id: int,
        entities=None,
        relations=None,
        claims=None,
        cards=None,
    ) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "nodes": 0, "edges": 0}

    def search(
        self,
        knowledge_base_id: int,
        query: str,
        top_k: int = 10,
    ) -> List[Dict[str, Any]]:
        return []

    def neighborhood(
        self,
        knowledge_base_id: int,
        object_code: str,
        depth: int = 2,
        limit: int = 50,
    ) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "nodes": [], "edges": []}

    def update_object_status(
        self,
        knowledge_base_id: int,
        object_type: str,
        object_code: str,
        status: str,
        index_version: int,
    ) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "updated": 0}


class Neo4jGraphProvider(GraphProvider):
    """Neo4j-backed graph retrieval/write implementation.

    Connectivity checks are cached for ten seconds because ``verify_connectivity``
    is relatively expensive. The provider itself is process-cached by
    ``get_graph_provider`` so the Neo4j driver and connection pool are reused.
    """

    def __init__(self) -> None:
        try:
            from neo4j import GraphDatabase
        except ImportError as exc:
            raise RuntimeError(
                "未安装 neo4j Python Driver，请执行 pip install neo4j"
            ) from exc

        self._driver = GraphDatabase.driver(
            settings.neo4j_uri,
            auth=(settings.neo4j_user, settings.neo4j_password),
        )
        self._database = settings.neo4j_database
        self._availability = False
        self._availability_checked_at = 0.0

    def available(self) -> bool:
        """Check Neo4j connectivity with a short in-process cache."""
        now = time.monotonic()
        if now - self._availability_checked_at < 10.0:
            return self._availability
        try:
            self._driver.verify_connectivity()
            self._availability = True
        except Exception:
            self._availability = False
        self._availability_checked_at = now
        return self._availability

    def _execute(self, cypher: str, params: Dict[str, Any]):
        records, summary, _ = self._driver.execute_query(
            cypher,
            parameters_=params,
            database_=self._database,
        )
        return [record.data() for record in records], summary

    def upsert_knowledge(
        self,
        knowledge_base_id: int,
        entities=None,
        relations=None,
        claims=None,
        cards=None,
    ) -> Dict[str, Any]:
        """Synchronize entities, cards, claims, and relations using MERGE."""
        entities = entities or []
        relations = relations or []
        claims = claims or []
        cards = cards or []
        nodes = 0
        edges = 0

        for entity in entities:
            nodes += self._upsert_entity(knowledge_base_id, entity)
        for card in cards:
            nodes += self._upsert_card(knowledge_base_id, card)
        for claim in claims:
            created_nodes, created_edges = self._upsert_claim(
                knowledge_base_id,
                claim,
            )
            nodes += created_nodes
            edges += created_edges
        for relation in relations:
            edges += self._upsert_relation(knowledge_base_id, relation)

        return {
            "provider": "neo4j",
            "available": True,
            "nodesCreated": nodes,
            "edgesCreated": edges,
        }

    def _upsert_entity(self, knowledge_base_id: int, entity: Dict[str, Any]) -> int:
        code = str(
            entity.get("entity_code")
            or entity.get("id")
            or entity.get("name")
            or ""
        ).strip()
        name = str(entity.get("canonical_name") or entity.get("name") or "").strip()
        if not code or not name:
            return 0
        _, summary = self._execute(
            """
            MERGE (n:Entity {knowledgeBaseId: $kb, code: $code})
            SET n.name=$name, n.type=$type, n.confidence=$confidence,
                n.updatedAt=datetime()
            """,
            {
                "kb": knowledge_base_id,
                "code": code,
                "name": name,
                "type": entity.get("type") or entity.get("entity_type") or "TERM",
                "confidence": float(entity.get("confidence", 0.0) or 0.0),
            },
        )
        return summary.counters.nodes_created

    def _upsert_card(self, knowledge_base_id: int, card: Dict[str, Any]) -> int:
        code = str(card.get("knowledge_code") or card.get("id") or "").strip()
        if not code:
            return 0
        _, summary = self._execute(
            """
            MERGE (n:KnowledgeCard {knowledgeBaseId: $kb, code: $code})
            SET n.title=$title, n.type=$type, n.status=$status,
                n.confidence=$confidence, n.updatedAt=datetime()
            """,
            {
                "kb": knowledge_base_id,
                "code": code,
                "title": card.get("title", ""),
                "type": card.get("type", ""),
                "status": card.get("status", "DRAFT"),
                "confidence": float(card.get("confidence", 0.0) or 0.0),
            },
        )
        return summary.counters.nodes_created

    def _upsert_claim(
        self,
        knowledge_base_id: int,
        claim: Dict[str, Any],
    ) -> tuple:
        code = str(claim.get("claim_code") or claim.get("id") or "").strip()
        if not code:
            return 0, 0
        _, summary = self._execute(
            """
            MERGE (n:Claim {knowledgeBaseId: $kb, code: $code})
            SET n.subject=$subject, n.predicate=$predicate,
                n.objectValue=$objectValue, n.status=$status,
                n.confidence=$confidence, n.updatedAt=datetime()
            """,
            {
                "kb": knowledge_base_id,
                "code": code,
                "subject": claim.get("subject", ""),
                "predicate": claim.get("predicate", ""),
                "objectValue": (
                    claim.get("object") or claim.get("object_value") or ""
                ),
                "status": claim.get("status", "UNCERTAIN"),
                "confidence": float(claim.get("confidence", 0.0) or 0.0),
            },
        )
        nodes = summary.counters.nodes_created
        edges = 0

        entity_name = str(claim.get("subject", "")).strip()
        if entity_name:
            _, edge_summary = self._execute(
                """
                MERGE (e:Entity {knowledgeBaseId:$kb, code:$entityCode})
                ON CREATE SET e.name=$entityName, e.type='TERM'
                MATCH (c:Claim {knowledgeBaseId:$kb, code:$claimCode})
                MERGE (c)-[r:ABOUT]->(e)
                """,
                {
                    "kb": knowledge_base_id,
                    "entityCode": "AUTO_" + entity_name,
                    "entityName": entity_name,
                    "claimCode": code,
                },
            )
            edges += edge_summary.counters.relationships_created
        return nodes, edges

    def _upsert_relation(
        self,
        knowledge_base_id: int,
        relation: Dict[str, Any],
    ) -> int:
        source = str(
            relation.get("source")
            or relation.get("source_entity_code")
            or relation.get("from")
            or ""
        ).strip()
        target = str(
            relation.get("target")
            or relation.get("target_entity_code")
            or relation.get("to")
            or ""
        ).strip()
        relation_type = str(
            relation.get("relation_type") or relation.get("type") or "RELATED_TO"
        ).upper()
        if not source or not target:
            return 0

        # Relationship type cannot be parameterized in Cypher. Whitelist the
        # identifier before interpolation to avoid query injection.
        if not relation_type.replace("_", "").isalnum():
            relation_type = "RELATED_TO"
        cypher = (
            "MERGE (a:Entity {knowledgeBaseId:$kb, code:$source}) "
            "ON CREATE SET a.name=$sourceName, a.type='TERM' "
            "MERGE (b:Entity {knowledgeBaseId:$kb, code:$target}) "
            "ON CREATE SET b.name=$targetName, b.type='TERM' "
            "MERGE (a)-[r:%s]->(b) SET r.confidence=$confidence" % relation_type
        )
        _, summary = self._execute(
            cypher,
            {
                "kb": knowledge_base_id,
                "source": source,
                "sourceName": source,
                "target": target,
                "targetName": target,
                "confidence": float(relation.get("confidence", 0.0) or 0.0),
            },
        )
        return summary.counters.relationships_created

    def update_object_status(
        self,
        knowledge_base_id: int,
        object_type: str,
        object_code: str,
        status: str,
        index_version: int,
    ) -> Dict[str, Any]:
        """Update graph lifecycle state while rejecting stale index events."""
        label = "Claim" if str(object_type).upper() == "CLAIM" else "KnowledgeCard"
        cypher = (
            "MATCH (n:%s {knowledgeBaseId:$kb, code:$code}) "
            "WHERE coalesce(n.indexVersion,0) <= $version "
            "SET n.status=$status, n.indexVersion=$version, "
            "n.updatedAt=datetime() RETURN count(n) AS updated" % label
        )
        records, _ = self._execute(
            cypher,
            {
                "kb": knowledge_base_id,
                "code": object_code,
                "status": status,
                "version": int(index_version),
            },
        )
        return {
            "provider": "neo4j",
            "available": True,
            "updated": int((records[0] if records else {}).get("updated") or 0),
        }

    def search(
        self,
        knowledge_base_id: int,
        query: str,
        top_k: int = 10,
    ) -> List[Dict[str, Any]]:
        """Search published graph nodes and return a small neighbor context."""
        records, _ = self._execute(
            """
            MATCH (n)
            WHERE n.knowledgeBaseId=$kb
              AND (n.status IS NULL OR n.status IN ['APPROVED','EFFECTIVE'])
              AND (toLower(coalesce(n.name,'')) CONTAINS toLower($q)
               OR toLower(coalesce(n.title,'')) CONTAINS toLower($q)
               OR toLower(coalesce(n.subject,'')) CONTAINS toLower($q)
               OR toLower(coalesce(n.objectValue,'')) CONTAINS toLower($q))
            OPTIONAL MATCH (n)-[r]-(m)
            WHERE m.knowledgeBaseId=$kb
            RETURN labels(n)[0] AS nodeType, n.code AS code,
                   coalesce(n.title,n.name,n.subject,n.code) AS title,
                   coalesce(n.objectValue,n.name,n.title,n.subject,'') AS content,
                   collect({type:type(r),
                            target:coalesce(m.title,m.name,m.subject,m.code),
                            code:m.code})[0..5] AS neighbors
            LIMIT $limit
            """,
            {"kb": knowledge_base_id, "q": query, "limit": int(top_k)},
        )
        result = []
        for index, row in enumerate(records):
            result.append(
                {
                    "id": str(row.get("code") or "graph-%s" % index),
                    "channel": "graph",
                    "title": row.get("title") or "图谱对象",
                    "content": "%s\n关联：%s"
                    % (row.get("content") or "", row.get("neighbors") or []),
                    "score": max(0.2, 1.0 - index * 0.05),
                    "metadata": {
                        "object_type": row.get("nodeType"),
                        "graph_neighbors": row.get("neighbors") or [],
                    },
                }
            )
        return result

    def neighborhood(
        self,
        knowledge_base_id: int,
        object_code: str,
        depth: int = 2,
        limit: int = 50,
    ) -> Dict[str, Any]:
        """Return a de-duplicated bounded neighborhood for graph exploration."""
        depth = max(1, min(int(depth), 3))
        limit = max(1, min(int(limit), 200))
        cypher = (
            "MATCH p=(n {knowledgeBaseId:$kb, code:$code})-[*1..%d]-(m) "
            "WHERE m.knowledgeBaseId=$kb WITH p LIMIT $limit "
            "UNWIND nodes(p) AS node "
            "WITH collect(DISTINCT node) AS ns, collect(DISTINCT p) AS ps "
            "RETURN [x IN ns | {code:x.code, type:labels(x)[0], "
            "title:coalesce(x.title,x.name,x.subject,x.code)}] AS nodes, "
            "[p IN ps | [r IN relationships(p) | "
            "{source:startNode(r).code, target:endNode(r).code, "
            "type:type(r)}]] AS edgeGroups" % depth
        )
        records, _ = self._execute(
            cypher,
            {"kb": knowledge_base_id, "code": object_code, "limit": limit},
        )
        if not records:
            return {
                "provider": "neo4j",
                "available": True,
                "nodes": [],
                "edges": [],
            }

        row = records[0]
        edges = []
        seen = set()
        for group in row.get("edgeGroups") or []:
            for edge in group:
                key = (edge.get("source"), edge.get("target"), edge.get("type"))
                if key not in seen:
                    seen.add(key)
                    edges.append(edge)
        return {
            "provider": "neo4j",
            "available": True,
            "nodes": row.get("nodes") or [],
            "edges": edges,
        }


@lru_cache(maxsize=1)
def get_graph_provider() -> GraphProvider:
    """Return the process-wide graph provider selected from configuration."""
    if settings.graph_provider.lower() == "neo4j":
        try:
            return Neo4jGraphProvider()
        except Exception:
            return NullGraphProvider()
    return NullGraphProvider()
