#!/usr/bin/env python3
"""Compute Recall@K, MRR and NDCG from a JSONL retrieval evaluation set."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def dcg(rels):
    return sum(rel / math.log2(i + 2) for i, rel in enumerate(rels))


def evaluate(rows, k):
    recalls = []
    rr = []
    ndcg = []
    for row in rows:
        relevant = set(map(str, row["relevantIds"]))
        retrieved = [str(x) for x in row["retrievedIds"][:k]]
        hits = [1 if x in relevant else 0 for x in retrieved]
        recalls.append(len(relevant.intersection(retrieved)) / max(1, len(relevant)))
        rr.append(next((1 / (i + 1) for i, x in enumerate(retrieved) if x in relevant), 0))
        ideal = [1] * min(k, len(relevant))
        ndcg.append(dcg(hits) / dcg(ideal) if ideal else 0)

    def avg(xs):
        return sum(xs) / max(1, len(xs))

    return {
        "queries": len(rows),
        "k": k,
        "recallAtK": round(avg(recalls), 4),
        "mrr": round(avg(rr), 4),
        "ndcgAtK": round(avg(ndcg), 4),
    }


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset")
    ap.add_argument("--k", type=int, default=10)
    ap.add_argument("--output", default="performance/results/retrieval-quality.json")
    args = ap.parse_args()
    rows = [json.loads(line) for line in Path(args.dataset).read_text().splitlines() if line.strip()]
    result = evaluate(rows, args.k)
    Path(args.output).write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
