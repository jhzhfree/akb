"""Graph-provider abstraction and Neo4j implementation.

Graph storage is a rebuildable retrieval view. Business lifecycle state remains
in MySQL. The graph provider therefore favors idempotent ``MERGE`` operations,
version-aware status updates, and graceful degradation when Neo4j is disabled.
"""

import re
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
        permission_tags=None,
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
        permission_tags=None,
    ) -> Dict[str, Any]:
        """Return a bounded graph neighborhood for one knowledge object."""
        raise NotImplementedError

    @abstractmethod
    def reset_generation(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        """Delete one non-active governed graph generation before rebuild."""
        raise NotImplementedError

    def delete_knowledge_base(self, knowledge_base_id: int) -> Dict[str, Any]:
        """Delete the whole knowledge base from the graph view after a console purge.

        Not abstract: graph backends are optional, and a provider without a graph store
        simply reports nothing to delete instead of forcing every implementation to
        carry an empty method.
        """
        return {"provider": "graph", "available": False, "deleted": 0}

    @abstractmethod
    def upsert_generation(
        self, knowledge_base_id: int, generation_id: str, documents
    ) -> Dict[str, Any]:
        """Write authoritative snapshot objects into one graph generation."""
        raise NotImplementedError

    @abstractmethod
    def activate_generation(
        self, knowledge_base_id: int, generation_id: str
    ) -> Dict[str, Any]:
        """Move the graph active-generation pointer after qualification."""
        raise NotImplementedError

    @abstractmethod
    def active_generation(self, knowledge_base_id: int) -> Optional[str]:
        """Return the graph generation currently selected for reads."""
        raise NotImplementedError

    @abstractmethod
    def target_manifest(
        self, knowledge_base_id: int, generation_id: str
    ) -> Dict[str, Any]:
        """Return deterministic manifest metadata for one graph generation."""
        raise NotImplementedError

    @abstractmethod
    def qualify_acl_target(
        self, knowledge_base_id: int, generation_id: str, permission_tags, admin=False
    ) -> List[str]:
        """Return visible object IDs for a persona against a graph generation."""
        raise NotImplementedError

    @abstractmethod
    def reset_generation(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "deleted": 0}

    def upsert_generation(self, knowledge_base_id: int, generation_id: str, documents) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "indexed": 0}

    def activate_generation(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "generationId": generation_id}

    def active_generation(self, knowledge_base_id: int) -> Optional[str]:
        return None

    def target_manifest(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        return {"provider": "neo4j", "available": False, "manifestHash": "", "count": 0, "generations": []}

    def qualify_acl_target(self, knowledge_base_id: int, generation_id: str, permission_tags, admin=False) -> List[str]:
        return []

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
    @abstractmethod
    def update_object_acl(
        self, knowledge_base_id: int, object_type: str, object_code: str,
        permission_tags, index_version: int,
    ) -> Dict[str, Any]:
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
        permission_tags=None,
    ) -> List[Dict[str, Any]]:
        return []

    def neighborhood(
        self,
        knowledge_base_id: int,
        object_code: str,
        depth: int = 2,
        limit: int = 50,
        permission_tags=None,
    ) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "nodes": [], "edges": []}

    def reset_generation(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "deleted": 0}

    def upsert_generation(self, knowledge_base_id: int, generation_id: str, documents) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "indexed": 0}

    def activate_generation(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "generationId": generation_id}

    def active_generation(self, knowledge_base_id: int) -> Optional[str]:
        return None

    def target_manifest(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "manifestHash": "", "count": 0, "generations": []}

    def qualify_acl_target(self, knowledge_base_id: int, generation_id: str, permission_tags, admin=False) -> List[str]:
        return []

    def update_object_status(
        self,
        knowledge_base_id: int,
        object_type: str,
        object_code: str,
        status: str,
        index_version: int,
    ) -> Dict[str, Any]:
        return {"provider": "null", "available": False, "updated": 0}


    def update_object_acl(self, knowledge_base_id:int, object_type:str, object_code:str, permission_tags, index_version:int)->Dict[str,Any]:
        return {"provider":"none","updated":0,"skipped":True}

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

        code_by_name = self._entity_code_index(entities)
        for entity in entities:
            nodes += self._upsert_entity(knowledge_base_id, entity)
        for card in cards:
            nodes += self._upsert_card(knowledge_base_id, card)
        for claim in claims:
            created_nodes, created_edges = self._upsert_claim(
                knowledge_base_id,
                claim,
                code_by_name,
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

    @staticmethod
    def _permission_tags(item: Dict[str, Any]) -> List[str]:
        raw = item.get("permission_tags") or item.get("permissionTags") or []
        if isinstance(raw, str):
            import json
            try:
                raw = json.loads(raw) if raw.strip().startswith("[") else raw.split(",")
            except (TypeError, ValueError):
                raw = raw.split(",")
        return [str(value).strip() for value in raw if str(value).strip()]

    @staticmethod
    def _entity_key(value: Any) -> str:
        """Comparison key for entity names: spacing and case must not split a node."""
        return re.sub(r"\s+", "", str(value or "")).strip().lower()

    @classmethod
    def _entity_code_index(cls, entities: List[Dict[str, Any]]) -> Dict[str, str]:
        """Map entity names to their codes so claim ABOUT edges hit real nodes."""
        index: Dict[str, str] = {}
        for entity in entities or []:
            code = str(entity.get("entity_code") or entity.get("id") or entity.get("name") or "").strip()
            name = str(entity.get("canonical_name") or entity.get("name") or "").strip()
            if code and name:
                index.setdefault(cls._entity_key(name), code)
        return index

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
                n.permissionTags=$permissionTags, n.updatedAt=datetime()
            """,
            {
                "kb": knowledge_base_id,
                "code": code,
                "name": name,
                "permissionTags": self._permission_tags(entity),
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
                n.confidence=$confidence, n.permissionTags=$permissionTags, n.updatedAt=datetime()
            """,
            {
                "kb": knowledge_base_id,
                "code": code,
                "title": card.get("title", ""),
                "permissionTags": self._permission_tags(card),
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
        code_by_name: Optional[Dict[str, str]] = None,
    ) -> tuple:
        code = str(claim.get("claim_code") or claim.get("id") or "").strip()
        if not code:
            return 0, 0
        _, summary = self._execute(
            """
            MERGE (n:Claim {knowledgeBaseId: $kb, code: $code})
            SET n.subject=$subject, n.predicate=$predicate,
                n.objectValue=$objectValue, n.status=$status,
                n.confidence=$confidence, n.permissionTags=$permissionTags, n.updatedAt=datetime()
            """,
            {
                "kb": knowledge_base_id,
                "code": code,
                "subject": claim.get("subject", ""),
                "permissionTags": self._permission_tags(claim),
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
            # Prefer the code of an entity indexed from the same batch: an AUTO_ node
            # would fragment the graph view into disconnected subject-keyed duplicates.
            entity_code = (code_by_name or {}).get(self._entity_key(entity_name)) or ("AUTO_" + entity_name)
            _, edge_summary = self._execute(
                """
                MERGE (e:Entity {knowledgeBaseId:$kb, code:$entityCode})
                ON CREATE SET e.name=$entityName, e.type='TERM'
                SET e.permissionTags=$permissionTags
                WITH e
                MATCH (c:Claim {knowledgeBaseId:$kb, code:$claimCode})
                MERGE (c)-[r:ABOUT]->(e)
                SET r.permissionTags=$permissionTags
                """,
                {
                    "kb": knowledge_base_id,
                    "entityCode": entity_code,
                    "entityName": entity_name,
                    "claimCode": code,
                    "permissionTags": self._permission_tags(claim),
                },
            )
            edges += edge_summary.counters.relationships_created
        return nodes, edges

    def _upsert_relation(
        self,
        knowledge_base_id: int,
        relation: Dict[str, Any],
    ) -> int:
        # A resolved entity code always wins over the raw name: relations stamped by the
        # ingestion pipeline carry both, and the code points at the indexed entity node.
        source = str(
            relation.get("source_entity_code")
            or relation.get("source")
            or relation.get("from")
            or ""
        ).strip()
        target = str(
            relation.get("target_entity_code")
            or relation.get("target")
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
            "ON CREATE SET a.name=$sourceName, a.type='TERM' SET a.permissionTags=$permissionTags "
            "MERGE (b:Entity {knowledgeBaseId:$kb, code:$target}) "
            "ON CREATE SET b.name=$targetName, b.type='TERM' SET b.permissionTags=$permissionTags "
            "MERGE (a)-[r:%s]->(b) SET r.confidence=$confidence, r.permissionTags=$permissionTags" % relation_type
        )
        _, summary = self._execute(
            cypher,
            {
                "kb": knowledge_base_id,
                "source": source,
                "sourceName": str(relation.get("source") or source).strip() or source,
                "target": target,
                "targetName": str(relation.get("target") or target).strip() or target,
                "confidence": float(relation.get("confidence", 0.0) or 0.0),
                "permissionTags": self._permission_tags(relation),
            },
        )
        return summary.counters.relationships_created

    def reset_generation(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        """Delete only the requested green generation; active pointer is untouched."""
        records, _ = self._execute(
            "MATCH (n:KnowledgeObject {knowledgeBaseId:$kb, generationId:$generation}) "
            "DETACH DELETE n RETURN count(n) AS deleted",
            {"kb": knowledge_base_id, "generation": generation_id},
        )
        return {"provider": "neo4j", "deleted": int((records[0] if records else {}).get("deleted") or 0)}

    def delete_knowledge_base(self, knowledge_base_id: int) -> Dict[str, Any]:
        """Delete every graph node of one knowledge base, including the generation pointer.

        Every node written by this provider carries ``knowledgeBaseId``, so one
        unlabelled match removes entities, cards, claims, generation snapshots and the
        active-generation pointer without depending on a label staying stable.
        """
        if not self.available():
            return {"provider": "neo4j", "available": False, "deleted": 0}
        records, _ = self._execute(
            "MATCH (n {knowledgeBaseId:$kb}) RETURN count(n) AS total",
            {"kb": knowledge_base_id},
        )
        total = int((records[0] if records else {}).get("total") or 0)
        self._execute("MATCH (n {knowledgeBaseId:$kb}) DETACH DELETE n", {"kb": knowledge_base_id})
        return {"provider": "neo4j", "available": True, "deleted": total}

    def upsert_generation(self, knowledge_base_id: int, generation_id: str, documents) -> Dict[str, Any]:
        """Project the authoritative retrieval snapshot into generation-scoped graph nodes."""
        indexed = 0
        for item in documents or []:
            object_id = str(item.get("id") or item.get("object_id") or "")
            if not object_id:
                continue
            self._execute(
                "MERGE (n:KnowledgeObject {knowledgeBaseId:$kb, generationId:$generation, objectId:$objectId}) "
                "SET n.objectType=$objectType,n.objectKey=$objectKey,n.title=$title,n.content=$content,"
                "n.contentHash=$contentHash,n.status=$status,n.permissionTags=$permissionTags,n.updatedAt=datetime()",
                {"kb": knowledge_base_id, "generation": generation_id, "objectId": object_id,
                 "objectType": item.get("object_type"), "objectKey": item.get("object_key"),
                 "title": item.get("title") or "", "content": item.get("content") or "",
                 "contentHash": item.get("content_hash") or "", "status": item.get("status"),
                 "permissionTags": list(item.get("permission_tags") or [])},
            )
            indexed += 1
        return {"provider": "neo4j", "indexed": indexed, "generationId": generation_id}

    def activate_generation(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        """Atomically move the Neo4j pointer used by generation-scoped reads."""
        records, _ = self._execute(
            "MERGE (p:IndexGenerationPointer {knowledgeBaseId:$kb}) "
            "SET p.activeGenerationId=$generation,p.updatedAt=datetime() "
            "RETURN p.activeGenerationId AS generationId",
            {"kb": knowledge_base_id, "generation": generation_id},
        )
        return {"provider": "neo4j", "generationId": (records[0] if records else {}).get("generationId")}

    def active_generation(self, knowledge_base_id: int) -> Optional[str]:
        records, _ = self._execute(
            "MATCH (p:IndexGenerationPointer {knowledgeBaseId:$kb}) "
            "RETURN p.activeGenerationId AS generationId",
            {"kb": knowledge_base_id},
        )
        value = (records[0] if records else {}).get("generationId")
        return str(value) if value else None

    def _generation_rows(self, knowledge_base_id: int, generation_id: str):
        records, _ = self._execute(
            "MATCH (n:KnowledgeObject {knowledgeBaseId:$kb, generationId:$generation}) "
            "RETURN n.objectId AS id,n.objectKey AS object_key,n.contentHash AS content_hash,"
            "n.status AS status,n.permissionTags AS permission_tags,n.generationId AS generation_id",
            {"kb": knowledge_base_id, "generation": generation_id},
        )
        return records

    def target_manifest(self, knowledge_base_id: int, generation_id: str) -> Dict[str, Any]:
        """Hash the same canonical fields used by MySQL, Elasticsearch and Milvus."""
        import hashlib
        import json
        rows = self._generation_rows(knowledge_base_id, generation_id)
        digest = hashlib.sha256()
        for row in sorted(rows, key=lambda item: str(item.get("object_key") or "")):
            canonical = {"objectKey": row.get("object_key"), "contentHash": row.get("content_hash") or "",
                         "status": row.get("status"), "permissionTags": sorted(row.get("permission_tags") or [])}
            line = json.dumps(canonical, ensure_ascii=False, separators=(",", ":")) + "\n"
            digest.update(line.encode("utf-8"))
        generations = sorted({str(row.get("generation_id")) for row in rows if row.get("generation_id")})
        return {"provider": "neo4j", "manifestHash": digest.hexdigest(), "count": len(rows), "generations": generations}

    def qualify_acl_target(self, knowledge_base_id: int, generation_id: str, permission_tags, admin=False) -> List[str]:
        rows = self._generation_rows(knowledge_base_id, generation_id)
        if admin:
            return [str(row.get("id")) for row in rows]
        caller = set(permission_tags or [])
        return [str(row.get("id")) for row in rows
                if not (row.get("permission_tags") or []) or caller.intersection(row.get("permission_tags") or [])]

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
        self, knowledge_base_id: int, query: str, top_k: int = 10, permission_tags=None,
    ) -> List[Dict[str, Any]]:
        """Search only the active governed graph generation with pre-TopK ACL."""
        records, _ = self._execute(
            "MATCH (p:IndexGenerationPointer {knowledgeBaseId:$kb}) "
            "MATCH (n:KnowledgeObject {knowledgeBaseId:$kb, generationId:p.activeGenerationId}) "
            "WHERE (size(coalesce(n.permissionTags, []))=0 OR any(tag IN coalesce(n.permissionTags, []) WHERE tag IN $permissionTags)) "
            "AND (n.status IS NULL OR n.status IN ['APPROVED','EFFECTIVE']) "
            "AND (toLower(coalesce(n.title,'')) CONTAINS toLower($q) OR toLower(coalesce(n.content,'')) CONTAINS toLower($q)) "
            "RETURN n.objectType AS nodeType,n.objectId AS code,n.title AS title,n.content AS content,n.generationId AS generationId "
            "LIMIT $limit",
            {"kb": knowledge_base_id, "q": query, "limit": int(top_k), "permissionTags": list(permission_tags or [])},
        )
        result = []
        for index, row in enumerate(records):
            result.append({"id": str(row.get("code") or "graph-%s" % index), "channel": "graph",
                           "title": row.get("title") or "图谱对象", "content": row.get("content") or "",
                           "score": max(0.2, 1.0-index*0.05),
                           "metadata": {"object_type": row.get("nodeType"), "graph_neighbors": [],
                                        "generation_id": row.get("generationId"), "retrieval_provider": "neo4j"}})
        return result

    def neighborhood(
        self,
        knowledge_base_id: int,
        object_code: str,
        depth: int = 2,
        limit: int = 50,
        permission_tags=None,
    ) -> Dict[str, Any]:
        """Return a de-duplicated bounded neighborhood for graph exploration."""
        depth = max(1, min(int(depth), 3))
        limit = max(1, min(int(limit), 200))
        cypher = (
            "MATCH p=(n {knowledgeBaseId:$kb, code:$code})-[*1..%d]-(m) "
            "WHERE m.knowledgeBaseId=$kb "
            "AND (size(coalesce(n.permissionTags, []))=0 OR any(tag IN coalesce(n.permissionTags, []) WHERE tag IN $permissionTags)) "
            "AND (size(coalesce(m.permissionTags, []))=0 OR any(tag IN coalesce(m.permissionTags, []) WHERE tag IN $permissionTags)) "
            "AND all(rel IN relationships(p) "
            "WHERE size(coalesce(rel.permissionTags, []))=0 "
            "OR any(tag IN coalesce(rel.permissionTags, []) "
            "WHERE tag IN $permissionTags)) "
            "WITH p LIMIT $limit "
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
            {"kb": knowledge_base_id, "code": object_code, "limit": limit,
             "permissionTags": list(permission_tags or [])},
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

    def update_object_acl(
        self, knowledge_base_id: int, object_type: str, object_code: str,
        permission_tags, index_version: int,
    ) -> Dict[str, Any]:
        label = "Claim" if str(object_type).upper() == "CLAIM" else "KnowledgeCard"
        cypher = (
            "MATCH (n:%s {knowledgeBaseId:$kb, code:$code}) "
            "WHERE coalesce(n.indexVersion,0) <= $version "
            "SET n.permissionTags=$tags,n.indexVersion=$version,n.updatedAt=datetime() "
            "RETURN count(n) AS updated" % label
        )
        records, _ = self._execute(cypher, {
            "kb": knowledge_base_id, "code": object_code,
            "tags": list(permission_tags or []), "version": int(index_version),
        })
        updated = int((records[0] if records else {}).get("updated") or 0)
        return {"provider": "neo4j", "available": True, "updated": updated}


@lru_cache(maxsize=1)
def get_graph_provider() -> GraphProvider:
    """Return the process-wide graph provider selected from configuration."""
    if settings.graph_provider.lower() == "neo4j":
        try:
            return Neo4jGraphProvider()
        except Exception:
            return NullGraphProvider()
    return NullGraphProvider()

# Phase 9 methods are attached explicitly to preserve compatibility with older provider implementations.
def _neo4j_rebuild_authoritative_graph(self, knowledge_base_id, generation_id, rows):
    """Rebuild generation-scoped Entity/Relation graph; never touches the active pointer."""
    self._execute("MATCH (n:EntityGen {knowledgeBaseId:$kb,generationId:$g}) DETACH DELETE n",
                  {"kb": knowledge_base_id, "g": generation_id})
    entities = [x for x in (rows or []) if str(x.get("graph_type")) == "ENTITY"]
    relations = [x for x in (rows or []) if str(x.get("graph_type")) == "RELATION"]
    links = [x for x in (rows or []) if str(x.get("graph_type")) in ("CLAIM_ENTITY", "CARD_ENTITY")]
    for x in entities:
        self._execute(
            "MERGE (n:EntityGen {knowledgeBaseId:$kb,generationId:$g,code:$code}) "
            "SET n.entityType=$type,n.name=$name,n.description=$description,n.status=$status,"
            "n.permissionTags=$tags,n.contentHash=$hash,n.updatedAt=datetime()",
            {"kb":knowledge_base_id,"g":generation_id,"code":x.get("code"),"type":x.get("semantic_type"),
             "name":x.get("name") or "","description":x.get("description") or "","status":x.get("status"),
             "tags":list(x.get("permission_tags") or []),"hash":x.get("content_hash") or ""})
    created = 0
    for x in relations:
        records,_=self._execute(
            "MATCH (a:EntityGen {knowledgeBaseId:$kb,generationId:$g,code:$source}),"
            "(b:EntityGen {knowledgeBaseId:$kb,generationId:$g,code:$target}) "
            "MERGE (a)-[r:RELATION_GEN {generationId:$g,code:$code}]->(b) "
            "SET r.relationType=$type,r.permissionTags=$tags,r.contentHash=$hash,r.status=$status "
            "RETURN count(r) AS updated",
            {"kb":knowledge_base_id,"g":generation_id,"source":x.get("source_code"),"target":x.get("target_code"),
             "code":x.get("code"),"type":x.get("semantic_type"),"tags":list(x.get("permission_tags") or []),
             "hash":x.get("content_hash") or "","status":x.get("status")})
        created += int((records[0] if records else {}).get("updated") or 0)
    linked = 0
    for x in links:
        object_type = "CLAIM" if str(x.get("graph_type")) == "CLAIM_ENTITY" else "CARD"
        records,_=self._execute(
            "MATCH (o:KnowledgeObject {knowledgeBaseId:$kb,generationId:$g,objectId:$objectCode}),"
            "(e:EntityGen {knowledgeBaseId:$kb,generationId:$g,code:$entityCode}) "
            "MERGE (o)-[r:ABOUT_ENTITY_GEN {generationId:$g,code:$code}]->(e) "
            "SET r.role=$role,r.permissionTags=$tags,r.contentHash=$hash,r.objectType=$objectType RETURN count(r) AS updated",
            {"kb":knowledge_base_id,"g":generation_id,"objectCode":x.get("source_code"),"entityCode":x.get("target_code"),
             "code":x.get("code"),"role":x.get("semantic_type"),"tags":list(x.get("permission_tags") or []),
             "hash":x.get("content_hash") or "","objectType":object_type})
        linked += int((records[0] if records else {}).get("updated") or 0)
    return {
        "provider": "neo4j", "generationId": generation_id,
        "entityCount": len(entities), "relationCount": created,
        "knowledgeEntityLinkCount": linked,
    }

def _neo4j_generation_neighborhood(
    self, knowledge_base_id, entity_code, depth=2, limit=50, permission_tags=None
):
    """Traverse only the active Entity/Relation generation and apply ACL to every node and edge."""
    depth=max(1,min(int(depth),3)); limit=max(1,min(int(limit),200))
    cypher=("MATCH (ptr:IndexGenerationPointer {knowledgeBaseId:$kb}) "
            "MATCH p=(n:EntityGen {knowledgeBaseId:$kb,generationId:ptr.activeGenerationId,code:$code})"
            "-[:RELATION_GEN*1..%d]-(m:EntityGen) "
            "WHERE m.generationId=ptr.activeGenerationId "
            "AND (size(coalesce(n.permissionTags,[]))=0 OR any(t IN coalesce(n.permissionTags,[]) WHERE t IN $tags)) "
            "AND (size(coalesce(m.permissionTags,[]))=0 OR any(t IN coalesce(m.permissionTags,[]) WHERE t IN $tags)) "
            "AND all(rel IN relationships(p) WHERE rel.generationId=ptr.activeGenerationId AND "
            "(size(coalesce(rel.permissionTags,[]))=0 OR any(t IN coalesce(rel.permissionTags,[]) WHERE t IN $tags))) "
            "RETURN p LIMIT $limit" % depth)
    records,_=self._execute(cypher,{"kb":knowledge_base_id,"code":entity_code,"tags":list(permission_tags or []),"limit":limit})
    return {"generationId":self.active_generation(knowledge_base_id),"pathCount":len(records),"paths":records}

if 'Neo4jGraphProvider' in globals():
    Neo4jGraphProvider.rebuild_authoritative_graph = _neo4j_rebuild_authoritative_graph
    Neo4jGraphProvider.generation_neighborhood = _neo4j_generation_neighborhood

# Phase 10: machine-verifiable authoritative graph manifest and evidence-bound multi-hop traversal.
def _neo4j_authoritative_graph_manifest(self, knowledge_base_id, generation_id):
    """Recompute the Phase-9 canonical graph manifest from one physical Neo4j generation."""
    import hashlib
    import json
    records, _ = self._execute(
        "MATCH (e:EntityGen {knowledgeBaseId:$kb,generationId:$g}) "
        "RETURN 'ENTITY:' + e.code AS graph_key,'ENTITY' AS graph_type,e.code AS code,"
        "null AS source_code,null AS target_code,e.entityType AS semantic_type,e.name AS name,"
        "e.description AS description,e.status AS status,e.permissionTags AS permission_tags,"
        "e.contentHash AS content_hash UNION ALL "
        "MATCH (a:EntityGen {knowledgeBaseId:$kb,generationId:$g})"
        "-[r:RELATION_GEN {generationId:$g}]->"
        "(b:EntityGen {knowledgeBaseId:$kb,generationId:$g}) "
        "RETURN 'RELATION:' + r.code,'RELATION',r.code,a.code,b.code,r.relationType,"
        "null,null,r.status,r.permissionTags,r.contentHash UNION ALL "
        "MATCH (o:KnowledgeObject {knowledgeBaseId:$kb,generationId:$g})"
        "-[r:ABOUT_ENTITY_GEN {generationId:$g}]->"
        "(e:EntityGen {knowledgeBaseId:$kb,generationId:$g}) "
        "RETURN CASE WHEN r.objectType='CLAIM' THEN 'CLAIM_ENTITY:' ELSE 'CARD_ENTITY:' END "
        "+ o.objectId + ':' + e.code,CASE WHEN r.objectType='CLAIM' THEN 'CLAIM_ENTITY' "
        "ELSE 'CARD_ENTITY' END,o.objectId + ':' + e.code,o.objectId,e.code,r.role,"
        "null,null,o.status,r.permissionTags,r.contentHash",
        {"kb": knowledge_base_id, "g": generation_id},
    )
    rows = sorted(records, key=lambda x: str(x.get("graph_key") or ""))
    digest = hashlib.sha256()
    for row in rows:
        item = {key: row.get(key) for key in (
            "graph_key", "graph_type", "code", "source_code", "target_code", "semantic_type",
            "name", "description", "status", "permission_tags", "content_hash"
        )}
        item["permission_tags"] = list(item.get("permission_tags") or [])
        digest.update((json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
    return {
        "provider": "neo4j-graph", "available": True, "generationId": generation_id,
        "manifestHash": digest.hexdigest(),
        "entityCount": sum(1 for x in rows if x.get("graph_type") == "ENTITY"),
        "relationCount": sum(1 for x in rows if x.get("graph_type") == "RELATION"),
        "rowCount": len(rows),
    }


def _neo4j_generation_multihop(self, knowledge_base_id, entity_code, depth=3, limit=20,
                                permission_tags=None, token_budget=2500):
    """Return ACL-safe active-generation paths with bounded hops, cycles and context budget."""
    depth = max(1, min(int(depth), 4)); limit = max(1, min(int(limit), 100))
    token_budget = max(256, min(int(token_budget), 12000))
    cypher = (
        "MATCH (ptr:IndexGenerationPointer {knowledgeBaseId:$kb}) "
        "MATCH p=(s:EntityGen {knowledgeBaseId:$kb,generationId:ptr.activeGenerationId,code:$code})"
        "-[:RELATION_GEN*1..%d]-(t:EntityGen) "
        "WHERE all(n IN nodes(p) WHERE n.generationId=ptr.activeGenerationId AND "
        "(size(coalesce(n.permissionTags,[]))=0 OR any(tag IN coalesce(n.permissionTags,[]) WHERE tag IN $tags))) "
        "AND all(r IN relationships(p) WHERE r.generationId=ptr.activeGenerationId AND "
        "(size(coalesce(r.permissionTags,[]))=0 OR any(tag IN coalesce(r.permissionTags,[]) WHERE tag IN $tags))) "
        "AND all(n IN nodes(p) WHERE single(m IN nodes(p) WHERE id(m)=id(n))) "
        "OPTIONAL MATCH (o:KnowledgeObject {knowledgeBaseId:$kb,generationId:ptr.activeGenerationId})"
        "-[about:ABOUT_ENTITY_GEN {generationId:ptr.activeGenerationId}]->(t) "
        "WHERE size(coalesce(about.permissionTags,[]))=0 OR any(tag IN coalesce(about.permissionTags,[]) WHERE tag IN $tags) "
        "RETURN [n IN nodes(p)|{code:n.code,name:n.name,type:n.entityType}] AS nodes,"
        "[r IN relationships(p)|{code:r.code,type:r.relationType}] AS relations,"
        "collect(DISTINCT {objectId:o.objectId,objectType:o.objectType,title:o.title,content:o.content}) AS evidence,"
        "length(p) AS hops LIMIT $limit" % depth
    )
    records, _ = self._execute(cypher, {"kb": knowledge_base_id, "code": entity_code,
                                        "tags": list(permission_tags or []), "limit": limit})
    paths=[]; used=0
    for row in records:
        evidence=[x for x in (row.get("evidence") or []) if x.get("objectId")]
        text=" ".join(str(x.get("content") or x.get("title") or "") for x in evidence)
        cost=max(1, len(text)//4)
        if used + cost > token_budget and paths: continue
        used += min(cost, token_budget-used)
        hops=int(row.get("hops") or 1)
        paths.append({"nodes":row.get("nodes") or [],"relations":row.get("relations") or [],
                      "evidence":evidence,"hops":hops,"pathScore":round(1.0/(1.0+0.18*hops),4)})
        if used >= token_budget: break
    return {"provider":"neo4j","generationId":self.active_generation(knowledge_base_id),
            "startEntity":entity_code,"paths":paths,"pathCount":len(paths),
            "tokenBudget":token_budget,"estimatedTokens":used,"cyclePolicy":"SIMPLE_PATH",
            "aclPolicy":"NODE_EDGE_EVIDENCE_PRE_FILTER"}

if 'Neo4jGraphProvider' in globals():
    Neo4jGraphProvider.authoritative_graph_manifest = _neo4j_authoritative_graph_manifest
    Neo4jGraphProvider.generation_multihop = _neo4j_generation_multihop

# Phase 12: evidence-scoped graph facets. Canonical identity is never used as an ACL union.
def _neo4j_rebuild_authoritative_graph_with_facets(self, knowledge_base_id, generation_id, rows):
    """Rebuild canonical graph plus source-scoped entity/relation facets for one generation."""
    base_rows = [x for x in (rows or []) if str(x.get("graph_type")) not in ("ENTITY_FACET", "RELATION_FACET")]
    result = _neo4j_rebuild_authoritative_graph(self, knowledge_base_id, generation_id, base_rows)
    self._execute("MATCH (n:EntityFacetGen {knowledgeBaseId:$kb,generationId:$g}) DETACH DELETE n",
                  {"kb": knowledge_base_id, "g": generation_id})
    self._execute("MATCH (n:RelationFacetGen {knowledgeBaseId:$kb,generationId:$g}) DETACH DELETE n",
                  {"kb": knowledge_base_id, "g": generation_id})
    entity_facets = [x for x in (rows or []) if str(x.get("graph_type")) == "ENTITY_FACET"]
    relation_facets = [x for x in (rows or []) if str(x.get("graph_type")) == "RELATION_FACET"]
    for x in entity_facets:
        self._execute(
            "MATCH (e:EntityGen {knowledgeBaseId:$kb,generationId:$g,code:$entity}) "
            "MERGE (f:EntityFacetGen {knowledgeBaseId:$kb,generationId:$g,code:$code}) "
            "SET f.entityCode=$entity,f.entityType=$type,f.name=$name,f.description=$description,"
            "f.permissionTags=$tags,f.contentHash=$hash,f.status=$status "
            "MERGE (f)-[:FACET_OF {generationId:$g}]->(e)",
            {"kb": knowledge_base_id, "g": generation_id, "entity": x.get("source_code"),
             "code": x.get("code"), "type": x.get("semantic_type"), "name": x.get("name") or "",
             "description": x.get("description") or "", "tags": list(x.get("permission_tags") or []),
             "hash": x.get("content_hash") or "", "status": x.get("status")})
    for x in relation_facets:
        self._execute(
            "MATCH (a:EntityGen {knowledgeBaseId:$kb,generationId:$g,code:$source}),"
            "(b:EntityGen {knowledgeBaseId:$kb,generationId:$g,code:$target}) "
            "MERGE (f:RelationFacetGen {knowledgeBaseId:$kb,generationId:$g,code:$code}) "
            "SET f.relationType=$type,f.permissionTags=$tags,f.contentHash=$hash,f.status=$status "
            "MERGE (f)-[:FACET_SOURCE {generationId:$g}]->(a) "
            "MERGE (f)-[:FACET_TARGET {generationId:$g}]->(b)",
            {"kb": knowledge_base_id, "g": generation_id, "source": x.get("source_code"),
             "target": x.get("target_code"), "code": x.get("code"), "type": x.get("semantic_type"),
             "tags": list(x.get("permission_tags") or []), "hash": x.get("content_hash") or "",
             "status": x.get("status")})
    result["entityFacetCount"] = len(entity_facets)
    result["relationFacetCount"] = len(relation_facets)
    result["facetAclPolicy"] = "EVIDENCE_SCOPED_NON_BROADENING"
    return result


def _neo4j_facet_multihop(self, knowledge_base_id, entity_code, depth=3, limit=20,
                           permission_tags=None, token_budget=2500):
    """Traverse only relation facets visible to the persona; canonical nodes carry identity only."""
    depth = max(1, min(int(depth), 4)); limit = max(1, min(int(limit), 100))
    tags = list(permission_tags or [])
    cypher = (
        "MATCH (ptr:IndexGenerationPointer {knowledgeBaseId:$kb}) "
        "MATCH (s:EntityGen {knowledgeBaseId:$kb,generationId:ptr.activeGenerationId,code:$code}) "
        "MATCH p=(s)<-[:FACET_SOURCE|FACET_TARGET*1..%d]-(rf:RelationFacetGen)-[:FACET_SOURCE|FACET_TARGET*1..%d]->(t:EntityGen) "
        "WHERE rf.generationId=ptr.activeGenerationId AND t.generationId=ptr.activeGenerationId "
        "AND (size(coalesce(rf.permissionTags,[]))=0 OR any(tag IN coalesce(rf.permissionTags,[]) WHERE tag IN $tags)) "
        "RETURN DISTINCT t.code AS targetCode,t.name AS targetName,rf.code AS facetCode,"
        "rf.relationType AS relationType,rf.permissionTags AS permissionTags,rf.contentHash AS contentHash "
        "LIMIT $limit" % (depth, depth))
    records, _ = self._execute(cypher, {"kb": knowledge_base_id, "code": entity_code,
                                        "tags": tags, "limit": limit})
    paths=[]; used=0; token_budget=max(256,min(int(token_budget),12000))
    for row in records:
        text="%s %s %s" % (row.get("targetName") or "", row.get("relationType") or "", row.get("targetCode") or "")
        cost=max(1,len(text)//4)
        if used+cost>token_budget and paths: continue
        used += min(cost, token_budget-used)
        paths.append(row)
        if used>=token_budget: break
    return {"provider":"neo4j","generationId":self.active_generation(knowledge_base_id),
            "startEntity":entity_code,"paths":paths,"pathCount":len(paths),"estimatedTokens":used,
            "tokenBudget":token_budget,"aclPolicy":"FACET_PRE_FILTER_NON_BROADENING",
            "identityPolicy":"CANONICAL_ENTITY_IDENTITY_ONLY"}

if 'Neo4jGraphProvider' in globals():
    Neo4jGraphProvider.rebuild_authoritative_graph = _neo4j_rebuild_authoritative_graph_with_facets
    Neo4jGraphProvider.facet_multihop = _neo4j_facet_multihop

# Phase 12 manifest includes physical facet projections, so qualification includes protected facts.
def _neo4j_authoritative_graph_manifest_with_facets(self, knowledge_base_id, generation_id):
    import hashlib
    import json
    queries = [
        (
            "MATCH (e:EntityGen {knowledgeBaseId:$kb,generationId:$g}) "
            "RETURN 'ENTITY:' + e.code AS graph_key,'ENTITY' AS graph_type,e.code AS code,"
            "null AS source_code,null AS target_code,e.entityType AS semantic_type,e.name AS name,"
            "e.description AS description,e.status AS status,e.permissionTags AS permission_tags,"
            "e.contentHash AS content_hash"
        ),
        (
            "MATCH (a:EntityGen {knowledgeBaseId:$kb,generationId:$g})"
            "-[r:RELATION_GEN {generationId:$g}]->(b:EntityGen {knowledgeBaseId:$kb,generationId:$g}) "
            "RETURN 'RELATION:' + r.code AS graph_key,'RELATION' AS graph_type,r.code AS code,"
            "a.code AS source_code,b.code AS target_code,r.relationType AS semantic_type,null AS name,"
            "null AS description,r.status AS status,r.permissionTags AS permission_tags,r.contentHash AS content_hash"
        ),
        (
            "MATCH (f:EntityFacetGen {knowledgeBaseId:$kb,generationId:$g})-[:FACET_OF]->(e:EntityGen) "
            "RETURN 'ENTITY_FACET:' + f.code AS graph_key,'ENTITY_FACET' AS graph_type,f.code AS code,"
            "e.code AS source_code,null AS target_code,f.entityType AS semantic_type,f.name AS name,"
            "f.description AS description,f.status AS status,f.permissionTags AS permission_tags,"
            "f.contentHash AS content_hash"
        ),
        (
            "MATCH (f:RelationFacetGen {knowledgeBaseId:$kb,generationId:$g})-[:FACET_SOURCE]->(a:EntityGen),"
            "(f)-[:FACET_TARGET]->(b:EntityGen) RETURN 'RELATION_FACET:' + f.code AS graph_key,"
            "'RELATION_FACET' AS graph_type,f.code AS code,a.code AS source_code,b.code AS target_code,"
            "f.relationType AS semantic_type,null AS name,null AS description,f.status AS status,"
            "f.permissionTags AS permission_tags,f.contentHash AS content_hash"
        ),
    ]
    rows = []
    for query in queries:
        part, _summary = self._execute(query, {"kb": knowledge_base_id, "g": generation_id})
        rows.extend(part)
    links, _summary = self._execute(
        "MATCH (o:KnowledgeObject {knowledgeBaseId:$kb,generationId:$g})"
        "-[r:ABOUT_ENTITY_GEN {generationId:$g}]->(e:EntityGen {knowledgeBaseId:$kb,generationId:$g}) "
        "RETURN CASE WHEN r.objectType='CLAIM' THEN 'CLAIM_ENTITY:' ELSE 'CARD_ENTITY:' END "
        "+ o.objectId + ':' + e.code AS graph_key,CASE WHEN r.objectType='CLAIM' THEN 'CLAIM_ENTITY' "
        "ELSE 'CARD_ENTITY' END AS graph_type,o.objectId + ':' + e.code AS code,o.objectId AS source_code,"
        "e.code AS target_code,r.role AS semantic_type,null AS name,null AS description,o.status AS status,"
        "r.permissionTags AS permission_tags,r.contentHash AS content_hash",
        {"kb": knowledge_base_id, "g": generation_id},
    )
    rows.extend(links)
    rows = sorted(rows, key=lambda x: str(x.get("graph_key") or ""))
    digest = hashlib.sha256()
    keys = (
        "graph_key", "graph_type", "code", "source_code", "target_code", "semantic_type",
        "name", "description", "status", "permission_tags", "content_hash",
    )
    for row in rows:
        item = {key: row.get(key) for key in keys}
        item["permission_tags"] = list(item.get("permission_tags") or [])
        canonical = json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n"
        digest.update(canonical.encode("utf-8"))
    return {
        "provider": "neo4j-graph", "available": True, "generationId": generation_id,
        "manifestHash": digest.hexdigest(),
        "entityCount": sum(1 for x in rows if x.get("graph_type") == "ENTITY"),
        "relationCount": sum(1 for x in rows if x.get("graph_type") == "RELATION"),
        "entityFacetCount": sum(1 for x in rows if x.get("graph_type") == "ENTITY_FACET"),
        "relationFacetCount": sum(1 for x in rows if x.get("graph_type") == "RELATION_FACET"),
        "rowCount": len(rows),
    }


if 'Neo4jGraphProvider' in globals():
    Neo4jGraphProvider.authoritative_graph_manifest = _neo4j_authoritative_graph_manifest_with_facets

# Phase 13: citation-safe graph paths require visible immutable evidence bindings.
def _neo4j_rebuild_graph_evidence(self, knowledge_base_id, generation_id, bindings):
    """Project immutable EvidenceGen/SourceGen nodes and facet support edges."""
    params = {"kb": knowledge_base_id, "g": generation_id}
    self._execute(
        "MATCH (e:EvidenceGen {knowledgeBaseId:$kb,generationId:$g}) DETACH DELETE e", params)
    self._execute(
        "MATCH (s:SourceGen {knowledgeBaseId:$kb,generationId:$g}) DETACH DELETE s", params)
    for item in bindings or []:
        self._execute(
            "MERGE (s:SourceGen {knowledgeBaseId:$kb,generationId:$g,code:$source}) "
            "SET s.permissionTags=$tags,s.sourceVersion=$version "
            "MERGE (e:EvidenceGen {knowledgeBaseId:$kb,generationId:$g,code:$evidence}) "
            "SET e.permissionTags=$tags,e.contentHash=$hash,e.locator=$locator "
            "MERGE (e)-[:DERIVED_FROM {generationId:$g}]->(s) WITH e "
            "OPTIONAL MATCH (ef:EntityFacetGen {knowledgeBaseId:$kb,generationId:$g,code:$facet}) "
            "FOREACH (_ IN CASE WHEN ef IS NULL THEN [] ELSE [1] END | "
            "MERGE (ef)-[:SUPPORTED_BY {generationId:$g}]->(e)) WITH e "
            "OPTIONAL MATCH (rf:RelationFacetGen {knowledgeBaseId:$kb,generationId:$g,code:$facet}) "
            "FOREACH (_ IN CASE WHEN rf IS NULL THEN [] ELSE [1] END | "
            "MERGE (rf)-[:SUPPORTED_BY {generationId:$g}]->(e))",
            {"kb": knowledge_base_id, "g": generation_id, "source": item.get("sourceCode"),
             "version": item.get("sourceVersion"), "evidence": item.get("evidenceCode"),
             "tags": list(item.get("permissionTags") or []), "hash": item.get("evidenceHash") or "",
             "locator": item.get("locator") or "", "facet": item.get("facetCode")})
    return {"provider": "neo4j", "generationId": generation_id,
            "bindingCount": len(bindings or []), "citationPolicy": "VISIBLE_EVIDENCE_REQUIRED"}


def _neo4j_citation_safe_multihop(self, knowledge_base_id, entity_code, depth=3, limit=20,
                                   permission_tags=None, token_budget=2500):
    """Return only facet paths with evidence visible to the current persona."""
    tags = list(permission_tags or [])
    limit = max(1, min(int(limit), 100))
    query = (
        "MATCH (ptr:IndexGenerationPointer {knowledgeBaseId:$kb}) "
        "MATCH (s:EntityGen {knowledgeBaseId:$kb,generationId:ptr.activeGenerationId,code:$code}) "
        "MATCH (rf:RelationFacetGen {knowledgeBaseId:$kb,generationId:ptr.activeGenerationId})"
        "-[:FACET_SOURCE|FACET_TARGET]->(s) "
        "MATCH (rf)-[:SUPPORTED_BY {generationId:ptr.activeGenerationId}]->(ev:EvidenceGen)"
        "-[:DERIVED_FROM]->(src:SourceGen) "
        "WHERE (size(coalesce(rf.permissionTags,[]))=0 OR any(x IN rf.permissionTags WHERE x IN $tags)) "
        "AND (size(coalesce(ev.permissionTags,[]))=0 OR any(x IN ev.permissionTags WHERE x IN $tags)) "
        "AND (size(coalesce(src.permissionTags,[]))=0 OR any(x IN src.permissionTags WHERE x IN $tags)) "
        "MATCH (rf)-[:FACET_SOURCE|FACET_TARGET]->(t:EntityGen) WHERE t.code<>$code "
        "RETURN DISTINCT t.code targetCode,t.name targetName,rf.code facetCode,"
        "rf.relationType relationType,collect(DISTINCT {evidenceCode:ev.code,contentHash:ev.contentHash,"
        "locator:ev.locator,sourceCode:src.code,sourceVersion:src.sourceVersion}) citations LIMIT $limit")
    rows, _ = self._execute(query, {"kb": knowledge_base_id, "code": entity_code,
                                    "tags": tags, "limit": limit})
    budget = max(256, min(int(token_budget), 12000))
    used = 0
    paths = []
    for row in rows:
        citations = [x for x in (row.get("citations") or []) if x.get("evidenceCode")]
        if not citations:
            continue
        cost = max(1, len(str(row)) // 4)
        if used + cost > budget and paths:
            continue
        used += min(cost, budget - used)
        row["citationCount"] = len(citations)
        paths.append(row)
    return {"provider": "neo4j", "generationId": self.active_generation(knowledge_base_id),
            "paths": paths, "pathCount": len(paths), "estimatedTokens": used,
            "tokenBudget": budget, "aclPolicy": "FACET_EVIDENCE_SOURCE_PRE_FILTER",
            "citationPolicy": "AT_LEAST_ONE_VISIBLE_EVIDENCE_PER_PATH"}


if 'Neo4jGraphProvider' in globals():
    Neo4jGraphProvider.rebuild_graph_evidence = _neo4j_rebuild_graph_evidence
    Neo4jGraphProvider.citation_safe_multihop = _neo4j_citation_safe_multihop
