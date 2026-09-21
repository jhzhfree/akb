"""Fail-closed answer-level citation completeness and faithfulness verification."""

import re
from typing import Any

from app.services.answer_claims import split_claims, strip_citations

_CITATION_RE = re.compile(r"\[证据(\d+)\]")


class AnswerEvidenceGate:
    """Verify that factual answer claims are bound to visible selected evidence."""

    def __init__(self, faithfulness_min: float = 0.03) -> None:
        self.faithfulness_min = max(0.0, min(1.0, faithfulness_min))

    def verify(self, answer: str, citations: list[dict[str, Any]], candidates: list[dict[str, Any]]) -> dict[str, Any]:
        citation_by_index = {int(c["index"]): c for c in citations if c.get("index") is not None}
        candidate_by_id = {str(c.get("id")): c for c in candidates}
        claims = self._claims(answer)
        details = []
        supported = 0
        for claim in claims:
            refs = [int(value) for value in _CITATION_RE.findall(claim)]
            valid_refs = [ref for ref in refs if ref in citation_by_index]
            evidence = [candidate_by_id.get(str(citation_by_index[ref].get("id"))) for ref in valid_refs]
            evidence = [item for item in evidence if item]
            score = max([self._support_score(claim, item.get("content") or "") for item in evidence] or [0.0])
            exact = all(self._citation_is_exact(citation_by_index[ref]) for ref in valid_refs) if valid_refs else False
            ok = bool(valid_refs) and exact and score >= self.faithfulness_min
            if ok:
                supported += 1
            details.append(
                {
                    # Raw sentence kept verbatim (with citations) so a partially supported answer can be
                    # rebuilt sentence by sentence instead of being replaced wholesale.
                    "text": claim,
                    "claim": self._strip_citations(claim),
                    "citationIndexes": refs,
                    "validCitationIndexes": valid_refs,
                    "exactEvidence": exact,
                    "faithfulnessScore": round(score, 5),
                    "supported": ok,
                }
            )
        count = len(claims)
        completeness = supported / count if count else (1.0 if not answer.strip() else 0.0)
        faithfulness = sum(item["faithfulnessScore"] for item in details) / count if count else completeness
        passed = count > 0 and supported == count
        return {
            "gateStatus": "PASS" if passed else "BLOCKED",
            "policy": "FACTUAL_CLAIM_REQUIRES_EXACT_VISIBLE_EVIDENCE",
            "factualClaimCount": count,
            "supportedClaimCount": supported,
            "citationCount": len(citations),
            "completenessScore": round(completeness, 5),
            "faithfulnessScore": round(faithfulness, 5),
            "unsupportedClaims": [item["claim"] for item in details if not item["supported"]],
            "claims": details,
        }

    @staticmethod
    def safe_answer(original: str, verification: dict[str, Any]) -> str:
        """Return the original answer only when every factual claim passes the gate."""
        if verification.get("gateStatus") == "PASS":
            return original
        return "现有知识不足以形成全部可验证的结论；未通过证据完整性与忠实度校验的内容已阻止输出。"

    @staticmethod
    def _claims(answer: str) -> list[str]:
        return split_claims(answer)

    @staticmethod
    def _strip_citations(text: str) -> str:
        return strip_citations(text)

    @staticmethod
    def _citation_is_exact(citation: dict[str, Any]) -> bool:
        return bool(citation.get("evidenceCode") and citation.get("evidenceHash"))

    @staticmethod
    def _support_score(claim: str, evidence: str) -> float:
        left = AnswerEvidenceGate._tokens(AnswerEvidenceGate._strip_citations(claim))
        right = AnswerEvidenceGate._tokens(evidence)
        if not left or not right:
            return 0.0
        return len(left.intersection(right)) / float(len(left))

    @staticmethod
    def _tokens(text: str) -> set:
        normalized = re.sub(r"\s+", "", (text or "").lower())
        latin = set(re.findall(r"[a-z0-9_.+-]{2,}", normalized))
        han = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
        grams = {han[i : i + 2] for i in range(max(0, len(han) - 1))}
        return latin.union(grams)
