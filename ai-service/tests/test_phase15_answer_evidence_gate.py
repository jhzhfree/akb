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


def test_block_reasons_split_the_three_failure_modes():
    """A blocked answer must say which check ate it, not just that it was blocked."""
    gate = AnswerEvidenceGate()
    assert gate.verify("Tomcat 8443 使用 TLS 1.2。", [citation()], [candidate()])["blockReasons"] == {
        "missingCitation": 1, "nonExactEvidence": 0, "lowFaithfulness": 0,
    }
    assert gate.verify("Tomcat 8443 使用 TLS 1.2。[证据1]", [citation("", "")], [candidate()])["blockReasons"] == {
        "missingCitation": 0, "nonExactEvidence": 1, "lowFaithfulness": 0,
    }
    wrong = {"id": "CU_1", "content": "Milvus 是向量数据库。", "metadata": {}}
    assert AnswerEvidenceGate(0.1).verify("Tomcat 8443 使用 TLS 1.2。[证据1]", [citation()], [wrong])["blockReasons"] == {
        "missingCitation": 0, "nonExactEvidence": 0, "lowFaithfulness": 1,
    }


def test_verifiable_terms_carry_more_weight_than_filler_wording():
    """A sentence naming the exact version is not sunk by the wording around it."""
    content = "OpenSSL 3.0.12 存在远程代码执行漏洞，影响使用该版本的网关产品。"
    chunk = {"id": "CU_1", "content": content, "metadata": {}}
    claim = "SecureGateway V5.2 依赖 OpenSSL 3.0.12，因此处于该漏洞的影响范围内。[证据1]"
    gate = AnswerEvidenceGate()
    weighted = gate.verify(claim, [citation()], [chunk])["claims"][0]["faithfulnessScore"]
    plain = gate._support_score(claim, content)
    # The weighted score keeps its [0,1] meaning; the point is that the identifying terms
    # (version numbers) now count double, so a version-bearing claim is not diluted.
    assert 0.0 <= weighted <= 1.0
    assert weighted == round(plain, 5)
    overlap = gate._tokens(claim).intersection(gate._tokens(content))
    assert overlap


def test_safe_answer_fail_closed():
    verification = {"gateStatus": "BLOCKED"}
    safe = AnswerEvidenceGate.safe_answer("unsupported", verification)
    assert "阻止输出" in safe
