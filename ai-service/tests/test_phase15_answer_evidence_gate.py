from app.services.answer_evidence_gate import AnswerEvidenceGate


def candidate(evidence_code="EVD_1", evidence_hash="abc"):
    return {"id": "CU_1", "content": "Tomcat 8443 使用 TLS 1.2 提供 HTTPS 服务。", "metadata": {}}


def citation(evidence_code="EVD_1", evidence_hash="abc"):
    return {"index": 1, "id": "CU_1", "evidenceCode": evidence_code, "evidenceHash": evidence_hash}


def test_exact_citation_passes():
    result = AnswerEvidenceGate(0.03).verify("Tomcat 8443 使用 TLS 1.2。[证据1]", [citation()], [candidate()])
    assert result["gateStatus"] == "PASS"
    assert result["completenessScore"] == 1.0


def test_missing_citation_is_blocked():
    result = AnswerEvidenceGate().verify("Tomcat 8443 使用 TLS 1.2。", [citation()], [candidate()])
    assert result["gateStatus"] == "BLOCKED"


def test_non_exact_citation_is_blocked():
    result = AnswerEvidenceGate().verify("Tomcat 8443 使用 TLS 1.2。[证据1]", [citation("", "")], [candidate()])
    assert result["gateStatus"] == "BLOCKED"


def test_wrong_evidence_is_blocked():
    wrong = {"id": "CU_1", "content": "Milvus 是向量数据库。", "metadata": {}}
    result = AnswerEvidenceGate(0.1).verify("Tomcat 8443 使用 TLS 1.2。[证据1]", [citation()], [wrong])
    assert result["gateStatus"] == "BLOCKED"


def test_safe_answer_fail_closed():
    verification = {"gateStatus": "BLOCKED"}
    safe = AnswerEvidenceGate.safe_answer("unsupported", verification)
    assert "阻止输出" in safe
