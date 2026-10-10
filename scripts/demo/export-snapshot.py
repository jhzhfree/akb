#!/usr/bin/env python3
"""Export the demo showcase as a restorable snapshot.

The snapshot is everything a fresh deployment needs to look "already in use":
MySQL authoritative state, Elasticsearch full-text index, Milvus vectors,
Neo4j graph and the ingested source files. ``restore.sh`` replays it without
Python, so it can run inside the backend image (bash + curl + mysql client).

Usage
-----
    python3 scripts/demo/export-snapshot.py                     # -> backend/demo-seed
    python3 scripts/demo/export-snapshot.py --out /tmp/seed
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import requests

REPO = Path(__file__).resolve().parent.parent.parent

MYSQL_CONTAINER = "kos-dev-mysql"
MYSQL_USER = "root"
MYSQL_PASSWORD = "root"
MYSQL_DB = "knowledge_os"
ES_URL = "http://127.0.0.1:9200"
ES_USER = "elastic"
ES_PASSWORD = "eucwdo0h4IlxoHdErDsa"
ES_INDICES = ["llmkb_knowledge_v1", "llmkb_standard_doc_v1"]
MILVUS_URL = "http://127.0.0.1:19530"
MILVUS_COLLECTION = "llmkb_knowledge_v1"
NEO4J_URL = "http://127.0.0.1:7474"
NEO4J_USER = "neo4j"
NEO4J_PASSWORD = "knowledge123"
SOURCE_FILES = REPO / "backend" / "data" / "source-files"

ES_PAGE = 2000
MILVUS_PAGE = 500
# 向量保留 6 位小数：检索相似度不受影响，JSON 体积约为原始的一半。
VECTOR_NDIGITS = 6


def log(msg: str) -> None:
    print("[export] %s" % msg, flush=True)


# --------------------------------------------------------------------- MySQL
def export_mysql(out: Path) -> dict[str, Any]:
    target = out / "mysql" / "knowledge_os.sql"
    target.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "docker", "exec", MYSQL_CONTAINER, "mysqldump",
        "--default-character-set=utf8mb4",
        "-u%s" % MYSQL_USER, "-p%s" % MYSQL_PASSWORD,
        "--single-transaction", "--skip-lock-tables", "--no-create-info",
        "--ignore-table=%s.flyway_schema_history" % MYSQL_DB,
        MYSQL_DB,
    ]
    with target.open("w", encoding="utf-8") as handle:
        subprocess.run(cmd, stdout=handle, check=True, stderr=subprocess.PIPE)
    log("MySQL -> %s（%.1f MB）" % (target, target.stat().st_size / 1e6))
    return {"file": "mysql/knowledge_os.sql", "bytes": target.stat().st_size}


# ------------------------------------------------------------------- ES
def export_es(out: Path) -> list[dict[str, Any]]:
    auth = (ES_USER, ES_PASSWORD)
    session = requests.Session()
    result = []
    for index in ES_INDICES:
        resp = session.get("%s/%s" % (ES_URL, index), auth=auth, timeout=30)
        if resp.status_code != 200:
            log("ES 索引不存在，跳过：%s" % index)
            continue
        mapping = resp.json()[index]["mappings"]
        mapping = {"mappings": mapping}
        mapping_file = out / "es" / ("%s.mapping.json" % index)
        mapping_file.parent.mkdir(parents=True, exist_ok=True)
        mapping_file.write_text(json.dumps(mapping, ensure_ascii=False), encoding="utf-8")

        count = session.get("%s/%s/_count" % (ES_URL, index), auth=auth, timeout=30).json().get("count", 0)
        log("ES %s：%d 篇文档" % (index, count))
        written = 0
        part = 0
        search_after = None
        while written < count:
            body: dict[str, Any] = {"size": ES_PAGE, "query": {"match_all": {}},
                                    "sort": [{"_doc": "asc"}]}
            if search_after:
                body["search_after"] = search_after
            resp = session.post("%s/%s/_search" % (ES_URL, index), auth=auth, json=body, timeout=120)
            hits = resp.json().get("hits", {}).get("hits", [])
            if not hits:
                break
            part += 1
            bulk_file = out / "es" / ("%s.bulk-%04d.ndjson" % (index, part))
            with bulk_file.open("w", encoding="utf-8") as fh:
                for hit in hits:
                    fh.write(json.dumps({"index": {"_index": index, "_id": hit["_id"]}}, ensure_ascii=False))
                    fh.write("\n")
                    fh.write(json.dumps(hit["_source"], ensure_ascii=False))
                    fh.write("\n")
            written += len(hits)
            search_after = hits[-1].get("sort")
            log("  %s 第 %d 片：%d 条（累计 %d/%d）" % (index, part, len(hits), written, count))
        result.append({"index": index, "docs": written, "parts": part,
                       "mapping": "es/%s.mapping.json" % index})
    return result


# ---------------------------------------------------------------- Milvus
def export_milvus(out: Path) -> dict[str, Any]:
    collection_dir = out / "milvus" / MILVUS_COLLECTION
    collection_dir.mkdir(parents=True, exist_ok=True)
    describe = requests.post("%s/v2/vectordb/collections/describe" % MILVUS_URL,
                             json={"collectionName": MILVUS_COLLECTION}, timeout=30).json()
    data = describe.get("data") or {}
    fields = [f.get("name") for f in data.get("fields") or []]
    dimension = 0
    for f in data.get("fields") or []:
        if f.get("type") == "FloatVector":
            for p in f.get("params") or []:
                if p.get("key") == "dim":
                    dimension = int(p.get("value"))
    indexes = data.get("indexes") or []
    metric = "COSINE"
    if indexes:
        for p in (indexes[0].get("params") or []):
            if p.get("key") == "metric_type":
                metric = p.get("value")
    (collection_dir / "schema.json").write_text(json.dumps(
        {"collectionName": MILVUS_COLLECTION, "dimension": dimension, "metricType": metric,
         "primaryField": "id", "vectorField": "vector", "fields": fields,
         "enableDynamicField": data.get("enableDynamicField", True)},
        ensure_ascii=False, indent=2), encoding="utf-8")

    output_fields = [f for f in fields if f != "vector"] + ["vector"]
    total = 0
    part = 0
    offset = 0
    while True:
        payload = {"collectionName": MILVUS_COLLECTION, "filter": "",
                   "outputFields": output_fields, "limit": MILVUS_PAGE, "offset": offset}
        resp = requests.post("%s/v2/vectordb/entities/query" % MILVUS_URL, json=payload, timeout=180)
        body = resp.json()
        rows = body.get("data") or []
        if not rows:
            break
        for row in rows:
            vec = row.get("vector")
            if isinstance(vec, list):
                row["vector"] = [round(float(v), VECTOR_NDIGITS) for v in vec]
        part += 1
        part_file = collection_dir / ("insert-%04d.json" % part)
        part_file.write_text(json.dumps({"collectionName": MILVUS_COLLECTION, "data": rows},
                                        ensure_ascii=False), encoding="utf-8")
        total += len(rows)
        offset += len(rows)
        log("  Milvus 第 %d 片：%d 条（累计 %d）" % (part, len(rows), total))
        if len(rows) < MILVUS_PAGE:
            break
    log("Milvus -> %d 条向量（dim=%d, metric=%s）" % (total, dimension, metric))
    return {"collection": MILVUS_COLLECTION, "rows": total, "parts": part,
            "dimension": dimension, "metricType": metric}


# ----------------------------------------------------------------- Neo4j
def neo4j_query(statement: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    payload: dict[str, Any] = {"statement": statement}
    if params:
        payload["parameters"] = params
    resp = requests.post("%s/db/neo4j/query/v2" % NEO4J_URL, auth=(NEO4J_USER, NEO4J_PASSWORD),
                         json=payload, timeout=300)
    body = resp.json()
    if "errors" in body and body["errors"]:
        raise SystemExit("Neo4j 查询失败：%s" % body["errors"][0].get("message"))
    return body.get("data") or {}


def export_neo4j(out: Path) -> dict[str, Any]:
    graph_dir = out / "neo4j"
    graph_dir.mkdir(parents=True, exist_ok=True)

    labels = [row[0][0] for row in neo4j_query(
        "MATCH (n) RETURN DISTINCT labels(n) AS l")["values"] if row[0]]
    rel_types = [row[0] for row in neo4j_query(
        "MATCH ()-[r]->() RETURN DISTINCT type(r) AS t")["values"]]
    log("Neo4j：标签 %s；关系 %s" % (labels, rel_types))

    files = []
    for label in labels:
        rows = neo4j_query(
            "MATCH (n:`%s`) RETURN properties(n) AS p" % label)["values"]
        props = [row[0] for row in rows]
        # 优先用 code 作为幂等键，退化时用 name，最后退化为全属性匹配。
        key = "code"
        if props and not all(isinstance(p.get("code"), str) and p.get("code") for p in props):
            key = "name" if all(isinstance(p.get("name"), str) and p.get("name") for p in props) else ""
        target = graph_dir / ("nodes-%s.json" % label)
        target.write_text(json.dumps(
            {"statement": "UNWIND $rows AS r MERGE (n:`%s` {%s: r.%s}) SET n = r"
                          % (label, key or "code", key or "code"),
             "parameters": {"rows": props}}, ensure_ascii=False), encoding="utf-8")
        files.append({"file": "neo4j/nodes-%s.json" % label, "kind": "nodes", "label": label,
                      "count": len(props), "key": key or "code"})
        log("  节点 %s：%d 个（键 %s）" % (label, len(props), key or "code"))

    for rel_type in rel_types:
        rows = neo4j_query(
            "MATCH (a)-[r:`%s`]->(b) RETURN a.code AS from, b.code AS to, properties(r) AS p"
            % rel_type)["values"]
        payload = [{"from": row[0], "to": row[1], "props": row[2]} for row in rows]
        target = graph_dir / ("rels-%s.json" % rel_type)
        target.write_text(json.dumps(
            {"statement": "UNWIND $rows AS r MATCH (a {code: r.from}), (b {code: r.to}) "
                          "MERGE (a)-[rel:`%s`]->(b) SET rel = r.props" % rel_type,
             "parameters": {"rows": payload}}, ensure_ascii=False), encoding="utf-8")
        files.append({"file": "neo4j/rels-%s.json" % rel_type, "kind": "rels",
                      "type": rel_type, "count": len(payload)})
        log("  关系 %s：%d 条" % (rel_type, len(payload)))
    return {"files": files}


# ------------------------------------------------------------ 源文件
def export_source_files(out: Path) -> dict[str, Any] | None:
    if not SOURCE_FILES.exists() or not any(SOURCE_FILES.iterdir()):
        log("源文件目录为空，跳过")
        return None
    target = out / "source-files.tar.gz"
    subprocess.run(["tar", "czf", str(target), "-C", str(SOURCE_FILES.parent), SOURCE_FILES.name],
                   check=True)
    log("源文件 -> %s（%.1f MB）" % (target, target.stat().st_size / 1e6))
    return {"file": "source-files.tar.gz", "bytes": target.stat().st_size}


def main() -> int:
    parser = argparse.ArgumentParser(description="导出演示环境快照")
    parser.add_argument("--out", default=str(REPO / "backend" / "demo-seed"))
    args = parser.parse_args()

    out = Path(args.out)
    if out.exists():
        subprocess.run(["rm", "-rf", str(out)], check=True)
    out.mkdir(parents=True, exist_ok=True)

    manifest: dict[str, Any] = {
        "exportedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "mysql": export_mysql(out),
        "elasticsearch": export_es(out),
        "milvus": export_milvus(out),
        "neo4j": export_neo4j(out),
        "sourceFiles": export_source_files(out),
    }
    # 恢复脚本随快照一起打包：镜像只 COPY demo-seed 目录，脚本缺失会导致 DEMO_SEED 静默失效。
    restore_src = REPO / "scripts" / "demo" / "restore.sh"
    if restore_src.exists():
        dest = out / "restore.sh"
        dest.write_text(restore_src.read_text(encoding="utf-8"), encoding="utf-8")
        dest.chmod(0o755)
        log("恢复脚本 -> %s" % dest)

    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                                       encoding="utf-8")
    total = sum(f.stat().st_size for f in out.rglob("*") if f.is_file())
    log("导出完成：%s（%.1f MB）" % (out, total / 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
