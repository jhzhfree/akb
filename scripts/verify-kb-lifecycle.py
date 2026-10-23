#!/usr/bin/env python3
"""端到端验证知识生命周期：建库 -> 批量导入 -> 抽取质量 -> 衍生 -> 检索。

只走控制台公开接口，因此可以反复跑在任意一套运行中的环境上：

    python3 scripts/verify-kb-lifecycle.py --dataset dataset/enterprise_kb_validation_5sets/01_综合知识库
    python3 scripts/verify-kb-lifecycle.py --username admin --password <pwd>

它回答四个问题：
  1. 每篇文档到底产出了多少卡片 / 断言（有没有"解析成功但零知识"的文档）；
  2. 卡片与断言有没有重复（同库相同标题 / 相同三元组）；
  3. 有没有空壳卡片（正文只是标题加页脚）与无意义断言（主词等于宾词）；
  4. 检索能不能答对，各通道命中情况如何；
  5. 删除工作区后，挂在库上的配置（衍生规则等）有没有一并清掉（--purge）。

第 5 项查的是"清库漏删配置"：衍生规则不是知识本身，清库时最容易漏，漏了就变成孤儿
——库已经不在了，规则还占着列表。实测 181 条规则里 158 条是这类孤儿。

退出码 0 表示未发现问题，1 表示有需要关注的问题。
"""

from __future__ import annotations

import argparse
import collections
import json
import mimetypes
import os
import pathlib
import re
import sys
import time

import requests

TERMINAL = {"SUCCEEDED", "FAILED", "DONE", "CANCELLED"}


def normalize(value: str) -> str:
    """标题比较用的归一化键：去掉空白与标点差异。"""
    return re.sub(r"[\s｜|：:、，,。.（）()]+", "", (value or "")).lower()


def content_key(value: str) -> str:
    """
    内容指纹用的归一化：去空白与标点，但保留数字里的小数点。

    去掉小数点会让「1.5 天」和「15 天」算成同一条知识，所以这里只剔中文标点，
    “.” “-” “%” “/” 这些承载单位与区间的符号一律保留。也刻意不截断：同一套模板下
    的两篇文档往往前半段相同、结论不同，只比前若干字会把它们误判为重复。
    """
    text = re.sub(r"\s+", "", value or "")
    return re.sub(r"[，。；、：？！（）()【】《》“”‘’\"'｜|]+", "", text).lower()


def is_noise_line(line: str) -> bool:
    """页码与装饰行不该算作正文。"""
    stripped = content_key(line)
    if not stripped:
        return True
    if stripped.isdigit():
        return True
    return bool(re.match(r"^第\d+页", stripped)) or bool(re.match(r"^[-—_.]{2,}$", stripped))


def effective_content(card: dict) -> str:
    """
    与后端同口径的有效正文：剔除噪声行与作为独立标题行重复出现的标题。

    不扣摘要——摘要通常是正文的前若干字，扣掉它等于把内容清空，
    「请假要求 / 年假应提前三个工作日申请」这种有效短卡片会被误判成空壳。
    """
    title = (card.get("title") or "").strip()
    kept = []
    for line in (card.get("content") or "").split("\n"):
        line = line.strip()
        if not line or is_noise_line(line):
            continue
        if title and line == title:
            continue
        kept.append(line)
    return content_key("".join(kept))


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

    def delete(self, path: str, **params):
        return self.session.delete(self.base + path, params=params or None, timeout=180).json().get("data")

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


def report_ingestion(client: Client, runs: dict) -> int:
    """
    逐篇文档报告产出：台账里的 card/claim 是实际新增数，处置明细取自运行事件。

    区分几种“新增为 0”很重要：模型没给候选、候选被质量闸门拒绝、候选全部命中已有知识
    （复用）——它们在台账上长得一模一样，处理方式却完全不同。
    """
    print("\n=== 1. 生产：每篇文档的产出（新增数 / 处置明细）===")
    problems = 0
    for name in sorted(runs):
        run = runs[name]
        chunks, cards, claims = run.get("chunkCount") or 0, run.get("cardCount") or 0, run.get("claimCount") or 0
        flag = ""
        if chunks and cards == 0 and claims == 0:
            flag = "  <== 有内容却零知识"
            problems += 1
        print(f"  {name[:38]:40} chunk={chunks} 新增 card={cards} claim={claims}{flag}")
        detail = extraction_detail(client, run)
        if detail:
            print(f"      卡片 {brief(detail.get('cardStats'))}")
            print(f"      断言 {brief(detail.get('claimStats'))}")
    return problems


def extraction_detail(client: Client, run: dict) -> dict:
    """取本次运行 EXTRACTION_COMPLETED 事件的明细（候选 / 新增 / 复用 / 拒绝）。"""
    try:
        data = client.get(f"/knowledge/ingestion/runs/{run.get('id')}") or {}
    except Exception:
        return {}
    for event in data.get("events") or []:
        if event.get("eventType") != "EXTRACTION_COMPLETED":
            continue
        # 事件明细列在 SQL 里别名为 detailJson（一个 JSON 字符串），不是 detail。
        raw = event.get("detailJson") or event.get("detail")
        if isinstance(raw, dict):
            return raw
        if isinstance(raw, str) and raw.strip():
            try:
                return json.loads(raw)
            except ValueError:
                return {}
    return {}


def brief(stats) -> str:
    """一行说清这一批到底发生了什么。"""
    if not isinstance(stats, dict):
        return "（事件未携带明细）"
    return (f"候选{stats.get('received', 0)} 新增{stats.get('created', 0)} 复用{stats.get('reused', 0)} "
            f"拒绝{stats.get('rejected', 0)} 降级{stats.get('lowInformation', 0)} "
            f"重复{stats.get('duplicates', 0)} 证据{stats.get('evidenceLinked', 0)}")


def report_quality(client: Client, kb: int) -> int:
    """
    卡片与断言的质量：重复、空壳、无意义。

    判定口径必须与后端一致，否则脚本会自己制造误报：
    - 重复按**完整正文指纹**，不按标题。两篇文档用了同一套章节名（「一、适用范围」）
      但正文不同，那是两条各自独立的知识，报成重复就是在鼓励误删。
    - 空壳**不扣摘要**（见 effective_content）。
    - 断言指纹包含极性、条件与有效期，只按三元组比会漏掉「支持 / 不支持」的差异。
    """
    print("\n=== 2. 质量：重复 / 空壳 / 无意义 ===")
    problems = 0
    cards = client.get(f"/knowledge/cards/{kb}", limit=500) or []

    fingerprints = collections.Counter(content_key(c.get("content")) for c in cards if content_key(c.get("content")))
    dup_content = {k: v for k, v in fingerprints.items() if v > 1}
    print(f"  卡片 {len(cards)} 张，正文完全相同的 {len(dup_content)} 组（应只保留一份或已记录重复关系）")
    for key, count in list(dup_content.items())[:5]:
        sample = next(c for c in cards if content_key(c.get("content")) == key)
        print(f"    · 「{str(sample.get('title'))[:36]}」× {count}")
    problems += len(dup_content)

    # 同名但正文不同：按设计各自保留，不算问题，但要在报告里看得见
    by_title = collections.defaultdict(set)
    for c in cards:
        title = normalize(c.get("title"))
        if title:
            by_title[title].add(content_key(c.get("content")))
    distinct = {k: v for k, v in by_title.items() if len(v) > 1}
    print(f"  同名但正文不同的卡片 {len(distinct)} 组（保留各自资产是正确行为）")
    for title in list(distinct)[:5]:
        print(f"    · 「{title[:36]}」有 {len(distinct[title])} 种不同正文")

    stubs = [c for c in cards if effective_content(c) == ""]
    print(f"  空壳卡片（正文只剩标题/页码/噪声）{len(stubs)} 张")
    for c in stubs[:5]:
        print(f"    · 「{str(c.get('title'))[:36]}」正文 {len(str(c.get('content') or ''))} 字")
    problems += len(stubs)

    claims = client.get(f"/knowledge/claims/{kb}", limit=500) or []

    def claim_fingerprint(a: dict) -> tuple:
        return (content_key(a.get("subject")), content_key(a.get("predicate")), content_key(a.get("objectValue")),
                content_key(a.get("polarity")), content_key(a.get("validFrom")), content_key(a.get("validTo")))

    dup_claims = {k: v for k, v in collections.Counter(claim_fingerprint(a) for a in claims).items() if v > 1}
    meaningless = [a for a in claims
                   if content_key(a.get("subject")) and content_key(a.get("subject")) == content_key(a.get("objectValue"))]
    noise = [a for a in claims if len(re.sub(r"[^：:]", "", str(a.get("objectValue") or ""))) >= 2
             and ("；" in str(a.get("objectValue") or "") or ";" in str(a.get("objectValue") or ""))]
    print(f"  断言 {len(claims)} 条，指纹完全相同 {len(dup_claims)} 组，主词=宾词 {len(meaningless)} 条，"
          f"宾语是元数据噪声 {len(noise)} 条")
    for a in meaningless[:4]:
        print(f"    · 「{str(a.get('subject'))[:24]} | {a.get('predicate')} | {str(a.get('objectValue'))[:24]}」")
    problems += len(dup_claims) + len(meaningless) + len(noise)

    # 重复关系是否已经作为待裁决项落库（跨文档重复靠它发现，而不是靠删掉一方）
    conflicts = client.get(f"/knowledge/conflicts/{kb}", status="OPEN") or []
    duplicates = [c for c in conflicts if c.get("conflictType") == "DUPLICATE"]
    print(f"  待裁决项 {len(conflicts)} 条，其中重复关系 {len(duplicates)} 条")

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


def verify_purge(client: Client, kb: int) -> int:
    """删除工作区，并校验“挂在库上的配置”一并清掉。

    清库时最容易漏的是配置类表（衍生规则、运行记录、产出候选）——它们不是知识本身，
    漏了就变成孤儿：库已经不在了，规则还占着列表，规则总数一路涨到实际库的几十倍。
    这里在删除后查该库的规则是否归零，好让这类遗漏下次能被自动测出来。
    """
    # 列表接口不按 knowledgeBaseId 过滤（传什么库都返回全部），只能客户端筛。
    # 更要紧的是：库被删之后该接口会直接返回空（因为库不存在了），若拿它当"规则已清"
    # 的证据，会得到永远 PASS 的假象——它测的是库存不存在，不是规则清没清。
    def rules_of(base: int) -> int:
        return len([r for r in (client.get("/knowledge/derivations/rules") or [])
                    if r.get("knowledgeBaseId") in (base, str(base))])

    before = rules_of(kb)
    removed = client.delete(f"/knowledge/bases/{kb}", confirm=str(kb)) or {}
    after = rules_of(kb)

    print("\n=== 删除工作区 ===")
    ok_removed = bool(removed.get("workspaceRemoved"))
    print(f"  [{'PASS' if ok_removed else 'FAIL'}] 工作区已移除 — {removed.get('knowledgeBaseName')}")
    # 规则归零才说明清库没漏表。删除前本身是 0 的话这项证明力有限，单独提示一句，
    # 免得看到 PASS 就以为真的验证过了。
    ok_rules = after == 0
    print(f"  [{'PASS' if ok_rules else 'FAIL'}] 衍生规则一并清掉 — 删除前 {before} 条 → 删除后 {after} 条")
    if before == 0:
        print("    （删除前该库没有规则，这项的证明力有限）")
    return (0 if ok_removed else 1) + (0 if ok_rules else 1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("KOS_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--dataset", default="dataset/enterprise_kb_validation_5sets/01_综合知识库")
    parser.add_argument("--username", default=os.environ.get("KOS_ADMIN_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("KOS_ADMIN_PASSWORD"))
    parser.add_argument("--token", default=os.environ.get("KOS_TOKEN"))
    parser.add_argument("--no-wait", action="store_true", help="提交批次后立即返回，不等生产完成")
    parser.add_argument("--purge", action="store_true", help="验证结束后删除该知识库，并校验衍生规则等配置一并清掉")
    args = parser.parse_args()

    client = Client(args.base_url, args.token)
    if not args.token and args.password:
        client.login(args.username, args.password)

    dataset = pathlib.Path(args.dataset)
    if not dataset.is_dir():
        print(f"数据集目录不存在：{dataset}", file=sys.stderr)
        return 1

    kb, runs = build(client, dataset, wait=not args.no_wait)
    problems = report_ingestion(client, runs)
    if not args.no_wait:
        problems += report_quality(client, kb)
        problems += report_derivation(client, kb)
        report_search(client, kb, ["企业知识管理办法的关键要求", "项目星河的交付范围", "知识质量评估抽样多少条"])
        if args.purge:
            problems += verify_purge(client, kb)
    print(f"\n知识库 id={kb}，需要关注的问题 {problems} 项")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
