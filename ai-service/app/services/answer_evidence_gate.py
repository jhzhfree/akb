"""Fail-closed answer-level citation completeness and faithfulness verification."""

import re
from typing import Any

from app.services.answer_claims import split_claims, strip_citations

_CITATION_RE = re.compile(r"\[证据(\d+)\]")
# Version strings, error codes and proper nouns are what make a claim checkable: a sentence
# that gets those right is worth more than one that only shares filler wording.
_VERIFIABLE_RE = re.compile(r"\d|[a-z]{3,}")


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
        block_reasons = {"missingCitation": 0, "nonExactEvidence": 0, "lowFaithfulness": 0}
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
            else:
                # One reason per sentence, in the order the checks fire: knowing which of the
                # three is eating the answer tells you what to tune instead of guessing.
                if not valid_refs:
                    block_reasons["missingCitation"] += 1
                elif not exact:
                    block_reasons["nonExactEvidence"] += 1
                else:
                    block_reasons["lowFaithfulness"] += 1
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
        if not count:
            # Nothing factual to verify: the answer is an insufficiency statement or meta
            # narration, and every one of its sentences was skipped upstream. Blocking here
            # would turn an honest "I do not have this" into the same canned refusal, so the
            # gate passes with an empty ledger instead of failing closed on zero claims.
            return {
                "gateStatus": "PASS",
                "policy": "FACTUAL_CLAIM_REQUIRES_EXACT_VISIBLE_EVIDENCE",
                "factualClaimCount": 0,
                "supportedClaimCount": 0,
                "citationCount": len(citations),
                "completenessScore": 1.0,
                "faithfulnessScore": 1.0,
                "unsupportedClaims": [],
                "blockReasons": block_reasons,
                "claims": [],
                "noFactualClaim": True,
            }
        completeness = supported / count
        faithfulness = sum(item["faithfulnessScore"] for item in details) / count
        passed = supported == count
        return {
            "gateStatus": "PASS" if passed else "BLOCKED",
            "policy": "FACTUAL_CLAIM_REQUIRES_EXACT_VISIBLE_EVIDENCE",
            "factualClaimCount": count,
            "supportedClaimCount": supported,
            "citationCount": len(citations),
            "completenessScore": round(completeness, 5),
            "faithfulnessScore": round(faithfulness, 5),
            "unsupportedClaims": [item["claim"] for item in details if not item["supported"]],
            "blockReasons": block_reasons,
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
        overlap = left.intersection(right)
        if not overlap:
            return 0.0
        # Weighted coverage: identical semantics to before, but the terms that actually
        # identify a fact — versions, codes, proper nouns — count double, so a sentence
        # naming "OpenSSL 3.0.12" is not sunk by the wording around it.
        hit = sum(2.0 if _VERIFIABLE_RE.search(term) else 1.0 for term in overlap)
        total = sum(2.0 if _VERIFIABLE_RE.search(term) else 1.0 for term in left)
        return hit / float(total) if total else 0.0

    @staticmethod
    def _tokens(text: str) -> set:
        normalized = re.sub(r"\s+", "", (text or "").lower())
        # Chinese is stripped before Latin terms are read: left in place, "OpenSSL3.0.12漏洞"
        # and "OpenSSL" tokenize differently depending on where the Chinese sits, and two
        # texts describing the same fact would share nothing.
        stripped = re.sub(r"[\u4e00-\u9fff]+", " ", normalized)
        # A Latin run is split at the letter/digit boundary so "openssl3.0.12" becomes
        # {"openssl", "3.0.12"}: a chunk naming the product then matches a question naming
        # the product and its version, instead of needing the exact same concatenation.
        latin = set()
        for run in re.findall(r"[a-z0-9_.+-]{2,}", stripped):
            for part in re.findall(r"[a-z]{2,}|\d[\d._]{1,}", run):
                latin.add(part)
        han = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
        grams = {han[i : i + 2] for i in range(max(0, len(han) - 1))}
        return latin.union(grams)
