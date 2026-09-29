"""Query normalization, intent routing, and rewrite policy helpers."""
import re
from app.core.query_policy import DEFAULT_SYNONYMS, load_policy
from app.services.llm_provider import get_llm_provider

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
                best = (
                    candidate,
                    {
                        "questionType": rule.questionType,
                        "strategies": list(rule.strategies),
                        "reason": rule.reason,
                        "matchedRule": "keyword",
                        "matchedKeyword": max(matched, key=len),
                    },
                )
        if best is not None:
            return best[1]
        return self._fallback(defaults, "default", "未命中任何路由规则，按默认通道组合处理。")

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
            '请把用户问题改写为 1-2 个用于企业知识库检索的同义问法，只输出 JSON：\n{"rewrites":["",""]}\n问题：%s' % q
        )


