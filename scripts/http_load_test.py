#!/usr/bin/env python3
"""Black-box HTTP load test for a deployed llmkb backend.

Example:
  python scripts/http_load_test.py --base-url http://127.0.0.1:8080 \
      --kb 1 --requests 100 --concurrency 8

Use this after enabling the real MySQL/Neo4j/Ollama/BGE stack. The local capacity
benchmark intentionally does not fake those external runtimes.
"""
import argparse
import asyncio
import json
import math
import statistics
import time
import uuid

import httpx


def percentile(values, p):
    values = sorted(values)
    if not values:
        return None
    k = (len(values) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return values[int(k)]
    return values[f] * (c - k) + values[c] * (k - f)


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--kb", type=int, default=1)
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--query", default="如何完成Tomcat证书部署流程和知识裁决？")
    parser.add_argument("--out", default="performance/results/http-load.json")
    args = parser.parse_args()

    semaphore = asyncio.Semaphore(max(1, args.concurrency))
    latencies = []
    failures = []
    stage_latencies = []

    async with httpx.AsyncClient(base_url=args.base_url.rstrip("/"), timeout=args.timeout) as client:
        async def one(index):
            trace_id = "load-%s-%d" % (uuid.uuid4().hex[:12], index)
            payload = {
                "knowledgeBaseId": args.kb,
                "query": args.query,
                "topK": args.top_k,
            }
            async with semaphore:
                started = time.perf_counter()
                try:
                    response = await client.post(
                        "/api/knowledge/query",
                        json=payload,
                        headers={"X-Trace-Id": trace_id},
                    )
                    elapsed = (time.perf_counter() - started) * 1000
                    latencies.append(elapsed)
                    if response.status_code >= 400:
                        failures.append({"index": index, "status": response.status_code, "body": response.text[:300]})
                        return
                    body = response.json()
                    data = body.get("data") if isinstance(body, dict) else None
                    trace = (data or {}).get("trace") if isinstance(data, dict) else None
                    if isinstance(trace, dict):
                        stage_latencies.append(trace)
                except Exception as exc:
                    elapsed = (time.perf_counter() - started) * 1000
                    latencies.append(elapsed)
                    failures.append({"index": index, "error": str(exc)})

        started = time.perf_counter()
        await asyncio.gather(*[one(i) for i in range(args.requests)])
        wall = time.perf_counter() - started

    successful = args.requests - len(failures)
    result = {
        "requests": args.requests,
        "successful": successful,
        "failed": len(failures),
        "concurrency": args.concurrency,
        "wallSeconds": round(wall, 3),
        "throughputQps": round(successful / wall, 3) if wall else None,
        "latencyMs": {
            "p50": round(percentile(latencies, 0.50), 2) if latencies else None,
            "p95": round(percentile(latencies, 0.95), 2) if latencies else None,
            "p99": round(percentile(latencies, 0.99), 2) if latencies else None,
            "max": round(max(latencies), 2) if latencies else None,
            "mean": round(statistics.mean(latencies), 2) if latencies else None,
        },
        "traceSamples": stage_latencies[:20],
        "failures": failures[:20],
    }
    from pathlib import Path
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
