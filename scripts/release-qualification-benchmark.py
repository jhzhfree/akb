#!/usr/bin/env python3
"""Streaming Elasticsearch + Milvus release benchmark.

Generates deterministic batches instead of materializing 100K vectors in memory.
Writes a machine-readable qualification result used by the Go/No-Go evaluator.
"""

from __future__ import annotations

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


def percentile(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, int((len(values) - 1) * q))]


def make_batch(start, size, dim, seed, kb):
    rnd = random.Random(seed + start)
    docs = []
    for i in range(start, start + size):
        topic = i % 20
        docs.append(
            {
                "id": "RQ_%d" % i,
                "knowledge_base_id": kb,
                "object_type": "CARD",
                "title": "release qualification topic %d item %d" % (topic, i),
                "content": "enterprise knowledge retrieval topic_%d operational procedure evidence item_%d"
                % (topic, i),
                "status": "APPROVED",
                "permission_tags": ["ops"] if i % 3 else [],
                "index_version": 1,
                "vector": [rnd.uniform(-1, 1) for _ in range(dim)],
            }
        )
    return docs


async def run(args):
    os.environ["ELASTICSEARCH_URL"] = args.elasticsearch
    os.environ["MILVUS_URL"] = args.milvus
    es = ElasticsearchProvider()
    mv = MilvusProvider()
    if not await es.available() or not await mv.available():
        raise SystemExit("Elasticsearch or Milvus unavailable")
    schema = "rq_%d" % int(time.time())
    es_target = es.schema_target(schema)
    mv_target = mv.schema_target(schema)
    # Empty target initialization.
    await es.rebuild_target([], schema)
    await mv.rebuild_target([], schema)
    ingest_started = time.perf_counter()
    for start in range(0, args.documents, args.batch):
        docs = make_batch(start, min(args.batch, args.documents - start), args.dim, args.seed, args.kb)
        await es.upsert(docs, es_target)
        await mv.upsert(docs, mv_target)
    ingest_seconds = time.perf_counter() - ingest_started
    await es.activate_target(schema)
    await mv.activate_target(schema)
    ec = (await es.count(args.kb))["count"]
    mc = (await mv.count(args.kb))["count"]
    if ec != args.documents or mc != args.documents:
        raise AssertionError("count mismatch es=%s milvus=%s expected=%s" % (ec, mc, args.documents))

    sample = make_batch(1, 1, args.dim, args.seed, args.kb)[0]
    es_lat = []
    mv_lat = []
    for i in range(args.queries):
        topic = i % 20
        started = time.perf_counter()
        await es.search(args.kb, "topic_%d operational procedure" % topic, args.topk, ["ops"], "CARD")
        es_lat.append((time.perf_counter() - started) * 1000)
        started = time.perf_counter()
        await mv.search(args.kb, sample["vector"], args.topk, ["ops"], "CARD")
        mv_lat.append((time.perf_counter() - started) * 1000)
    result = {
        "qualified": True,
        "documents": args.documents,
        "queries": args.queries,
        "batch": args.batch,
        "dimension": args.dim,
        "ingestion": {"seconds": round(ingest_seconds, 3), "docsPerSecond": round(args.documents / ingest_seconds, 2)},
        "elasticsearch": {
            "count": ec,
            "p50Ms": round(statistics.median(es_lat), 2),
            "p95Ms": round(percentile(es_lat, 0.95), 2),
            "p99Ms": round(percentile(es_lat, 0.99), 2),
        },
        "milvus": {
            "count": mc,
            "p50Ms": round(statistics.median(mv_lat), 2),
            "p95Ms": round(percentile(mv_lat, 0.95), 2),
            "p99Ms": round(percentile(mv_lat, 0.99), 2),
        },
    }
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    await es.client.aclose()
    await mv.client.aclose()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--elasticsearch", default="http://127.0.0.1:9200")
    ap.add_argument("--milvus", default="http://127.0.0.1:19530")
    ap.add_argument("--documents", type=int, default=10000)
    ap.add_argument("--queries", type=int, default=300)
    ap.add_argument("--batch", type=int, default=500)
    ap.add_argument("--dim", type=int, default=384)
    ap.add_argument("--topk", type=int, default=10)
    ap.add_argument("--seed", type=int, default=20260915)
    ap.add_argument("--kb", type=int, default=99991)
    ap.add_argument("--output", default="performance/results/release-benchmark.json")
    asyncio.run(run(ap.parse_args()))
