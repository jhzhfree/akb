#!/usr/bin/env python3
"""场景模板生成配置校验与生成质量验证。

KbScenarioTemplates 的 Javadoc 写着「默认值是量出来的，不是猜出来的」，但仓库里没有留下
量的方法：改一次默认值，没有机器判断它是否更好，只能凭印象。这个脚本把那句话变成可执行的
检查，分两层：

1. 默认参数体检（只需要后端可达）
   从 GET /api/knowledge/settings/scenario 取权威 schema（模板目录 + 取值范围），逐个场景校验
   生成段是否自洽、是否落在后端会夹紧的边界内，以及**是否符合该场景的设计意图**——运维库的
   bm25 权重就该是最高，制度库的 claim 就该压过全文，这些不是数值巧合而是场景定位。

2. 生成质量验证（需要给出各场景的知识库 id）
   对真实知识库抽样卡片与断言，统计「每篇文档产出多少可召回对象」「有多少是兜底产物」
   「有多少带证据锚点」「有多少状态可被检索放行」。生成质量不是看产出了多少，而是看产出的
   东西能不能被召回、能不能被引用。

用法：
    # 只做默认参数体检
    python3 scripts/check-kb-scenario-templates.py --base-url http://localhost:8080

    # 连带验证三个场景知识库的实际生成质量
    python3 scripts/check-kb-scenario-templates.py \
        --kb OPS=10 --kb POLICY=11 --kb BUSINESS=12

    # 后端不在本机时，用事先保存的 schema 快照体检
    python3 scripts/check-kb-scenario-templates.py --scenario-json snapshot.json

退出码：0 = 全部通过（允许 WARN）；1 = 存在 FAIL 或校验无法执行。
"""
import argparse
import datetime as _dt
import json
import os
import sys
import urllib.error
import urllib.request

CHANNELS = ("bm25", "vector", "card", "claim", "graph")
SCENARIOS = ("GENERAL", "OPS", "POLICY", "BUSINESS", "VULN")

# 各场景的设计意图：写在这里是把它固化成断言，而不是留在注释里靠人记住。
# 每条是 (场景, 断言名, 判定函数 profile->bool, 期望说明)。
FALLBACK_PREDICATES = {"DESCRIBES", "描述", "RELATES_TO"}
# 兜底抽取的 object 是从句子里切的一段，长度有上限且常常切在半句上。
FALLBACK_OBJECT_MIN = 90
RECALLABLE_STATUS = {"APPROVED", "EFFECTIVE", "PUBLISHED"}


def intent_rules():
    """场景意图断言：默认参数必须体现该场景「靠什么命中」。"""
    def top_channel(profile, name):
        channels = (profile.get("query") or {}).get("channels") or {}
        weights = {k: float((channels.get(k) or {}).get("weight", 0)) for k in CHANNELS}
        return max(weights, key=lambda k: weights[k]) == name

    def chunk_of(profile):
        return (profile.get("generation") or {}).get("chunk") or {}

    def extract_of(profile):
        return (profile.get("generation") or {}).get("extract") or {}

    def query_of(profile):
        return profile.get("query") or {}

    def derivation_of(profile):
        return profile.get("derivation") or {}

    return [
        ("OPS", "精确命中优先于语义", lambda p: top_channel(p, "bm25"),
         "运维问法以错误码与命令为主，bm25 权重应为最高"),
        ("OPS", "步骤可单独命中", lambda p: chunk_of(p).get("strategy") == "STRUCTURE"
         and bool(chunk_of(p).get("parentChild")),
         "处置步骤需要单独成块并被引用，应为章节结构 + 父子分块"),
        ("POLICY", "断言优先于全文", lambda p: top_channel(p, "claim"),
         "条款问法以条件与适用范围为主，claim 权重应为最高"),
        ("POLICY", "条款库不做实体消歧", lambda p: extract_of(p).get("entityResolution") is False,
         "制度库的主体是条款而非实体，实体消歧收益低且会稀释图谱"),
        ("POLICY", "必须检出新旧口径冲突", lambda p: extract_of(p).get("conflictDetection") is True,
         "制度库必然存在修订前后的口径差异，冲突检测不可关闭"),
        ("POLICY", "答案默认带引用章节", lambda p: query_of(p).get("answerFormat") == "markdown",
         "条款答案需要可核对出处，默认格式应为 markdown"),
        ("BUSINESS", "图谱优先", lambda p: top_channel(p, "graph") and derivation_of(p).get("graphEnabled") is True,
         "归属与关系问题靠图谱遍历，graph 权重最高且必须建图"),
        ("BUSINESS", "实体消歧必开", lambda p: extract_of(p).get("entityResolution") is True,
         "同一个人在不同文档里写法不同，不消歧则图谱会分裂"),
        ("VULN", "编号精确命中", lambda p: top_channel(p, "bm25"),
         "CVE 编号是精确术语，bm25 权重应为最高"),
        ("VULN", "影响面靠断言", lambda p: extract_of(p).get("claims") is True,
         "受影响版本与修复状态是典型断言，claims 不可关闭"),
        ("GENERAL", "不偏向任何通道",
         lambda p: max(float(((query_of(p).get("channels") or {}).get(k) or {}).get("weight", 0)) for k in CHANNELS) <= 1.2,
         "综合库没有可押注的问法，任何通道权重都不应一枝独秀"),
    ]


class Report:
    """收集检查结论：PASS / WARN / FAIL 三档，FAIL 决定退出码。"""

    def __init__(self):
        self.rows = []

    def add(self, section, name, status, detail=""):
        self.rows.append({"section": section, "name": name, "status": status, "detail": detail})

    def ok(self, section, name, detail=""):
        self.add(section, name, "PASS", detail)

    def warn(self, section, name, detail=""):
        self.add(section, name, "WARN", detail)

    def fail(self, section, name, detail=""):
        self.add(section, name, "FAIL", detail)

    def expect(self, section, name, condition, detail=""):
        self.add(section, name, "PASS" if condition else "FAIL", detail)
        return bool(condition)

    def count(self, status):
        return sum(1 for row in self.rows if row["status"] == status)


# ---------------------------------------------------------------- HTTP


def http_json(url, token=None, timeout=15):
    """GET 一个后端接口并返回 ApiResponse 的 data 段；不可用时抛出 RuntimeError。"""
    request = urllib.request.Request(url)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise RuntimeError("HTTP %s %s" % (exc.code, url)) from exc
    except (urllib.error.URLError, OSError) as exc:
        raise RuntimeError("无法连接 %s：%s" % (url, exc)) from exc
    if isinstance(payload, dict) and "data" in payload:
        return payload["data"]
    return payload


def login(base_url, username, password):
    """Console login: returns the Bearer token, or raises RuntimeError."""
    body = json.dumps({"username": username, "password": password}).encode("utf-8")
    request = urllib.request.Request(base_url + "/api/auth/login", data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError, OSError) as exc:
        raise RuntimeError("登录失败：%s" % exc) from exc
    data = payload.get("data") if isinstance(payload, dict) else None
    data = data if isinstance(data, dict) else payload
    for key in ("token", "accessToken", "access_token", "idToken"):
        if data.get(key):
            return str(data[key])
    raise RuntimeError("登录响应里没有 token")


def unwrap_list(payload):
    """后端列表接口有的直接给数组，有的包一层 {items|records}。"""
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("items", "records", "list", "content"):
            if isinstance(payload.get(key), list):
                return payload[key]
    return []


# ---------------------------------------------------------------- 静态体检


def check_schema(report, schema):
    """取值范围与词表本身要完整，否则界面上的滑杆与后端夹紧会不一致。"""
    scenarios = schema.get("scenarios") or []
    codes = [item.get("code") for item in scenarios]
    for code in SCENARIOS:
        report.expect("模板", "场景 %s 存在" % code, code in codes, "缺失场景无法被选择")
    for item in scenarios:
        code = item.get("code")
        default = item.get("default") or {}
        report.expect("模板", "%s 有默认档案" % code,
                      bool(default.get("generation") and default.get("derivation") and default.get("query")),
                      "模板不带默认值等于要求用户从零调参")
        report.expect("模板", "%s 有说明与适用资料" % code,
                      bool(item.get("label")) and bool(item.get("summary")) and bool(item.get("docTypes")),
                      "场景选择要能看懂，不能只有一个编码")
    ranges = schema.get("ranges") or {}
    for key in ("chunkSize", "chunkOverlap", "minChunkSize", "weight", "rrfK", "contextMaxChars"):
        report.expect("模板", "取值范围含 %s" % key, key in ranges, "缺少边界时前端会退回自己的默认值")


def check_generation(report, schema):
    """生成段自洽：数值在界内、开关与图谱一致、默认策略不会路由到关闭的通道。"""
    ranges = schema.get("ranges") or {}
    strategies = {item.get("name") for item in ((schema.get("chunk") or {}).get("strategies") or [])}
    measures = {item.get("name") for item in ((schema.get("chunk") or {}).get("measures") or [])}
    presets = {item.get("code") for item in ((schema.get("derivation") or {}).get("presets") or [])}
    graph_free_presets = {"CLAIM_SENTENCE", "AI_CONCLUSION"}

    for item in schema.get("scenarios") or []:
        code = item.get("code")
        default = item.get("default") or {}
        section = "生成·%s" % code
        chunk = ((default.get("generation") or {}).get("chunk")) or {}
        extract = ((default.get("generation") or {}).get("extract")) or {}
        derivation = default.get("derivation") or {}
        query = default.get("query") or {}

        size = int(chunk.get("chunkSize") or 0)
        overlap = int(chunk.get("chunkOverlap") or 0)
        minimum = int(chunk.get("minChunkSize") or 0)
        size_range = ranges.get("chunkSize") or {}
        report.expect(section, "窗口大小在取值范围内",
                      size_range.get("min", 200) <= size <= size_range.get("max", 4000),
                      "chunkSize=%s" % size)
        report.expect(section, "重叠不超过半个窗口", 0 <= overlap <= size // 2,
                      "chunkOverlap=%s / chunkSize=%s" % (overlap, size))
        report.expect(section, "最小片段不超过半个窗口", 0 <= minimum <= size // 2,
                      "minChunkSize=%s / chunkSize=%s" % (minimum, size))
        report.expect(section, "切片策略合法", chunk.get("strategy") in strategies, str(chunk.get("strategy")))
        report.expect(section, "计量方式合法", chunk.get("measure") in measures, str(chunk.get("measure")))
        if chunk.get("strategy") == "PARENT_CHILD":
            report.expect(section, "父子分块开关与策略一致", bool(chunk.get("parentChild")),
                          "策略为 PARENT_CHILD 时开关必须为开")

        graph_enabled = derivation.get("graphEnabled") is True
        if not graph_enabled:
            used = [p for p in (derivation.get("presets") or []) if p not in graph_free_presets]
            report.expect(section, "不建图时不装图谱预设", not used, "装了也跑不出东西：" + ",".join(used))
            channel = ((query.get("channels") or {}).get("graph")) or {}
            report.expect(section, "不建图时关闭图谱通道", channel.get("enabled") is not True,
                          "空通道只会白花召回预算")
        unknown = [p for p in (derivation.get("presets") or []) if presets and p not in presets]
        report.expect(section, "衍生预设均已登记", not unknown, ",".join(unknown))

        # 默认策略是路由的落点：路由到已关闭的通道等于这次召回什么都没有。
        channels = query.get("channels") or {}
        strategies_used = query.get("defaultStrategies") or []
        report.expect(section, "默认召回通道非空", bool(strategies_used), "空策略等于没有默认路由")
        closed = [name for name in strategies_used if (channels.get(name) or {}).get("enabled") is not True]
        report.expect(section, "默认通道均已启用", not closed, "已关闭却被路由：" + ",".join(closed))
        bad = [name for name, cfg in channels.items()
               if cfg.get("enabled") is True and not 0.0 <= float(cfg.get("weight", 0)) <= 5.0]
        report.expect(section, "通道权重在 0-5 之间", not bad, ",".join(bad))

        for flag in ("claims", "entityResolution", "conflictDetection"):
            value = extract.get(flag)
            report.expect(section, "抽取开关 %s 为布尔" % flag, isinstance(value, bool), str(value))
        # 断言是冲突检测的输入：关掉断言再开冲突检测，永远不会检出冲突。
        if extract.get("conflictDetection") is True and extract.get("claims") is not True:
            report.fail(section, "冲突检测依赖断言", "conflictDetection=true 但 claims=false")


def check_intent(report, schema):
    """默认参数是否真的体现了场景定位：这是「默认参数确认」的核心。"""
    defaults = {item.get("code"): (item.get("default") or {}) for item in (schema.get("scenarios") or [])}
    for code, name, predicate, why in intent_rules():
        profile = defaults.get(code)
        if not profile:
            report.fail("意图", "%s · %s" % (code, name), "场景模板缺失")
            continue
        try:
            passed = bool(predicate(profile))
        except Exception as exc:  # 断言本身不该炸，炸了说明默认值结构变了
            report.fail("意图", "%s · %s" % (code, name), "判定失败：%s" % exc)
            continue
        report.expect("意图", "%s · %s" % (code, name), passed, why)


# ---------------------------------------------------------------- 在线质量


def claim_object(claim):
    return str(claim.get("objectValue") or claim.get("object") or "")


def is_fallback_claim(claim):
    """兜底产物：predicate 恒为 DESCRIBES，object 是从句子切下的一段。"""
    predicate = str(claim.get("predicate") or "").strip().upper()
    if predicate in FALLBACK_PREDICATES:
        return True
    value = claim_object(claim)
    if len(value) >= FALLBACK_OBJECT_MIN and not str(claim.get("subject") or "").strip():
        return True
    return len(value) >= FALLBACK_OBJECT_MIN and value.rstrip().endswith(("…", "..."))


def has_evidence(item):
    for key in ("evidenceIds", "evidence_ids", "evidenceCodes", "evidence"):
        value = item.get(key)
        if isinstance(value, list) and value:
            return True
        if isinstance(value, str) and value.strip():
            return True
    return bool(str(item.get("sourceCode") or "").strip())


def check_quality(report, base_url, token, code, kb, scenario_defaults):
    """一个场景知识库的实际生成质量：产了多少、多少能用、多少是兜底。"""
    section = "质量·%s" % code
    try:
        overview = http_json("%s/api/knowledge/overview/%s" % (base_url, kb), token)
        cards = unwrap_list(http_json("%s/api/knowledge/cards/%s" % (base_url, kb), token))
        claims = unwrap_list(http_json("%s/api/knowledge/claims/%s" % (base_url, kb), token))
    except RuntimeError as exc:
        report.fail(section, "读取知识库 %s" % kb, str(exc))
        return None

    docs = int(overview.get("documentCount") or 0)
    chunks = int(overview.get("chunkCount") or 0)
    card_total = int(overview.get("cardCount") or 0) or len(cards)
    claim_total = int(overview.get("claimCount") or 0) or len(claims)
    metrics = {
        "kb": kb, "documents": docs, "chunks": chunks, "cards": card_total, "claims": claim_total,
        "effectiveCards": int(overview.get("effectiveCardCount") or 0),
        "openConflicts": int(overview.get("openConflictCount") or 0),
    }

    report.expect(section, "有文档可生成", docs > 0, "documentCount=%s" % docs)
    if docs:
        per_doc = chunks / docs
        report.expect(section, "每篇文档都切出了片段", per_doc >= 1, "chunk/doc=%.1f" % per_doc)
        report.expect(section, "每篇文档都产出了卡片", card_total >= docs, "card/doc=%.1f" % (card_total / docs))

    extract = ((scenario_defaults.get("generation") or {}).get("extract")) or {}
    if extract.get("claims") is True:
        report.expect(section, "断言开关已开就有断言", claim_total > 0, "claimCount=%s" % claim_total)
    if extract.get("conflictDetection") is True:
        # 冲突是数据集刻意埋的口径差异：一条都没检出，多半是抽取没给出可比对的断言。
        report.expect(section, "冲突检测有产出或库内本无冲突",
                      int(overview.get("openConflictCount") or 0) > 0 or claim_total == 0,
                      "openConflictCount=%s" % metrics["openConflicts"])

    sample_claims = claims[:200]
    if sample_claims:
        fallback = sum(1 for item in sample_claims if is_fallback_claim(item))
        rate = fallback / len(sample_claims)
        metrics["fallbackClaimRate"] = round(rate, 3)
        status = "FAIL" if rate > 0.8 else "WARN" if rate > 0.5 else "PASS"
        report.add(section, "断言非兜底产物", status,
                   "兜底率 %.0f%%（%s/%s 抽样）" % (rate * 100, fallback, len(sample_claims)))
        evidenced = sum(1 for item in sample_claims if has_evidence(item))
        evidence_rate = evidenced / len(sample_claims)
        metrics["evidenceRate"] = round(evidence_rate, 3)
        report.expect(section, "断言带证据锚点", evidence_rate >= 0.5,
                      "%.0f%% 带证据" % (evidence_rate * 100))
        recallable = sum(1 for item in sample_claims
                         if str(item.get("status") or "").upper() in RECALLABLE_STATUS)
        metrics["recallableClaimRate"] = round(recallable / len(sample_claims), 3)
        if recallable == 0:
            report.warn(section, "断言可被检索召回",
                        "抽样中 0 条处于 %s，检索闸门会全部过滤" % "/".join(sorted(RECALLABLE_STATUS)))
        else:
            report.ok(section, "断言可被检索召回", "%s/%s 已放行" % (recallable, len(sample_claims)))

    sample_cards = cards[:200]
    if sample_cards:
        recallable = sum(1 for item in sample_cards
                         if str(item.get("status") or "").upper() in RECALLABLE_STATUS)
        metrics["recallableCardRate"] = round(recallable / len(sample_cards), 3)
        if recallable == 0:
            report.warn(section, "卡片可被检索召回",
                        "抽样中 0 张处于 %s，card 通道等于空" % "/".join(sorted(RECALLABLE_STATUS)))
        else:
            report.ok(section, "卡片可被检索召回", "%s/%s 已放行" % (recallable, len(sample_cards)))
    return metrics


# ---------------------------------------------------------------- main


def print_report(report, metrics_by_scenario):
    print("=" * 78)
    print("场景模板 · 默认参数与生成质量校验")
    print("=" * 78)
    width = max(len("%s · %s" % (row["section"], row["name"])) for row in report.rows) if report.rows else 20
    for row in report.rows:
        print("%-6s %-*s  %s" % (row["status"], width, "%s · %s" % (row["section"], row["name"]), row["detail"]))
    if metrics_by_scenario:
        print("-" * 78)
        print("%-10s %6s %7s %7s %7s %9s %9s" % ("场景", "文档", "片段", "卡片", "断言", "兜底率", "召回率"))
        for code, metrics in metrics_by_scenario.items():
            if not metrics:
                continue
            print("%-10s %6s %7s %7s %7s %9s %9s" % (
                code,
                metrics.get("documents", 0), metrics.get("chunks", 0),
                metrics.get("cards", 0), metrics.get("claims", 0),
                "%.0f%%" % ((metrics.get("fallbackClaimRate") or 0) * 100),
                "%.0f%%" % ((metrics.get("recallableClaimRate") or 0) * 100)))
    print("-" * 78)
    print("PASS %s / WARN %s / FAIL %s" % (report.count("PASS"), report.count("WARN"), report.count("FAIL")))


def main():
    parser = argparse.ArgumentParser(description="场景模板默认参数体检与各场景生成质量验证")
    parser.add_argument("--base-url", default=os.environ.get("KOS_BASE_URL", "http://localhost:8080"),
                        help="后端地址，默认 http://localhost:8080")
    parser.add_argument("--token", default=os.environ.get("KOS_TOKEN", ""), help="管理员 Bearer token")
    parser.add_argument("--username", default=os.environ.get("KOS_ADMIN_USER", "admin"), help="登录账号")
    parser.add_argument("--password", default=os.environ.get("KOS_ADMIN_PASSWORD", ""), help="登录密码")
    parser.add_argument("--scenario-json", help="本地 schema 快照（后端不可达时用于静态体检）")
    parser.add_argument("--kb", action="append", default=[], metavar="CODE=ID",
                        help="场景与知识库 id 的对应关系，可重复，如 --kb POLICY=11")
    parser.add_argument("--report", help="写出 JSON 报告的路径")
    args = parser.parse_args()

    token = args.token
    if not token and args.password:
        try:
            token = login(args.base_url, args.username, args.password)
        except RuntimeError as exc:
            print("登录失败：%s" % exc)
            return 1

    report = Report()
    schema = None
    if args.scenario_json:
        try:
            with open(args.scenario_json, encoding="utf-8") as handle:
                snapshot = json.load(handle)
            schema = snapshot.get("schema") or snapshot
        except (OSError, ValueError) as exc:
            report.fail("模板", "读取 schema 快照", str(exc))
    if schema is None:
        try:
            settings = http_json("%s/api/knowledge/settings/scenario?kb=1" % args.base_url, token)
            schema = settings.get("schema")
        except RuntimeError as exc:
            report.fail("模板", "读取场景 schema", str(exc))
    if not schema:
        print_report(report, {})
        return 1

    check_schema(report, schema)
    check_generation(report, schema)
    check_intent(report, schema)

    defaults = {item.get("code"): (item.get("default") or {}) for item in (schema.get("scenarios") or [])}
    metrics_by_scenario = {}
    for pair in args.kb:
        if "=" not in pair:
            report.fail("质量", "参数 --kb %s" % pair, "应为 CODE=ID 形式")
            continue
        code, _, raw_kb = pair.partition("=")
        code = code.strip().upper()
        if not raw_kb.strip().isdigit():
            report.fail("质量", "参数 --kb %s" % pair, "知识库 id 必须为数字")
            continue
        if code not in SCENARIOS:
            report.warn("质量", "场景 %s" % code, "不在内置场景内，按 GENERAL 的开关校验")
        metrics_by_scenario[code] = check_quality(report, args.base_url, token, code,
                                                  raw_kb.strip(), defaults.get(code) or {})

    print_report(report, metrics_by_scenario)
    if args.report:
        payload = {
            "generatedAt": _dt.datetime.now().isoformat(timespec="seconds"),
            "baseUrl": args.base_url,
            "checks": report.rows,
            "metrics": metrics_by_scenario,
        }
        with open(args.report, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        print("报告已写入 %s" % args.report)
    return 1 if report.count("FAIL") else 0


if __name__ == "__main__":
    sys.exit(main())
