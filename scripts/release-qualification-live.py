#!/usr/bin/env python3
"""Live Release Qualification for Elasticsearch 9.5.3 + Milvus 3.0.1.
Run after the two services are reachable. It is intentionally independent of Docker.
"""

import argparse
import asyncio
import json
import os
import random
import statistics
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-service"))
from app.services.retrieval.elasticsearch_provider import ElasticsearchProvider
from app.services.retrieval.milvus_provider import MilvusProvider


async def main(args):
    os.environ["ELASTICSEARCH_URL"] = args.elasticsearch
    os.environ["MILVUS_URL"] = args.milvus
    es = ElasticsearchProvider()
    mv = MilvusProvider()
    report = {"elasticsearch": {}, "milvus": {}, "performance": {}}
    assert await es.available(), "Elasticsearch unavailable"
    assert await mv.available(), "Milvus unavailable"
    schema = "rq%d" % int(time.time())
    docs = []
    rnd = random.Random(20260915)
    for i in range(args.documents):
        vector = [rnd.uniform(-1, 1) for _ in range(args.dim)]
        docs.append(
            {
                "id": "RQ_%d" % i,
                "knowledge_base_id": 99991,
                "object_type": "CARD",
                "title": "Tomcat 证书 %d" % i,
                "content": "Tomcat HTTPS PKCS12 证书部署 自动更新 运维知识 %d" % i,
                "status": "APPROVED",
                "permission_tags": ["ops"] if i % 2 else [],
                "index_version": 1,
                "vector": vector,
            }
        )
    t = time.perf_counter()
    await es.rebuild_target(docs, schema)
    report["elasticsearch"]["rebuildMs"] = round((time.perf_counter() - t) * 1000, 2)
    t = time.perf_counter()
    await mv.rebuild_target(docs, schema)
    report["milvus"]["rebuildMs"] = round((time.perf_counter() - t) * 1000, 2)
    await es.activate_target(schema)
    await mv.activate_target(schema)
    ecount = (await es.count(99991))["count"]
    mcount = (await mv.count(99991))["count"]
    assert ecount == args.documents and mcount == args.documents, (ecount, mcount, args.documents)
    report["elasticsearch"]["count"] = ecount
    report["milvus"]["count"] = mcount
    qv = docs[1]["vector"]
    es_lat = []
    mv_lat = []
    for _ in range(args.queries):
        t = time.perf_counter()
        await es.search(99991, "Tomcat 证书", 10, ["ops"], "CARD")
        es_lat.append((time.perf_counter() - t) * 1000)
        t = time.perf_counter()
        await mv.search(99991, qv, 10, ["ops"], "CARD")
        mv_lat.append((time.perf_counter() - t) * 1000)

    def pct(v, p):
        return sorted(v)[min(len(v) - 1, int(len(v) * p))]

    report["performance"]["elasticsearch"] = {
        "p50Ms": round(statistics.median(es_lat), 2),
        "p95Ms": round(pct(es_lat, 0.95), 2),
    }
    report["performance"]["milvus"] = {
        "p50Ms": round(statistics.median(mv_lat), 2),
        "p95Ms": round(pct(mv_lat, 0.95), 2),
    }
    report["qualified"] = True
    print(json.dumps(report, ensure_ascii=False, indent=2))
    await es.client.aclose()
    await mv.client.aclose()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--elasticsearch", default="http://127.0.0.1:9200")
    ap.add_argument("--milvus", default="http://127.0.0.1:19530")
    ap.add_argument("--documents", type=int, default=1000)
    ap.add_argument("--queries", type=int, default=100)
    ap.add_argument("--dim", type=int, default=384)
    args = ap.parse_args()
    asyncio.run(main(args))
