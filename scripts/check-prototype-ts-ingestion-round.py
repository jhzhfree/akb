from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
assert (ROOT / "backend/src/main/resources/db/migration/V8__knowledge_base_and_ingestion_observability.sql").exists()
controller = (ROOT / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeIngestionController.java").read_text()
for token in ["/bases", "/ingestion/{kb}/runs", "/ingestion/runs/{runId}"]:
    assert token in controller, token
ingestion = (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/DocumentIngestionService.java").read_text()
for token in ["PARSE_STARTED", "PERSIST_COMPLETED", "INDEX_EVENT_CREATED", "EXTRACTION_COMPLETED"]:
    assert token in ingestion, token
frontend = ROOT / "frontend/src"
for token in ["types.ts", "api/knowledge.ts", "main.ts"]:
    assert (frontend / token).exists(), token
for vue in frontend.rglob("*.vue"):
    if "<script" in vue.read_text():
        assert '<script setup lang="ts">' in vue.read_text(), str(vue)
assert (frontend / "components/KnowledgeBaseDialog.vue").exists()
assert (frontend / "components/IngestionLogPanel.vue").exists()
print("prototype TypeScript + ingestion observability round contract passed")
