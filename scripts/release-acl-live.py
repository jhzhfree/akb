#!/usr/bin/env python3
"""Live ACL leakage qualification for Elasticsearch and Milvus.

Seeds public/team-a/team-b objects into a dedicated green schema and verifies that
anonymous and tagged callers never receive unauthorized object ids.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
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
    schema = "acl_rq_%d" % int(time.time())
    vector = [0.01] * args.dim
    docs = [
        {
            "id": "ACL_PUBLIC",
            "knowledge_base_id": args.kb,
            "object_type": "CARD",
            "title": "acl qualification",
            "content": "shared release procedure",
            "status": "APPROVED",
            "permission_tags": [],
            "index_version": 1,
            "vector": vector,
        },
        {
            "id": "ACL_A",
            "knowledge_base_id": args.kb,
            "object_type": "CARD",
            "title": "acl qualification",
            "content": "team a secret release procedure",
            "status": "APPROVED",
            "permission_tags": ["team-a"],
            "index_version": 1,
            "vector": vector,
        },
        {
            "id": "ACL_B",
            "knowledge_base_id": args.kb,
            "object_type": "CARD",
            "title": "acl qualification",
            "content": "team b secret release procedure",
            "status": "APPROVED",
            "permission_tags": ["team-b"],
            "index_version": 1,
            "vector": vector,
        },
    ]
    await es.rebuild_target(docs, schema)
    await mv.rebuild_target(docs, schema)
    await es.activate_target(schema)
    await mv.activate_target(schema)
    cases = [([], {"ACL_PUBLIC"}), (["team-a"], {"ACL_PUBLIC", "ACL_A"}), (["team-b"], {"ACL_PUBLIC", "ACL_B"})]
    failures = []
    evidence = []
    for tags, allowed in cases:
        es_ids = {x["id"] for x in await es.search(args.kb, "acl qualification release procedure", 10, tags, "CARD")}
        mv_ids = {x["id"] for x in await mv.search(args.kb, vector, 10, tags, "CARD")}
        for provider, ids in [("elasticsearch", es_ids), ("milvus", mv_ids)]:
            leaked = sorted(ids - allowed)
            missing_public = "ACL_PUBLIC" not in ids
            evidence.append({"provider": provider, "tags": tags, "ids": sorted(ids), "leaked": leaked})
            if leaked or missing_public:
                failures.append({"provider": provider, "tags": tags, "leaked": leaked, "missingPublic": missing_public})
    result = {"qualified": not failures, "schema": schema, "failures": failures, "evidence": evidence}
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    await es.client.aclose()
    await mv.client.aclose()
    if failures:
        raise SystemExit(2)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--elasticsearch", default="http://127.0.0.1:9200")
    ap.add_argument("--milvus", default="http://127.0.0.1:19530")
    ap.add_argument("--kb", type=int, default=99992)
    ap.add_argument("--dim", type=int, default=384)
    ap.add_argument("--output", default="performance/results/acl-qualification.json")
    asyncio.run(main(ap.parse_args()))
