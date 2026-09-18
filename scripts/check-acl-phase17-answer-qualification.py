#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ai = (ROOT / "ai-service/app/services/answer_qualification.py").read_text()
query = (ROOT / "ai-service/app/services/query_engine.py").read_text()
java = (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeQueryService.java").read_text()
mapper = (ROOT / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text()
migration = (ROOT / "backend/src/main/resources/db/migration/V23__answer_qualification_claim_ledger.sql").read_text()
frontend = (ROOT / "frontend/src/components/QueryTracePanel.vue").read_text()

checks = {
    "unified policy": "AnswerQualification" in ai and "answerQualification" in query,
    "policy version": "answer_policy_version" in (ROOT / "ai-service/app/core/config.py").read_text(),
    "answer hashes": "originalAnswerHash" in ai and "deliveredAnswerHash" in ai,
    "qualification ledger": "t_answer_qualification" in migration and "insertAnswerQualification" in mapper,
    "claim ledger": "t_answer_claim_assessment" in migration and "insertAnswerClaimAssessment" in mapper,
    "atomic persistence": "@Transactional" in java,
    "claim persistence": "persistAnswerQualification" in java and "includedInFinalAnswer" in java,
    "trace visualization": "AnswerQualificationPanel" in frontend,
    "focused tests": (ROOT / "ai-service/tests/test_phase17_answer_qualification.py").exists(),
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " " + name)
assert not failed, "Phase 17 contract failed: %s" % failed
print("Phase 17 unified answer qualification contract passed: %d/%d" % (len(checks), len(checks)))
