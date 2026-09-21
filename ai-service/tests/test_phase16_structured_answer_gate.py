from app.services.structured_answer_gate import StructuredAnswerGate


def candidate(status="SUPPORTED", content="Tomcat 8443 支持 TLS 1.2。"):
    return {"id": "e1", "content": content, "metadata": {"status": status}}


def citation():
    return {"index": 1, "id": "e1", "evidenceCode": "EV-1", "evidenceHash": "abc"}


def test_supported_claim():
    result = StructuredAnswerGate(0.05).verify("Tomcat 8443 支持 TLS 1.2。[证据1]", [citation()], [candidate()])
    assert result["gateStatus"] == "PASS"
    assert result["claims"][0]["decision"] == "SUPPORTED"


def test_missing_exact_evidence_is_insufficient():
    bad = dict(citation())
    bad["evidenceHash"] = ""
    result = StructuredAnswerGate().verify("Tomcat 8443 支持 TLS 1.2。[证据1]", [bad], [candidate()])
    assert result["claims"][0]["decision"] == "INSUFFICIENT"


def test_conflicted_governance_blocks_claim():
    result = StructuredAnswerGate(0.05).verify(
        "Tomcat 8443 支持 TLS 1.2。[证据1]", [citation()], [candidate("CONFLICTED")]
    )
    assert result["gateStatus"] == "BLOCKED"
    assert result["claims"][0]["decision"] == "CONTRADICTED"


def test_partial_governance_marks_partial():
    result = StructuredAnswerGate(0.05).verify(
        "Tomcat 8443 支持 TLS 1.2。[证据1]", [citation()], [candidate("PARTIALLY_SUPPORTED")]
    )
    assert result["gateStatus"] == "PARTIAL"


def test_negation_conflict_is_contradicted():
    result = StructuredAnswerGate(0.05).verify("Tomcat 8443 不支持 TLS 1.2。[证据1]", [citation()], [candidate()])
    assert result["claims"][0]["decision"] == "CONTRADICTED"


def test_no_substring_does_not_negate_english_word():
    result = StructuredAnswerGate(0.05).verify(
        "The system supports the knowledge base.[证据1]",
        [citation()],
        [candidate(content="The system supports the knowledge base.")],
    )
    assert result["claims"][0]["decision"] == "SUPPORTED"
