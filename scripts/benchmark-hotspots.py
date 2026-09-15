#!/usr/bin/env python3
"""Small local regression benchmark. Results are directional, not release SLOs."""
import json
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-service"))

from app.services.conflict_detector import ConflictDetector
from app.storage.index_store import IndexStore


def benchmark_index_cache():
    with tempfile.TemporaryDirectory() as directory:
        store = IndexStore()
        store._path = lambda kb: Path(directory) / ("kb_%s.json" % kb)
        docs = [
            {"id": str(i), "title": "title", "content": "knowledge %s" % i, "vector": [0.1] * 64}
            for i in range(5000)
        ]
        store.save(1, docs)
        started = time.perf_counter()
        for _ in range(100):
            store.load(1)
        cached_ms = (time.perf_counter() - started) * 1000

        path = store._path(1)
        started = time.perf_counter()
        for _ in range(10):
            json.loads(path.read_text(encoding="utf-8"))
        raw_ms = (time.perf_counter() - started) * 1000
        return cached_ms, raw_ms


class CountingDetector(ConflictDetector):
    def __init__(self):
        self.compares = 0

    def compare(self, left, right):
        self.compares += 1
        return super().compare(left, right)


def benchmark_conflicts():
    claims = [
        {
            "claim_code": "C%s" % i,
            "subject": "S%s" % (i % 1000),
            "predicate": "IS",
            "object": "V%s" % (i % 2),
        }
        for i in range(5000)
    ]
    detector = CountingDetector()
    detector.detect(claims, focus_codes=["C1"])
    naive_pairs = len(claims) * (len(claims) - 1) // 2
    return detector.compares, naive_pairs


if __name__ == "__main__":
    cached_ms, raw_ms = benchmark_index_cache()
    comparisons, naive_pairs = benchmark_conflicts()
    print("index cache: 100 cached loads %.2f ms; 10 raw JSON loads %.2f ms" % (cached_ms, raw_ms))
    print("conflict comparisons: optimized=%d naive=%d" % (comparisons, naive_pairs))
