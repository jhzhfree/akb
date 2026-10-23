#!/usr/bin/env python3
"""端到端验证知识生命周期：建库 -> 批量导入 -> 抽取质量 -> 衍生 -> 检索。

只走控制台公开接口，因此可以反复跑在任意一套运行中的环境上：

    python3 scripts/verify-kb-lifecycle.py --dataset dataset/enterprise_kb_validation_5sets/01_综合知识库
    python3 scripts/verify-kb-lifecycle.py --username admin --password <pwd>

它回答四个问题：
  1. 每篇文档到底产出了多少卡片 / 断言（有没有"解析成功但零知识"的文档）；
  2. 卡片与断言有没有重复（同库相同标题 / 相同三元组）；
  3. 有没有空壳卡片（正文只是标题加页脚）与无意义断言（主词等于宾词）；
  4. 检索能不能答对，各通道命中情况如何。

退出码 0 表示未发现问题，1 表示有需要关注的问题。
"""

from __future__ import annotations

import argparse
import collections
import mimetypes
import os
import pathlib
import re
import sys
import time

import requests

TERMINAL = {"SUCCEEDED", "FAILED", "DONE", "CANCELLED"}


def normalize(value: str) -> str:
    """比较用的归一化键：去掉空白与标点差异。"""
    return re.sub(r"[\s｜|：:、，,。.（）()]+", "", (value or "")).lower()


class Client:
    def __init__(self, base_url: str, token: str | None = None) -> None:
        self.base = base_url.rstrip("/") + "/api"
        self.session = requests.Session()
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def login(self, username: str, password: str) -> None:
        response = self.session.post(
            self.base + "/auth/login", json={"username": username, "password": password}, timeout=30
        ).json()
        self.session.headers["Authorization"] = "Bearer " + response["data"]["token"]

    def get(self, path: str, **params):
        return self.session.get(self.base + path, params=params or None, timeout=60).json().get("data")

    def post(self, path: str, payload=None, **params):
        return self.session.post(self.base + path, json=payload or {}, params=params or None, timeout=180).json().get("data")

    def upload(self, kb: int, files: list[pathlib.Path]):
        form = [("files", (p.name, p.read_bytes(), mimetypes.guess_type(p.name)[0] or "application/octet-stream"))
                for p in files]
        return self.session.post(
            self.base + "/knowledge/documents/batch-upload", files=form,
            data={"knowledgeBaseId": str(kb)}, timeout=300,
        ).json()["data"]


def build(client: Client, dataset: pathlib.Path, wait: bool) -> tuple[int, dict]:
    """建库并批量导入，返回（知识库 id，每篇文档的产出统计）。"""
    base = client.post("/knowledge/bases", {"name": "生命周期验证-" + dataset.name,
                                           "visibility": "PUBLIC", "scenario": "GENERAL"})
    kb = base["id"]
    print(f"· 已建库 id={kb}（{dataset.name}，{len(list(dataset.glob('*')))} 个文件）")
    files = sorted(p for p in dataset.glob("*") if p.is_file())
    batch = client.upload(kb, files)
    code = batch["batchCode"]
    if wait:
        for _ in range(60):
            time.sleep(10)
            status = client.get(f"/knowledge/documents/batch/{code}")
            if status.get("status") in TERMINAL:
                break
        print(f"· 批次 {code} 完成：{status.get('succeededCount')}/{status.get('fileCount')} 成功")
    runs = client.get(f"/knowledge/ingestion/{kb}/runs", limit=50) or []
    return kb, {r["sourceName"]: r for r in runs}


def report_ingestion(runs: dict) -> int:
    """逐篇文档报告产出，重点标出 chunk>0 却零知识的文档。"""
    print("\n=== 1. 生产：每篇文档的产出 ===")
    problems = 0
    for name in sorted(runs):
        run = runs[name]
        chunks, cards, claims = run.get("chunkCount") or 0, run.get("cardCount") or 0, run.get("claimCount") or 0
        flag = ""
        if chunks and cards == 0 and claims == 0:
            flag = "  <== 有内容却零知识"
            problems += 1
        print(f"  {name[:38]:40} chunk={chunks} card={cards} claim={claims}{flag}")
    return problems


def report_quality(client: Client, kb: int) -> int:
    """卡片与断言的质量：重复、空壳、无意义。"""
    print("\n=== 2. 质量：重复 / 空壳 / 无意义 ===")
    problems = 0
    cards = client.get(f"/knowledge/cards/{kb}", limit=500) or []
    titles = collections.Counter(normalize(c.get("title")) for c in cards if normalize(c.get("title")))
    dup_titles = {k: v for k, v in titles.items() if v > 1}
    print(f"  卡片 {len(cards)} 张，重复标题 {len(dup_titles)} 组")
    for key, count in list(dup_titles.items())[:5]:
        sample = next(c.get("title") for c in cards if normalize(c.get("title")) == key)
        print(f"    · 「{sample[:36]}」× {count}")
    problems += len(dup_titles)

    # 空壳：正文去掉标题与摘要后所剩无几
    stubs = [c for c in cards
             if len(normalize(str(c.get("content") or "").replace(str(c.get("title") or ""), "")
                              .replace(str(c.get("summary") or ""), ""))) < 8]
    print(f"  空壳卡片（正文只剩标题/摘要）{len(stubs)} 张")
    for c in stubs[:5]:
        print(f"    · 「{str(c.get('title'))[:36]}」正文 {len(str(c.get('content') or ''))} 字")
    problems += len(stubs)

    claims = client.get(f"/knowledge/claims/{kb}", limit=500) or []
    triples = collections.Counter(
        (normalize(a.get("subject")), normalize(a.get("predicate")), normalize(a.get("objectValue"))) for a in claims)
    dup_triples = {k: v for k, v in triples.items() if v > 1}
    meaningless = [a for a in claims
                   if normalize(a.get("subject")) and normalize(a.get("subject")) == normalize(a.get("objectValue"))]
    noise = [a for a in claims if len(re.sub(r"[^：:]", "", str(a.get("objectValue") or ""))) >= 2
             and ("；" in str(a.get("objectValue") or "") or ";" in str(a.get("objectValue") or ""))]
    print(f"  断言 {len(claims)} 条，重复三元组 {len(dup_triples)} 组，主词=宾词 {len(meaningless)} 条，"
          f"宾语是元数据噪声 {len(noise)} 条")
    for a in meaningless[:4]:
        print(f"    · 「{str(a.get('subject'))[:24]} | {a.get('predicate')} | {str(a.get('objectValue'))[:24]}」")
    problems += len(dup_triples) + len(meaningless) + len(noise)

    status = collections.Counter(a.get("status") for a in claims)
    print(f"  断言状态分布：{dict(status)}")
    return problems


def report_derivation(client: Client, kb: int) -> int:
    print("\n=== 3. 衍生 ===")
    rules = client.get("/knowledge/derivations/rules", kb=kb) or []
    print(f"  规则 {len(rules)} 条")
    produced = 0
    for rule in rules:
        run = client.post(f"/knowledge/derivations/rules/{rule['ruleCode']}/run", {"knowledgeBaseId": kb}, kb=kb) or {}
        produced += run.get("candidates") or 0
        print(f"    · {str(rule.get('name'))[:16]:18} 扫描={run.get('scanned')} 产出={run.get('candidates')}")
    candidates = client.get("/knowledge/derivations/candidates", kb=kb) or []
    print(f"  候选 {len(candidates)} 条")
    return 0 if candidates else 1


def report_search(client: Client, kb: int, questions: list[str]) -> None:
    print("\n=== 4. 检索 ===")
    for question in questions:
        result = client.post("/knowledge/query", {"knowledgeBaseId": kb, "query": question, "topK": 5}) or {}
        recall = result.get("recall") or {}
        channels = {k: (len(v) if isinstance(v, list) else v) for k, v in recall.items()} if isinstance(recall, dict) else {}
        answer = str(result.get("answer") or "").replace("\n", " ")
        print(f"  Q: {question[:28]}")
        print(f"     通道命中 {channels} | 引用 {len(result.get('citations') or [])} 条")
        print(f"     A: {answer[:110]}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("KOS_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--dataset", default="dataset/enterprise_kb_validation_5sets/01_综合知识库")
    parser.add_argument("--username", default=os.environ.get("KOS_ADMIN_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("KOS_ADMIN_PASSWORD"))
    parser.add_argument("--token", default=os.environ.get("KOS_TOKEN"))
    parser.add_argument("--no-wait", action="store_true", help="提交批次后立即返回，不等生产完成")
    args = parser.parse_args()

    client = Client(args.base_url, args.token)
    if not args.token and args.password:
        client.login(args.username, args.password)

    dataset = pathlib.Path(args.dataset)
    if not dataset.is_dir():
        print(f"数据集目录不存在：{dataset}", file=sys.stderr)
        return 1

    kb, runs = build(client, dataset, wait=not args.no_wait)
    problems = report_ingestion(runs)
    if not args.no_wait:
        problems += report_quality(client, kb)
        problems += report_derivation(client, kb)
        report_search(client, kb, ["企业知识管理办法的关键要求", "项目星河的交付范围", "知识质量评估抽样多少条"])
    print(f"\n知识库 id={kb}，需要关注的问题 {problems} 项")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
