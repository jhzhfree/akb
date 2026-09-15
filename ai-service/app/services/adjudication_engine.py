"""Explainable knowledge-adjudication scoring with optional LLM assistance.

Deterministic scoring and governance policy own the decision boundaries. The LLM
judge is advisory: it can add semantic evidence but must not bypass conflict,
evidence, or high-risk human-review rules.
"""

import logging
from datetime import datetime, timezone
from typing import Any, Dict

from app.services.llm_provider import get_llm_provider

logger = logging.getLogger(__name__)


class AdjudicationEngine:
    """Score a candidate and decide whether automatic approval is safe."""

    WEIGHTS = {
        "evidence_support": 0.28,
        "source_authority": 0.22,
        "freshness": 0.14,
        "claim_confidence": 0.20,
        "cross_source": 0.10,
        "completeness": 0.06,
    }

    @staticmethod
    def _clamp(value: Any, default: float = 0.0) -> float:
        try:
            return max(0.0, min(1.0, float(value)))
        except (TypeError, ValueError):
            return default

    def score(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Calculate the deterministic, explainable confidence/risk components."""
        evidence_count = int(payload.get("evidenceCount", 0) or 0)
        source_count = int(payload.get("sourceCount", 0) or 0)
        conflict_count = int(payload.get("conflictCount", 0) or 0)
        conflict_score = self._clamp(payload.get("maxConflictScore", 0))
        confidence = self._clamp(payload.get("confidence", 0.5), 0.5)
        authority = self._clamp(payload.get("authorityScore", 0.5), 0.5)
        freshness = self._clamp(payload.get("freshnessScore", 0.8), 0.8)
        evidence_support = self._clamp(
            payload.get("evidenceSupportScore", min(1.0, evidence_count / 2.0))
        )
        cross_source = min(1.0, source_count / 2.0)
        completeness = self._clamp(
            payload.get(
                "completenessScore",
                1.0 if payload.get("objectContent") else 0.5,
            )
        )

        positive = (
            evidence_support * self.WEIGHTS["evidence_support"]
            + authority * self.WEIGHTS["source_authority"]
            + freshness * self.WEIGHTS["freshness"]
            + confidence * self.WEIGHTS["claim_confidence"]
            + cross_source * self.WEIGHTS["cross_source"]
            + completeness * self.WEIGHTS["completeness"]
        )
        conflict_penalty = min(
            0.45,
            conflict_score * 0.35 + (0.08 if conflict_count > 1 else 0.0),
        )
        no_evidence_penalty = 0.35 if evidence_count == 0 else 0.0
        score = max(
            0.0,
            min(1.0, positive - conflict_penalty - no_evidence_penalty),
        )
        if conflict_count > 0 or authority < 0.45:
            risk = "HIGH"
        elif score < 0.82:
            risk = "MEDIUM"
        else:
            risk = "LOW"

        return {
            "knowledgeConfidence": round(score, 4),
            "riskLevel": risk,
            "components": {
                "evidenceSupport": round(evidence_support, 4),
                "sourceAuthority": round(authority, 4),
                "freshness": round(freshness, 4),
                "claimConfidence": round(confidence, 4),
                "crossSourceSupport": round(cross_source, 4),
                "completeness": round(completeness, 4),
                "conflictPenalty": round(conflict_penalty, 4),
                "noEvidencePenalty": round(no_evidence_penalty, 4),
            },
            "weights": self.WEIGHTS,
        }

    async def judge(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Combine deterministic policy with optional semantic LLM judgment."""
        base = self.score(payload)
        llm = get_llm_provider()
        ai_judge: Dict[str, Any] = {
            "decision": "NOT_RUN",
            "confidence": 0.0,
            "reason": "未启用真实大模型裁决",
        }

        if llm.name != "mock":
            try:
                result = await llm.extract_json(self._judge_prompt(payload))
                if isinstance(result, dict):
                    ai_judge = result
            except Exception as exc:
                # AI judge is advisory. Preserve deterministic governance if the
                # model is temporarily unavailable, but leave a diagnostic reason.
                logger.warning("AI adjudication judge failed: %s", exc)
                ai_judge = {
                    "decision": "ERROR",
                    "confidence": 0.0,
                    "reason": str(exc)[:300],
                }

        final, human = self._apply_policy(payload, base, ai_judge)
        return {
            **base,
            "decision": final,
            "requiresHumanReview": human,
            "aiJudge": ai_judge,
            "policyVersion": "adjudication-v1.1.0",
            "evaluatedAt": datetime.now(timezone.utc).isoformat(),
        }

    @staticmethod
    def _judge_prompt(payload: Dict[str, Any]) -> str:
        return (
            "你是企业知识治理裁决器。只能输出JSON，不得补充其他文字。"
            "根据对象、证据、冲突信息判断知识是否被证据支持。"
            "decision只能为SUPPORTED、PARTIALLY_SUPPORTED、UNCERTAIN、REJECTED。"
            'JSON格式:{"decision":"","confidence":0.0,"reason":"",'
            '"evidenceAssessment":""}\n'
            "对象:"
            + str(payload.get("objectContent", ""))[:4000]
            + "\n证据:"
            + str(payload.get("evidenceSamples", []))[:6000]
            + "\n冲突:"
            + str(payload.get("conflicts", []))[:3000]
        )

    def _apply_policy(
        self,
        payload: Dict[str, Any],
        base: Dict[str, Any],
        ai_judge: Dict[str, Any],
    ) -> tuple:
        """Apply hard governance boundaries after scoring and AI advice."""
        score = base["knowledgeConfidence"]
        evidence_count = int(payload.get("evidenceCount", 0) or 0)
        conflict_count = int(payload.get("conflictCount", 0) or 0)
        ai_decision = str(ai_judge.get("decision", "NOT_RUN")).upper()
        ai_confidence = self._clamp(ai_judge.get("confidence", 0))
        hard_block = (
            conflict_count > 0
            or base["riskLevel"] == "HIGH"
            or ai_decision in ("REJECTED", "UNCERTAIN")
        )

        if (
            not hard_block
            and score >= 0.88
            and evidence_count >= 2
            and (ai_decision in ("SUPPORTED", "NOT_RUN") or ai_confidence < 0.5)
        ):
            return "APPROVED", False
        if score < 0.35 and evidence_count == 0:
            return "REJECTED", True
        if conflict_count > 0:
            return "CONFLICTED", True
        return "PENDING_REVIEW", True
