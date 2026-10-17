#!/usr/bin/env python3
"""Export the five validation knowledge bases as a loadable default-data image.

Run after the dataset has been imported and validated:

    python3 scripts/export_default_kb_image.py 6 7 8 9 10

The image is MySQL rows plus the uploaded source files. The vector/lexical/graph
indexes are not copied: they are rebuildable views, and loading the image ends with
a blue/green rebuild so the new environment ends up with its own indexes.
"""
import os
import subprocess
import sys

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "deploy", "seed", "enterprise-kb-demo")
SOURCE_FILES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "backend", "data", "source-files")
MYSQL = ["docker", "exec", "kos-dev-mysql", "mysqldump", "-uknowledge", "-pknowledge123",
         "--single-transaction", "--skip-extended-insert", "--no-create-info",
         "--no-tablespaces", "--skip-triggers", "knowledge_os"]

# Tables that carry the knowledge base id directly.
KB_TABLES = [
    "t_kb_scenario", "t_chunk_policy", "t_source", "t_claim",
    "t_knowledge_card", "t_evidence", "t_exact_evidence_binding", "t_entity",
    "t_entity_alias", "t_entity_facet", "t_entity_acl_scope", "t_relation",
    "t_relation_facet", "t_relation_acl_scope", "t_knowledge_conflict", "t_adjudication_assessment",
    "t_knowledge_review", "t_knowledge_version", "t_derivation_rule",
    "t_index_outbox", "t_index_schema_release", "t_index_schema_snapshot_audit",
    "t_index_acl_manifest", "t_index_graph_manifest", "t_index_graph_evidence_manifest",
    "t_index_graph_facet_manifest", "t_ingestion_run", "t_task_run", "t_schema_release",
    "t_schema_type", "t_connector_source",
]

# Tables reached through one hop from a knowledge-base-scoped row.
LINKED_TABLES = {
    "t_source_version": "source_id in (select id from t_source where knowledge_base_id between {a} and {b})",
    "t_document": "source_version_id in (select id from t_source_version where source_id in "
                  "(select id from t_source where knowledge_base_id between {a} and {b}))",
    "t_content_unit": "document_id in (select id from t_document where source_version_id in "
                      "(select id from t_source_version where source_id in "
                      "(select id from t_source where knowledge_base_id between {a} and {b})))",
    "t_claim_evidence": "claim_code in (select claim_code from t_claim where knowledge_base_id between {a} and {b})",
    "t_claim_entity": "claim_code in (select claim_code from t_claim where knowledge_base_id between {a} and {b})",
    "t_card_entity": "knowledge_code in (select knowledge_code from t_knowledge_card "
                     "where knowledge_base_id between {a} and {b})",
    "t_knowledge_evidence": "knowledge_code in (select knowledge_code from t_knowledge_card "
                            "where knowledge_base_id between {a} and {b})",
    "t_ingestion_event": "run_id in (select id from t_ingestion_run where knowledge_base_id between {a} and {b})",
    "t_entity_facet_evidence": "evidence_id in (select id from t_evidence where knowledge_base_id between {a} and {b})",
    "t_relation_facet_evidence": "evidence_id in (select id from t_evidence where knowledge_base_id between {a} and {b})",
}


def has_column(table, column):
    query = ("select count(*) from information_schema.columns where table_schema='knowledge_os' "
             "and table_name='%s' and column_name='%s'" % (table, column))
    out = subprocess.run(["docker", "exec", "kos-dev-mysql", "mysql", "-N", "-uknowledge",
                          "-pknowledge123", "-e", query], capture_output=True, text=True, check=True)
    return out.stdout.strip().endswith("1")


def dump(table, where, target):
    cmd = MYSQL + [table, "--where=" + where]
    with open(target, "a", encoding="utf-8") as handle:
        done = subprocess.run(cmd, stdout=handle, stderr=subprocess.PIPE)
    if done.returncode != 0:
        raise RuntimeError("导出 %s 失败: %s" % (table, done.stderr.decode("utf-8", "replace")[:300]))


def main():
    kbs = [int(x) for x in sys.argv[1:]] or [6, 7, 8, 9, 10]
    low, high = min(kbs), max(kbs)
    os.makedirs(OUT, exist_ok=True)
    sql_path = os.path.join(OUT, "knowledge_data.sql")
    open(sql_path, "w", encoding="utf-8").write(
        "-- Default knowledge data: five scenario knowledge bases built from\n"
        "-- dataset/enterprise_kb_validation_5sets (synthetic, non-production).\n"
        "-- Loaded by load.sh; indexes are rebuilt after loading, not copied.\n")
    # The workspace table is keyed by id, every derived table by knowledge_base_id.
    dump("t_knowledge_base", "id between %d and %d" % (low, high), sql_path)
    for table in KB_TABLES:
        if not has_column(table, "knowledge_base_id"):
            print("跳过（无 knowledge_base_id 列）:", table)
            continue
        dump(table, "knowledge_base_id between %d and %d" % (low, high), sql_path)
    for table, where in LINKED_TABLES.items():
        dump(table, where.format(a=low, b=high), sql_path)
    files_dir = os.path.join(OUT, "source-files")
    os.makedirs(files_dir, exist_ok=True)
    for kb in kbs:
        src = os.path.join(SOURCE_FILES, str(kb))
        if os.path.isdir(src):
            subprocess.run(["cp", "-r", src, files_dir], check=True)
    print("导出完成:", sql_path, os.path.getsize(sql_path), "字节")
    print("源文件:", files_dir)


if __name__ == "__main__":
    main()
