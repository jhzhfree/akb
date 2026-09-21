#!/usr/bin/env python3
"""Concurrent-query blue/green switch and rollback qualification."""

import argparse
import asyncio
import json
import os
import sys
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
    failures = []
    stop = False

    async def query_loop():
        while not stop:
            try:
                await es.search(args.kb, "release qualification", 5, ["ops"], "CARD")
                await mv.search(args.kb, [0.01] * args.dim, 5, ["ops"], "CARD")
            except Exception as exc:
                failures.append(repr(exc))
            await asyncio.sleep(0.02)

    task = asyncio.create_task(query_loop())
    old = args.old_schema
    new = args.new_schema
    await asyncio.sleep(0.5)
    await es.activate_target(new)
    await mv.activate_target(new)
    await asyncio.sleep(0.5)
    await es.activate_target(old)
    await mv.activate_target(old)
    await asyncio.sleep(0.5)
    stop = True
    await task
    result = {
        "qualified": not failures,
        "queryFailures": len(failures),
        "sampleFailures": failures[:5],
        "switchedTo": new,
        "rolledBackTo": old,
    }
    Path(args.output).write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    await es.client.aclose()
    await mv.client.aclose()
    if failures:
        raise SystemExit(2)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--elasticsearch", default="http://127.0.0.1:9200")
    ap.add_argument("--milvus", default="http://127.0.0.1:19530")
    ap.add_argument("--kb", type=int, default=99991)
    ap.add_argument("--dim", type=int, default=384)
    ap.add_argument("--old-schema", required=True)
    ap.add_argument("--new-schema", required=True)
    asyncio.run(main(ap.parse_args()))
