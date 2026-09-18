from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "V15 manifest migration": "t_index_acl_manifest" in (ROOT / "backend/src/main/resources/db/migration/V15__authoritative_acl_manifest_generation.sql").read_text(),
    "Java authoritative manifest": "aclManifestHash" in (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/IndexSchemaService.java").read_text(),
    "ACL-sensitive checksum": "permissionTags" in (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/IndexSchemaService.java").read_text(),
    "Machine expected manifest": "expectedManifestHash" in (ROOT / "backend/src/main/java/com/jh/knowledgeos/service/AclRebuildOrchestrator.java").read_text(),
    "ES target manifest": "target_manifest" in (ROOT / "ai-service/app/services/retrieval/elasticsearch_provider.py").read_text(),
    "Milvus target manifest": "target_manifest" in (ROOT / "ai-service/app/services/retrieval/milvus_provider.py").read_text(),
    "Generation metadata": "generation_id" in (ROOT / "ai-service/app/services/indexer.py").read_text(),
    "Fail closed generation barrier": "CROSS_ENGINE_GENERATION_MISMATCH" in (ROOT / "ai-service/app/services/retrieval/hybrid.py").read_text(),
    "Phase6 ID normalization fix": '"object_id"' in (ROOT / "ai-service/app/services/retrieval/milvus_provider.py").read_text(),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("FAILED: " + ", ".join(failed))
print("ACL Phase 7 manifest/generation contract passed: %d checks" % len(checks))
