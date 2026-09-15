"""Hybrid retrieval orchestration for lexical, vector, card, claim, and graph recall with local fallbacks."""

import asyncio
import heapq
import math
import re

import numpy as np
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from app.services.embedding import cosine, get_embedding_provider
from app.services.graph_provider import get_graph_provider
from app.core.config import settings
from app.services.retrieval.external import get_elasticsearch_provider, get_milvus_provider
from app.storage.index_store import store

TOKEN_RE = re.compile(r"[A-Za-z0-9_.+-]+|[一-鿿]")
BM25_K1 = 1.5
BM25_B = 0.75


def toks(text: str) -> List[str]:
    """Tokenize text for the local BM25 fallback."""
    return TOKEN_RE.findall((text or "").lower())


def allowed(doc: Dict[str, Any], tags: Optional[Iterable[str]]) -> bool:
    """Evaluate publication-state and ACL visibility before local Top-K selection."""
    status = doc.get("status")
    if status and str(status).upper() not in {"APPROVED", "EFFECTIVE"}:
        return False
    required = doc.get("permission_tags") or []
    if not required:
        return True
    if not tags:
        return False
    return bool(set(required).intersection(tags if isinstance(tags, set) else set(tags)))


@dataclass(frozen=True)
class LexicalDocument:
    """Pre-tokenized local lexical document used by BM25 fallback."""
    doc: Dict[str, Any]
    tokens: Tuple[str, ...]
    term_frequency: Counter


class LexicalSnapshot:
    """Pre-tokenized lexical view for one knowledge-base index generation."""

    def __init__(self, docs: List[Dict[str, Any]]):
        self.documents: List[LexicalDocument] = []
        self.documents_by_type: Dict[str, List[LexicalDocument]] = defaultdict(list)
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

    def __init__(self, docs: List[Dict[str, Any]]):
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
        self._lexical_cache: Dict[str, Tuple[int, LexicalSnapshot]] = {}
        self._vector_cache: Dict[str, Tuple[int, VectorSnapshot]] = {}

    async def search(self, kb, query, channels, top_k, permission_tags=None):
        result = {}
        use_external = settings.retrieval_mode in {"auto", "external", "dual"}
        elasticsearch = get_elasticsearch_provider()
        milvus = get_milvus_provider()

        async def lexical(channel, object_type=None):
            if use_external and settings.elasticsearch_enabled:
                try:
                    return await elasticsearch.search(kb, query, top_k, permission_tags, object_type)
                except Exception:
                    if settings.retrieval_external_required:
                        raise
            if channel == "bm25":
                return self._bm25(kb, query, top_k, permission_tags)
            return self._typed(kb, object_type, query, top_k, permission_tags)

        if "bm25" in channels:
            result["bm25"] = await lexical("bm25")
        if "vector" in channels:
            if use_external and settings.milvus_enabled:
                try:
                    vector = (await get_embedding_provider().embed([query]))[0]
                    result["vector"] = await milvus.search(kb, vector, top_k, permission_tags)
                except Exception:
                    if settings.retrieval_external_required:
                        raise
                    result["vector"] = await self._vector(kb, query, top_k, permission_tags)
            else:
                result["vector"] = await self._vector(kb, query, top_k, permission_tags)
        if "card" in channels:
            result["card"] = await lexical("card", "CARD")
        if "claim" in channels:
            result["claim"] = await lexical("claim", "CLAIM")
        if "graph" in channels:
            graph = get_graph_provider()
            if graph.available():
                try:
                    result["graph"] = await asyncio.to_thread(graph.search, kb, query, top_k)
                except Exception:
                    result["graph"] = await lexical("claim", "CLAIM")
            else:
                result["graph"] = await lexical("claim", "CLAIM")
        return result

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
    def _bm25_score(snapshot: LexicalSnapshot, item: LexicalDocument, query_tokens: List[str]) -> float:
        score = 0.0
        length = len(item.tokens)
        for token in query_tokens:
            frequency = item.term_frequency.get(token, 0)
            if not frequency:
                continue
            document_frequency = snapshot.document_frequency.get(token, 0)
            idf = math.log(
                1
                + (snapshot.document_count - document_frequency + 0.5)
                / (document_frequency + 0.5)
            )
            denominator = frequency + BM25_K1 * (
                1 - BM25_B + BM25_B * length / max(snapshot.average_length, 1)
            )
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
        query_tokens: Set[str] = set(toks(query))
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


def rrf(result_sets, k=60, weights=None, top_k=10):
    """Fuse ranked recall lists with Reciprocal Rank Fusion."""
    scores = defaultdict(float)
    best = {}
    channels = defaultdict(list)
    weights = weights or {}

    for channel, items in result_sets.items():
        weight = float(weights.get(channel, 1.0))
        for rank, item in enumerate(items, 1):
            doc_id = item["id"]
            scores[doc_id] += weight / (k + rank)
            channels[doc_id].append(channel)
            if doc_id not in best or item["score"] > best[doc_id]["score"]:
                best[doc_id] = dict(item)

    top_ids = heapq.nlargest(top_k, scores.keys(), key=lambda doc_id: scores[doc_id])
    output = []
    for doc_id in top_ids:
        item = best[doc_id]
        item["score"] = scores[doc_id]
        item["metadata"] = dict(item.get("metadata") or {})
        item["metadata"]["matched_channels"] = channels[doc_id]
        output.append(item)
    return output
