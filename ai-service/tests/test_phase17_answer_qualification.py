from app.services.answer_qualification import AnswerQualification


def evidence(status="PASS"):
    return {"gateStatus": status}


def structured(status="PASS", decision="SUPPORTED"):
    return {
        "gateStatus": status,
        "claims": [
            {
                "claimId": "claim-1",
                "statement": "Tomcat 支持 TLS 1.2。",
                "decision": decision,
                "confidence": 0.9,
                "entailmentScore": 0.9,
                "citationIndexes": [1],
                "evidenceIds": ["EVD_1"],
                "evidenceStatuses": ["SUPPORTED"],
            }
        ],
    }


def test_pass_preserves_answer_and_hashes():
    result = AnswerQualification("policy-17").qualify("可信答案", evidence(), structured())
    assert result["gateStatus"] == "PASS"
    assert result["deliveredAnswer"] == "可信答案"
    assert result["originalAnswerHash"] == result["deliveredAnswerHash"]
    assert result["claims"][0]["includedInFinalAnswer"] is True


def test_evidence_block_has_priority():
    result = AnswerQualification().qualify("不可信答案", evidence("BLOCKED"), structured("BLOCKED"))
    assert result["gateStatus"] == "BLOCKED"
    assert result["decisionReasons"] == ["ANSWER_EVIDENCE_GATE_BLOCKED"]
    assert result["originalAnswerHash"] != result["deliveredAnswerHash"]


def test_partial_is_versioned_and_not_marked_as_delivered_claim():
    result = AnswerQualification("policy-17").qualify(
        "部分可信", evidence(), structured("PARTIAL", "PARTIALLY_SUPPORTED")
    )
    assert result["gateStatus"] == "PARTIAL"
    assert result["policyVersion"] == "policy-17"
    assert result["claims"][0]["includedInFinalAnswer"] is False


def test_disabled_gate_is_explicitly_bypassed():
    result = AnswerQualification().qualify("原始答案", evidence("BLOCKED"), structured(), False, "qwen")
    assert result["gateStatus"] == "BYPASSED"
    assert result["deliveredAnswer"] == "原始答案"


if __name__ == "__main__":
    test_pass_preserves_answer_and_hashes()
    test_evidence_block_has_priority()
    test_partial_is_versioned_and_not_marked_as_delivered_claim()
    test_disabled_gate_is_explicitly_bypassed()
    print("Phase 17 answer qualification tests passed: 4")
