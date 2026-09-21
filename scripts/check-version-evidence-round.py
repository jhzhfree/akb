from pathlib import Path

R = Path(__file__).resolve().parents[1]
assert (R / "backend/src/main/resources/db/migration/V9__knowledge_version_history.sql").exists()
mapper = (R / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text()
for token in ["snapshotKnowledgeObject", "listKnowledgeVersions", "contentUnitCode", "documentTitle"]:
    assert token in mapper
read = (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeReadService.java").read_text()
assert '"versions"' in read
extract = (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java").read_text()
for token in ['"CREATED"', '"CONFLICT_DETECTED"']:
    assert token in extract
ui = (R / "frontend/src/components/KnowledgeDetailWorkspace.vue").read_text()
assert "VersionDiffPanel" in ui and "contentUnitCode" in ui
assert (R / "frontend/src/components/VersionDiffPanel.vue").exists()
print("knowledge version + evidence locator contract passed")
