"""Unified, versioned answer qualification and claim-ledger projection."""

import hashlib
import re
from typing import Any

from app.services.answer_evidence_gate import AnswerEvidenceGate
from app.services.structured_answer_gate import StructuredAnswerGate

_WITHHELD_NOTE = "（另有 %d 项结论未通过证据完整性与忠实度校验，已阻止输出。）"
_CONFLICT_NOTE = "（另有 %d 项结论与现有知识冲突或证据不足，已阻止输出。）"
_NORMALIZE_RE = re.compile(r"\s+")


class AnswerQualification:
    """Combine Phase 15/16 gates into one auditable delivery decision.

    Delivery is claim-led rather than answer-led: when only part of an answer survives
    verification the surviving sentences are still delivered and the rest is withheld. A
    whole-answer takeover stays reserved for answers with no verifiable claim at all, which
    keeps the fail-closed guarantee (no hallucinated sentence is ever shown) while stopping
    one unsupported sentence from silencing an otherwise sourced answer.
    """

    def __init__(self, policy_version: str = "answer-policy-v1") -> None:
        self.policy_version = policy_version or "answer-policy-v1"

    def qualify(
        self, original: str, evidence: dict[str, Any], structured: dict[str, Any], enabled: bool = True, model: str = ""
    ) -> dict[str, Any]:
        ledger = [dict(item) for item in (structured.get("claims") or [])]
        rows = [dict(item) for item in (evidence.get("claims") or [])]
        if not rows:
            # Structured claims are the reporting contract; evidence details drive redaction.
            rows = [dict(item) for item in (ledger or [])]
        if not enabled or model == "none":
            status, delivered, reasons, kept = "BYPASSED", original, [], None
        else:
            status, delivered, reasons, kept = self._decide(original, evidence, structured, rows, ledger)

        claims = []
        for index, row in enumerate(ledger or rows):
            item = {key: value for key, value in row.items() if key not in ("text",)}
            item["policyVersion"] = self.policy_version
            item["includedInFinalAnswer"] = (
                index in kept if kept is not None else status == "PASS" and item.get("decision") == "SUPPORTED"
            )
            claims.append(item)
        return {
            "gateStatus": status,
            "policyVersion": self.policy_version,
            "evidenceGateStatus": evidence.get("gateStatus"),
            "structuredGateStatus": structured.get("gateStatus"),
            "blockReasons": evidence.get("blockReasons") or {},
            "decisionReasons": reasons,
            "originalAnswerHash": self._hash(original),
            "deliveredAnswerHash": self._hash(delivered),
            "deliveredAnswer": delivered,
            "claimCount": len(claims),
            "claims": claims,
        }

    def _decide(
        self,
        original: str,
        evidence: dict[str, Any],
        structured: dict[str, Any],
        rows: list[dict[str, Any]],
        ledger: list[dict[str, Any]],
    ) -> tuple[str, str, list[str], set[int] | None]:
        """Return status, deliverable text, reasons, and the set of delivered claim indexes."""
        decisions = self._decisions(rows, ledger)
        # The evidence gate has priority: a sentence without a trustworthy citation never ships.
        if evidence.get("gateStatus") != "PASS":
            kept = [i for i in range(len(rows)) if rows[i].get("supported") and decisions[i] != "CONTRADICTED"]
            return self._render(
                original,
                rows,
                kept,
                "ANSWER_EVIDENCE_GATE_PARTIAL_REDACTION",
                "ANSWER_EVIDENCE_GATE_BLOCKED",
                AnswerEvidenceGate.safe_answer(original, evidence),
            )
        if structured.get("gateStatus") == "BLOCKED":
            kept = [i for i in range(len(rows)) if decisions[i] == "SUPPORTED"]
            return self._render(
                original,
                rows,
                kept,
                "STRUCTURED_ANSWER_GATE_PARTIAL_REDACTION",
                "STRUCTURED_ANSWER_GATE_BLOCKED",
                StructuredAnswerGate.safe_answer(original, structured),
            )
        if structured.get("gateStatus") == "PARTIAL":
            return "PARTIAL", StructuredAnswerGate.safe_answer(original, structured), ["PARTIAL_CLAIM_SUPPORT"], None
        return "PASS", original, [], None

    def _render(
        self,
        original: str,
        rows: list[dict[str, Any]],
        kept: list[int],
        reason: str,
        blocked_reason: str,
        blocked_text: str,
    ) -> tuple[str, str, list[str], set[int] | None]:
        """Rebuild the answer from the surviving claims, or fail closed when nothing survives."""
        if not rows or not kept:
            return "BLOCKED", blocked_text, [blocked_reason], set()
        dropped = len(rows) - len(kept)
        if dropped <= 0:
            return "PASS", original, [], None
        text = "".join(str(rows[index].get("text") or rows[index].get("claim") or "") for index in kept)
        note = _CONFLICT_NOTE % dropped if reason.startswith("STRUCTURED") else _WITHHELD_NOTE % dropped
        return "PARTIAL", (text.strip() + "\n\n" + note), [reason], set(kept)

    def _decisions(self, rows: list[dict[str, Any]], ledger: list[dict[str, Any]]) -> list[str]:
        """Map each redaction row onto the claim-level decision made by the structured gate."""
        outcome = {self._key(item.get("statement")): str(item.get("decision") or "") for item in ledger}
        decisions = []
        for index, row in enumerate(rows):
            if len(ledger) == len(rows):
                decision = str(ledger[index].get("decision") or "")
            else:
                decision = outcome.get(self._key(row.get("claim") or row.get("statement")), "")
            decisions.append(decision)
        return decisions

    @staticmethod
    def _key(value: Any) -> str:
        return _NORMALIZE_RE.sub("", str(value or ""))

    @staticmethod
    def _hash(value: str) -> str:
        return hashlib.sha256((value or "").encode("utf-8")).hexdigest()
