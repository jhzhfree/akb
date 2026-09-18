"""Claim-level entailment and conflict-aware answer qualification."""

import re
from typing import Any, Dict, List

from app.services.answer_claims import split_claims

_CITATION_RE = re.compile(r"\[证据(\d+)\]")
_HAN_NEGATION = {"不", "未", "无", "不能", "禁止", "否"}
_LATIN_NEGATION = {"not", "no", "never", "without"}
_CONFLICTED = {"CONFLICTED", "REJECTED", "EXPIRED", "SUPERSEDED"}
_PARTIAL = {"PARTIALLY_SUPPORTED", "UNCERTAIN"}
# A negation token only disputes a claim when it sits in the sentence that
# actually backs that claim; below this overlap the sentence is unrelated.
_POLARITY_MIN_OVERLAP = 0.2


class StructuredAnswerGate:
    """Classify each answer claim against exact evidence and governance state."""

    def __init__(self, entailment_min: float = 0.08) -> None:
        self.entailment_min = max(0.0, min(1.0, entailment_min))

    def verify(self, answer: str, citations: List[Dict[str, Any]], candidates: List[Dict[str, Any]]) -> Dict[str, Any]:
        citation_by_index = {int(c["index"]): c for c in citations if c.get("index") is not None}
        candidate_by_id = {str(c.get("id")): c for c in candidates}
        claims = self._claims(answer)
        structured = []
        for number, text in enumerate(claims, 1):
            refs = [int(v) for v in _CITATION_RE.findall(text)]
            evidence = []
            for ref in refs:
                citation = citation_by_index.get(ref)
                candidate = candidate_by_id.get(str((citation or {}).get("id")))
                if citation and candidate and citation.get("evidenceCode") and citation.get("evidenceHash"):
                    evidence.append((citation, candidate))
            structured.append(self._assess(number, text, refs, evidence))
        blocked = [c for c in structured if c["decision"] in ("CONTRADICTED", "INSUFFICIENT")]
        partial = [c for c in structured if c["decision"] == "PARTIALLY_SUPPORTED"]
        status = "BLOCKED" if blocked else ("PARTIAL" if partial else "PASS")
        return {
            "gateStatus": status,
            "policy": "CLAIM_LEVEL_ENTAILMENT_AND_CONFLICT_AWARE",
            "claimCount": len(structured),
            "supportedCount": sum(c["decision"] == "SUPPORTED" for c in structured),
            "partialCount": len(partial),
            "contradictedCount": sum(c["decision"] == "CONTRADICTED" for c in structured),
            "insufficientCount": sum(c["decision"] == "INSUFFICIENT" for c in structured),
            "claims": structured,
        }

    def _assess(self, number, text, refs, evidence):
        statement = _CITATION_RE.sub("", text).strip()
        statuses = [self._status(candidate) for _, candidate in evidence]
        scores = [self._entailment(statement, candidate.get("content") or "") for _, candidate in evidence]
        best = max(scores or [0.0])
        contradicted = any(status in _CONFLICTED for status in statuses)
        partial = any(status in _PARTIAL for status in statuses)
        polarity_conflict = any(
            self._polarity_conflict(statement, c.get("content") or "") for _, c in evidence
        )
        if contradicted or (evidence and polarity_conflict and best >= self.entailment_min):
            decision = "CONTRADICTED"
        elif not evidence or best < self.entailment_min:
            decision = "INSUFFICIENT"
        elif partial:
            decision = "PARTIALLY_SUPPORTED"
        else:
            decision = "SUPPORTED"
        return {
            "claimId": "claim-%d" % number,
            "statement": statement,
            "citationIndexes": refs,
            "evidenceIds": [c.get("evidenceCode") for c, _ in evidence],
            "evidenceStatuses": statuses,
            "entailmentScore": round(best, 5),
            "polarityConflict": polarity_conflict,
            "decision": decision,
            "confidence": round(best * (0.7 if partial else 1.0), 5),
        }

    @staticmethod
    def _status(candidate):
        meta = candidate.get("metadata") or {}
        return str(meta.get("claim_status") or meta.get("status") or "SUPPORTED").upper()

    @staticmethod
    def _claims(answer):
        return split_claims(answer)

    @staticmethod
    def _sentences(text):
        # Stored evidence keeps markdown line breaks as literal "\n" in some
        # providers, so both forms are treated as sentence boundaries here.
        normalized = (text or "").replace("\\n", "\n")
        return [x for x in re.split(r"(?<=[。！？!?；;])|\n+", normalized) if x.strip()]

    @classmethod
    def _best_sentence(cls, claim, evidence):
        """Return the evidence sentence that actually backs the claim."""
        left = cls._tokens(claim)
        best_text, best_score = "", 0.0
        for sentence in cls._sentences(evidence):
            right = cls._tokens(sentence)
            if not left or not right:
                continue
            score = len(left.intersection(right)) / float(len(left))
            if score > best_score:
                best_text, best_score = sentence, score
        return best_text, best_score

    def _polarity_conflict(self, statement, evidence):
        """Negation is judged on the backing sentence, never on the whole document.

        A long evidence block nearly always contains a negation character
        somewhere, so comparing against the full text flagged every grounded
        claim as CONTRADICTED; only the matched sentence can dispute a claim.
        """
        sentence, overlap = self._best_sentence(statement, evidence)
        if overlap < _POLARITY_MIN_OVERLAP:
            return False
        return self._polarity(statement) != self._polarity(sentence)

    @staticmethod
    def _tokens(text):
        normalized = re.sub(r"\s+", "", (text or "").lower())
        latin = set(re.findall(r"[a-z0-9_.+-]{2,}", normalized))
        han = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
        return latin.union({han[i:i + 2] for i in range(max(0, len(han) - 1))})

    @classmethod
    def _entailment(cls, claim, evidence):
        left, right = cls._tokens(claim), cls._tokens(evidence)
        return len(left.intersection(right)) / float(len(left)) if left and right else 0.0

    @staticmethod
    def _polarity(text):
        lowered = (text or "").lower()
        if any(token in lowered for token in _HAN_NEGATION):
            return -1
        words = set(re.findall(r"[a-z]+", lowered))
        return -1 if words.intersection(_LATIN_NEGATION) else 1

    @staticmethod
    def safe_answer(original, verification):
        if verification.get("gateStatus") == "PASS":
            return original
        if verification.get("gateStatus") == "PARTIAL":
            supported = [c for c in verification.get("claims", []) if c.get("decision") == "SUPPORTED"]
            if supported:
                return "部分结论已获得充分证据支持；其余结论因证据不完整或存在不确定性未作为确定性结论输出。"
        return "现有证据无法支持全部结论，或存在冲突证据；相关确定性结论已阻止输出。"
