from pathlib import Path

R = Path(__file__).resolve().parents[1]
controller = (R / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeGraphController.java").read_text()
mapper_sources = "\n".join((R / "backend/src/main/java/com/jh/knowledgeos/mapper" / name).read_text()
    for name in ["KnowledgeObjectMapper.java", "GovernanceMapper.java", "EvidenceMapper.java"])
xml_sources = "\n".join((R / "backend/src/main/resources/mapper" / name).read_text()
    for name in ["KnowledgeObjectMapper.xml", "GovernanceMapper.xml", "EvidenceMapper.xml"])
app = (R / "frontend/src/App.vue").read_text()
detail = (R / "frontend/src/components/KnowledgeDetailWorkspace.vue").read_text()
api = (R / "frontend/src/api/knowledge-impl.ts").read_text()
for token in ["/objects/{kb}/{type}/{code}", "knowledgeDetail"]:
    assert (
        token in controller
        or token in (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeService.java").read_text()
    )
for token in ["getKnowledgeObject", "listReviewsForObject", "listAssessmentsForObject", "listEvidenceForObject"]:
    assert token in mapper_sources and token in xml_sources
for token in ["证据链", "人工复核历史", "智能裁决记录", "知识冲突"]:
    assert token in detail
assert "getKnowledgeObjectDetail" in api
assert "KnowledgeDetailWorkspace" in app and "async function openDetail" in app
print("knowledge detail workspace contract passed")
