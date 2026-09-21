#!/usr/bin/env python3
import argparse
import asyncio
import json
import math
import os
import sys
import time
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-service"))
from app.services.retrieval.hybrid import HybridRetriever
from app.storage.index_store import store

PROC = psutil.Process(os.getpid())


def rss():
    return PROC.memory_info().rss / 1024 / 1024


def vec(i, dim=384):
    v = [0.0] * dim
    val = 1 / math.sqrt(12)
    for j in range(12):
        v[(i * 17 + j * 43) % dim] = val if j % 2 == 0 else -val
    return v


def doc(i, v):
    content = (
        "企业知识库项目管理流程要求证据来源可追溯。Tomcat证书部署需要检查版本配置权限条件和回滚方案。知识裁决需要比较来源权威性新鲜度冲突状态和支持证据。检索系统使用向量全文知识卡片断言和图关系进行融合排序。本条知识主题编号%d，场景编号%d。"
        % (i % 1000, i % 97)
    )
    kinds = ("CONTENT_UNIT", "CONTENT_UNIT", "CARD", "CLAIM", "CONTENT_UNIT")
    return {
        "id": str(i),
        "object_type": kinds[i % 5],
        "title": "Tomcat证书部署与知识治理 %d" % (i % 1000),
        "content": content,
        "vector": v,
        "document_id": i // 20,
        "content_unit_id": i,
        "source_name": "synthetic-load-source-%d" % (i % 50),
        "source_uri": "load://source/%d" % (i % 50),
        "page_no": i % 80 + 1,
        "status": "EFFECTIVE",
        "permission_tags": [],
    }


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("phase", choices=["create", "lexical", "vector"])
    ap.add_argument("--path", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    path = Path(args.path)
    store._path = lambda kb: path
    store._cache.clear()
    store._generation.clear()
    result = {"phase": args.phase, "rss_start_mb": round(rss(), 2)}
    if args.phase == "create":
        shared = [vec(j) for j in range(1000)]
        t = time.perf_counter()
        docs = [doc(i, shared[i % 1000]) for i in range(100000)]
        result["generate_ms"] = round((time.perf_counter() - t) * 1000, 2)
        result["rss_generated_mb"] = round(rss(), 2)
        t = time.perf_counter()
        store.save(100000, docs)
        result["save_ms"] = round((time.perf_counter() - t) * 1000, 2)
        result["file_mb"] = round(path.stat().st_size / 1024 / 1024, 2)
    elif args.phase == "lexical":
        t = time.perf_counter()
        docs = store.load(100000)
        result["cold_load_ms"] = round((time.perf_counter() - t) * 1000, 2)
        result["rss_loaded_mb"] = round(rss(), 2)
        result["count"] = len(docs)
        r = HybridRetriever()
        q = "如何完成Tomcat证书部署流程和知识裁决"
        t = time.perf_counter()
        r._bm25(100000, q, 20)
        result["first_bm25_ms"] = round((time.perf_counter() - t) * 1000, 2)
        result["rss_lexical_mb"] = round(rss(), 2)
        t = time.perf_counter()
        r._bm25(100000, q, 20)
        result["cached_bm25_ms"] = round((time.perf_counter() - t) * 1000, 2)
    elif args.phase == "vector":
        t = time.perf_counter()
        docs = store.load(100000)
        result["cold_load_ms"] = round((time.perf_counter() - t) * 1000, 2)
        result["rss_loaded_mb"] = round(rss(), 2)
        result["count"] = len(docs)
        r = HybridRetriever()
        q = "如何完成Tomcat证书部署流程和知识裁决"
        t = time.perf_counter()
        await r._vector(100000, q, 20)
        result["vector_first_ms"] = round((time.perf_counter() - t) * 1000, 2)
        result["rss_after_vector_mb"] = round(rss(), 2)
        t = time.perf_counter()
        await r._vector(100000, q, 20)
        result["vector_warm_ms"] = round((time.perf_counter() - t) * 1000, 2)
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
