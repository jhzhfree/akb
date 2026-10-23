#!/usr/bin/env python3
"""
用例 8：衍生零产出必须自带解释。

衍生建立在图谱两跳路径之上，"产出 0"至少有四种成因，处置方式完全不同：
  1. 库里没有关系          → 先做实体与关系抽取
  2. 关系接不成两跳        → 图的形状不支持这类推导
  3. 没有任何单条关系达标  → 提升关系质量，或调低阈值
  4. 单条达标但配不成对    → 达标关系没落在同一条路径上

第 3 和第 4 最容易看错：看到"最高置信 0.62 ≥ 阈值 0.6"就以为该有产出，其实两跳要求
两段都达标。所以用例不只要求"有 reason"，还要求**不同阈值给出不同结论**——能区分的
诊断才是有用的诊断。

本脚本**不建库**，因此也没有 --purge：它跑的是 --kb 指定的已有库（默认 6），删掉就
找不回来了。需要清理请用 verify-kb-lifecycle.py --purge。
"""
import argparse
import json
import sys

import requests

BASE = "http://127.0.0.1:8080/api"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--password", default="1qaz@WSX")
    parser.add_argument("--kb", type=int, default=6, help="要跑衍生的知识库 id")
    args = parser.parse_args()

    session = requests.Session()
    token = session.post(f"{BASE}/auth/login", json={"username": "admin", "password": args.password}, timeout=30).json()
    session.headers["Authorization"] = "Bearer " + (token.get("data") or {}).get("token", "")

    rules_url = f"{BASE}/knowledge/derivations/rules"
    rules = [r for r in (session.get(rules_url, params={"knowledgeBaseId": args.kb}, timeout=30).json().get("data") or [])
             if r.get("knowledgeBaseId") in (args.kb, str(args.kb))]
    print(f"· 库 {args.kb} 的衍生规则 {len(rules)} 条")
    if not rules:
        print("  [FAIL] 该库没有衍生规则")
        return 1

    reasons = {}
    checks = []
    for rule in rules:
        definition = rule.get("definitionJson") or {}
        if isinstance(definition, str):
            try:
                definition = json.loads(definition)
            except ValueError:
                definition = {}
        res = (session.post(f"{rules_url}/{rule.get('ruleCode')}/run", timeout=300).json() or {}).get("data") or {}
        diag = res.get("diagnosis") or {}
        name = rule.get("name")
        threshold = definition.get("minConfidence")
        print(f"\n  {name}（阈值 {threshold}）scanned={res.get('scanned')} candidates={res.get('candidates')}")
        print(f"    {diag.get('reason')}")
        if res.get("scanned"):
            continue  # 有产出就不要求诊断
        reasons[threshold] = diag.get("reason")
        has_fields = all(k in diag for k in
                         ("relations", "relationsAboveThreshold", "twoHopPaths", "twoHopAboveThreshold", "maxConfidence"))
        checks.append((f"零产出带诊断：{name}", bool(diag.get("reason")) and has_fields,
                       f"字段齐备={has_fields}，结论：{diag.get('reason')}"))

    # 阈值不同 → 结论不同：能区分成因的诊断才算有用。
    distinct = len({v for v in reasons.values() if v}) > 1
    checks.append(("不同阈值给出不同结论", distinct,
                   f"{len(reasons)} 个阈值得到 {len({v for v in reasons.values() if v})} 种结论"))

    print("\n=== 用例 8：衍生零产出自解释 ===")
    failed = 0
    for name, ok, detail in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} — {detail[:90]}")
        failed += 0 if ok else 1
    print(f"\n失败 {failed} 项")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
