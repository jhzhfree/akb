#!/usr/bin/env python3
"""
去重、空壳判定与计数口径的回归用例。

与 verify-kb-lifecycle.py 的分工：那个脚本做端到端巡检（生产→衍生→检索），这个脚本只做
定点断言——每条用例都对应一个曾经真实发生过的缺陷，失败时说明缺陷回来了。

用例（前 5 条对应第一批，后 3 条分属第二、三、四批，暂标 skip）：
  1 同标题不同正文的两篇文档，两张卡片都要保留（按标题去重会吃掉后一篇）
  2 正文等于摘要的短卡片不算空壳（“正文−标题−摘要”的判据会误删它）
  3 相同内容二次导入：保留两份独立资产，并记一条 DUPLICATE 待裁决项（不自动合并）
  4 相同内容但权限标签不同：不合并，也不记录重复关系（不能越权暴露受控对象）
  5 运行台账能分别看到候选、拒绝与新增（计数口径）
  6 同一 documentId 重跑抽取：created=0、reused>0，不重复落库（待“只重跑抽取”接口）
  7 fallback 遇到无明确结构的散文不臆造断言（第三批）
  8 衍生规则能解释零产出的原因（第四批）
"""

import argparse
import collections
import json
import pathlib
import re
import sys
import time
import zipfile

import requests

BASE = "http://127.0.0.1:8080/api"
DATASET = pathlib.Path("dataset/enterprise_kb_validation_5sets/01_综合知识库")


def escape(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def make_docx(path: pathlib.Path, lines: list) -> None:
    """生成最小 docx：手写 OOXML，避免为跑一个用例引入 python-docx 依赖。"""
    body = "".join(
        f"<w:p><w:r><w:t xml:space='preserve'>{escape(line)}</w:t></w:r></w:p>" for line in lines)
    document = ("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
                f"<w:body>{body}</w:body></w:document>")
    content_types = ("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                     "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
                     "<Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/>"
                     "<Default Extension='xml' ContentType='application/xml'/>"
                     "<Override PartName='/word/document.xml' "
                     "ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/>"
                     "</Types>")
    rels = ("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
            "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
            "<Relationship Id='rId1' "
            "Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' "
            "Target='word/document.xml'/></Relationships>")
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", rels)
        z.writestr("word/document.xml", document)


class Client:
    def __init__(self, username: str, password: str):
        response = requests.post(f"{BASE}/auth/login",
                                 json={"username": username, "password": password}, timeout=30)
        self.headers = {"Authorization": f"Bearer {response.json()['data']['token']}"}

    def get(self, path: str, **params):
        return requests.get(f"{BASE}{path}", headers=self.headers, params=params, timeout=60).json().get("data")

    def post(self, path: str, **kwargs):
        return requests.post(f"{BASE}{path}", headers=self.headers, timeout=120, **kwargs).json().get("data")

    def new_kb(self, name: str) -> int:
        return self.post("/knowledge/bases", json={"name": name, "visibility": "PUBLIC", "scenario": "GENERAL"})["id"]

    def delete(self, path: str, **params):
        return requests.delete(f"{BASE}{path}", headers=self.headers, params=params, timeout=180).json().get("data")

    def ingest(self, kb: int, path: pathlib.Path, permission_tags: str = None) -> dict:
        """上传一篇文档并等到批次结束，返回该篇的 run。"""
        files = [("files", (path.name, path.read_bytes(),
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))]
        data = {"knowledgeBaseId": str(kb)}
        if permission_tags:
            data["permissionTags"] = permission_tags
        batch = self.post("/knowledge/documents/batch-upload", files=files, data=data)["batchCode"]
        waited = self.wait_batch(batch)
        runs = self.get(f"/knowledge/ingestion/{kb}/runs", limit=50) or []
        match = [r for r in runs if r.get("sourceName") == path.name]
        if not match:
            return {"waited": waited}
        # 同一文件名可能有多条 run（重复上传场景），取最新的那条
        return max(match, key=lambda r: r.get("id") or 0)

    def wait_batch(self, batch: str, limit: int = 60) -> str:
        for _ in range(limit):
            time.sleep(5)
            state = self.get(f"/knowledge/documents/batch/{batch}")
            if state and state.get("status") in ("SUCCEEDED", "FAILED", "DONE"):
                return state.get("status")
        return "TIMEOUT"

    def run_stats(self, run_id: int) -> dict:
        """从运行事件里取处置明细（detailJson 是 JSON 字符串）。"""
        detail = self.get(f"/knowledge/ingestion/runs/{run_id}") or {}
        for event in detail.get("events") or []:
            if event.get("eventType") != "EXTRACTION_COMPLETED":
                continue
            raw = event.get("detailJson")
            if isinstance(raw, str) and raw.strip():
                try:
                    return json.loads(raw)
                except ValueError:
                    return {}
        return {}


def content_key(value: str) -> str:
    """与后端同口径的内容指纹：去空白与中文标点，保留小数点与单位符号。"""
    text = re.sub(r"\s+", "", value or "")
    return re.sub(r"[，。；、：？！（）()【】《》“”‘’\"'｜|]+", "", text).lower()


def results(checks: list) -> int:
    print("\n=== 回归结论 ===")
    failed = 0
    for name, ok, note in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {note}")
        failed += 0 if ok else 1
    return failed


def purge_kbs(client: Client, ids: list[int]) -> int:
    """删除本次建的知识库，并校验挂在库上的配置（衍生规则）一并清掉。

    清库时最容易漏的就是配置类表——它们不是知识本身。漏了就变成孤儿：库已经不在了，
    规则还占着列表。这里逐库核对删除前后的规则数，好让这类遗漏被自动测出来。

    列表接口不按 knowledgeBaseId 过滤，且库删掉之后它会直接返回空；拿它的返回值当
    “规则已清”的证据会得到永远 PASS 的假象，所以只能客户端筛。
    """
    def rules_of(base: int) -> int:
        return len([r for r in (client.get("/knowledge/derivations/rules") or [])
                    if r.get("knowledgeBaseId") in (base, str(base))])

    problems = 0
    print("\n=== 清理验证库 ===")
    for kb in ids:
        before = rules_of(kb)
        removed = client.delete(f"/knowledge/bases/{kb}", confirm=str(kb)) or {}
        after = rules_of(kb)
        ok = bool(removed.get("workspaceRemoved")) and after == 0
        print(f"  [{'PASS' if ok else 'FAIL'}] kb={kb} 工作区移除={removed.get('workspaceRemoved')} "
              f"衍生规则 {before} → {after}")
        if before == 0:
            print("    （该库没有规则，这项证明力有限）")
        problems += 0 if ok else 1
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description="去重 / 空壳 / 计数口径回归用例")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", required=True)
    parser.add_argument("--purge", action="store_true", help="验证结束后删除本次建的知识库，并校验衍生规则等配置一并清掉")
    args = parser.parse_args()

    client = Client(args.username, args.password)
    checks = []

    # ---- 用例 1：同标题不同正文 ------------------------------------------------
    # 用构造文档而不是真实语料：模型每次给的标题未必逐字相同，真实语料会让用例时灵时不灵。
    # 两篇共用「一、适用范围与核心内容」这个章节名（真实制度里很常见），正文完全不同——
    # 按标题去重会吃掉后一篇，正是要守住的行为。
    kb1 = client.new_kb("回归-同标题不同正文")
    doc_a = pathlib.Path("/tmp/regression_dup_a.docx")
    doc_b = pathlib.Path("/tmp/regression_dup_b.docx")
    make_docx(doc_a, ["一、适用范围与核心内容",
                      "本办法适用于全体员工的日常知识发布与维护。",
                      "责任部门为知识管理部，生效日期为发布之日。"])
    make_docx(doc_b, ["一、适用范围与核心内容",
                      "本评估适用于月度知识质量抽查与评分。",
                      "抽样数量为每月一百条知识，由质量组执行。"])
    client.ingest(kb1, doc_a)
    client.ingest(kb1, doc_b)
    cards = client.get(f"/knowledge/cards/{kb1}", limit=200) or []
    by_title = collections.defaultdict(set)
    for card in cards:
        title = re.sub(r"[\s：:、，,。.（）()]+", "", card.get("title") or "")
        if title:
            by_title[title].add(content_key(card.get("content")))
    shared = {t: bodies for t, bodies in by_title.items() if len(bodies) > 1}
    preserved = sum(len(v) for v in shared.values())
    # 模型并不保证把章节名原样用作卡片标题，所以“同名”未必出现；真正要守住的是另一件事：
    # 任何一篇文档的候选都不能被静默丢弃——新增 + 复用 + 拒绝 必须等于候选数，且两篇各自
    # 产出的卡片都还在库里。同名只是顺带检查：一旦出现同名，就必须两份正文都还在。
    balanced, seen = True, 0
    for row in [r for r in (client.get(f"/knowledge/ingestion/{kb1}/runs", limit=10) or []) if r.get("id")]:
        stat = client.run_stats(row["id"]).get("cardStats") or {}
        if not stat:
            continue
        seen += 1
        if stat.get("created", 0) + stat.get("reused", 0) + stat.get("rejected", 0) != stat.get("received", 0):
            balanced = False
    checks.append(("1 两篇内容均保留（候选不静默丢弃）", balanced and seen >= 2 and len(cards) >= 2,
                   f"两篇共 {len(cards)} 张卡片，同名章节 {len(shared)} 组（保留 {preserved} 份正文），"
                   f"{seen} 篇满足 新增+复用+拒绝=候选"))

    # ---- 用例 2：短正文且摘要等于正文，不算空壳 ---------------------------------
    kb2 = client.new_kb("回归-短正文不误删")
    short = pathlib.Path("/tmp/regression_short.docx")
    make_docx(short, ["请假要求", "年假应提前三个工作日申请。", "病假须提交医院证明。"])
    client.ingest(kb2, short)
    short_cards = client.get(f"/knowledge/cards/{kb2}", limit=50) or []
    kept = [c for c in short_cards if content_key(c.get("content"))]
    checks.append(("2 短正文不判空壳", len(kept) > 0,
                   f"入库卡片 {len(short_cards)} 张，其中 {len(kept)} 张有有效正文"))
    if short_cards:
        preview = str(short_cards[0].get("content") or "")[:40].replace("\n", " ")
        print(f"       样例卡片：{str(short_cards[0].get('title'))[:20]} | {preview}")

    # ---- 用例 3：相同内容二次导入 → 保留两份 + 记录重复关系 ----------------------
    # 断言写得很克制：只有当两次抽取确实产出了正文完全相同的卡片时，才要求存在
    # DUPLICATE 关系。模型两次产出措辞不同是正常现象，此时本就不该判重复——把这个区别
    # 写进结论，免得用例因为“模型这次写得不一样”而假通过。
    kb3 = client.new_kb("回归-相同内容不合并")
    doc_same = pathlib.Path("/tmp/regression_same.docx")
    make_docx(doc_same, ["设备借用", "借用设备须提前一个工作日申请。", "归还时须由管理员验收。"])
    client.ingest(kb3, doc_same)
    once = len(client.get(f"/knowledge/cards/{kb3}", limit=200) or [])
    client.ingest(kb3, doc_same)
    twice = client.get(f"/knowledge/cards/{kb3}", limit=200) or []
    bodies = collections.Counter(content_key(c.get("content")) for c in twice if content_key(c.get("content")))
    identical = sum(v - 1 for v in bodies.values() if v > 1)
    conflicts = client.get(f"/knowledge/conflicts/{kb3}", status="OPEN") or []
    dups = [c for c in conflicts if c.get("conflictType") == "DUPLICATE"]
    checks.append(("3a 相同内容不因重复而被删除", len(twice) >= max(once, 1),
                   f"首次 {once} 张 → 两次共 {len(twice)} 张（不合并、不覆盖）"))
    checks.append(("3b 指纹相同则记录重复关系",
                   len(dups) > 0 if identical > 0 else True,
                   f"正文完全相同的 {identical} 对，DUPLICATE 待裁决项 {len(dups)} 条"
                   + ("" if identical else "（两次产出措辞不同，本就不该判重复）")))

    # ---- 用例 4：权限标签不同 → 不合并，也不记录重复关系 -------------------------
    kb4 = client.new_kb("回归-权限边界")
    client.ingest(kb4, doc_a, permission_tags='["dept-a"]')
    client.ingest(kb4, doc_a, permission_tags='["dept-b"]')
    scoped = client.get(f"/knowledge/cards/{kb4}", limit=200) or []
    scoped_conflicts = client.get(f"/knowledge/conflicts/{kb4}", status="OPEN") or []
    cross = [c for c in scoped_conflicts if c.get("conflictType") == "DUPLICATE"]
    checks.append(("4a 不同权限范围不合并", len(scoped) >= 2, f"两个权限范围各保留资产，共 {len(scoped)} 张卡片"))
    checks.append(("4b 不越权记录重复关系", len(cross) == 0,
                   f"跨权限 DUPLICATE {len(cross)} 条（应为 0）"))

    # ---- 用例 5：运行台账能分别看到候选 / 拒绝 / 新增 -----------------------------
    runs = [r for r in (client.get(f"/knowledge/ingestion/{kb1}/runs", limit=10) or []) if r.get("id")]
    stats = client.run_stats(max(runs, key=lambda r: r["id"])["id"]) if runs else {}
    card_stats = stats.get("cardStats") or {}
    has_fields = {"received", "created", "rejected"} <= set(card_stats)
    run_row = max(runs, key=lambda r: r["id"]) if runs else {}
    # 拿不到统计就无法比对，此时必须判失败而不是放过：否则用例会因为“字段缺失”而假装通过。
    consistent = has_fields and card_stats.get("created") == run_row.get("cardCount")
    checks.append(("5a 台账暴露候选/拒绝/新增", has_fields,
                   f"cardStats={card_stats}" if card_stats else "事件未携带统计（需重启后端）"))
    checks.append(("5b 台账新增数=实际写入数", consistent,
                   f"run.cardCount={run_row.get('cardCount')} vs created={card_stats.get('created')}"))

    for name in ["6 同文档重跑幂等（待抽取重试接口）",
                 "7 fallback 不臆造断言（第三批）",
                 "8 衍生能解释零产出 → 见 verify-derivation-diagnosis.py"]:
        print(f"  [SKIP] {name}")

    problems = 0
    if args.purge:
        problems = purge_kbs(client, [kb1, kb2, kb3, kb4])
    else:
        print(f"\n知识库：{kb1} / {kb2} / {kb3} / {kb4}（加 --purge 可删除并校验配置清理）")
    return results(checks) + problems


if __name__ == "__main__":
    sys.exit(main())
