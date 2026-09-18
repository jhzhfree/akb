"""Query understanding, retrieval routing, fusion, reranking, context assembly, and answer generation.

Every tunable node reads its parameters from the runtime query policy (``app.core.query_policy``),
so operations staff can edit routing rules, recall channels, fusion weights, rerank depth,
and answer guardrails from the console without redeploying the AI service.
"""

from app.core.config import settings
from app.core.query_policy import (
    DEFAULT_SYNONYMS,
    channel_weights,
    effective_strategies,
    load_policy,
    policy_summary,
    quality_weights,
    recall_sizes,
)
import asyncio
from app.services.retrieval.hybrid import HybridRetriever, rrf
from app.services.llm_provider import get_llm_provider
from app.services.reranker import get_reranker
from app.services.answer_evidence_gate import AnswerEvidenceGate
from app.services.structured_answer_gate import StructuredAnswerGate
from app.services.answer_qualification import AnswerQualification
from datetime import datetime
import logging
import re
import time

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


class QueryRouter:
    """Map query intent to the minimum useful set of retrieval channels.

    Rules come from the runtime query policy (design V1.1 section 8.1) instead of a
    hard-coded table, so operators can add question types, change trigger keywords, tune
    priorities, or switch the recall channels of one intent without a code change. Every
    decision carries a human-readable reason so it stays auditable inside the trace.
    """

    def __init__(self, rules=None):
        """Bind an optional static rule set; ``None`` resolves the live policy per call."""
        self._rules = rules

    def route(self, q, rules=None, enabled=True, defaultStrategies=None):
        """Return the winning rule for one question, or the configured fallback."""
        entries = self._resolve(rules)
        defaults = defaultStrategies if defaultStrategies is not None else load_policy().route.defaultStrategies
        if not enabled:
            return self._fallback(defaults, "policy_disabled", "路由规则已停用，统一使用默认通道组合。")
        query = q or ""
        best = None
        for rule in entries:
            if not rule.enabled or not rule.keywords:
                continue
            matched = [keyword for keyword in rule.keywords if keyword in query]
            if not matched:
                continue
            # The longest matched keyword is the most specific one, so it wins:
            # "timeout 配置项哪个版本还在生效" must be routed to CONFLICT, not EXACT.
            candidate = (max(len(keyword) for keyword in matched), rule.priority)
            if best is None or candidate > best[0]:
                best = (candidate, {
                    "questionType": rule.questionType,
                    "strategies": list(rule.strategies),
                    "reason": rule.reason,
                    "matchedRule": "keyword",
                    "matchedKeyword": max(matched, key=len),
                })
        if best is not None:
            return best[1]
        return self._fallback(
            defaults, "default", "未命中任何路由规则，按默认通道组合处理。"
        )

    def _resolve(self, rules=None):
        """Return the rules to evaluate: explicit > bound > persisted policy."""
        if rules is not None:
            return rules
        if self._rules is not None:
            return self._rules
        return list(load_policy().route.rules)

    @staticmethod
    def _fallback(defaults, matched_rule, reason):
        """Build the fallback decision used when no rule matches."""
        return {
            "questionType": "DEFAULT",
            "strategies": list(defaults),
            "reason": reason,
            "matchedRule": matched_rule,
        }

    def available_types(self, rules=None):
        """Expose the routing rules so operators can see which intents are supported."""
        return [
            {"questionType": rule.questionType, "keywords": list(rule.keywords), "strategies": rule.strategies}
            for rule in self._resolve(rules)
        ]


class QueryRewriter:
    """Produce query rewrites used for recall expansion.

    Design V1.1 section 8 expects an explicit rewrite/expansion stage. Synonyms, the
    variant cap, and the LLM assist are policy-driven; the deterministic branch always
    produces a usable rewrite set so retrieval never depends on model availability.
    """

    SYNONYMS = DEFAULT_SYNONYMS

    async def rewrite(self, q, policy=None):
        """Return up to ``policy.maxVariants`` rewritten variants of the question."""
        rewrite_policy = policy or load_policy().rewrite
        normalized = " ".join((q or "").strip().split())
        if not normalized or not rewrite_policy.enabled:
            return [normalized] if normalized else []
        rewrites = [normalized]
        for source, targets in (rewrite_policy.synonyms or self.SYNONYMS).items():
            if source in normalized:
                for target in targets:
                    candidate = normalized.replace(source, target)
                    if candidate not in rewrites:
                        rewrites.append(candidate)
        llm_variants = await self._llm_rewrite(normalized, rewrite_policy)
        for variant in llm_variants:
            if variant and variant not in rewrites:
                rewrites.append(variant)
        return rewrites[: max(1, int(rewrite_policy.maxVariants))]

    async def _llm_rewrite(self, q, policy=None):
        rewrite_policy = policy or load_policy().rewrite
        if not rewrite_policy.llmEnabled:
            return []
        try:
            llm = get_llm_provider()
            if llm.name == "mock":
                return []
            result = await llm.extract_json(self._prompt(q))
            if not isinstance(result, dict):
                return []
            variants = result.get("rewrites") or []
            return [str(item).strip() for item in variants if str(item).strip()][:2]
        except Exception as exc:
            # Rewriting is optional; losing it must not break the query.
            logger.warning("LLM query rewrite failed: %s", exc)
            return []

    @staticmethod
    def _prompt(q):
        return (
            "请把用户问题改写为 1-2 个用于企业知识库检索的同义问法，只输出 JSON：\n"
            '{"rewrites":["",""]}\n问题：%s' % q
        )


def _clamp01(value, default):
    """Coerce a numeric signal into [0,1] and fall back when the index lacks the field."""
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return default


def quality_signals(candidate):
    """Read the evidence/authority/freshness/confidence signals carried by an indexed object.

    Missing signals fall back to neutral values so an incomplete index cannot distort ranking.
    """
    metadata = candidate.get("metadata") or {}
    return {
        "authority": _clamp01(metadata.get("authority_score"), 0.5),
        "freshness": _freshness(metadata.get("updated_at")),
        "confidence": _clamp01(metadata.get("confidence"), 0.5),
        "evidence": _clamp01(metadata.get("evidence_count"), 0.0),
    }


def _freshness(updated_at):
    """Decay freshness from the indexed update time; unknown timestamps get a neutral score."""
    if not updated_at:
        return 0.6
    try:
        if isinstance(updated_at, (int, float)):
            moment = datetime.fromtimestamp(float(updated_at))
        else:
            moment = datetime.fromisoformat(str(updated_at).replace("Z", "+00:00"))
        if moment.tzinfo is not None:
            moment = moment.replace(tzinfo=None)
        age_days = max(0.0, (datetime.utcnow() - moment).total_seconds() / 86400.0)
    except (TypeError, ValueError):
        return 0.6
    # 365-day half-life keeps long-lived enterprise knowledge from collapsing to zero.
    return round(max(0.2, 0.5 ** (age_days / 365.0)), 4)


class QueryEngine:
    """Coordinate query rewrite, multi-channel recall, fusion, reranking, context, and answer generation."""

    # Claim rows are indexed as "subject PREDICATE object"; the uppercase verb splits the triple.
    _CLAIM_TRIPLE_RE = re.compile(r"^\s*(\S.*?)\s+([A-Z][A-Z_]{2,})\s+(\S.*)$")

    def __init__(self):
        self.retriever = HybridRetriever()
        self.router = QueryRouter()
        self.rerank_semaphore = asyncio.Semaphore(max(1, settings.reranker_concurrency))
        self.rewriter = QueryRewriter()

    async def query(self, qid, kb, q, topk, permission_tags=None):
        policy = load_policy()
        total_started = time.perf_counter()
        rewrite_started = time.perf_counter()
        normalized = " ".join((q or "").strip().split())
        rewritten = await self.rewriter.rewrite(q, policy.rewrite)
        if not rewritten:
            rewritten = [normalized]
        rewrite_ms = int((time.perf_counter() - rewrite_started) * 1000)
        route = self.router.route(
            q, enabled=policy.route.enabled, defaultStrategies=policy.route.defaultStrategies
        )
        strategies = effective_strategies(policy, route["strategies"])

        retrieval_started = time.perf_counter()
        result_sets, retrieval_diagnostics, used_rewrite = await self._recall(
            kb, rewritten, strategies, topk, permission_tags, policy
        )
        retrieval_ms = int((time.perf_counter() - retrieval_started) * 1000)

        fusion_weights = channel_weights(policy)
        fusion_started = time.perf_counter()
        candidates = rrf(result_sets, k=policy.fusion.rrfK, weights=fusion_weights, top_k=topk)
        signal_weights = quality_weights(policy)
        quality_sample = []
        if signal_weights:
            for candidate in candidates:
                signals = quality_signals(candidate)
                boost = round(
                    1.0 + sum(weight * signals[key] for key, weight in signal_weights.items()), 4)
                candidate["qualityBoost"] = boost
                candidate["quality"] = signals
                candidate["score"] = float(candidate.get("score") or 0.0) * boost
            candidates.sort(key=lambda item: item.get("score") or 0.0, reverse=True)
            quality_sample = [
                {"id": item.get("id"), **item.get("quality", {}), "boost": item.get("qualityBoost")}
                for item in candidates[:5]
            ]
        fusion_ms = int((time.perf_counter() - fusion_started) * 1000)
        fused_snapshot = [dict(c) for c in candidates]
        fusion_rank = {str(c.get("id")): rank for rank, c in enumerate(fused_snapshot, 1)}
        channel_ranks = {}
        for channel, items in result_sets.items():
            for rank, item in enumerate(items, 1):
                channel_ranks.setdefault(str(item.get("id")), {})[channel] = rank

        reranker = get_reranker()
        rerank_started = time.perf_counter()
        if policy.rerank.enabled:
            depth = int(policy.rerank.topN) if int(policy.rerank.topN) > 0 else len(candidates)
            async with self.rerank_semaphore:
                head = await asyncio.to_thread(reranker.rerank, rewritten[0], candidates[:depth], depth)
            candidates = head + candidates[depth:]
        rerank_ms = int((time.perf_counter() - rerank_started) * 1000)
        for rank, candidate in enumerate(candidates, 1):
            candidate["fusionRank"] = fusion_rank.get(str(candidate.get("id")))
            candidate["rerankRank"] = rank
            candidate["rankDelta"] = (candidate.get("fusionRank") or rank) - rank
            candidate["channelRanks"] = channel_ranks.get(str(candidate.get("id")), {})

        context, citations = self._context(candidates, policy.answer.contextMaxChars)
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
            }
            for c in candidates[:topk]
        ]

        llm_started = time.perf_counter()
        if not candidates:
            answer = policy.answer.noAnswerText
            model = "none"
        else:
            llm = get_llm_provider()
            answer = await llm.generate(self._prompt(q, context))
            model = llm.name
        verification = AnswerEvidenceGate(policy.answer.faithfulnessMin).verify(
            answer, citations, candidates[:topk]
        )
        structured_verification = StructuredAnswerGate(policy.answer.entailmentMin).verify(
            answer, citations, candidates[:topk]
        )
        qualification = AnswerQualification(policy.answer.policyVersion).qualify(
            answer, verification, structured_verification,
            policy.answer.evidenceGateEnabled, model
        )
        answer = qualification.pop("deliveredAnswer")
        llm_ms = int((time.perf_counter() - llm_started) * 1000)
        total_ms = int((time.perf_counter() - total_started) * 1000)

        recall_counts = {name: len(items) for name, items in result_sets.items()}
        # llmwiki-style presentation payload: everything the answer card badges and chips need.
        supporting_claims = self._supporting_claims(candidates[:topk])
        confidence = self._confidence(candidates[:topk], citations, supporting_claims, qualification)
        question_type = route.get("questionType") or "DEFAULT"
        type_label = QUESTION_TYPE_LABELS.get(question_type, question_type)
        router_note = "%s｜%s%s" % (
            type_label, " + ".join(strategies), "｜%s" % route["reason"] if route.get("reason") else "",
        )
        retrieval_providers = {
            name: sorted({
                str((item.get("metadata") or {}).get("retrieval_provider") or ("neo4j" if name == "graph" else "unknown"))
                for item in items
            })
            for name, items in result_sets.items()
        }
        return {
            "queryId": qid,
            "traceId": qid,
            "question": q,
            "questionType": question_type,
            "questionTypeLabel": type_label,
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
            "recall": result_sets,
            "fusion": {"algorithm": "weighted_rrf", "k": policy.fusion.rrfK, "weights": fusion_weights,
                       "qualitySignals": signal_weights, "qualitySample": quality_sample,
                       "latencyMs": fusion_ms},
            "candidates": candidates,
            "reranker": {"name": reranker.name, "enabled": policy.rerank.enabled,
                         "topN": policy.rerank.topN, "latencyMs": rerank_ms},
            "selectedContext": selected_context,
            "context": context,
            "citations": citations,
            "answer": answer,
            "answerVerification": verification,
            "structuredAnswerVerification": structured_verification,
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
                "queryPolicy": policy_summary(policy),
            },
        }

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
            chips.append({
                "claimId": claim_id,
                "subject": subject,
                "predicate": predicate,
                "object": obj,
                "conditions": [],
                "confidence": metadata.get("confidence"),
                "status": str(metadata.get("status") or ""),
            })
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

    def _context(self, candidates, limit=None):
        blocks = []
        citations = []
        used = 0
        budget = int(limit) if limit else int(load_policy().answer.contextMaxChars)
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
            if used + len(block) > budget:
                break
            blocks.append(block)
            used += len(block)
            citations.append({
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
            })
        return "\n\n".join(blocks), citations

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
