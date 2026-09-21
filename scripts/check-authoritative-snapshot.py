from pathlib import Path

migration = Path(
    "backend/src/main/resources/db/migration/V6__authoritative_snapshot_and_release_integrity.sql"
).read_text()
service = Path("backend/src/main/java/com/jh/knowledgeos/service/IndexSchemaService.java").read_text()
mapper = Path("backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text()
routes = Path("ai-service/app/routes.py").read_text()
assert "snapshot_checksum" in migration and "source_high_watermark" in migration
assert "t_index_schema_snapshot_audit" in migration
assert "listAuthoritativeIndexSnapshot" in mapper
assert "maxIndexOutboxId" in mapper
assert "INDEX_SCHEMA_REBUILD_BATCH_PREFIX" in service
assert "sourceHighWatermark" in service and "SOURCE_CHANGED" in service
assert "/ai/index/schema/rebuild-batch/" in routes
print("authoritative snapshot contract passed")
