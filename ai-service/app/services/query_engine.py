"""Query understanding, retrieval routing, fusion, reranking, context assembly, and answer generation."""

from app.core.config import settings
import asyncio
from app.services.retrieval.hybrid import HybridRetriever, rrf
from app.services.llm_provider import get_llm_provider
from app.services.reranker import get_reranker
import time


class QueryRouter:
    """Map query intent to the minimum useful set of retrieval channels."""
    def route(self, q):
        if any(k in q for k in ["错误码", "配置项", "接口", "类名"]):
            return {"questionType": "EXACT", "strategies": ["bm25", "vector"]}
        if any(k in q for k in ["关系", "依赖", "关联", "影响", "上游", "下游"]):
            return {"questionType": "RELATION", "strategies": ["graph", "vector", "card"]}
        if any(k in q for k in ["如何", "步骤", "流程", "操作", "怎么"]):
            return {"questionType": "PROCEDURE", "strategies": ["card", "bm25", "vector"]}
        if any(k in q for k in ["是否", "支持", "条件", "规则", "必须"]):
            return {"questionType": "CLAIM", "strategies": ["claim", "card", "bm25", "vector"]}
        return {"questionType": "FACT", "strategies": ["card", "vector", "bm25", "claim"]}


class QueryEngine:
    """Coordinate query rewrite, multi-channel recall, fusion, reranking, context, and answer generation."""
    def __init__(self):
        self.retriever = HybridRetriever()
        self.router = QueryRouter()
        self.rerank_semaphore = asyncio.Semaphore(max(1, settings.reranker_concurrency))

    async def query(self, qid, kb, q, topk, permission_tags=None):
        total_started = time.perf_counter()
        rewritten = [" ".join(q.strip().split())]
        route = self.router.route(q)

        retrieval_started = time.perf_counter()
        result_sets = await self.retriever.search(
            kb, rewritten[0], route["strategies"], max(topk * 3, 20), permission_tags
        )
        retrieval_ms = int((time.perf_counter() - retrieval_started) * 1000)

        fusion_weights = {"card": 1.15, "claim": 1.1, "bm25": 1.0, "vector": 1.0, "graph": 1.05}
        fusion_started = time.perf_counter()
        candidates = rrf(result_sets, k=settings.rrf_k, weights=fusion_weights, top_k=topk)
        fusion_ms = int((time.perf_counter() - fusion_started) * 1000)

        reranker = get_reranker()
        rerank_started = time.perf_counter()
        async with self.rerank_semaphore:
            candidates = await asyncio.to_thread(reranker.rerank, rewritten[0], candidates, topk)
        rerank_ms = int((time.perf_counter() - rerank_started) * 1000)

        context, citations = self._context(candidates)
        selected_context = [
            {"id": c.get("id"), "channel": c.get("channel"), "score": c.get("score"), "rerankScore": c.get("rerankScore")}
            for c in candidates[:topk]
        ]

        llm_started = time.perf_counter()
        if not candidates:
            answer = "现有知识库中未检索到足够证据，暂时无法确认。"
            model = "none"
        else:
            llm = get_llm_provider()
            answer = await llm.generate(self._prompt(q, context))
            model = llm.name
        llm_ms = int((time.perf_counter() - llm_started) * 1000)
        total_ms = int((time.perf_counter() - total_started) * 1000)

        recall_counts = {name: len(items) for name, items in result_sets.items()}
        retrieval_providers = {
            name: sorted({
                str((item.get("metadata") or {}).get("retrieval_provider") or ("neo4j" if name == "graph" else "unknown"))
                for item in items
            })
            for name, items in result_sets.items()
        }
        return {
            "queryId": qid,
            "rewrittenQueries": rewritten,
            "router": route,
            "strategies": route["strategies"],
            "recall": result_sets,
            "fusion": {"algorithm": "weighted_rrf", "k": settings.rrf_k, "weights": fusion_weights, "latencyMs": fusion_ms},
            "candidates": candidates,
            "reranker": {"name": reranker.name, "enabled": settings.reranker_enabled, "latencyMs": rerank_ms},
            "selectedContext": selected_context,
            "context": context,
            "citations": citations,
            "answer": answer,
            "model": model,
            "tokenUsage": {"promptTokens": None, "completionTokens": None, "totalTokens": None},
            "trace": {
                "retrievalLatencyMs": retrieval_ms,
                "fusionLatencyMs": fusion_ms,
                "rerankLatencyMs": rerank_ms,
                "llmLatencyMs": llm_ms,
                "totalLatencyMs": total_ms,
                "recallCounts": recall_counts,
                "retrievalProviders": retrieval_providers,
                "candidateCount": len(candidates),
                "citationCount": len(citations),
            },
        }

    def _context(self, candidates):
        blocks = []
        citations = []
        used = 0
        for i, c in enumerate(candidates, 1):
            meta = c.get("metadata", {})
            loc = ""
            if meta.get("page_no"):
                loc = "，第%s页" % meta["page_no"]
            block = "[证据%s] %s（来源：%s%s）\n%s" % (
                i,
                c.get("title", ""),
                meta.get("source_name") or "知识库",
                loc,
                c.get("content", ""),
            )
            if used + len(block) > settings.context_max_chars:
                break
            blocks.append(block)
            used += len(block)
            citations.append({
                "index": i,
                "id": c["id"],
                "title": c.get("title", ""),
                "channel": c["channel"],
                "sourceName": meta.get("source_name"),
                "sourceUri": meta.get("source_uri"),
                "pageNo": meta.get("page_no"),
                "contentUnitId": meta.get("content_unit_id"),
            })
        return "\n\n".join(blocks), citations

    def _prompt(self, q, ctx):
        return (
            "你是企业知识助手。必须遵守：\n"
            "1. 只能依据提供的知识上下文回答，不得编造。\n"
            "2. 关键结论后标注 [证据N]。\n"
            "3. 存在冲突时明确指出冲突。\n"
            "4. 证据不足时回答‘现有知识不足以确认’。\n\n"
            "用户问题：%s\n\n知识上下文：\n%s\n" % (q, ctx)
        )
