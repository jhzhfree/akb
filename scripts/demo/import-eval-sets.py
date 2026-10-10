#!/usr/bin/env python3
"""把各场景的检索测试语句导入成对应知识库的评测数据集。

测试语句的源头是 ``dataset/<场景>/retrieval-eval/检索质量评测用例集.md``（人工编写的
用例表：编号 / 提问 / 期望命中 / 期望要点 / 失败信号）。脚本做两件事：

1. 解析用例表，产出 ``dataset/_scenarios/<场景>.eval.json``，供 ``build-scenario-kbs.py
   --with-eval`` 在建库时直接导入；
2. 通过控制台 API 把它们写入已存在的知识库，使「知识检索」能按知识库取出该场景的
   测试语句作为默认示例问题，同时让评测中心有真实用例可回放。

可重复执行：已存在同名数据集时先清理旧用例再写入。

用法
----
    python3 scripts/demo/import-eval-sets.py                       # 解析 + 导入
    python3 scripts/demo/import-eval-sets.py --parse-only          # 只生成 json
    python3 scripts/demo/import-eval-sets.py --scenario ops
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

import requests

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DATASET_DIR = REPO_ROOT / "dataset"
SCENARIO_DIR = DATASET_DIR / "_scenarios"

SCENARIOS = {
    "ops": ("ops-knowledge", "运维知识库"),
    "policy": ("company-policy", "公司制度知识库"),
    "eng": ("engineering-workspace", "工程协同知识库"),
}

# 期望命中列中表示「无期望命中」的占位（拒答类用例）
EMPTY_HITS = {"—", "-", "--", "无", "", "/", "N/A", "无（应拒答）"}


class Client:
    def __init__(self, base_url: str, token: str | None) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        if token:
            self.session.headers["X-Auth-Token"] = token

    def _unwrap(self, response: requests.Response) -> Any:
        response.raise_for_status()
        payload = response.json()
        if isinstance(payload, dict) and "data" in payload:
            return payload["data"]
        return payload

    def login(self, username: str, password: str) -> None:
        payload = self._unwrap(self.session.post(
            self.base_url + "/api/auth/login",
            json={"username": username, "password": password}, timeout=30))
        token = payload.get("token") if isinstance(payload, dict) else None
        if not token:
            raise SystemExit("登录成功但未返回 token")
        self.session.headers["X-Auth-Token"] = token

    def get(self, path: str) -> Any:
        return self._unwrap(self.session.get(self.base_url + path, timeout=60))

    def post(self, path: str, body: dict[str, Any]) -> Any:
        return self._unwrap(self.session.post(self.base_url + path, json=body, timeout=60))

    def delete(self, path: str) -> Any:
        return self._unwrap(self.session.delete(self.base_url + path, timeout=60))


def clean_question(text: str) -> str:
    """去掉 markdown 行内代码反引号与多余空白。"""
    return re.sub(r"\s+", " ", text.replace("`", "")).strip()


def resolve_source_names(token: str, dataset_dir: Path) -> list[str]:
    """把「期望命中」里的文档编号映射成源文件名（检索候选中带 source_name）。"""
    token = token.strip()
    if token in EMPTY_HITS:
        return []
    for candidate in (token, token.zfill(2), token.lstrip("0")):
        matches = sorted(dataset_dir.glob("%s-*.md" % candidate))
        if matches:
            return [matches[0].name]
    return []


def parse_eval(md: Path, dataset_dir: Path) -> dict[str, Any]:
    cases: list[dict[str, Any]] = []
    level = "L1"
    for raw_line in md.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        heading = re.match(r"^##\s+(L\d)\b", line)
        if heading:
            level = heading.group(1)
            continue
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        if len(cells) < 4:
            continue
        if set("".join(cells)) <= set("-: "):  # 分隔行
            continue
        if cells[0] in ("编号", "用例编号"):
            continue
        case_code, question = cells[0], cells[1]
        hits, expected = cells[2], cells[3]
        if not question or not case_code:
            continue
        source_names: list[str] = []
        for part in re.split(r"[、,，/]|(?:\s+)", hits):
            source_names.extend(resolve_source_names(part, dataset_dir))
        cases.append({
            "question": clean_question(question),
            "expectedObjectCodes": sorted(set(source_names)),
            "expectedAnswer": expected,
            "tags": [level, case_code.rsplit("-", 1)[0]],
            "difficulty": level,
        })
    title = md.read_text(encoding="utf-8").splitlines()[0].lstrip("# ").strip()
    return {"name": title, "description": "来源：%s" % md.relative_to(REPO_ROOT), "cases": cases}


def import_eval(client: Client, kb_id: int, kb_name: str, payload: dict[str, Any]) -> int:
    datasets = client.get("/api/knowledge/evaluations/datasets/%s" % kb_id) or []
    dataset = next((item for item in datasets if item.get("name") == payload["name"]), None)
    if dataset is None:
        dataset = client.post("/api/knowledge/evaluations/datasets/%s" % kb_id, {
            "name": payload["name"], "description": payload["description"]})
    code = dataset.get("code") or dataset.get("datasetCode")
    # 幂等：先清掉旧用例，避免重复执行时越积越多
    for existing in client.get("/api/knowledge/evaluations/datasets/%s/cases" % code) or []:
        client.delete("/api/knowledge/evaluations/cases/%s" % existing.get("id"))
    imported = 0
    skipped = 0
    for case in payload["cases"]:
        # 拒答/边界类用例没有期望命中，评测要求至少一个对象 code，导入无意义。
        if not case.get("expectedObjectCodes"):
            skipped += 1
            continue
        client.post("/api/knowledge/evaluations/datasets/%s/cases" % code, case)
        imported += 1
    print("  · %s（kb=%s，数据集 %s）：%d 条测试语句%s"
          % (kb_name, kb_id, code, imported,
             "，跳过 %d 条无期望命中的拒答用例" % skipped if skipped else ""))
    return imported


def main() -> int:
    parser = argparse.ArgumentParser(description="导入各场景检索测试语句")
    parser.add_argument("--base-url", default=os.environ.get("KOS_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.environ.get("KOS_ADMIN_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("KOS_ADMIN_PASSWORD", "1qaz@WSX"))
    parser.add_argument("--token", default=os.environ.get("KOS_TOKEN"))
    parser.add_argument("--scenario", default="all", choices=["all", *SCENARIOS.keys()])
    parser.add_argument("--parse-only", action="store_true", help="只生成 eval json，不调用接口")
    args = parser.parse_args()

    keys = list(SCENARIOS) if args.scenario == "all" else [args.scenario]
    client = Client(args.base_url, args.token)
    if not args.parse_only and not args.token:
        client.login(args.username, args.password)

    bases = [] if args.parse_only else (client.get("/api/knowledge/bases") or [])
    total = 0
    for key in keys:
        directory_name, fallback_name = SCENARIOS[key]
        source_dir = DATASET_DIR / directory_name
        eval_dir = source_dir / "retrieval-eval"
        md_files = sorted(eval_dir.glob("*.md"))
        if not md_files:
            print("! %s：未找到测试语句文件，跳过" % fallback_name)
            continue
        payload = parse_eval(md_files[0], source_dir)
        SCENARIO_DIR.mkdir(parents=True, exist_ok=True)
        target = SCENARIO_DIR / ("%s.eval.json" % directory_name)
        target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print("== %s：解析 %d 条测试语句 -> %s" % (fallback_name, len(payload["cases"]),
                                                 target.relative_to(REPO_ROOT)))
        if args.parse_only:
            continue
        base = next((item for item in bases
                     if str(item.get("name")) == fallback_name), None)
        if base is None:
            print("  ! 未找到知识库「%s」，跳过导入" % fallback_name)
            continue
        total += import_eval(client, int(base["id"]), fallback_name, payload)

    if not args.parse_only:
        print("\n导入完成，共 %d 条测试语句" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
