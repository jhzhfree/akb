#!/usr/bin/env python3
"""
用例 6：抽取为空时只重跑抽取，不重新解析。

场景很常见：文档解析成功（切片、证据都建好了），但抽取这一步模型没给东西。这时重新导入
一遍是最差的选择——重新解析会生成一批新的证据编码，已经建立的引用全部变成死链，而解析
结果本身并没有问题。

所以要守的是两件事：
  1. 重跑抽取复用已入库的切片与证据编码（切片数不变，证明没有重新解析）；
  2. 连续重跑不产生重复卡片（幂等键兜底）。

断言刻意不依赖"模型这次有没有产出"——模型偶发不给结果是正常的，那是重跑机制存在的
理由，不是用例失败的理由。
"""
import argparse
import mimetypes
import pathlib
import subprocess
import sys
import time

import requests

BASE = "http://127.0.0.1:8080/api"
DOC = pathlib.Path("dataset/enterprise_kb_validation_5sets/01_综合知识库/01_企业知识管理办法_V1.0.docx")


def sql(query: str) -> str:
    return subprocess.run(
        ["docker", "exec", "kos-dev-mysql", "mysql", "-uknowledge", "-pknowledge123", "-D", "knowledge_os", "-e", query],
        capture_output=True, text=True,
    ).stdout


def rows(query: str) -> list:
    """把 mysql -e 的输出解析成字典列表；只用于读取校验，不参与判定。"""
    out = [line for line in sql(query).splitlines() if line and not line.lower().startswith("warning")]
    if len(out) < 2:
        return []
    header = out[0].split("\t")
    return [dict(zip(header, line.split("\t"), strict=True)) for line in out[1:]]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--password", default="1qaz@WSX")
    parser.add_argument("--purge", action="store_true", help="验证结束后删除本次建的库，并校验衍生规则等配置一并清掉")
    args = parser.parse_args()

    session = requests.Session()
    token = session.post(f"{BASE}/auth/login", json={"username": "admin", "password": args.password}, timeout=30).json()
    session.headers["Authorization"] = "Bearer " + (token.get("data") or {}).get("token", "")

    kb = session.post(f"{BASE}/knowledge/bases",
                      json={"name": "回归-只重跑抽取", "visibility": "PUBLIC", "scenario": "GENERAL"},
                      timeout=30).json()["data"]["id"]
    print(f"· 已建库 id={kb}")

    batch = session.post(f"{BASE}/knowledge/documents/batch-upload", timeout=300,
                         files=[("files", (DOC.name, DOC.read_bytes(), mimetypes.guess_type(DOC.name)[0]))],
                         data={"knowledgeBaseId": str(kb)}).json()["data"]["batchCode"]
    for _ in range(60):
        time.sleep(5)
        state = session.get(f"{BASE}/knowledge/documents/batch/{batch}", timeout=30).json()["data"]
        if state.get("status") in ("SUCCEEDED", "FAILED", "DONE"):
            break
    time.sleep(15)

    found = rows(f"SELECT id, parse_status, extraction_status, index_status, chunk_count FROM t_document "
                 f"WHERE source_version_id IN (SELECT id FROM t_source_version WHERE source_id IN "
                 f"(SELECT id FROM t_source WHERE knowledge_base_id={kb}));")
    doc = found[0] if found else None
    if not doc:
        print("  [FAIL] 没找到导入的文档")
        return 1
    print(f"· 首次导入：parse={doc['parse_status']} extraction={doc['extraction_status']} "
          f"index={doc['index_status']} chunk={doc['chunk_count']}")

    document_id = doc["id"]
    first = (session.post(f"{BASE}/knowledge/documents/{document_id}/reextract", timeout=600).json() or {}).get("data") or {}
    cards_a = len((session.get(f"{BASE}/knowledge/cards/{kb}", params={"limit": 200}, timeout=30).json() or {}).get("data") or [])
    second = (session.post(f"{BASE}/knowledge/documents/{document_id}/reextract", timeout=600).json() or {}).get("data") or {}
    cards_b = len((session.get(f"{BASE}/knowledge/cards/{kb}", params={"limit": 200}, timeout=30).json() or {}).get("data") or [])

    checks = [
        ("6a 三阶段状态分别可查",
         doc.get("parse_status") is not None and doc.get("extraction_status") is not None,
         f"parse={doc.get('parse_status')} extraction={doc.get('extraction_status')} index={doc.get('index_status')}"),
        ("6b 重跑不重新解析（切片复用）",
         first.get("unitCount") == second.get("unitCount") and (first.get("unitCount") or 0) > 0,
         f"两次重跑 unitCount 均为 {first.get('unitCount')}（文档切片 {doc.get('chunk_count')}）"),
        ("6c 重跑幂等（不重复写入）",
         cards_b == cards_a,
         f"第一次重跑后 {cards_a} 张 → 第二次重跑后 {cards_b} 张"),
    ]

    print("\n=== 用例 6：只重跑抽取 ===")
    failed = 0
    for name, ok, detail in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} — {detail}")
        failed += 0 if ok else 1
    if args.purge:
        # 列表接口不按 knowledgeBaseId 过滤，且库删掉之后它直接返回空——拿它当"规则已清"
        # 的证据会得到永远 PASS 的假象，所以只能客户端筛。
        def rules_of(base: int) -> int:
            return len([r for r in (session.get(f"{BASE}/knowledge/derivations/rules", timeout=30).json().get("data") or [])
                        if r.get("knowledgeBaseId") in (base, str(base))])

        before = rules_of(kb)
        removed = session.delete(f"{BASE}/knowledge/bases/{kb}", params={"confirm": str(kb)}, timeout=180).json().get("data") or {}
        after = rules_of(kb)
        ok = bool(removed.get("workspaceRemoved")) and after == 0
        print(f"  [{'PASS' if ok else 'FAIL'}] 删除验证库并清掉衍生规则 — "
              f"工作区移除={removed.get('workspaceRemoved')} 规则 {before} → {after}")
        failed += 0 if ok else 1
        print(f"\n失败 {failed} 项")
        return 1 if failed else 0

    print(f"\n知识库 id={kb}（加 --purge 可删除并校验配置清理）；失败 {failed} 项")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
