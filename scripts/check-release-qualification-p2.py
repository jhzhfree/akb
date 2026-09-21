#!/usr/bin/env python3
"""Static contract for Release Qualification P2/P3 assets."""

from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
dev = yaml.safe_load((ROOT / "docker-compose.yml").read_text())
services = dev["services"]
for name in ["elasticsearch", "milvus", "milvus-etcd", "milvus-minio"]:
    assert name in services, "missing service " + name
assert services["milvus"]["environment"]["ETCD_ENDPOINTS"] == "milvus-etcd:2379"
assert services["milvus"]["environment"]["MINIO_ADDRESS"] == "milvus-minio:9000"
assert "healthz" in " ".join(services["milvus"]["healthcheck"]["test"])
for script in [
    "release-qualification-benchmark.py",
    "release-quality-eval.py",
    "release-fault-injection.sh",
    "release-blue-green-live.py",
    "release-go-no-go.py",
]:
    assert (ROOT / "scripts" / script).exists(), "missing " + script
bench = (ROOT / "scripts/release-qualification-benchmark.py").read_text()
assert "for start in range(0,args.documents,args.batch)" in bench
assert "p99Ms" in bench
print("Release Qualification P2 contract passed")
