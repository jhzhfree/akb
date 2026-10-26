#!/usr/bin/env python3
"""Check extraction confidence against how reviewers actually ruled.

A confidence score only means something if higher scores survive human review
more often than lower ones. This buckets claims and cards by the confidence the
extractor assigned and reports how each bucket was ruled, so the weights in
``extractor._blend`` can be checked against reality instead of intuition.

Buckets with no human ruling at all (everything auto-approved) carry no signal:
the script says so rather than reporting a meaningless approval rate.
"""

import subprocess
import sys
from collections import defaultdict

MYSQL = [
    "docker", "exec", "kos-dev-mysql", "mysql",
    "-uknowledge", "-pknowledge123", "--default-character-set=utf8mb4",
    "-D", "knowledge_os", "-N", "-e",
]

# 自动批准（宽松模式）也会写 APPROVED，只有 REJECTED 一定是人工判的；UNCERTAIN 多数
# 来自人工退回。所以"有没有人工信号"看这两者是否存在。
HUMAN_RULED = ("REJECTED", "UNCERTAIN")


def query(sql: str) -> list[str]:
    result = subprocess.run(MYSQL + [sql], capture_output=True, text=True)
    return [
        line for line in result.stdout.split("\n")
        if line.strip() and "Warning" not in line and "password" not in line
    ]


def report(table: str, label: str) -> bool:
    rows = query(
        f"SELECT ROUND(confidence, 1), status, COUNT(*) FROM {table} "
        f"WHERE confidence IS NOT NULL GROUP BY ROUND(confidence, 1), status;"
    )
    if not rows:
        print(f"{label}: 没有数据")
        return False

    buckets: dict[str, dict[str, int]] = defaultdict(dict)
    for line in rows:
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        bucket, status, count = parts[0], parts[1], int(parts[2])
        buckets[bucket][status] = buckets[bucket].get(status, 0) + count

    print(f"\n=== {label} ===")
    print(f"{'置信度档':>8} {'总数':>6} {'批准':>6} {'驳回':>6} {'待审':>6} {'人工认可率':>10}")
    human_seen = False
    for bucket in sorted(buckets, key=lambda item: float(item)):
        counts = buckets[bucket]
        total = sum(counts.values())
        approved = counts.get("APPROVED", 0)
        rejected = counts.get("REJECTED", 0)
        uncertain = counts.get("UNCERTAIN", 0)
        human = rejected + uncertain
        if human:
            human_seen = True
            rate = f"{approved / total * 100:.0f}%"
        else:
            rate = "—"
        print(f"{bucket:>8} {total:>6} {approved:>6} {rejected:>6} {uncertain:>6} {rate:>10}")

    if not human_seen:
        print("  没有人工驳回/退回记录：全是自动批准，这一批数据校准不出结论。")
        print("  需要审核面板里做过裁决（驳回或退回待审）之后，认可率才有意义。")
    return human_seen


def main() -> int:
    claim_ok = report("t_claim", "断言 t_claim")
    card_ok = report("t_knowledge_card", "卡片 t_knowledge_card")
    if not (claim_ok or card_ok):
        print("\n结论：暂无法校准。先在审核面板对一批知识做裁决，再跑这个脚本。")
        return 1
    print("\n判读：高置信档的认可率应明显高于低置信档。若各档持平，")
    print("说明权重没有区分能力，需要调 extractor._blend 里的权重或做 Platt 校准。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
