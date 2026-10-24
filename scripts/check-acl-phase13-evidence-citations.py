from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    "V20 migration": "t_index_graph_evidence_manifest"
    in (root / "backend/src/main/resources/db/migration/V20__immutable_evidence_graph_binding.sql").read_text(),
    "authoritative binding mapper": "listAuthoritativeGraphEvidenceBindings"
    in (root / "backend/src/main/resources/mapper/GraphMapper.xml").read_text(),
    "java manifest": "insertGraphEvidenceManifest"
    in (root / "backend/src/main/java/com/jh/knowledgeos/service/AuthoritativeGraphGenerationService.java").read_text(),
    "EvidenceGen": "EvidenceGen" in (root / "ai-service/app/services/graph_provider.py").read_text(),
    "SourceGen": "SourceGen" in (root / "ai-service/app/services/graph_provider.py").read_text(),
    "SUPPORTED_BY": "SUPPORTED_BY" in (root / "ai-service/app/services/graph_provider.py").read_text(),
    "citation safe route": "citation-safe-multihop" in (root / "ai-service/app/routes.py").read_text(),
    "visible evidence gate": "AT_LEAST_ONE_VISIBLE_EVIDENCE_PER_PATH"
    in (root / "ai-service/app/services/graph_provider.py").read_text(),
}
for k, v in checks.items():
    print(("PASS" if v else "FAIL"), k)
raise SystemExit(0 if all(checks.values()) else 1)
