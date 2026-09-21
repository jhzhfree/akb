from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path):
    return (ROOT / path).read_text(encoding="utf-8")


graph = text("ai-service/app/services/graph_provider.py")
indexer = text("ai-service/app/services/indexer.py")
hybrid = text("ai-service/app/services/retrieval/hybrid.py")
java = text("backend/src/main/java/com/jh/knowledgeos/service/IndexSchemaService.java")

checks = {
    "graph reset generation": "def reset_generation" in graph,
    "graph upsert generation": "def upsert_generation" in graph,
    "graph active pointer": "IndexGenerationPointer" in graph and "activeGenerationId" in graph,
    "graph manifest": "def target_manifest" in graph and '"provider": "neo4j"' in graph,
    "graph persona qualification": "def qualify_acl_target" in graph,
    "graph pre-topk active generation": "generationId:p.activeGenerationId" in graph,
    "rebuild fans out graph": "graph.upsert_generation" in indexer and '"neo4jCount"' in indexer,
    "qualification includes graph": "graph_manifest" in indexer and '"provider": "neo4j"' in indexer,
    "activation moves graph pointer": "graph.activate_generation" in indexer,
    "java validates graph count": "snapshot.neo4jCount == expected" in java,
    "query barrier includes neo4j": "FAIL_CLOSED_ES_MILVUS_NEO4J" in hybrid,
    "query reads graph active generation": "graph.active_generation" in hybrid,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Phase 8 contract failed: " + ", ".join(failed))
print("ACL Phase 8 Neo4j generation contract passed: %d checks" % len(checks))
