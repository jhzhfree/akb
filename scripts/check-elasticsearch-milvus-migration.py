#!/usr/bin/env python3
"""Verify the V1.1 Elasticsearch + Milvus production retrieval selection."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
active = [
    ROOT / "ai-service/app",
    ROOT / "backend/src/main/java",
    ROOT / "backend/src/main/resources/mapper",
    ROOT / "backend/src/main/resources/application.yml",
    ROOT / "docker-compose.yml",
    ROOT / "deploy/docker-compose.prod.yml",
    ROOT / ".env.example",
    ROOT / ".env.production.example",
]
bad = []
for item in active:
    paths = [item] if item.is_file() else list(item.rglob("*"))
    for path in paths:
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8").lower()
        except UnicodeDecodeError:
            continue
        if "opensearch" in text or "qdrant" in text:
            bad.append(str(path.relative_to(ROOT)))
assert not bad, "legacy production engine references remain: " + ", ".join(sorted(set(bad)))
assert (ROOT / "ai-service/app/services/retrieval/elasticsearch_provider.py").exists()
assert (ROOT / "ai-service/app/services/retrieval/milvus_provider.py").exists()
cfg = (ROOT / "ai-service/app/core/config.py").read_text()
for key in ["ELASTICSEARCH_URL", "ELASTICSEARCH_INDEX", "MILVUS_URL", "MILVUS_COLLECTION", "MILVUS_TOKEN"]:
    assert key in cfg, "missing config " + key
compose = (ROOT / "docker-compose.yml").read_text()
assert "docker.elastic.co/elasticsearch/elasticsearch:9.5.3" in compose
assert "milvusdb/milvus:v3.0.1" in compose
assert "19530:19530" in compose
print("Elasticsearch + Milvus migration contract passed")
