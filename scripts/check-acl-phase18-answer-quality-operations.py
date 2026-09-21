#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
mapper_java = (ROOT / "backend/src/main/java/com/jh/knowledgeos/mapper/KnowledgeMapper.java").read_text()
mapper_xml = (ROOT / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text()
read_service = (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeReadService.java").read_text()
controller = (ROOT / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java").read_text()
frontend = (ROOT / "frontend/src/components/QueryTracePanel.vue").read_text()
api = (ROOT / "frontend/src/api/knowledge.ts").read_text()

checks = {
    "qualification detail query": "getAnswerQualification" in mapper_java
    and "listAnswerClaimAssessments" in mapper_xml,
    "trace authoritative assembly": 'trace.put("answerQualification"' in read_service,
    "quality summary": "summarizeAnswerQualifications" in mapper_xml and "passRate" in mapper_xml,
    "policy distribution": "listAnswerQualificationPolicyStats" in mapper_xml,
    "detail endpoint": "answer-qualification/{qid}" in controller,
    "summary endpoint": "answer-qualifications/{kb}/summary" in controller,
    "bounded time window": "Math.min(hours, 24 * 90)" in read_service,
    "typed frontend API": "getAnswerQualificationSummary" in api,
    "authoritative UI preference": "props.trace?.answerQualification" in frontend,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " " + name)
assert not failed, "Phase 18 answer-quality operations contract failed: %s" % failed
print("Phase 18 answer-quality operations contract passed: %d/%d" % (len(checks), len(checks)))
