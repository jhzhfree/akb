#!/usr/bin/env python3
"""Seed a calibration dataset: extract knowledge, then rule on it like a reviewer would.

Confidence can only be checked against something outside itself. This script runs
the real extractor over real documents and then rules on every item the way a
reviewer would — rejecting what is visibly broken: a triple missing a leg, a
fragment that starts with stray punctuation, a subject still carrying a list
bullet, a value cut off mid-word.

The ruling deliberately never reads ``confidence``. If it did, the calibration
would be grading itself and would always come out looking good.

Freshly extracted items almost never fail a form check, so on their own they yield
zero rejections and no calibration signal at all. The script therefore also pulls
in the fragments left behind by the rule fallback in an older library (「和市场响」
「)硬件授权申请流程」) — items already known to be unusable — and scores them with the
current formula. That gives the comparison two sides.

This stands in for human review, it does not replace it: it proves the pipeline
runs end to end, but the final weights should still be checked against rulings
made by an actual person.
"""

import asyncio
import glob
import os
import re
import shutil
import subprocess
import sys

SERVICE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ai-service")
sys.path.insert(0, SERVICE)

WORKDIR = "/tmp/calib"
KB_NAME = "置信度校准验证库"

SOURCES = [
    "/mnt/c/Users/zhanghe/Desktop/知识库系统设计/1-公司制度知识库/建恒信安岗位职责说明书_v1.5.pdf",
    "/mnt/c/Users/zhanghe/Desktop/知识库系统设计/1-公司制度知识库/建恒信安员工手册2026-9-2.pdf",
] + sorted(glob.glob(os.path.join(SERVICE, "data/uploads/*绩效管理制度*.pdf")))[:1]

# 残句的形式特征：以标点开头、被截断、还带着列表符号。
STRAY_START = re.compile(r"^[\s，。；：、）)\]】、,.;:!！?？]")
TRUNCATED_END = re.compile(r"(的|和|与|或|在|对|把|被|将|由|向|其|之)$")
BULLET = re.compile(r"[•◦●○▪■□‣·※]")


def sql(statement: str) -> str:
    cmd = [
        "docker", "exec", "kos-dev-mysql", "mysql",
        "-uknowledge", "-pknowledge123", "--default-character-set=utf8mb4",
        "-D", "knowledge_os", "-N", "-e", statement,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return "\n".join(
        line for line in result.stdout.split("\n")
        if "Warning" not in line and "password" not in line
    ).strip()


def quote(value: object) -> str:
    return "'" + str(value if value is not None else "").replace("\\", "\\\\").replace("'", "''") + "'"


def rule_claim(claim: dict) -> str:
    subject = (claim.get("subject") or "").strip()
    obj = (claim.get("object") or "").strip()
    if len(subject) < 2 or len(obj) < 2:
        return "REJECTED"
    if STRAY_START.match(subject) or STRAY_START.match(obj):
        return "REJECTED"
    if BULLET.search(subject):
        return "REJECTED"
    if TRUNCATED_END.search(subject):
        return "REJECTED"
    return "APPROVED"


def rule_card(card: dict) -> str:
    title = (card.get("title") or "").strip()
    content = (card.get("content") or "").strip()
    if len(title) < 4 or len(content) < 10:
        return "REJECTED"
    if STRAY_START.match(title) or BULLET.search(title):
        return "REJECTED"
    return "APPROVED"


def main() -> int:
    os.makedirs(WORKDIR, exist_ok=True)
    sources = []
    for path in SOURCES:
        if not os.path.exists(path):
            print("跳过（不存在）:", path)
            continue
        target = os.path.join(WORKDIR, os.path.basename(path))
        shutil.copyfile(path, target)   # 复制到 /tmp：解析会在源文件旁落盘 .md
        sources.append(target)
    if not sources:
        print("没有可用的源文档")
        return 1

    from app.services.document_parser import DocumentParser
    from app.services.extractor import KnowledgeExtractor

    policy = {
        "strategy": "STRUCTURE", "chunkSize": 600, "chunkOverlap": 80,
        "minChunkSize": 100, "measure": "CHAR", "keepStructure": True,
        "respectSentence": True, "parentChild": True,
    }
    parser = DocumentParser()
    extractor = KnowledgeExtractor()

    claims: list[dict] = []
    cards: list[dict] = []

    async def run() -> None:
        for path in sources:
            parsed = parser.parse(path, policy=policy)
            result = await extractor.extract(
                (parsed.get("text") or "")[:14000],
                chunks=parsed.get("chunks"),
                units=parsed.get("units"),
                scenario="POLICY",
            )
            got_claims = result.get("claims") or []
            got_cards = result.get("cards") or []
            print("  %-34s 断言 %3d  卡片 %3d" % (os.path.basename(path)[:34], len(got_claims), len(got_cards)))
            claims.extend(got_claims)
            cards.extend(got_cards)

    print("抽取中（真实文档 + 模型）…")
    asyncio.run(run())

    legacy_kb = int(os.getenv("LEGACY_KB", "61"))
    legacy_rows = sql(
        "SELECT subject, predicate, object_value FROM t_claim WHERE knowledge_base_id=%d "
        "AND subject IS NOT NULL AND object_value IS NOT NULL LIMIT 60;" % legacy_kb
    )
    legacy = 0
    for line in (legacy_rows or "").split("\n"):
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0].strip():
            continue
        item = {
            "subject": parts[0], "predicate": parts[1], "object": parts[2],
            "derivation": "RULE_FALLBACK",
        }
        score, _ = extractor._blend(item, [], "claim")
        item["confidence"] = score
        item["status"] = "REJECTED"
        claims.append(item)
        legacy += 1
    print("并入历史坏样本 %d 条（库 %d，修复前规则兜底造的残句）" % (legacy, legacy_kb))

    if not claims and not cards:
        print("没有抽到知识，无法构建校准数据")
        return 1

    kb_id = int(sql("SELECT COALESCE(MAX(id),0)+1 FROM t_knowledge_base;") or "1")
    sql(
        "INSERT INTO t_knowledge_base (id, base_code, name, description, visibility, status) VALUES "
        "(%d, %s, %s, %s, 'PRIVATE', 'ACTIVE');"
        % (kb_id, quote("CALIB_%d" % kb_id), quote(KB_NAME), quote("置信度校准用，由 seed-calibration-data.py 生成"))
    )
    print("已建验证库 id=%d" % kb_id)

    statements = []
    for claim in claims:
        statements.append(
            "INSERT INTO t_claim (knowledge_base_id, subject, predicate, object_value, status, confidence) VALUES "
            "(%d, %s, %s, %s, %s, %s);"
            % (
                kb_id,
                quote((claim.get("subject") or "")[:255]),
                quote((claim.get("predicate") or "")[:64]),
                quote((claim.get("object") or "")[:500]),
                quote(claim.get("status") or rule_claim(claim)),
                float(claim.get("confidence") or 0),
            )
        )
    for card in cards:
        statements.append(
            "INSERT INTO t_knowledge_card (knowledge_base_id, title, content, type, status, confidence) VALUES "
            "(%d, %s, %s, %s, %s, %s);"
            % (
                kb_id,
                quote((card.get("title") or "")[:255]),
                quote((card.get("content") or "")[:2000]),
                quote((card.get("type") or "FACT")[:32]),
                quote(card.get("status") or rule_card(card)),
                float(card.get("confidence") or 0),
            )
        )
    for statement in statements:
        sql(statement)

    def tally(items: list[dict], rule) -> tuple[int, int]:
        approved = sum(1 for item in items if (item.get("status") or rule(item)) == "APPROVED")
        return approved, len(items) - approved

    c_ok, c_no = tally(claims, rule_claim)
    k_ok, k_no = tally(cards, rule_card)
    print("\n已写入库 %d：断言 %d（批准 %d / 驳回 %d）  卡片 %d（批准 %d / 驳回 %d）"
          % (kb_id, len(claims), c_ok, c_no, len(cards), k_ok, k_no))
    print("下一步：python3 scripts/calibrate-confidence.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
