#!/usr/bin/env python3
"""Generate the reviewable skeleton for a production retrieval-quality dataset."""

import argparse
import json
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--output", default="performance/datasets/release-quality-template.jsonl")
a = ap.parse_args()
scenarios = [
    "fact",
    "sop",
    "exact_error_code",
    "card",
    "claim",
    "relation",
    "multi_hop",
    "conflict_version",
    "acl",
    "no_answer",
]
rows = []
for i, s in enumerate(scenarios, 1):
    rows.append(
        {
            "id": "RQ-%03d" % i,
            "scenario": s,
            "query": "REPLACE_WITH_REVIEWED_QUERY",
            "relevantIds": ["REPLACE_WITH_GOLD_OBJECT_ID"],
            "forbiddenIds": [],
            "callerPermissionTags": [],
            "notes": "Human-reviewed gold set required before production qualification.",
        }
    )
p = Path(a.output)
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in rows) + "\n")
print(p)
