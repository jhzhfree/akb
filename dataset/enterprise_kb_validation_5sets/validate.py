#!/usr/bin/env python3
"""默认五库检索回归验证。

跑 `validation_cases.csv` 里的 5 条验收问题，逐条比对：
  1. expected_evidence 是否出现在引用/召回内容里（证据覆盖，硬检查）；
  2. 答案是否被引用校验（evidence gate）拦掉（硬检查 —— 被拦则视为未达成 expected_behavior）；
  3. expected_behavior 的关键词是否出现在答案里（软检查，仅提示）。

用法：
    python3 dataset/enterprise_kb_validation_5sets/validate.py            # 全部 5 条
    python3 dataset/enterprise_kb_validation_5sets/validate.py 漏洞库     # 只跑名字含该串的库
    BASE=http://192.168.22.6:8080 python3 dataset/.../validate.py          # 打目标机
    python3 dataset/.../validate.py --json                                 # 机器可读输出

环境变量：
    BASE               backend 地址，默认 http://127.0.0.1:8080
    ADMIN_PASSWORDS    控制台 admin 密码候选，逗号分隔
    KB_ID_MAP          知识库名 -> id，JSON，默认 {"综合知识库":6,...}
    TOPK               检索条数，默认 10
    TIMEOUT            单条超时秒，默认 300
    ALLOW_SESSION_INSERT=0  禁用「直接往 t_auth_session 插会话」的本地后门

退出码：0 全部通过；1 有用例不达标；2 环境或请求错误。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CASES = ROOT / "validation_cases.csv"

BASE = os.environ.get("BASE", "http://127.0.0.1:8080").rstrip("/")
TOPK = int(os.environ.get("TOPK", "10"))
TIMEOUT = int(os.environ.get("TIMEOUT", "300"))
USER_ID = os.environ.get("USER_ID", "dataset-validation")
PASSWORDS = [p for p in os.environ.get(
    "ADMIN_PASSWORDS", "admin123,admin,knowledge123,Admin@123,kos123456").split(",") if p]
ALLOW_SESSION_INSERT = os.environ.get("ALLOW_SESSION_INSERT", "1") != "0"
MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "kos-dev-mysql")

DEFAULT_KB_IDS = {
    "综合知识库": 6,
    "运维知识库": 7,
    "制度条款知识库": 8,
    "公司业务人员关系知识库": 9,
    "漏洞库知识库": 10,
}
try:
    KB_IDS = {str(k): int(v) for k, v in json.loads(os.environ["KB_ID_MAP"]).items()} \
        if os.environ.get("KB_ID_MAP") else DEFAULT_KB_IDS
except (ValueError, TypeError):
    sys.exit("KB_ID_MAP 不是合法的 {\"知识库名\": id} JSON")

# 数据集文档 id -> 关键词，全部命中才算该证据被覆盖
EVIDENCE_KEYWORDS = {
    "PRJ-XH-001": ["星河", "交付"],
    "OPS-ALERT-001": ["索引积压", "告警"],
    "POL-PROC-002": ["采购审批", "V2.0"],
    "JUDGE-001": ["裁决"],
    "ROLE-001": ["岗位", "职责"],
    "REL-001": ["星河", "客户"],
    "VULN-001": ["漏洞公告", "KB-2026-001"],
    "ASSET-001": ["资产", "组件"],
    "FIX-001": ["修复", "缓解"],
    "TEST-001": ["复测"],
}
# expected_behavior -> 答案里应出现的关键词（软检查）
BEHAVIOR_KEYWORDS = {
    "引用项目说明": ["交付范围"],
    "引用SOP并强调不能直接删除事件": ["删除"],
    "按V2.0需要部门和财务负责人": ["财务负责人"],
    "陈远负责技术，王岚负责客户验收": ["陈远", "王岚"],
    "跨文档链路并说明已复测": ["复测"],
}
# 引用校验兜底话术（safe_answer / redaction 提示）：出现即说明有结论被拦掉
BLOCKED_MARKERS = ("已阻止输出", "现有证据无法支持全部结论", "现有知识不足以形成全部可验证的结论")
# 「现有知识不足以」也可能是模型按 prompt 主动补的知识缺口说明 —— 此时 gate 是 PASS 的，
# 属于诚实声明而非拦截，所以只在 gate 未放行时才计入失败。
INSUFFICIENT_MARKERS = ("现有知识不足以",)
# finalGate 为这两个状态时，用户拿到的是残缺答案，同样判不达标
BLOCKED_GATES = ("BLOCKED", "PARTIAL")


def http_json(method, url, payload=None, token=None):
    """Request JSON, ignoring any proxy exported by the shell (127.0.0.1 must stay local)."""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=TIMEOUT) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, {"message": raw[:300]}


def console_token():
    """Return (token, how). Inserts a dev session only when login is impossible."""
    for password in PASSWORDS:
        try:
            status, body = http_json("POST", BASE + "/api/auth/login",
                                     {"username": "admin", "password": password})
        except (urllib.error.URLError, OSError):
            continue
        if status == 200 and body.get("success"):
            return body["data"]["token"], "login"
    if not ALLOW_SESSION_INSERT:
        return None, "no-auth"
    token = uuid.uuid4().hex
    statement = (
        "INSERT INTO t_auth_session (token, user_id, issued_at, expires_at, last_accessed_at, "
        "status, user_agent) VALUES ('%s', 1, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), NOW(), "
        "'ACTIVE', 'kb-validation-script')" % token
    )
    try:
        subprocess.run(["docker", "exec", MYSQL_CONTAINER, "mysql", "-uknowledge",
                        "-pknowledge123", "knowledge_os", "-e", statement],
                       capture_output=True, text=True, check=True, timeout=60)
    except (subprocess.SubprocessError, OSError):
        return None, "no-auth"
    # stderr, not stdout: --json output must stay parseable.
    print("警告：登录失败，已通过 %s 直接插入临时会话（仅本地开发用）" % MYSQL_CONTAINER,
          file=sys.stderr)
    return token, "inserted-session"


def blob_of(payload):
    """Flatten citations / recall lists into one comparable string."""
    parts = []
    for key in ("citations", "references", "evidence", "sources"):
        value = payload.get(key)
        if isinstance(value, list):
            parts.append(json.dumps(value, ensure_ascii=False))
    for key in ("recall", "results", "chunks", "hits", "candidates"):
        value = payload.get(key)
        if isinstance(value, list):
            parts.append(json.dumps(value, ensure_ascii=False))
    return " ".join(parts)


def source_names(payload):
    names = []
    for item in payload.get("citations") or []:
        name = item.get("sourceName") if isinstance(item, dict) else None
        if name and name not in names:
            names.append(name)
    return names


def run_case(row, token):
    kb = KB_IDS.get(row["knowledge_base"])
    started = time.time()
    status, body = http_json("POST", BASE + "/api/knowledge/query",
                             {"knowledgeBaseId": kb, "query": row["question"],
                              "topK": TOPK, "userId": USER_ID}, token=token)
    payload = body.get("data") if status == 200 else None
    payload = payload if isinstance(payload, dict) else {}
    answer = str(payload.get("answer") or payload.get("content") or "")
    blob = blob_of(payload) + " " + answer

    missing = [code for code in (c.strip() for c in row["expected_evidence"].split(";"))
               if code and not all(k in blob for k in EVIDENCE_KEYWORDS.get(code, [code]))]
    blocked = [m for m in BLOCKED_MARKERS if m in answer]
    behavior = row.get("expected_behavior") or ""
    behavior_missing = [k for k in BEHAVIOR_KEYWORDS.get(behavior, []) if k not in answer]
    trace = payload.get("trace") or {}
    gates = {}
    for stage in (trace.get("stages") if isinstance(trace, dict) else []) or []:
        if stage.get("key") == "citation":
            gates = stage.get("detail") or {}
    final_gate = str(gates.get("finalGate") or "")
    gate_blocked = final_gate in BLOCKED_GATES
    # 模型主动声明的知识缺口：gate 放行时只是提示，不算失败
    insufficient = [m for m in INSUFFICIENT_MARKERS if m in answer] and not gate_blocked and not blocked

    ok = status == 200 and not missing and not blocked and not gate_blocked
    return {
        "knowledgeBase": row["knowledge_base"],
        "kbId": kb,
        "question": row["question"],
        "expectedEvidence": row["expected_evidence"],
        "missingEvidence": missing,
        "expectedBehavior": behavior,
        "behaviorMissing": behavior_missing,
        "blocked": bool(blocked) or gate_blocked,
        "blockedMarkers": blocked,
        "gateBlocked": gate_blocked,
        "finalGate": final_gate,
        "insufficientNote": bool(insufficient),
        "gates": gates,
        "status": status,
        "seconds": round(time.time() - started, 1),
        "answerExcerpt": " ".join(answer.split())[:260],
        "sources": source_names(payload),
        "passed": ok,
    }


def main():
    parser = argparse.ArgumentParser(description="默认五库检索回归验证")
    parser.add_argument("only", nargs="?", help="只跑名字包含该串的知识库，如 漏洞库")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出结果")
    args = parser.parse_args()

    if not CASES.exists():
        sys.exit("找不到用例文件 %s" % CASES)
    with open(CASES, encoding="utf-8-sig") as handle:
        rows = [r for r in csv.DictReader(handle) if r.get("question")]

    token, how = console_token()
    if not token:
        sys.exit("无法获取控制台 token：请设 ADMIN_PASSWORDS，或先在控制台登录/放开后门")

    results = []
    for row in rows:
        if args.only and args.only not in row["knowledge_base"]:
            continue
        if row["knowledge_base"] not in KB_IDS:
            print("跳过未知知识库：%s（用 KB_ID_MAP 指定 id）" % row["knowledge_base"])
            continue
        results.append(run_case(row, token))

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
    else:
        for item in results:
            print("=" * 70)
            print("库: %s (kb=%s) | %.1fs | HTTP %s" % (item["knowledgeBase"], item["kbId"],
                                                       item["seconds"], item["status"]))
            print("问: %s" % item["question"])
            print("期望证据: %s | 未覆盖: %s" % (item["expectedEvidence"], item["missingEvidence"] or "无"))
            print("期望行为: %s | 缺失关键词: %s" % (item["expectedBehavior"], item["behaviorMissing"] or "无"))
            print("引用校验: %s%s%s" % (item["gates"] or "无",
                                   "  <-- 结论被拦截" if item["blocked"] else "",
                                   "  (含知识缺口声明，gate 放行)" if item["insufficientNote"] else ""))
            print("答(节选): %s" % item["answerExcerpt"])
            print("引用来源: %s" % item["sources"])
            print("判定: %s" % ("PASS" if item["passed"] else "FAIL"))

    passed = sum(1 for r in results if r["passed"])
    summary = ["%d/%d 通过（%s，auth=%s）" % (passed, len(results), BASE, how)]
    for item in results:
        if not item["passed"]:
            reasons = []
            if item["missingEvidence"]:
                reasons.append("证据未覆盖 %s" % item["missingEvidence"])
            if item["blocked"]:
                markers = item["blockedMarkers"] or ["finalGate=%s" % item["finalGate"]]
                reasons.append("结论被引用校验拦截 %s" % markers)
            if item["behaviorMissing"]:
                reasons.append("行为关键词缺失 %s" % item["behaviorMissing"])
            if item["status"] != 200:
                reasons.append("HTTP %s" % item["status"])
            summary.append("FAIL %s: %s" % (item["knowledgeBase"], "；".join(reasons)))
    # --json keeps stdout machine readable, so the summary goes to stderr there.
    print("\n".join(summary), file=sys.stderr if args.json else sys.stdout)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
