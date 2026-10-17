"""Query understanding, retrieval routing, fusion, reranking, context assembly, and answer generation.

Every tunable node reads its parameters from the runtime query policy (``app.core.query_policy``),
so operations staff can edit routing rules, recall channels, fusion weights, rerank depth,
and answer guardrails from the console without redeploying the AI service.
"""

import asyncio
import logging
import re
import time

from app.core.config import settings
from app.core.query_policy import (
    channel_weights,
    chunk_boost_weights,
    effective_strategies,
    load_policy,
    merge_override,
    policy_summary,
    quality_weights,
    recall_sizes,
)
from app.services.answer_evidence_gate import AnswerEvidenceGate
from app.services.answer_formatting import DEFAULT as DEFAULT_ANSWER_FORMAT
from app.services.answer_formatting import excerpt as answer_excerpt
from app.services.answer_formatting import normalize as normalize_answer_format
from app.services.answer_formatting import render as render_answer
from app.services.answer_qualification import AnswerQualification
from app.services.llm_provider import get_llm_provider
from app.services.query_understanding import QueryRouter, QueryRewriter
from app.services.query_presentation import build_score_breakdown, chunk_boost, is_chunk_evidence, quality_signals
from app.services.reranker import get_reranker
from app.services.retrieval.hybrid import HybridRetriever, rrf
from app.services.structured_answer_gate import StructuredAnswerGate

logger = logging.getLogger(__name__)

# Chinese labels shown on the answer card (mirrors the llmwiki question-type badge).
QUESTION_TYPE_LABELS = {
    "EXACT": "专有名词/错误码",
    "MULTIHOP": "多跳问题",
    "CONFLICT": "冲突/版本问题",
    "RELATION": "关系问题",
    "CONDITION": "条件/规则问题",
    "PROCEDURE": "SOP/操作问题",
    "GLOBAL": "全局总结",
    "FACT": "事实/定义",
    "DEFAULT": "通用问答",
}


def _without_claims(gate_result):
    """Return a gate result without its per-claim rows, keeping every aggregate.

    Used where the claim rows would be a duplicate of another part of the response: the
    counts and the gate status are what a consumer checks first, and dropping the rows
    leaves those untouched. Non-dict results (the "NOT_RUN" placeholders) pass through.
    """
    if not isinstance(gate_result, dict):
        return gate_result
    return {key: value for key, value in gate_result.items() if key != "claims"}


class QueryEngine:
    """Coordinate query rewrite, multi-channel recall, fusion, reranking, context, and answer generation."""

    # Claim rows are indexed as "subject PREDICATE object"; the uppercase verb splits the triple.
    _CLAIM_TRIPLE_RE = re.compile(r"^\s*(\S.*?)\s+([A-Z][A-Z_]{2,})\s+(\S.*)$")

    def __init__(self):
        self.retriever = HybridRetriever()
        self.router = QueryRouter()
        self.rerank_semaphore = asyncio.Semaphore(max(1, settings.reranker_concurrency))
        self.rewriter = QueryRewriter()

    async def query(self, qid, kb, q, topk, permission_tags=None, generate=True,
                    answer_format=DEFAULT_ANSWER_FORMAT, policy_override=None, progress=None,
                    min_score=None, include_recall_content=False):
        """Run the pipeline: recall → fusion → rerank, then generation unless `generate` is off.

        `generate=False` gives the retrieval-only shape the evaluation center needs — the same
        candidates and the same trace timeline, without an LLM call whose latency would otherwise
        be counted as retrieval latency.

        `policy_override` carries the knowledge base's own scenario settings (merged in
        :func:`app.core.query_policy.merge_override`): a library created as 漏洞库 asks with the
        channels and thresholds that suit CVE questions without changing what any other library
        sees.

        `progress`, when given, is awaited after every stage with a stage event and, during
        generation, with each piece of answer text. That is what turns a request that used to
        return one blob at the end into a stream a client can render as it happens; without it
        this method is byte-for-byte the old synchronous path, so nothing that already works
        has to change.
        """
        policy = load_policy()
        if policy_override:
            policy = merge_override(policy, policy_override)
        total_started = time.perf_counter()
        rewrite_started = time.perf_counter()
        normalized = " ".join((q or "").strip().split())
        rewritten = await self.rewriter.rewrite(q, policy.rewrite)
        if not rewritten:
            rewritten = [normalized]
        rewrite_ms = int((time.perf_counter() - rewrite_started) * 1000)
        if progress is not None:
            await progress({
                "type": "stage",
                "stage": {
                    "key": "rewrite",
                    "label": "查询改写",
                    "status": "done",
                    "latencyMs": rewrite_ms,
                    "inputCount": 1,
                    "outputCount": len(rewritten),
                    "detail": {"variants": rewritten, "enabled": bool(getattr(policy.rewrite, "enabled", True))},
                },
            })
        route = self.router.route(q, enabled=policy.route.enabled, defaultStrategies=policy.route.defaultStrategies)
        strategies = list(effective_strategies(policy, route["strategies"]))
        # The library's declared channels are a floor, not a suggestion: the router may add
        # channels it thinks the question needs, but it must not drop one the library relies on.
        # An operations library asking "CHG-2024-0117 影响了哪些服务" is routed to the graph, and
        # without BM25 that question returns nothing — the identifier is only in the text.
        if policy_override:
            for name in getattr(policy.route, "defaultStrategies", None) or []:
                if name not in strategies:
                    strategies.append(name)

        if progress is not None:
            await progress({
                "type": "stage",
                "stage": {
                    "key": "route",
                    "label": "意图路由",
                    "status": "done",
                    "latencyMs": None,
                    "inputCount": 1,
                    "outputCount": len(strategies),
                    "detail": {
                        "questionType": route.get("questionType") or "DEFAULT",
                        "strategies": strategies,
                        "reason": route.get("reason"),
                        "enabled": bool(getattr(policy.route, "enabled", False)),
                    },
                },
            })
        retrieval_started = time.perf_counter()
        result_sets, retrieval_diagnostics, used_rewrite = await self._recall(
            kb, rewritten, strategies, topk, permission_tags, policy
        )
        retrieval_ms = int((time.perf_counter() - retrieval_started) * 1000)
        if progress is not None:
            recall_counts = {name: len(items) for name, items in result_sets.items()}
            await progress({
                "type": "stage",
                "stage": {
                    "key": "recall",
                    "label": "多通道召回",
                    "status": "done",
                    "latencyMs": retrieval_ms,
                    "inputCount": 1,
                    "outputCount": sum(recall_counts.values()),
                    "detail": {
                        "sizes": recall_sizes(policy, topk),
                        "counts": recall_counts,
                        "channelLatencyMs": retrieval_diagnostics.get("channelLatencyMs", {}),
                    },
                },
            })
            await progress({
                "type": "stage",
                "stage": {
                    "key": "acl_filter",
                    "label": "ACL 与状态过滤",
                    "status": "done",
                    "latencyMs": None,
                    "inputCount": None,
                    "outputCount": sum(recall_counts.values()),
                    "detail": retrieval_diagnostics.get("filters", {}),
                },
            })

        fusion_weights = channel_weights(policy)
        fusion_started = time.perf_counter()
        candidates = rrf(result_sets, k=policy.fusion.rrfK, weights=fusion_weights, top_k=topk)
        candidates = self._chunk_candidate_floor(candidates, result_sets, policy)
        signal_weights = quality_weights(policy)
        chunk_weights = chunk_boost_weights(policy)
        quality_sample = []
        boosted = bool(signal_weights or chunk_weights)
        if signal_weights:
            for candidate in candidates:
                signals = quality_signals(candidate)
                boost = round(1.0 + sum(weight * signals[key] for key, weight in signal_weights.items()), 4)
                candidate["qualityBoost"] = boost
                candidate["quality"] = signals
                candidate["score"] = float(candidate.get("score") or 0.0) * boost
        if chunk_weights:
            for candidate in candidates:
                boost, signals = chunk_boost(candidate, rewritten, chunk_weights)
                if signals is None:
                    continue
                candidate["chunkBoost"] = boost
                candidate["chunkSignals"] = signals
                candidate["score"] = float(candidate.get("score") or 0.0) * boost
        if boosted:
            candidates.sort(key=lambda item: item.get("score") or 0.0, reverse=True)
            quality_sample = [
                {"id": item.get("id"), **item.get("quality", {}), "boost": item.get("qualityBoost")}
                for item in candidates[:5]
            ]
        fusion_ms = int((time.perf_counter() - fusion_started) * 1000)
        if progress is not None:
            await progress({
                "type": "stage",
                "stage": {
                    "key": "fusion",
                    "label": "RRF 融合",
                    "status": "done",
                    "latencyMs": fusion_ms,
                    "inputCount": sum(len(items) for items in result_sets.values()),
                    "outputCount": len(candidates),
                    "detail": {
                        "algorithm": "weighted_rrf",
                        "k": policy.fusion.rrfK,
                        "weights": fusion_weights,
                        "qualitySignals": signal_weights,
                    },
                },
            })
        fused_snapshot = [dict(c) for c in candidates]
        fusion_rank = {str(c.get("id")): rank for rank, c in enumerate(fused_snapshot, 1)}
        channel_ranks = {}
        for channel, items in result_sets.items():
            for rank, item in enumerate(items, 1):
                channel_ranks.setdefault(str(item.get("id")), {})[channel] = rank

        reranker = get_reranker()
        rerank_started = time.perf_counter()
        rerank_errors = []
        rerank_input_count = 0
        rerank_output_count = 0
        rerank_depth = 0
        if policy.rerank.enabled:
            rerank_depth = int(policy.rerank.topN) if int(policy.rerank.topN) > 0 else len(candidates)
            rerank_input_count = min(rerank_depth, len(candidates))
            try:
                async with self.rerank_semaphore:
                    head = await asyncio.to_thread(
                        reranker.rerank, rewritten[0], candidates[:rerank_depth], rerank_depth
                    )
                candidates = head + candidates[rerank_depth:]
                rerank_output_count = len(head)
            except Exception as exc:
                # Reranking is an accelerator, not a gate: fall back to the fused order and
                # surface the failure on the trace instead of failing the whole query (§16.3).
                logger.warning("rerank failed; keeping fused order: %s", exc)
                rerank_errors.append(str(exc))
                rerank_output_count = len(candidates)
        rerank_ms = int((time.perf_counter() - rerank_started) * 1000)
        for rank, candidate in enumerate(candidates, 1):
            candidate["fusionRank"] = fusion_rank.get(str(candidate.get("id")))
            candidate["rerankRank"] = rank
            candidate["rankDelta"] = (candidate.get("fusionRank") or rank) - rank
            candidate["channelRanks"] = channel_ranks.get(str(candidate.get("id")), {})
            candidate["scoreBreakdown"] = build_score_breakdown(
                candidate, fusion_weights, policy.fusion.rrfK, rerank_depth, topk
            )

        if progress is not None:
            await progress({
                "type": "stage",
                "stage": {
                    "key": "rerank",
                    "label": "重排",
                    "status": "done",
                    "latencyMs": rerank_ms,
                    "inputCount": rerank_input_count,
                    "outputCount": rerank_output_count,
                    "detail": {
                        "reranker": reranker.name,
                        "enabled": bool(policy.rerank.enabled),
                        "topN": policy.rerank.topN,
                    },
                    "errors": rerank_errors,
                },
            })
        # The score floor is applied after reranking, not before: a candidate that only looks
        # weak before reranking may be exactly the one the reranker promotes, and dropping it
        # early would change the ordering for every caller, including those that set no floor.
        # Filtering here also keeps `recall` honest — it reports what was actually found.
        if min_score is not None:
            candidates = [
                c for c in candidates if float(c.get("score") or 0.0) >= float(min_score)
            ]

        # Retrieval-only callers (evaluation runs, the retrieval test page) must not pay for
        # generation: measuring recall latency with an LLM call inside it measures the wrong thing.
        if generate:
            context, citations, evidence_pool = self._context(candidates, policy.answer.contextMaxChars)
            # 字符预算可能在第一条就超限，那时 _context 一个片段都装不进去，evidence_pool 是空的。
            # 空证据集会让门控把每条 claim 都判成"无证据支持"，答案被整段拦掉——表现出来就是
            # "库里有这条知识却检索不出来"。这时退回 topK 切片：至少保住调用方要的那几条，
            # 不会比改动前（固定用 candidates[:topk]）更差。
            if not evidence_pool and candidates:
                evidence_pool = candidates[:topk]
        else:
            context, citations = "", []
            evidence_pool = candidates[:topk]
        selected_context = [
            {
                "id": c.get("id"),
                "title": c.get("title"),
                "channel": c.get("channel"),
                "score": c.get("score"),
                "rerankScore": c.get("rerankScore"),
                "objectType": (c.get("metadata") or {}).get("object_type"),
                "contentUnitId": (c.get("metadata") or {}).get("content_unit_id"),
                "sourceName": (c.get("metadata") or {}).get("source_name"),
                "pageNo": (c.get("metadata") or {}).get("page_no"),
                "fusionRank": c.get("fusionRank"),
                "rerankRank": c.get("rerankRank"),
                "rankDelta": c.get("rankDelta"),
                "channelRanks": c.get("channelRanks"),
                "scoreBreakdown": c.get("scoreBreakdown"),
            }
            for c in evidence_pool
        ]

        if progress is not None:
            await progress({
                "type": "stage",
                "stage": {
                    "key": "context",
                    "label": "上下文装配",
                    "status": "done",
                    "latencyMs": None,
                    "inputCount": len(candidates),
                    "outputCount": len(selected_context),
                    "detail": {"chars": len(context), "maxChars": policy.answer.contextMaxChars},
                },
            })
        llm_started = time.perf_counter()
        if not generate:
            # Gates stay in the payload as explicit "not run" rather than absent keys, so a
            # consumer can tell "skipped" apart from "passed" without guessing.
            answer = ""
            model = "none"
            verification = {"gateStatus": "NOT_RUN", "reason": "retrieval-only"}
            structured_verification = {"gateStatus": "NOT_RUN", "reason": "retrieval-only"}
            qualification = {
                "gateStatus": "NOT_RUN",
                "policyVersion": policy.answer.policyVersion,
                "claims": [],
                "deliveredAnswer": "",
            }
            llm_ms = 0
        elif not candidates:
            answer = policy.answer.noAnswerText
            model = "none"
            verification = {"gateStatus": "NO_EVIDENCE", "reason": "no candidate"}
            structured_verification = {"gateStatus": "NO_EVIDENCE", "reason": "no candidate"}
            qualification = {
                "gateStatus": "NO_EVIDENCE",
                "policyVersion": policy.answer.policyVersion,
                "claims": [],
                "deliveredAnswer": answer,
            }
            llm_ms = int((time.perf_counter() - llm_started) * 1000)
        else:
            llm = get_llm_provider()
            if progress is None:
                answer = await llm.generate(self._prompt(q, context))
            else:
                answer = await self._generate_streaming(llm, self._prompt(q, context), progress)
            model = llm.name
            verification = AnswerEvidenceGate(policy.answer.faithfulnessMin).verify(
                answer, citations, evidence_pool
            )
            structured_verification = StructuredAnswerGate(policy.answer.entailmentMin).verify(
                answer, citations, evidence_pool
            )
            qualification = AnswerQualification(policy.answer.policyVersion).qualify(
                answer, verification, structured_verification, policy.answer.evidenceGateEnabled, model
            )
            answer = qualification.pop("deliveredAnswer")
            llm_ms = int((time.perf_counter() - llm_started) * 1000)
        if progress is not None:
            await progress({
                "type": "stage",
                "stage": {
                    "key": "llm",
                    "label": "答案生成",
                    "status": "done",
                    "latencyMs": llm_ms,
                    "inputCount": len(selected_context),
                    "outputCount": 1 if candidates else 0,
                    "detail": {"model": model},
                },
            })
            await progress({
                "type": "stage",
                "stage": {
                    "key": "citation",
                    "label": "引用校验",
                    "status": "done",
                    "latencyMs": None,
                    "inputCount": len(evidence_pool),
                    "outputCount": len(citations),
                    "detail": {
                        "evidenceGate": verification.get("gateStatus"),
                        "structuredGate": structured_verification.get("gateStatus"),
                        "finalGate": qualification.get("gateStatus"),
                        "policyVersion": qualification.get("policyVersion"),
                    },
                },
            })
        total_ms = int((time.perf_counter() - total_started) * 1000)

        recall_counts = {name: len(items) for name, items in result_sets.items()}
        # llmwiki-style presentation payload: everything the answer card badges and chips need.
        supporting_claims = self._supporting_claims(evidence_pool)
        confidence = self._confidence(evidence_pool, citations, supporting_claims, qualification)
        question_type = route.get("questionType") or "DEFAULT"
        type_label = QUESTION_TYPE_LABELS.get(question_type, question_type)
        router_note = "%s｜%s%s" % (
            type_label,
            " + ".join(strategies),
            "｜%s" % route["reason"] if route.get("reason") else "",
        )
        retrieval_providers = {
            name: sorted(
                {
                    str(
                        (item.get("metadata") or {}).get("retrieval_provider")
                        or ("neo4j" if name == "graph" else "unknown")
                    )
                    for item in items
                }
            )
            for name, items in result_sets.items()
        }
        # Design §12.5: the trace is a timeline, not a bag of latencies. Every stage
        # reports what went in, what came out, how long it took, and what failed, so the
        # console can answer "why did this answer look like that" without a log dive.
        channel_latency = retrieval_diagnostics.get("channelLatencyMs", {}) or {}
        recall_errors = retrieval_diagnostics.get("errors", {}) or {}
        stages = [
            {
                "key": "rewrite",
                "label": "查询改写",
                "latencyMs": rewrite_ms,
                "inputCount": 1,
                "outputCount": len(rewritten),
                "detail": {
                    "variants": rewritten,
                    "used": used_rewrite or normalized,
                    "enabled": bool(getattr(policy.rewrite, "enabled", True)),
                },
                "errors": [],
            },
            {
                "key": "route",
                "label": "意图路由",
                "latencyMs": None,
                "inputCount": 1,
                "outputCount": len(strategies),
                "detail": {
                    "questionType": question_type,
                    "strategies": strategies,
                    "reason": route.get("reason"),
                    "enabled": bool(getattr(policy.route, "enabled", False)),
                },
                "errors": [],
            },
            {
                "key": "recall",
                "label": "多通道召回",
                "latencyMs": retrieval_ms,
                "inputCount": 1,
                "outputCount": sum(recall_counts.values()),
                "detail": {
                    "sizes": recall_sizes(policy, topk),
                    "providers": retrieval_providers,
                    "channels": [
                        {
                            "channel": name,
                            "count": count,
                            "latencyMs": channel_latency.get(name),
                            "provider": (retrieval_providers.get(name) or ["local"])[0],
                        }
                        for name, count in recall_counts.items()
                    ],
                },
                "errors": recall_errors,
            },
            {
                "key": "acl_filter",
                "label": "ACL 与状态过滤",
                "latencyMs": None,
                "inputCount": None,
                "outputCount": sum(recall_counts.values()),
                "detail": retrieval_diagnostics.get("filters", {}),
                "errors": [],
            },
            {
                "key": "fusion",
                "label": "RRF 融合",
                "latencyMs": fusion_ms,
                "inputCount": sum(recall_counts.values()),
                "outputCount": len(fused_snapshot),
                "detail": {
                    "algorithm": "weighted_rrf",
                    "k": policy.fusion.rrfK,
                    "weights": fusion_weights,
                    "qualitySignals": signal_weights,
                },
                "errors": [],
            },
            {
                "key": "rerank",
                "label": "重排",
                "latencyMs": rerank_ms,
                "inputCount": rerank_input_count,
                "outputCount": rerank_output_count,
                "detail": {
                    "reranker": reranker.name,
                    "enabled": bool(policy.rerank.enabled),
                    "topN": policy.rerank.topN,
                },
                "errors": rerank_errors,
            },
            {
                "key": "context",
                "label": "上下文装配",
                "latencyMs": None,
                "inputCount": len(candidates),
                "outputCount": len(selected_context),
                "detail": {"chars": len(context), "maxChars": policy.answer.contextMaxChars},
                "errors": [],
            },
            {
                "key": "llm",
                "label": "答案生成",
                "latencyMs": llm_ms,
                "inputCount": len(selected_context),
                "outputCount": 1 if candidates else 0,
                "detail": {"model": model},
                "errors": [],
            },
            {
                "key": "citation",
                "label": "引用校验",
                "latencyMs": None,
                "inputCount": len(evidence_pool),
                "outputCount": len(citations),
                "detail": {
                    "evidenceGate": verification.get("gateStatus"),
                    "structuredGate": structured_verification.get("gateStatus"),
                    "finalGate": qualification.get("gateStatus"),
                    "policyVersion": qualification.get("policyVersion"),
                },
                "errors": [],
            },
        ]

        conclusion_source = self._conclusion_source(candidates, citations)

        return {
            "queryId": qid,
            "traceId": qid,
            "question": q,
            "questionType": question_type,
            "questionTypeLabel": type_label,
            "conclusionSource": conclusion_source,
            "rewrittenQuery": used_rewrite or normalized,
            "routerNote": router_note,
            "channels": strategies,
            "contextChars": len(context),
            "durationMs": total_ms,
            "reranked": bool(policy.rerank.enabled and candidates),
            "confidence": confidence,
            "claims": supporting_claims,
            "rewrittenQueries": rewritten,
            "rewrite": {"variants": rewritten, "used": used_rewrite, "latencyMs": rewrite_ms},
            "router": {**route, "configuredStrategies": route["strategies"]},
            "strategies": strategies,
            "recall": self._slim_recall(result_sets, include_recall_content),
            "fusion": {
                "algorithm": "weighted_rrf",
                "k": policy.fusion.rrfK,
                "weights": fusion_weights,
                "qualitySignals": signal_weights,
                "qualitySample": quality_sample,
                "latencyMs": fusion_ms,
            },
            "candidates": candidates,
            # 检索接口要能回答“给了多少、够格的有多少”：topK 是请求要的数量，
            # returned 是过滤后真的返回的数量，两者不一致时调用方才知道是阈值卡掉了结果，
            # 而不是库里没有。
            "topK": topk,
            "returned": len(candidates),
            "minScore": min_score,
            "reranker": {
                "name": reranker.name,
                "enabled": policy.rerank.enabled,
                "topN": policy.rerank.topN,
                "latencyMs": rerank_ms,
            },
            "selectedContext": selected_context,
            "context": context,
            "citations": citations,
            # 格式在所有门控之后才渲染：门控评判的是模型产出的原文，
            # 先拼上引用章节会让章节里的字被当成答案自己说的话。
            "answer": render_answer(answer, citations, answer_format),
            "answerFormat": normalize_answer_format(answer_format),
            "answerVerification": verification,
            # structured 的 claim 明细与 answerQualification.claims 是同一份：后者由前者的副本
            # 加 policyVersion / includedInFinalAnswer 构成（见 answer_qualification.qualify），
            # 是它的超集。两处都发等于把每条判定结果发两遍。这里只留统计口径，明细统一读
            # answerQualification.claims——那也是落库用的那份，口径不会分叉。
            "structuredAnswerVerification": _without_claims(structured_verification),
            "answerQualification": qualification,
            "model": model,
            "tokenUsage": {"promptTokens": None, "completionTokens": None, "totalTokens": None},
            "trace": {
                "rewriteLatencyMs": rewrite_ms,
                "questionType": route.get("questionType"),
                "retrievalLatencyMs": retrieval_ms,
                "fusionLatencyMs": fusion_ms,
                "rerankLatencyMs": rerank_ms,
                "llmLatencyMs": llm_ms,
                "totalLatencyMs": total_ms,
                "recallCounts": recall_counts,
                "retrievalProviders": retrieval_providers,
                "channelLatencyMs": retrieval_diagnostics.get("channelLatencyMs", {}),
                "filters": retrieval_diagnostics.get("filters", {}),
                "candidateCount": len(candidates),
                "citationCount": len(citations),
                "answerEvidenceGate": verification.get("gateStatus"),
                "citationCompleteness": verification.get("completenessScore"),
                "citationFaithfulness": verification.get("faithfulnessScore"),
                "structuredAnswerGate": structured_verification.get("gateStatus"),
                "claimEntailmentSupported": structured_verification.get("supportedCount"),
                "claimEntailmentContradicted": structured_verification.get("contradictedCount"),
                "finalAnswerGate": qualification.get("gateStatus"),
                "answerPolicyVersion": qualification.get("policyVersion"),
                "generationIds": retrieval_diagnostics.get("generationIds", []),
                "stages": stages,
                "queryPolicy": policy_summary(policy),
            },
        }

    async def _generate_streaming(self, llm, prompt, progress):
        """Generate the answer while forwarding every piece through `progress`.

        The concatenation of the pieces is returned so the gates downstream judge exactly the
        text the reader saw. If a provider cannot stream at all, the failure happens before the
        first chunk and a blocking call recovers it; once chunks are already on the wire there
        is nothing to recover — re-sending would duplicate what the reader is looking at.
        """
        pieces = []
        try:
            async for piece in llm.stream_generate(prompt):
                if not piece:
                    continue
                pieces.append(piece)
                await progress({"type": "delta", "text": piece})
        except Exception as exc:
            if pieces:
                logger.warning("llm stream interrupted after %d chunks: %s", len(pieces), exc)
            else:
                logger.warning("llm streaming unavailable; falling back to blocking generate: %s", exc)
                text = await llm.generate(prompt)
                if text:
                    pieces.append(text)
                    await progress({"type": "delta", "text": text})
        return "".join(pieces)

    async def _recall(self, kb, rewrites, strategies, topk, permission_tags, policy):
        """Recall over rewrite variants until one returns enough candidates (design §8.2).

        Only the first variant is used in the common case; the remaining ones act as a
        bounded retry whose depth and per-channel budget come from the query policy.
        """
        result_sets = {}
        diagnostics = {}
        used = None
        sizes = recall_sizes(policy, topk)
        rounds = max(1, int(policy.rewrite.maxRecallRounds))
        for rewrite in rewrites[:rounds]:
            result_sets, diagnostics = await self.retriever.search_with_diagnostics(
                kb, rewrite, strategies, sizes, permission_tags
            )
            used = rewrite
            if sum(len(items) for items in result_sets.values()) >= topk:
                break
        return result_sets, diagnostics, used

    @classmethod
    def _supporting_claims(cls, candidates):
        """Project recalled adjudicated claims into the chip list shown on the answer card."""
        chips = []
        seen = set()
        for candidate in candidates:
            metadata = candidate.get("metadata") or {}
            if str(metadata.get("object_type") or "").upper() != "CLAIM":
                continue
            claim_id = str(candidate.get("id") or "")
            if not claim_id or claim_id in seen:
                continue
            seen.add(claim_id)
            subject, predicate, obj = cls._claim_triple(candidate.get("content") or "")
            chips.append(
                {
                    "claimId": claim_id,
                    "subject": subject,
                    "predicate": predicate,
                    "object": obj,
                    "conditions": [],
                    "confidence": metadata.get("confidence"),
                    "status": str(metadata.get("status") or ""),
                }
            )
            if len(chips) >= 8:
                break
        return chips

    @classmethod
    def _claim_triple(cls, content):
        """Split an indexed claim row ("subject PREDICATE object") back into its triple."""
        match = cls._CLAIM_TRIPLE_RE.match(content or "")
        if not match:
            return (content or "").strip(), "", ""
        return match.group(1).strip(), match.group(2), match.group(3).strip()

    @staticmethod
    def _confidence(candidates, citations, claims, qualification):
        """Answer confidence: rerank strength first, claim support and citation coverage add."""
        scores = [float(item.get("rerankScore") or 0.0) for item in candidates if item.get("rerankScore")]
        if scores:
            top = sorted(scores, reverse=True)[:3]
            base = sum(top) / len(top)
        else:
            base = min(0.6, 0.25 + 0.05 * len(citations))
        if any(str(item.get("status") or "").upper() == "EFFECTIVE" for item in claims):
            base += 0.1
        if str(qualification.get("gateStatus") or "").upper() == "BLOCKED":
            base *= 0.5
        return round(max(0.0, min(base, 0.95)), 3)

    @staticmethod
    def _conclusion_source(candidates, citations):
        """State which kind of conclusion the answer is, from the evidence that supported it.

        Design §9.4 asks an answer to say whether it was retrieved, traversed or derived. Once a
        paragraph is generated that distinction is invisible, so it is computed from the evidence
        actually cited: knowledge published from a derivation candidate is a derived conclusion, a
        cited graph path is a traversed one, and everything else is sourced retrieval.
        """
        used = {str(item.get("id")) for item in citations}
        paths = []
        kinds = []
        for candidate in candidates:
            if str(candidate.get("id")) not in used:
                continue
            metadata = candidate.get("metadata") or {}
            path = metadata.get("graph_path")
            if path:
                paths.append(path)
            if (
                metadata.get("derived")
                or metadata.get("derivedFrom")
                or str(metadata.get("object_type") or "") == "DERIVED"
            ):
                kinds.append("DERIVED_KNOWLEDGE")
            elif path:
                kinds.append("GRAPH_TRAVERSAL")
            else:
                kinds.append("RETRIEVED_KNOWLEDGE")
        labels = {
            "DERIVED_KNOWLEDGE": "系统推导（Derived Knowledge）",
            "GRAPH_TRAVERSAL": "图谱遍历推导",
            "RETRIEVED_KNOWLEDGE": "证据检索",
        }
        kind = "RETRIEVED_KNOWLEDGE"
        for preferred in ("DERIVED_KNOWLEDGE", "GRAPH_TRAVERSAL"):
            if preferred in kinds:
                kind = preferred
                break
        return {
            "type": kind,
            "label": labels[kind],
            "graphPaths": paths[:5],
            "evidenceKinds": sorted(set(kinds)),
        }

    @staticmethod
    def _slim_recall(result_sets, include_content=False):
        """Drop recalled full text unless the caller asked for it.

        Recall is by far the largest thing in the response: every channel returns its own
        candidate list, and every candidate carries several hundred characters of verbatim
        chunk text. Twenty of those is five figures' worth of characters that then get
        serialised into the trace JSON column, logged, and shipped to external callers —
        while every consumer on record (the retrieval test page, the trace panel, the
        external search endpoint) only ever reads id / title / channel / score / metadata.

        The full text is not deleted so much as demoted to a length: `contentChars` keeps
        "was this chunk long enough to matter" answerable without paying for the text.
        """
        slimmed = {}
        for channel, items in (result_sets or {}).items():
            if not isinstance(items, list):
                slimmed[channel] = items
                continue
            rows = []
            for item in items:
                if not isinstance(item, dict):
                    rows.append(item)
                    continue
                text = item.get("content") or ""
                row = {key: value for key, value in item.items() if key != "content"}
                if include_content:
                    row["content"] = text
                else:
                    row["contentChars"] = len(text)
                rows.append(row)
            slimmed[channel] = rows
        return slimmed

    @staticmethod
    def _chunk_candidate_floor(candidates, result_sets, policy):
        """Give verbatim chunks a seat among the fused candidates before ranking continues.

        Boosting chunks only helps chunks that were recalled. Cards and graph paths routinely
        take every fused slot, so the boost would apply to nothing; this pulls the best
        chunk-shaped candidates that lost the RRF cut back into the list.
        """
        floor = int(getattr(policy.answer, "minChunkEvidence", 0) or 0)
        if floor <= 0:
            return candidates
        have = sum(1 for candidate in candidates if is_chunk_evidence(candidate))
        if have >= floor:
            return candidates
        seen = {str(candidate.get("id")) for candidate in candidates}
        extra = []
        for items in result_sets.values():
            for item in items:
                if have + len(extra) >= floor:
                    break
                if str(item.get("id")) in seen or not is_chunk_evidence(item):
                    continue
                seen.add(str(item.get("id")))
                extra.append(dict(item))
            if have + len(extra) >= floor:
                break
        return candidates + extra

    @staticmethod
    def _chunk_floor(candidates, policy):
        """Move verbatim chunks to the front so the prompt never loses its quotable evidence.

        Cards and graph paths can outrank chunks on score, yet only a chunk carries the
        evidence code and hash an answer needs to cite; without a floor the model ends up
        paraphrasing summaries and the evidence gate blocks every sentence.
        """
        floor = int(getattr(policy.answer, "minChunkEvidence", 0) or 0)
        if floor <= 0:
            return list(candidates)
        chunks = [c for c in candidates if is_chunk_evidence(c)][:floor]
        if len(chunks) < floor or not chunks:
            return list(candidates)
        head_ids = {id(item) for item in chunks}
        return chunks + [c for c in candidates if id(c) not in head_ids]

    def _context(self, candidates, limit=None):
        blocks = []
        citations = []
        # 实际被装进上下文的候选。字符预算可能在中间截断，所以它不是 len(ordered)——
        # 返回它是为了让 selectedContext / 门控拿到"答案真的看见了哪些证据"，而不是靠
        # 调用方自己猜一个切片。
        selected = []
        used = 0
        policy = load_policy()
        budget = int(limit) if limit else int(policy.answer.contextMaxChars)
        ordered = self._chunk_floor(candidates, policy)
        for i, c in enumerate(ordered, 1):
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
            if used + len(block) > budget:
                break
            blocks.append(block)
            used += len(block)
            # 与 citations 同源同序：进不了上下文的候选（字符预算截断）不算证据，
            # 否则 selectedContext / 门控会声称答案"看见"了它其实没看到的片段。
            selected.append(c)
            citations.append(
                {
                    "index": i,
                    "id": c["id"],
                    "title": c.get("title", ""),
                    "channel": c["channel"],
                    "source": c["channel"],
                    "score": c.get("score"),
                    "sourceName": meta.get("source_name"),
                    "sourceUri": meta.get("source_uri"),
                    "pageNo": meta.get("page_no"),
                    "contentUnitId": meta.get("content_unit_id"),
                    "objectId": c.get("id"),
                    "objectType": meta.get("object_type"),
                    "evidenceCode": meta.get("evidence_code"),
                    "evidenceHash": meta.get("evidence_hash") or meta.get("content_hash"),
                    "generationId": meta.get("generation_id"),
                    "sourceVersion": meta.get("source_version"),
                    # 引用原文：HTML / Markdown 两种格式要把被引用的那句话带在答案里，
                    # 否则读者拿到一个 [证据N] 却无处可查。
                    "excerpt": answer_excerpt(c.get("content", "")),
                }
            )
        return "\n\n".join(blocks), citations, selected

    def _prompt(self, q, ctx):
        return (
            "你是企业知识助手，基于提供的知识上下文回答用户问题。必须遵守：\n"
            "1. 只能依据上下文中的知识回答，不得编造。\n"
            "2. 每个事实论断所在句子的句末都必须标注 [证据N]（可同时标注多个，如 [证据1][证据2]）。\n"
            "3. 用 Markdown 组织答案：可用「## 小标题」分节、用「- 列表」和「**加粗**」突出要点，"
            "让结论一目了然；小标题行无需标注证据，列表项中的事实论断仍须标注 [证据N]。\n"
            "4. 上下文中标题以「断言：」开头的证据是已裁决断言，引用其结论时同样标注对应 [证据N]。\n"
            "5. 上下文不足以回答时，只输出以「现有知识不足以」开头的说明，"
            "指出缺少什么、建议摄入什么资料。\n"
            "6. 不要输出与问题无关的元叙述，不要描述检索或推理过程。\n\n"
            "用户问题：%s\n\n知识上下文：\n%s\n" % (q, ctx)
        )
