from app.services.answer_evidence_gate import AnswerEvidenceGate
from app.services.structured_answer_gate import StructuredAnswerGate
from app.services.answer_qualification import AnswerQualification


def citation(index, evidence_code="EVD_1", evidence_hash="hash-1"):
    return {
        "index": index, "id": "CU_1", "title": "LLM 推理",
        "evidenceCode": evidence_code, "evidenceHash": evidence_hash,
    }


def candidate():
    return {
        "id": "CU_1",
        "title": "LLM 推理",
        "content": "LLM 推理需要专门优化，因为解码阶段受显存带宽限制，KV Cache 会随上下文增长。",
        "metadata": {"status": "APPROVED"},
    }


def test_partially_supported_answer_keeps_supported_claims():
    """Two of three sentences are sourced; only the unsourced one may be withheld."""
    answer = (
        "LLM 推理解码阶段受显存带宽限制。[证据1]"
        "因此需要对注意力算子做专门优化。[证据1]"
        "在移动端还应该使用更激进的量化策略。"
    )
    evidence = AnswerEvidenceGate(0.03).verify(answer, [citation(1)], [candidate()])
    structured = StructuredAnswerGate(0.08).verify(answer, [citation(1)], [candidate()])
    assert evidence["gateStatus"] == "BLOCKED"

    result = AnswerQualification().qualify(answer, evidence, structured, True, "qwen")
    assert result["gateStatus"] == "PARTIAL"
    assert result["decisionReasons"] == ["ANSWER_EVIDENCE_GATE_PARTIAL_REDACTION"]
    # Supported sentences survive with their citation; the unsourced one does not.
    assert "显存带宽限制" in result["deliveredAnswer"]
    assert "激进的量化策略" not in result["deliveredAnswer"]
    assert "1 项结论" in result["deliveredAnswer"]
    included = [claim["includedInFinalAnswer"] for claim in result["claims"]]
    assert included.count(True) == 2 and included.count(False) == 1


def test_fully_unsourced_answer_still_fails_closed():
    """Nothing survives verification, so the whole answer stays blocked."""
    answer = "显存带宽是推理瓶颈。"
    evidence = AnswerEvidenceGate(0.03).verify(answer, [citation(1)], [candidate()])
    structured = StructuredAnswerGate(0.08).verify(answer, [citation(1)], [candidate()])

    result = AnswerQualification().qualify(answer, evidence, structured, True, "qwen")
    assert result["gateStatus"] == "BLOCKED"
    assert result["decisionReasons"] == ["ANSWER_EVIDENCE_GATE_BLOCKED"]
    assert "阻止输出" in result["deliveredAnswer"]
    assert all(not claim["includedInFinalAnswer"] for claim in result["claims"])


def test_contradicted_claim_is_never_delivered():
    """Conflict-awareness wins over partial delivery: contradicting evidence is withheld."""
    answer = "LLM 推理不需要任何优化。[证据1]"
    evidence = AnswerEvidenceGate(0.03).verify(answer, [citation(1)], [candidate()])
    structured = StructuredAnswerGate(0.08).verify(answer, [citation(1)], [candidate()])

    result = AnswerQualification().qualify(answer, evidence, structured, True, "qwen")
    assert result["gateStatus"] in ("BLOCKED", "PARTIAL")
    assert "不需要任何优化" not in result["deliveredAnswer"]


if __name__ == "__main__":
    test_partially_supported_answer_keeps_supported_claims()
    test_fully_unsourced_answer_still_fails_closed()
    test_contradicted_claim_is_never_delivered()
    print("Phase 18 claim-level answer redaction tests passed: 3")
