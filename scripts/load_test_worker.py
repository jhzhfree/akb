#!/usr/bin/env python3
import argparse
import asyncio
import gc
import json
import math
import os
import sys
import tempfile
import time
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-service"))

from app.services.query_engine import QueryEngine
from app.services.retrieval.hybrid import HybridRetriever
from app.storage.index_store import store

PROC = psutil.Process(os.getpid())


def rss_mb():
    return PROC.memory_info().rss / 1024 / 1024


def percentile(values, p):
    if not values:
        return None
    vals = sorted(values)
    if len(vals) == 1:
        return vals[0]
    k = (len(vals) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return vals[int(k)]
    return vals[f] * (c - k) + vals[c] * (k - f)


def make_vector(i, dim=384):
    # Sparse, normalized vector: close to the current hash embedding shape.
    v = [0.0] * dim
    positions = [(i * 17 + j * 43) % dim for j in range(12)]
    val = 1.0 / math.sqrt(len(positions))
    for j, pos in enumerate(positions):
        v[pos] = val if j % 2 == 0 else -val
    return v


def make_doc(i, vector):
    kinds = ("CONTENT_UNIT", "CONTENT_UNIT", "CARD", "CLAIM", "CONTENT_UNIT")
    obj = kinds[i % len(kinds)]
    topic = i % 1000
    # Chinese text intentionally exercises the current per-character tokenizer.
    content = (
        "企业知识库项目管理流程要求证据来源可追溯。"
        "Tomcat证书部署需要检查版本配置权限条件和回滚方案。"
        "知识裁决需要比较来源权威性新鲜度冲突状态和支持证据。"
        "检索系统使用向量全文知识卡片断言和图关系进行融合排序。"
        "本条知识主题编号%d，场景编号%d。" % (topic, i % 97)
    )
    return {
        "id": str(i),
        "object_type": obj,
        "title": "Tomcat证书部署与知识治理 %d" % topic,
        "content": content,
        "vector": vector,
        "document_id": i // 20,
        "content_unit_id": i,
        "source_name": "synthetic-load-source-%d" % (i % 50),
        "source_uri": "load://source/%d" % (i % 50),
        "page_no": (i % 80) + 1,
        "status": "EFFECTIVE",
        "permission_tags": [],
    }


async def timed(coro):
    t = time.perf_counter()
    result = await coro
    return (time.perf_counter() - t) * 1000, result


async def run(scale, repeats, concurrency):
    with tempfile.TemporaryDirectory(prefix="llmkb-load-") as td:
        path = Path(td) / ("kb_%s.json" % scale)
        store._path = lambda kb: path
        store._cache.clear()
        store._generation.clear()

        baseline = rss_mb()
        docs = []
        shared_vectors = [make_vector(j) for j in range(1000)]
        t = time.perf_counter()
        for i in range(scale):
            # 1000 vector patterns avoid the unrealistic one-vector special case while
            # keeping data generation memory below the query index itself.
            docs.append(make_doc(i, shared_vectors[i % 1000]))
        generation_ms = (time.perf_counter() - t) * 1000

        t = time.perf_counter()
        store.save(scale, docs)
        save_ms = (time.perf_counter() - t) * 1000
        file_mb = path.stat().st_size / 1024 / 1024

        # Force a cold JSON parse to measure the actual fallback index memory representation.
        del docs, shared_vectors
        store._cache.clear()
        gc.collect()
        pre_load_rss = rss_mb()
        t = time.perf_counter()
        loaded = store.load(scale)
        cold_load_ms = (time.perf_counter() - t) * 1000
        loaded_rss = rss_mb()
        assert len(loaded) == scale

        retriever = HybridRetriever()
        query = "如何完成Tomcat证书部署流程和知识裁决"
        # First BM25 builds LexicalSnapshot.
        t = time.perf_counter()
        retriever._bm25(scale, query, 20)
        lexical_build_ms = (time.perf_counter() - t) * 1000
        lexical_rss = rss_mb()

        bm25 = []
        for _ in range(repeats):
            t = time.perf_counter()
            retriever._bm25(scale, query, 20)
            bm25.append((time.perf_counter() - t) * 1000)

        vector = []
        for _ in range(max(2, min(repeats, 4))):
            ms, _ = await timed(retriever._vector(scale, query, 20))
            vector.append(ms)

        engine = QueryEngine()
        # Reuse warmed lexical snapshot by swapping in retriever.
        engine.retriever = retriever
        full = []
        for i in range(max(2, min(repeats, 4))):
            ms, res = await timed(engine.query("q-%d" % i, scale, query, 10))
            full.append(ms)
            assert res["candidates"]

        async def one(i):
            ms, res = await timed(engine.query("cq-%d" % i, scale, query, 10))
            return ms

        t = time.perf_counter()
        concurrent_lats = await asyncio.gather(*[one(i) for i in range(concurrency)])
        wall = (time.perf_counter() - t) * 1000

        return {
            "scale": scale,
            "embedding_dim": 384,
            "content_chars": len(make_doc(0, make_vector(0))["content"]),
            "index_file_mb": round(file_mb, 2),
            "generation_ms": round(generation_ms, 2),
            "save_ms": round(save_ms, 2),
            "cold_load_ms": round(cold_load_ms, 2),
            "lexical_build_ms": round(lexical_build_ms, 2),
            "rss_mb": {
                "baseline": round(baseline, 2),
                "pre_load": round(pre_load_rss, 2),
                "after_load": round(loaded_rss, 2),
                "after_lexical": round(lexical_rss, 2),
                "index_delta": round(loaded_rss - pre_load_rss, 2),
                "lexical_delta": round(lexical_rss - loaded_rss, 2),
                "total_delta": round(lexical_rss - pre_load_rss, 2),
            },
            "bm25_cached_ms": {
                "p50": round(percentile(bm25, 0.50), 2),
                "p95": round(percentile(bm25, 0.95), 2),
                "samples": [round(x, 2) for x in bm25],
            },
            "vector_scan_ms": {
                "p50": round(percentile(vector, 0.50), 2),
                "p95": round(percentile(vector, 0.95), 2),
                "samples": [round(x, 2) for x in vector],
            },
            "full_query_mock_ms": {
                "p50": round(percentile(full, 0.50), 2),
                "p95": round(percentile(full, 0.95), 2),
                "samples": [round(x, 2) for x in full],
            },
            "concurrency": {
                "workers": concurrency,
                "wall_ms": round(wall, 2),
                "throughput_qps": round(concurrency / (wall / 1000), 2),
                "latency_p50_ms": round(percentile(concurrent_lats, 0.5), 2),
                "latency_p95_ms": round(percentile(concurrent_lats, 0.95), 2),
                "samples": [round(x, 2) for x in concurrent_lats],
            },
        }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scale", type=int, required=True)
    ap.add_argument("--repeats", type=int, default=5)
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    result = asyncio.run(run(args.scale, args.repeats, args.concurrency))
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
