from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    "V22 answer assessment": "t_answer_evidence_assessment"
    in (root / "backend/src/main/resources/db/migration/V22__answer_evidence_faithfulness.sql").read_text(),
    "answer gate service": "FACTUAL_CLAIM_REQUIRES_EXACT_VISIBLE_EVIDENCE"
    in (root / "ai-service/app/services/answer_evidence_gate.py").read_text(),
    "query engine gate": "answerVerification" in (root / "ai-service/app/services/query_engine.py").read_text(),
    "citation exact metadata": "evidenceCode" in (root / "ai-service/app/services/query_engine.py").read_text(),
    "fail closed": "AnswerQualification" in (root / "ai-service/app/services/query_engine.py").read_text()
    and "ANSWER_EVIDENCE_GATE_BLOCKED" in (root / "ai-service/app/services/answer_qualification.py").read_text(),
    "trace diagnostics": "citationCompleteness" in (root / "ai-service/app/services/query_engine.py").read_text(),
    "assessment persistence": "insertAnswerEvidenceAssessment"
    in (root / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeQueryService.java").read_text(),
    "focused tests": (root / "ai-service/tests/test_phase15_answer_evidence_gate.py").exists(),
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " " + name)
if failed:
    raise SystemExit(1)
print("Phase 15 contract: %d/%d PASS" % (len(checks), len(checks)))
