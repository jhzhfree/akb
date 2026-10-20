"""Hybrid retrieval orchestration for lexical, vector, card, claim, and graph recall with local fallbacks."""

import asyncio
import heapq
import math
import re
import time
from collections import Counter, defaultdict
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

import numpy as np

from app.core.config import settings
from app.core.contracts import retrievable_statuses, statusless_objects_retrievable
from app.services.embedding import get_embedding_provider
from app.services.graph_provider import get_graph_provider
from app.services.retrieval.external import get_elasticsearch_provider, get_milvus_provider
from app.storage.graph_store import graph_store
from app.storage.index_store import store

TOKEN_RE = re.compile(r"[A-Za-z0-9_.+-]+|[一-鿿]")
BM25_K1 = 1.5
BM25_B = 0.75
# Fallback budget used when a per-channel mapping does not name the channel.
DEFAULT_CHANNEL_TOP_K = 20
# Traversal bounds: a question names a handful of entities, and each one only has so many
# neighbours worth putting in front of a reranker.
MAX_GRAPH_SEEDS = 8
MAX_GRAPH_BRANCH = 12


def toks(text: str) -> list[str]:
    """Tokenize text for the local BM25 fallback."""
    return TOKEN_RE.findall((text or "").lower())


def allowed(doc: dict[str, Any], tags: Iterable[str] | None) -> bool:
    """Evaluate publication-state and ACL visibility before local Top-K selection."""
    status = doc.get("status")
    if status is None or not str(status).strip():
        if not statusless_objects_retrievable():
            return False
    elif str(status).upper() not in retrievable_statuses():
        return False
    required = doc.get("permission_tags") or []
    if not required:
        return True
    # ``None`` carries the same meaning as in the Elasticsearch and Milvus providers: there is
    # no ACL identity to enforce (platform admin or auth off), so the clause is dropped and
    # everything is visible. Treating it as "holds no tag" used to reject every object that
    # carries __public__ — which is every card and claim, hence the empty card/claim channels.
    if tags is None:
        return True
    return bool(set(required).intersection(tags if isinstance(tags, set) else set(tags)))


@dataclass(frozen=True)
class LexicalDocument:
    """Pre-tokenized local lexical document used by BM25 fallback."""

    doc: dict[str, Any]
    tokens: tuple[str, ...]
    term_frequency: Counter


class LexicalSnapshot:
    """Pre-tokenized lexical view for one knowledge-base index generation."""

    def __init__(self, docs: list[dict[str, Any]]):
        self.documents: list[LexicalDocument] = []
        self.documents_by_type: dict[str, list[LexicalDocument]] = defaultdict(list)
        self.document_frequency: Counter = Counter()
        total_length = 0

        for doc in docs:
            tokens = tuple(toks((doc.get("title") or "") + " " + (doc.get("content") or "")))
            tf = Counter(tokens)
            item = LexicalDocument(doc=doc, tokens=tokens, term_frequency=tf)
            self.documents.append(item)
            self.documents_by_type[str(doc.get("object_type") or "")].append(item)
            total_length += len(tokens)
            self.document_frequency.update(tf.keys())

        self.document_count = len(self.documents)
        self.average_length = total_length / max(self.document_count, 1)


class VectorSnapshot:
    """Dense float32 vector view built once per index generation.

    The JSON fallback stores vectors as Python lists. Converting every document
    to a NumPy array for every query dominates latency at medium scale. This
    snapshot pays the conversion cost once after an index change, then performs
    one matrix-vector multiplication per query.
    """

    def __init__(self, docs: list[dict[str, Any]]):
        vector_docs = []
        vectors = []
        dimension = None
        for doc in docs:
            vector = doc.get("vector")
            if not vector:
                continue
            if dimension is None:
                dimension = len(vector)
            if len(vector) != dimension:
                continue
            vector_docs.append(doc)
            vectors.append(vector)

        self.documents = vector_docs
        if vectors:
            self.matrix = np.asarray(vectors, dtype=np.float32)
            self.norms = np.linalg.norm(self.matrix, axis=1)
            self.norms[self.norms == 0] = 1.0
        else:
            self.matrix = np.empty((0, 0), dtype=np.float32)
            self.norms = np.empty((0,), dtype=np.float32)

    @property
    def size(self) -> int:
        return len(self.documents)


class HybridRetriever:
    """Run external retrieval first and degrade to local lexical/vector/graph fallbacks when allowed."""

    def __init__(self):
        self._lexical_cache: dict[str, tuple[int, LexicalSnapshot]] = {}
        self._vector_cache: dict[str, tuple[int, VectorSnapshot]] = {}

    @staticmethod
    def size_for(top_k, channel):
        """Resolve one channel's recall budget from either an int or a per-channel mapping."""
        if isinstance(top_k, Mapping):
            value = top_k.get(channel, top_k.get("default", DEFAULT_CHANNEL_TOP_K))
            try:
                return max(int(value), 0)
            except (TypeError, ValueError):
                return DEFAULT_CHANNEL_TOP_K
        try:
            return max(int(top_k), 0)
        except (TypeError, ValueError):
            return DEFAULT_CHANNEL_TOP_K

    async def search(self, kb, query, channels, top_k, permission_tags=None):
        """Return recall results while preserving the historical public contract."""
        result, _ = await self.search_with_diagnostics(kb, query, channels, top_k, permission_tags)
        return result

    async def search_with_diagnostics(self, kb, query, channels, top_k, permission_tags=None):
        """Return recall results plus channel latency/provider/filter diagnostics.

        ``top_k`` is either one budget shared by every channel, or a mapping of
        channel -> budget, which is how the console queries-policy tunes each
        recall channel independently.
        """
        result = {}
        channel_latency_ms = {}
        # Degradations are part of the trace, not log noise: a query answered from the
        # local fallback must be distinguishable from one answered by ES/Milvus (§12.5).
        errors = {}
        use_external = settings.retrieval_mode in {"auto", "external", "dual"}
        elasticsearch = get_elasticsearch_provider()
        milvus = get_milvus_provider()

        async def measured(name, awaitable):
            started = time.perf_counter()
            value = await awaitable
            channel_latency_ms[name] = int((time.perf_counter() - started) * 1000)
            return value

        async def lexical(channel, object_type=None, size=None):
            size = self.size_for(top_k, channel) if size is None else size
            if use_external and settings.elasticsearch_enabled:
                try:
                    return await elasticsearch.search(kb, query, size, permission_tags, object_type)
                except Exception as exc:
                    if settings.retrieval_external_required:
                        raise
                    errors.setdefault(channel, []).append("elasticsearch degraded: %s" % exc)
            if channel == "bm25":
                return self._bm25(kb, query, size, permission_tags)
            return self._typed(kb, object_type, query, size, permission_tags)

        if "bm25" in channels:
            result["bm25"] = await measured("bm25", lexical("bm25"))
        if "vector" in channels:
            vector_size = self.size_for(top_k, "vector")

            async def vector_search():
                if use_external and settings.milvus_enabled:
                    try:
                        vector = (await get_embedding_provider().embed([query]))[0]
                        return await milvus.search(kb, vector, vector_size, permission_tags)
                    except Exception as exc:
                        if settings.retrieval_external_required:
                            raise
                        errors.setdefault("vector", []).append("milvus degraded: %s" % exc)
                return await self._vector(kb, query, vector_size, permission_tags)

            result["vector"] = await measured("vector", vector_search())
        if "card" in channels:
            result["card"] = await measured("card", lexical("card", "CARD"))
        if "claim" in channels:
            result["claim"] = await measured("claim", lexical("claim", "CLAIM"))
        if "graph" in channels:
            graph_size = self.size_for(top_k, "graph")

            async def graph_search():
                graph = get_graph_provider()
                if graph.available():
                    try:
                        hits = await asyncio.to_thread(graph.search, kb, query, graph_size, permission_tags)
                        if hits:
                            return hits
                        errors.setdefault("graph", []).append("neo4j empty: no entity in the question matched")
                    except Exception as exc:
                        errors.setdefault("graph", []).append("neo4j degraded: %s" % exc)
                # No graph engine does not mean no graph: the authoritative graph mirrored at
                # index time is still traversable, and answering a relation question with claim
                # sentences instead of paths is how a multi-hop answer gets missed.
                local = await asyncio.to_thread(self._graph_local, kb, query, graph_size, permission_tags)
                if local:
                    return local
                return await lexical("claim", "CLAIM", graph_size)

            result["graph"] = await measured("graph", graph_search())

        external_generations = set()
        external_missing_generation = False
        for candidates in result.values():
            for item in candidates or []:
                metadata = item.get("metadata") or {}
                if metadata.get("retrieval_provider") not in {"elasticsearch", "milvus", "neo4j"}:
                    continue
                generation = metadata.get("generation_id")
                if generation:
                    external_generations.add(str(generation))
                else:
                    external_missing_generation = True
        graph = get_graph_provider()
        if graph.available():
            try:
                graph_generation = await asyncio.to_thread(graph.active_generation, kb)
                if graph_generation:
                    external_generations.add(str(graph_generation))
                elif external_generations:
                    external_missing_generation = True
            except Exception:
                if settings.retrieval_external_required:
                    raise
        if len(external_generations) > 1 or (external_generations and external_missing_generation):
            raise RuntimeError("CROSS_ENGINE_GENERATION_MISMATCH")

        diagnostics = {
            "channelLatencyMs": channel_latency_ms,
            "errors": errors,
            "generationIds": sorted(external_generations),
            "generationBarrier": "FAIL_CLOSED_ES_MILVUS_NEO4J",
            "filters": {
                "status": "RETRIEVABLE_STATUSES_PRE_TOPK",
                "acl": "PERMISSION_TAGS_PRE_TOPK_ALL_CHANNELS",
                "graphAcl": "PERMISSION_TAGS_PRE_TOPK",
                "permissionTagCount": len(permission_tags or []),
                "dedup": "RRF_BY_OBJECT_ID",
                "conflict": "STATUS_POLICY",
            },
        }
        return result, diagnostics

    def _snapshot(self, kb) -> LexicalSnapshot:
        key = str(kb)
        generation = store.generation(kb)
        cached = self._lexical_cache.get(key)
        if cached and cached[0] == generation:
            return cached[1]
        snapshot = LexicalSnapshot(store.load(kb))
        self._lexical_cache[key] = (generation, snapshot)
        return snapshot

    def _vector_snapshot(self, kb) -> VectorSnapshot:
        key = str(kb)
        generation = store.generation(kb)
        cached = self._vector_cache.get(key)
        if cached and cached[0] == generation:
            return cached[1]
        snapshot = VectorSnapshot(store.load(kb))
        self._vector_cache[key] = (generation, snapshot)
        return snapshot

    def _as_candidate(self, doc, channel, score):
        metadata = {
            key: doc.get(key)
            for key in [
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
        }
        metadata["retrieval_provider"] = "local"
        return {
            "id": str(doc["id"]),
            "channel": channel,
            "title": doc.get("title", ""),
            "content": doc.get("content", ""),
            "score": float(score),
            "metadata": metadata,
        }

    def _bm25(self, kb, query, top_k, permission_tags=None):
        query_tokens = toks(query)
        snapshot = self._snapshot(kb)
        if not query_tokens or not snapshot.document_count:
            return []

        scored = []
        for item in snapshot.documents:
            if not allowed(item.doc, permission_tags):
                continue
            score = self._bm25_score(snapshot, item, query_tokens)
            if score > 0:
                scored.append((score, item.doc))

        return [
            self._as_candidate(doc, "bm25", score)
            for score, doc in heapq.nlargest(top_k, scored, key=lambda pair: pair[0])
        ]

    @staticmethod
    def _bm25_score(snapshot: LexicalSnapshot, item: LexicalDocument, query_tokens: list[str]) -> float:
        score = 0.0
        length = len(item.tokens)
        for token in query_tokens:
            frequency = item.term_frequency.get(token, 0)
            if not frequency:
                continue
            document_frequency = snapshot.document_frequency.get(token, 0)
            idf = math.log(1 + (snapshot.document_count - document_frequency + 0.5) / (document_frequency + 0.5))
            denominator = frequency + BM25_K1 * (1 - BM25_B + BM25_B * length / max(snapshot.average_length, 1))
            score += idf * (frequency * (BM25_K1 + 1)) / denominator
        return score

    async def _vector(self, kb, query, top_k, permission_tags=None):
        snapshot = self._vector_snapshot(kb)
        if not snapshot.size:
            return []

        query_vector = np.asarray((await get_embedding_provider().embed([query]))[0], dtype=np.float32)
        if snapshot.matrix.shape[1] != query_vector.shape[0]:
            return []
        query_norm = float(np.linalg.norm(query_vector))
        if query_norm == 0:
            return []

        scores = (snapshot.matrix @ query_vector) / (snapshot.norms * query_norm)

        # Permissions must be applied before Top-K selection. Most internal
        # knowledge bases have no per-object tags, so avoid building a mask
        # unless the index actually contains restricted documents.
        if any(doc.get("permission_tags") for doc in snapshot.documents):
            mask = np.fromiter(
                (allowed(doc, permission_tags) for doc in snapshot.documents),
                dtype=np.bool_,
                count=snapshot.size,
            )
            scores = scores.copy()
            scores[~mask] = -np.inf

        count = min(max(int(top_k), 0), snapshot.size)
        if count == 0:
            return []
        if count == snapshot.size:
            indices = np.argsort(scores)[::-1]
        else:
            indices = np.argpartition(scores, -count)[-count:]
            indices = indices[np.argsort(scores[indices])[::-1]]

        return [
            self._as_candidate(snapshot.documents[int(index)], "vector", float(scores[int(index)]))
            for index in indices
            if np.isfinite(scores[int(index)])
        ]

    def _typed(self, kb, object_type, query, top_k, permission_tags=None):
        snapshot = self._snapshot(kb)
        query_tokens: set[str] = set(toks(query))
        if not query_tokens:
            return []

        scored = []
        for item in snapshot.documents_by_type.get(object_type, []):
            if not allowed(item.doc, permission_tags):
                continue
            overlap = len(query_tokens.intersection(item.term_frequency.keys())) / float(len(query_tokens))
            scored.append((overlap + 0.2, item.doc))

        return [
            self._as_candidate(doc, object_type.lower(), score)
            for score, doc in heapq.nlargest(top_k, scored, key=lambda pair: pair[0])
        ]

    def _graph_like(self, kb, query, top_k, permission_tags=None):
        # Local fallback intentionally uses CLAIM lexical candidates instead of
        # pretending to perform graph traversal when Neo4j is unavailable.
        return self._typed(kb, "CLAIM", query, top_k, permission_tags)

    def _graph_local(self, kb, query, top_k, permission_tags=None):
        """Traverse the locally mirrored authoritative graph, evidence-bound per path.

        Traversal answers "what is connected, and through what"; the documents answer "why may
        anyone say so". A path is therefore only offered as recall when a card or claim already
        on the index states both of its endpoints — an unbound path would otherwise become a
        citation with nothing standing behind it, and the answer gate would be right to block it.
        """
        graph = graph_store.load(kb)
        entities = graph.get("entities") or {}
        relations = graph.get("relations") or []
        if not entities or not relations:
            return []
        seeds = self._seed_entities(entities, query)
        if not seeds:
            return []
        adjacency: dict[str, list[tuple[str, dict[str, Any]]]] = defaultdict(list)
        for relation in relations:
            if not self._graph_allowed(relation.get("permissionTags"), permission_tags):
                continue
            adjacency[str(relation.get("source"))].append((str(relation.get("target")), relation))
            adjacency[str(relation.get("target"))].append((str(relation.get("source")), relation))
        documents = [doc for doc in store.load(kb) if allowed(doc, permission_tags)]
        paths: list[tuple[str, list[tuple[dict[str, Any], str]]]] = []
        for seed in seeds:
            for neighbour, relation in adjacency.get(seed, [])[:MAX_GRAPH_BRANCH]:
                paths.append((seed, [(relation, neighbour)]))
                for second, relation2 in adjacency.get(neighbour, [])[:MAX_GRAPH_BRANCH]:
                    if second == seed:
                        continue
                    paths.append((seed, [(relation, neighbour), (relation2, second)]))
        scored = []
        for seed, steps in paths:
            support = self._path_support(documents, seed, steps[-1][1], entities)
            if support is None:
                continue
            confidence = min(float(step[0].get("confidence") or 0.5) for step in steps)
            # Every extra hop is another relation that has to hold, so a longer path is worth
            # strictly less than a short one even when both are equally well supported.
            score = confidence * (0.9 ** (len(steps) - 1)) + 0.15 * (len(steps) - 1)
            scored.append((score, seed, steps, support))
        scored.sort(key=lambda item: -item[0])
        return [
            self._as_graph_candidate(score, seed, steps, support, entities, permission_tags)
            for score, seed, steps, support in scored[: max(int(top_k), 0)]
        ]

    @staticmethod
    def _seed_entities(entities: dict[str, Any], query: str) -> list[str]:
        """Entities the question actually asks about; traversal starts where the question points."""
        text = re.sub(r"\s+", "", str(query or "").lower())
        if not text:
            return []
        seeds = []
        for code, entity in entities.items():
            name = re.sub(r"\s+", "", str((entity or {}).get("name") or "").lower())
            if len(name) >= 2 and (name in text or text in name):
                seeds.append(str(code))
        return seeds[:MAX_GRAPH_SEEDS]

    @staticmethod
    def _graph_allowed(required: Any, tags: Iterable[str] | None) -> bool:
        """Graph ACL: an edge with no tags is visible, a tagged edge needs the matching tag."""
        if isinstance(required, str):
            required = [required] if required.strip() and required.strip() != "[]" else []
        required = [str(value) for value in (required or []) if str(value) and str(value) not in {"[", "]"}]
        if not required:
            return True
        if not tags:
            return False
        return bool(set(required).intersection(set(tags)))

    @staticmethod
    def _path_support(documents: list[dict[str, Any]], head: str, tail: str, entities: dict[str, Any]):
        """The indexed card or claim that states both endpoints of a path."""
        head_name = str((entities.get(head) or {}).get("name") or "")
        tail_name = str((entities.get(tail) or {}).get("name") or "")
        if not head_name or not tail_name or head_name == tail_name:
            return None
        for doc in documents:
            haystack = "%s %s" % (doc.get("title") or "", doc.get("content") or "")
            if head_name not in haystack or tail_name not in haystack:
                continue
            evidence = doc.get("evidence_code") or doc.get("evidenceCode")
            evidence_hash = doc.get("evidence_hash") or doc.get("content_hash")
            if evidence and evidence_hash:
                return doc
        return None

    def _as_graph_candidate(self, score, seed, steps, support, entities, permission_tags=None):
        codes = [seed] + [str(step[1]) for step in steps]
        names = [self._entity_name(code, entities) for code in codes]
        types = [str(step[0].get("relationType") or "RELATED_TO") for step in steps]
        legs = []
        for index, relation_type in enumerate(types):
            legs.append("%s -%s-> %s" % (names[index], relation_type, names[index + 1]))
        title = "%s → %s（图谱路径）" % (names[0], names[-1])
        content = "图谱路径（%d 跳）：%s；支撑知识：%s" % (
            len(steps),
            " → ".join(legs),
            support.get("title") or support.get("id") or "",
        )
        metadata = {
            "object_type": "GRAPH",
            "retrieval_provider": "local",
            "source_name": "知识图谱路径",
            "source_uri": "",
            "status": "APPROVED",
            "permission_tags": list(permission_tags or []),
            # Borrowed from the document that states this path: the edge is structure, the
            # document is the evidence that lets the answer quote it.
            "evidence_code": support.get("evidence_code") or support.get("evidenceCode"),
            "evidence_hash": support.get("evidence_hash") or support.get("content_hash"),
            "content_hash": support.get("content_hash") or support.get("contentHash") or "",
            "graph_path": [
                {
                    "sourceCode": codes[index],
                    "relationType": types[index],
                    "targetCode": codes[index + 1],
                    "sourceName": names[index],
                    "targetName": names[index + 1],
                }
                for index in range(len(types))
            ],
            "graph_hops": len(steps),
            "confidence": min(float(step[0].get("confidence") or 0.5) for step in steps),
        }
        return {
            "id": "GRAPH:" + "|".join(codes),
            "channel": "graph",
            "title": title,
            "content": content,
            "score": float(score),
            "metadata": metadata,
        }

    @staticmethod
    def _entity_name(code: str, entities: dict[str, Any]) -> str:
        entity = entities.get(code) or {}
        return str(entity.get("name") or code)


def rrf(result_sets, k=60, weights=None, top_k=10):
    """Fuse ranked recall lists with Reciprocal Rank Fusion.

    Besides the fused score, every surviving candidate keeps its per-channel raw
    score and rank (design §12.4 "Score Breakdown"): the fused number alone cannot
    explain *why* a candidate was hit or *where* it came from, and overwriting the
    channel scores made the retrieval test page unexplainable.
    """
    scores = defaultdict(float)
    best = {}
    channels = defaultdict(list)
    channel_scores = defaultdict(dict)
    channel_ranks = defaultdict(dict)
    weights = weights or {}

    for channel, items in result_sets.items():
        weight = float(weights.get(channel, 1.0))
        for rank, item in enumerate(items, 1):
            doc_id = item["id"]
            scores[doc_id] += weight / (k + rank)
            channels[doc_id].append(channel)
            channel_scores[doc_id][channel] = float(item.get("score") or 0.0)
            channel_ranks[doc_id][channel] = rank
            if doc_id not in best or item["score"] > best[doc_id]["score"]:
                best[doc_id] = dict(item)

    top_ids = heapq.nlargest(top_k, scores.keys(), key=lambda doc_id: scores[doc_id])
    output = []
    for doc_id in top_ids:
        item = best[doc_id]
        item["rrfScore"] = scores[doc_id]
        item["score"] = scores[doc_id]
        item["channelScores"] = dict(channel_scores[doc_id])
        item["channelRanks"] = dict(channel_ranks[doc_id])
        item["metadata"] = dict(item.get("metadata") or {})
        item["metadata"]["matched_channels"] = channels[doc_id]
        output.append(item)
    return output
