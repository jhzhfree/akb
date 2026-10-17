"""Knowledge extraction orchestration that converts evidence into candidates.

Extraction reads the normalized standard units produced at ingest time, never raw
source text. Standard units are already stripped of format-only content, so a card
title can be "适用范围" instead of "## 适用范围" and a table never lands in a card
as a row of ``|---|`` separators.

Relations are part of the extraction contract: cards and claims carry the entities
they mention, and entities are connected by typed relations, so the knowledge graph
shows edges instead of isolated dots.
"""

import logging
import re
import uuid
from datetime import date, timedelta
from typing import Any

from app.core.knowledge_schema import entity_types, relation_vocabulary
from app.services.llm_provider import get_llm_provider
from app.services.standard_document import clean_inline, has_content

MAX_ENTITIES = 60
MAX_RELATIONS = 60
MAX_RELATIONS_PER_UNIT = 8
# Function words that must never start an entity: they glue clauses together, so a
# phrase captured from running text would otherwise begin with them.
STOP_PREFIXES = (
    "由",
    "与",
    "和",
    "或",
    "对",
    "从",
    "把",
    "被",
    "其",
    "该",
    "并",
    "但",
    "而",
    "则",
    "即",
    "如",
    "是",
    "的",
    "了",
    "也",
    "还",
    "以",
    "所",
    "就",
    "都",
    "又",
    "再",
    "让",
    "使",
    "给",
)
# Characters that glue clauses together. A phrase captured from running text that contains
# one of them is a clause, not a term, so it must not become a graph node.
CONNECTOR_CHARS = (
    "只",
    "能",
    "按",
    "于",
    "并",
    "且",
    "将",
    "使",
    "让",
    "被",
    "把",
    "对",
    "从",
    "由",
    "与",
    "和",
    "是",
    "的",
    "了",
    "也",
    "还",
    "又",
    "再",
    "就",
    "都",
    "很",
    "更",
    "最",
    "不",
    "无",
    "会",
    "可",
    "应",
    "需",
    "优先",
    "必须",
    "因此",
    "但是",
)
RELATION_VOCABULARY = (
    "RELATED_TO",
    "PART_OF",
    "DEPENDS_ON",
    "USES",
    "CAUSES",
    "BELONGS_TO",
    "LOCATED_IN",
    "DESCRIBES",
    "CONTAINS",
    "MANAGES",
    "AFFECTS",
    "DEPLOYED_IN",
)
# How each controlled relation label reads in running text. A model answers in the language it
# was asked in, so a Chinese predicate such as "受影响于" arrives even when the vocabulary is
# English; mapping it back here is what keeps an edge typed instead of collapsing into
# RELATED_TO, which no traversal rule can travel on.
RELATION_HINTS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("AFFECTS", ("受影响", "影响", "波及", "危害", "命中", "存在风险", "affects", "impacts")),
    ("CAUSES", ("导致", "引起", "造成", "引发", "causes")),
    ("DEPENDS_ON", ("依赖", "依赖于", "基于", "depends on")),
    ("USES", ("使用", "采用", "集成", "uses", "utilizes")),
    ("DEPLOYED_IN", ("部署于", "部署在", "部署到", "部署", "上线于", "运行于", "运行在", "deployed")),
    ("BELONGS_TO", ("属于", "归属于", "隶属于", "归属", "belongs to")),
    ("MANAGES", ("管理", "负责", "运维", "owner", "managed by")),
    ("PART_OF", ("组成部分", "的一部分", "组成", "part of")),
    ("CONTAINS", ("包含", "含有", "contains")),
    ("LOCATED_IN", ("位于", "坐落于", "located in")),
    ("DESCRIBES", ("描述", "说明", "定义为", "describes")),
)
RELATION_LABELS: dict[str, str] = {
    "RELATED_TO": "通用关联",
    "PART_OF": "组成部分",
    "DEPENDS_ON": "依赖",
    "USES": "使用",
    "CAUSES": "导致",
    "BELONGS_TO": "归属/属于",
    "LOCATED_IN": "位于",
    "DESCRIBES": "描述",
    "CONTAINS": "包含",
    "MANAGES": "管理/负责",
    "AFFECTS": "受影响于/影响",
    "DEPLOYED_IN": "部署于",
}

# Institutional clauses are written as inline key/value runs separated by a full-width
# semicolon: "假期: 年假；额度: 司龄满 1 年 5 天；申请时限: 提前 3 个工作日". That shape is what
# carries the numbers an exact-calibre question asks for ("年假多少天"), and it is already
# structured in the source, so reading it needs no model: parsing it deterministically is
# both cheaper and more faithful than asking an LLM to retype it.
CLAUSE_PAIR_SEP = re.compile(r"[；;]\s*")
# The field name may carry a citation in parenthesis — "原口径（HR-HB-03 V3.1）" is 18
# characters — so the bound has to leave room for it or the superseded reading is dropped.
CLAUSE_KV = re.compile(r"^\s*([^:：]{1,28})\s*[:：]\s*(.+?)\s*$")
# The field that names what the row is about. Every other field in the row is a property
# of it, so "假期: 年假；额度: 5 天" becomes one claim about 年假, not two unrelated ones.
CLAUSE_SUBJECT_KEYS = (
    "假期",
    "假期类型",
    "项目",
    "事项",
    "情形",
    "场景",
    "类型",
    "类别",
    "名称",
    "制度",
    "条款",
    "岗位",
    "职级",
    "费用",
    "福利",
    "价值观",
)
# Fields whose value is a restatement rather than a property: carrying them into claims
# would only duplicate the subject row in the graph.
CLAUSE_SKIP_KEYS = ("备注", "说明")
# A revision notice states the old and the new reading side by side. Keeping which side a
# clause belongs to is what lets an answer say "before X it was 5 days, from X it is 10".
CLAUSE_SUPERSEDED_KEYS = ("原口径", "旧口径", "原规定", "调整前")
CLAUSE_CURRENT_KEYS = ("新口径", "新规定", "调整后")
CLAUSE_EFFECTIVE_HINT = ("起", "生效", "自", "执行")
CLAUSE_DATE = re.compile(r"(20\d{2})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})")
# What a sentence says about its subject, ordered most specific first.
CLAUSE_PREDICATE_HINTS = (
    ("额度", ("额度", "天数", "上限", "下限", "标准", "金额", "比例", "带宽")),
    ("时限", ("时限", "期限", "有效期", "提前", "截止", "日内", "个工作日")),
    ("流程", ("流程", "步骤", "审批", "提交", "申请")),
    ("责任", ("负责", "归属", "归属部门", "管理", "维护人")),
    ("要求", ("必须", "禁止", "不得", "应当", "需")),
)

# Which field of a row names the subject depends on what the library is about: a runbook
# names its host or component, a handbook its leave type, a directory its post. Reading the
# subject from the scenario's own vocabulary is what keeps "额度: 5 天" attached to the
# thing it actually describes instead of to the first field that happens to match.
SCENARIO_SUBJECT_KEYS: dict[str, tuple[str, ...]] = {
    "OPS": ("故障", "告警", "命令", "错误码", "主机", "服务", "组件", "中间件", "工单", "会话",
            "事件", "规程", "SOP", "事项", "场景", "类型", "名称"),
    "VULN": ("漏洞", "CVE", "编号", "组件", "产品", "服务", "资产", "系统", "事项", "类型", "名称"),
    "BUSINESS": ("岗位", "人员", "部门", "项目", "客户", "产品", "团队", "组织", "事项", "名称", "类型"),
}

# What a model should look for in this kind of library. The scenario profile already tunes
# chunking and the recall weights; without a matching instruction the generation itself
# stays one-size-fits-all, and a library that is asked "影响到谁" gets answers built from
# the same generic claims as one asked "年假多少天".
SCENARIO_FOCUS: dict[str, str] = {
    "OPS": "本库是运维知识库，抽取重点：故障现象与根因、处置步骤（按执行顺序）、命令与错误码、"
           "告警等级与响应/止损时限、责任人。命令与错误码必须原样保留，步骤不要合并成一段。\n",
    "POLICY": "本库是制度条款知识库，抽取重点：条款的适用条件、额度与时限、生效与失效时间、"
              "新旧口径差异。同一条款的多个属性必须逐条成 claim，不要把整段原文塞进一个 object。\n",
    "BUSINESS": "本库是公司业务知识库，抽取重点：岗位与人员、所属部门、汇报关系、项目与客户归属、"
                "产品负责人。人与组织必须单独抽成实体，并在 relations 里给出归属与汇报关系。\n",
    "VULN": "本库是漏洞知识库，抽取重点：漏洞编号（CVE 等）、受影响产品与版本区间、修复版本、"
            "严重程度与修复方案。版本号必须原样保留，影响范围按组件逐条成 claim。\n",
}


def _day_before(date_text: str) -> str:
    """The last day on which a superseded reading still held, as YYYY-MM-DD.

    A rule replaced on 2026-09-01 held up to and including 2026-08-31. Giving the old
    clause that end date makes its window disjoint from the new one, which is how the
    conflict engine recognises a succession instead of reporting a contradiction.
    """
    try:
        parts = [int(part) for part in str(date_text).split("-")]
        if len(parts) != 3:
            return ""
        return (date(parts[0], parts[1], parts[2]) - timedelta(days=1)).isoformat()
    except (TypeError, ValueError):
        return ""


logger = logging.getLogger(__name__)


class KnowledgeExtractor:
    """Extract cards, claims, entities, and relations from source evidence."""

    async def extract(
        self,
        text: str,
        chunks: list | None = None,
        units: list | None = None,
        knowledge_base_id: int | None = None,
        scenario: str = "",
    ) -> dict[str, Any]:
        """Read structured clauses deterministically, then let a model add what is left.

        Model extraction is the only way to reach knowledge written in running prose, but
        it is also the part that fails silently: when it does, an institutional clause
        such as "额度: 司龄满 1 年 5 天" was being lost even though the source states it
        outright. Parsing those rows first means the numbers a policy question asks for
        survive regardless of whether the model answered, and the model only ever adds.

        ``scenario`` is the knowledge base's scenario template (GENERAL / OPS / POLICY /
        BUSINESS / VULN). It decides which vocabulary names the subject of a parsed row and
        what the model is told to look for, so two libraries of different kinds stop being
        generated by the same instruction.
        """
        text = (text or "").strip()
        material = self._material(units, chunks or [])
        if not text:
            text = "\n\n".join(item["content"] for item in material)
        if not text and chunks:
            text = "\n\n".join(chunk.get("content", "") for chunk in chunks)

        policy_claims, policy_cards = self._policy_clauses(material, scenario)

        llm = get_llm_provider()
        # Relation labels come from the published schema of this knowledge base, so a
        # customised vocabulary is honoured without redeploying the extractor.
        vocabulary = relation_vocabulary(knowledge_base_id)
        types = entity_types(knowledge_base_id)
        if llm.name != "mock" and text:
            try:
                # The full budget is spent up front: a reasoning model that runs out of tokens
                # mid-thought returns an empty answer, and losing cards and claims to that is
                # worse than the extra latency of asking for a bigger response once.
                result = await llm.extract_json(
                    self._prompt(text[:14000], material, vocabulary, types, scenario), max_tokens=8192
                )
                if not isinstance(result, dict):
                    # A reasoning model can spend the whole output budget on thinking and return an
                    # empty answer. One stricter retry with the full budget is worth the latency:
                    # the alternative is silently falling back to co-occurrence edges, which are
                    # exactly the untyped RELATED_TO relations no traversal rule can travel on.
                    result = await llm.extract_json(
                        self._strict_prompt(text[:8000], vocabulary, types), max_tokens=8192
                    )
                if isinstance(result, dict):
                    return self._merge(
                        self._normalize(result, material, vocabulary, types),
                        policy_claims,
                        policy_cards,
                        vocabulary,
                    )
            except Exception as exc:
                # Extraction must remain usable offline. Log why fallback was
                # selected instead of silently swallowing the model failure.
                logger.warning("LLM knowledge extraction failed; using fallback: %s", exc)
        return self._merge(self._fallback(text, material), policy_claims, policy_cards, vocabulary)

    def _merge(
        self,
        structured: dict[str, Any],
        policy_claims: list[dict[str, Any]],
        policy_cards: list[dict[str, Any]],
        vocabulary: tuple | None = None,
    ) -> dict[str, Any]:
        """Combine parsed clauses with model output, parsed clauses first."""
        cards = [dict(card) for card in policy_cards]
        seen_cards = {self._key(card.get("title")) for card in cards}
        for card in structured.get("cards", []):
            if not isinstance(card, dict):
                continue
            marker = self._key(card.get("title"))
            if not marker or marker in seen_cards:
                continue
            seen_cards.add(marker)
            cards.append(card)

        claims = [dict(claim) for claim in policy_claims]
        seen_claims = {
            (
                self._key(claim.get("subject")),
                self._key(claim.get("predicate")),
                self._key(claim.get("object")),
            )
            for claim in claims
        }
        for claim in structured.get("claims", []):
            if not isinstance(claim, dict):
                continue
            marker = (
                self._key(claim.get("subject")),
                self._key(claim.get("predicate")),
                self._key(claim.get("object")),
            )
            if not marker[0] or marker in seen_claims:
                continue
            seen_claims.add(marker)
            claims.append(claim)

        entities = [item for item in structured.get("entities", []) if isinstance(item, dict)]
        index = self._entity_index(entities)
        # Relations are rebuilt from the merged claims so a parsed clause contributes its
        # edge even when the model never saw it.
        self._link_entities(cards, claims, index)
        relations = self._dedupe_relations(
            self._claim_relations(claims, index, vocabulary) + structured.get("relations", [])
        )
        return {
            "cards": cards,
            "claims": claims,
            "entities": entities,
            "relations": relations[:MAX_RELATIONS],
        }

    @staticmethod
    def _material(units: list | None, chunks: list) -> list[dict[str, Any]]:
        """Join standard units with their persisted evidence codes.

        Units and chunks are produced from the same normalized blocks, so
        ``unit_id`` is the joining key that keeps provenance intact while the
        extractor works from the cleaned unit text.
        """
        if not units:
            return [
                {
                    "unit_id": str(chunk.get("unit_id") or ""),
                    "evidence_code": chunk.get("evidence_code"),
                    "content": chunk.get("content", ""),
                    "heading_path": chunk.get("heading_path", ""),
                    "block_type": chunk.get("block_type", ""),
                }
                for chunk in chunks
            ]
        by_unit = {str(chunk.get("unit_id")): chunk for chunk in chunks if chunk.get("unit_id")}
        material = []
        for unit in units:
            chunk = by_unit.get(str(unit.get("unit_id")), {})
            content = unit.get("content", "")
            if not has_content(content):
                continue
            material.append(
                {
                    "unit_id": str(unit.get("unit_id") or ""),
                    "evidence_code": chunk.get("evidence_code"),
                    "content": content,
                    "heading_path": unit.get("heading_path", ""),
                    "block_type": unit.get("block_type", ""),
                }
            )
        return material

    def _policy_clauses(self, material: list[dict[str, Any]], scenario: str = "") -> tuple[list, list]:
        """Read inline key/value institutional clauses into claims and one card per row.

        ``scenario`` selects the vocabulary the subject is read from, so one parser serves
        a runbook ("主机: srv-pay-01；用途: 延迟 1 小时的从库") and a handbook
        ("假期: 年假；额度: 5 天") without either having to know about the other.

        Policy documents state their numbers in rows like
        ``假期: 年假；额度: 司龄满 1 年 5 天；申请时限: 提前 3 个工作日``. Left as raw text those
        rows are only reachable through full-text search, which is why a question such as
        "年假多少天" has to rely on BM25 luck. The row already says what it is about, so it
        can be turned into a subject/predicate/object claim without a model: the first
        recognised key names the subject and every remaining field becomes a property of it.

        Returns ``(claims, cards)``. Nothing is invented here — every claim keeps the
        evidence code of the unit it was read from, so it is only as trustworthy as the
        source text, which is exactly why it does not need to wait for human review.
        """
        claims: list[dict[str, Any]] = []
        cards: list[dict[str, Any]] = []
        # A clause row rarely repeats the date, because the notice states it once in its
        # preamble. Scanning the whole document first means the row that carries the
        # number still gets the window that makes it the current or the superseded one.
        document_effective = self._document_effective_date(material)
        subject_keys = SCENARIO_SUBJECT_KEYS.get(str(scenario or "").upper()) or CLAUSE_SUBJECT_KEYS
        for item in material or []:
            content = str(item.get("content") or "")
            if not content:
                continue
            effective = self._clause_effective_date(content) or document_effective
            evidence = [str(item.get("evidence_code"))] if item.get("evidence_code") else []
            rows = []
            for line in content.split("\n"):
                parsed = self._parse_clause_row(line, effective, evidence, subject_keys)
                if parsed:
                    rows.append(parsed)
            if not rows:
                continue
            for row in rows:
                claims.extend(row["claims"])
            # One card per unit keeps the human-readable form available alongside the
            # claims: the card is what an answer quotes, the claims are what it matches on.
            cards.append(self._clause_card(item, rows))
        return claims, cards

    @classmethod
    def _parse_clause_row(
        cls, line: str, effective: str = "", evidence_ids: list[str] | None = None,
        subject_keys: tuple[str, ...] = CLAUSE_SUBJECT_KEYS,
    ) -> dict[str, Any] | None:
        """Split one clause row into its subject and per-field claims."""
        text = clean_inline(str(line or "")).strip()
        if not text or "：" not in text and ":" not in text:
            return None
        pairs = []
        for segment in CLAUSE_PAIR_SEP.split(text):
            matched = CLAUSE_KV.match(segment or "")
            if not matched:
                continue
            raw_key = matched.group(1).strip()
            value = matched.group(2).strip()
            if not raw_key or not value or not has_content(value):
                continue
            # A revision notice qualifies its field names — "原口径（HR-HB-03 V3.1）",
            # "新口径（本通知）". The parenthesis is a citation, not part of the field, so
            # it is moved aside before matching; otherwise "原口径（…）" matches nothing and
            # the superseded reading silently disappears from the knowledge base.
            key, note = cls._split_clause_note(raw_key)
            pairs.append((key, value, note))
        # A single field is a heading or a stray fragment, not a clause row: two or more
        # fields is what distinguishes "假期: 年假；额度: 5 天" from "一、工作时间:".
        if len(pairs) < 2:
            return None
        subject_key = ""
        subject = ""
        for key, value, _note in pairs:
            if key in subject_keys:
                subject_key, subject = key, value
                break
        if not subject:
            return None
        claims = []
        for key, value, note in pairs:
            if key == subject_key or key in CLAUSE_SKIP_KEYS:
                continue
            claim: dict[str, Any] = {
                "subject": subject[:200],
                "predicate": key[:60],
                "object": value[:400],
                "conditions": [],
                # The parenthesis the field name carried is provenance worth keeping —
                # "原口径（HR-HB-03 V3.1）" says which document the old reading came from.
                "qualifiers": {"sourceRef": note} if note else {},
                "polarity": "POSITIVE",
                "certainty": 0.9,
                "confidence": 0.9,
                "evidence_ids": list(evidence_ids or []),
                # Marks the provenance so downstream governance knows this was read from
                # the source rather than inferred, and can publish it without review.
                "derivation": "DETERMINISTIC",
            }
            claim.update(cls._clause_validity(key, effective))
            claims.append(claim)
        if not claims:
            return None
        return {"subject": subject, "claims": claims, "text": text}

    @staticmethod
    def _split_clause_note(raw_key: str) -> tuple[str, str]:
        """Split a clause field name from the citation in its parenthesis."""
        matched = re.match(r"^([^（(]*)\s*[（(]([^)）]*)[)）]\s*$", str(raw_key or "").strip())
        if not matched:
            return str(raw_key or "").strip(), ""
        return matched.group(1).strip(), matched.group(2).strip()

    @staticmethod
    def _clause_validity(key: str, effective: str) -> dict[str, str]:
        """Place a clause on the timeline when the row says which reading it is.

        A revision notice puts the old and the new figure in the same row. Without a
        validity window both claims look like a contradiction and end up in the conflict
        queue; with one they are recognised as successive versions of the same rule.
        """
        if key in CLAUSE_SUPERSEDED_KEYS:
            return {"valid_from": "", "valid_to": _day_before(effective) if effective else ""}
        if key in CLAUSE_CURRENT_KEYS:
            return {"valid_from": effective, "valid_to": ""}
        # Every other field belongs to the document as published.
        return {"valid_from": effective, "valid_to": ""}

    @classmethod
    def _document_effective_date(cls, material: list[dict[str, Any]]) -> str:
        """The date the document as a whole takes effect, from any of its units."""
        for item in material or []:
            found = cls._clause_effective_date(str(item.get("content") or ""))
            if found:
                return found
        return ""

    @staticmethod
    def _clause_effective_date(content: str) -> str:
        """The date this unit says its rule takes effect, as YYYY-MM-DD."""
        best = ""
        for matched in CLAUSE_DATE.finditer(str(content or "")):
            year, month, day = matched.group(1), matched.group(2), matched.group(3)
            if not (1 <= int(month) <= 12 and 1 <= int(day) <= 31):
                continue
            window = str(content or "")[max(0, matched.start() - 12) : matched.end() + 6]
            # A date is only an effective date when the wording says so; a document dated
            # 2026-08-30 also mentions 2025 figures that must not become validity windows.
            if any(hint in window for hint in CLAUSE_EFFECTIVE_HINT) or "生效" in window:
                best = "%s-%02d-%02d" % (year, int(month), int(day))
        return best

    @classmethod
    def _clause_card(cls, item: dict[str, Any], rows: list[dict[str, Any]]) -> dict[str, Any]:
        """One readable card per unit, titled by its heading rather than its first line."""
        heading = str(item.get("heading_path") or "")
        segments = [part.strip() for part in heading.split("/") if part.strip()]
        title = segments[-1] if segments else (rows[0]["subject"] if rows else "")
        body = "\n".join(row["text"] for row in rows)
        return {
            "type": cls._classify(body, heading),
            "title": clean_inline(title)[:200],
            "summary": body[:500],
            "content": body,
            "confidence": 0.9,
            "evidence_ids": [str(item.get("evidence_code"))] if item.get("evidence_code") else [],
            "derivation": "DETERMINISTIC",
        }

    @staticmethod
    def _relation_glossary(vocabulary: tuple | None = None) -> str:
        """One readable line per allowed relation type, with the wording that triggers it.

        A model asked for "DEPENDS_ON" answers "依赖" unless the vocabulary also says what the
        label means; the glossary closes that gap without hard-coding Chinese into the schema.
        """
        parts = []
        for rtype in vocabulary or RELATION_VOCABULARY:
            keywords = next((item[1] for item in RELATION_HINTS if item[0] == rtype), ())
            hint = "、".join(keywords[:3])
            label = RELATION_LABELS.get(rtype, "")
            parts.append("%s（%s%s）" % (rtype, label, ("，出现" + hint + "时用") if hint else ""))
        return "；".join(parts)

    @classmethod
    def _prompt(
        cls,
        text: str,
        material: list[dict[str, Any]],
        vocabulary: tuple | None = None,
        types: tuple | None = None,
        scenario: str = "",
    ) -> str:
        return (
            "请从企业知识材料中抽取结构化知识，直接输出 JSON，不要输出分析过程或代码块：\n"
            '{"cards":[{"type":"FACT|PROCEDURE|RULE|PROBLEM_SOLUTION|CONCEPT",'
            '"title":"","summary":"","content":"","confidence":0.0,'
            '"evidence_ids":[],"entity_codes":[]}],'
            '"claims":[{"subject":"","predicate":"","object":"",'
            '"conditions":[],"qualifiers":{},"polarity":"POSITIVE","certainty":0.0,'
            '"valid_from":"YYYY-MM-DD or empty","valid_to":"YYYY-MM-DD or empty",'
            '"confidence":0.0,"evidence_ids":[],"entity_codes":[]}],'
            '"entities":[{"name":"","type":"TERM","confidence":0.0,"aliases":[]}],'
            '"relations":[{"source":"","target":"","relation_type":"",'
            '"confidence":0.0}]}\n'
            "说明：polarity 只能为 POSITIVE/NEGATIVE；certainty 是表述确定性；"
            "valid_from/valid_to 表示该结论的适用期限，无法确定时留空。\n"
            "关系要求：relations 描述实体之间的关联，source/target 必须是同一个 "
            "entities 数组里出现过的实体名称；relation_type 必须原样输出下列受控英文代号之一，"
            "不得输出中文或自造类型：\n"
            + cls._relation_glossary(vocabulary)
            + "\n例：“OpenSSL 3.0.12 受 CVE-2026-1234 影响”→ AFFECTS；“SecureGateway V5.2 使用 OpenSSL 3.0.12”→ USES；"
            "“上海银行安全网关项目部署 SecureGateway V5.2”→ DEPLOYED_IN；“上海银行安全网关项目属于上海银行”→ BELONGS_TO。"
            "不要输出自环，最多 30 条有把握的关系。\n"
            "实体要求：entities 的 type 只能取 "
            + "|".join(types or ("TERM",))
            + "，例如漏洞取 VULNERABILITY、产品取 PRODUCT、项目取 PROJECT、客户取 CUSTOMER；"
            "客户、组织机构等参与方必须单独抽取为实体，不要只抽取项目名。\n"
            "挂载要求：cards/claims 的 entity_codes 填写该条知识涉及的实体名称，"
            "没有则留空数组。\n"
            "标题要求：title 不得包含 #、*、-、| 等排版符号，不得照抄表格分隔行；"
            "材料已去除排版标记，请直接产出可读标题。\n"
            # Institutional clauses: the numbers a policy question asks for live in
            # "key: value；key: value" rows. Asked generically, a model summarises the row
            # into a card and the individual figure stays unreachable, so the prompt has to
            # ask for one claim per property and show what that looks like.
            "制度条款要求：材料中的“键: 值；键: 值”式条款（如“假期: 年假；额度: 司龄满 1 年 5 天，每满 1 年 +1，上限 15 天；"
            "申请时限: 提前 3 个工作日”），必须为每一个属性单独产出一条 claim："
            "subject 取该条款描述的主体（年假），predicate 取属性名（额度、申请时限），object 取属性值；"
            "不要把整行原文塞进一个 object，也不要把主体名写进 predicate。\n"
            "新旧口径要求：制度修订类材料常同时给出“原口径/新口径”或“调整前/调整后”，"
            "必须分别产出 claim，并用 valid_from/valid_to 标注各自适用期限"
            "（新口径填 valid_from，原口径填 valid_to），使二者在时间上可区分、而不是互相冲突。\n"
            "数字口径要求：额度、天数、金额、比例、上限、时限等精确数值必须原样保留在 object 中，"
            "不要换算、不要概括、不要省略单位。\n"
            + SCENARIO_FOCUS.get(str(scenario or "").upper(), "")
            + "材料：\n" + text
        )

    def _normalize(
        self,
        result: dict[str, Any],
        material: list[dict[str, Any]],
        vocabulary: tuple | None = None,
        types: tuple | None = None,
    ) -> dict[str, Any]:
        """Keep only candidates that carry meaning after cleaning.

        A model occasionally echoes layout back into a title; dropping those rows here
        is cheaper and safer than repairing them downstream. Relations are resolved
        against the surviving entities so the graph never receives dangling endpoints,
        and co-occurrence relations are added when the model returned none.
        """
        cards = []
        for card in result.get("cards", []):
            if not isinstance(card, dict):
                continue
            title = clean_inline(str(card.get("title", ""))).strip()
            if not has_content(title):
                continue
            card["title"] = title[:200]
            card["summary"] = clean_inline(str(card.get("summary", "")))[:500]
            cards.append(card)
        claims = []
        for claim in result.get("claims", []):
            if not isinstance(claim, dict):
                continue
            subject = clean_inline(str(claim.get("subject", ""))).strip()
            if not has_content(subject):
                continue
            claim["subject"] = subject[:200]
            claims.append(claim)
        entities = []
        for entity in result.get("entities", []):
            if not isinstance(entity, dict):
                continue
            name = clean_inline(str(entity.get("name") or entity.get("canonical_name") or "")).strip()
            if not has_content(name):
                continue
            entity["name"] = name[:120]
            entity["type"] = self._entity_type(entity.get("type"), types)
            entities.append(entity)
        index = self._entity_index(entities)
        contexts = [str(item.get("content") or "") for item in material]
        relations = self._clean_relations(result.get("relations", []), index, vocabulary, contexts)
        self._link_entities(cards, claims, index)
        if not relations:
            relations = self._cooccurrence_relations(material, index)
        relations = self._dedupe_relations(self._claim_relations(claims, index, vocabulary) + relations)
        return {
            "cards": cards,
            "claims": claims,
            "entities": entities,
            "relations": relations[:MAX_RELATIONS],
        }

    @staticmethod
    def _key(value: Any) -> str:
        """Comparison key for entity names: spacing and case must not split a node."""
        return re.sub(r"\s+", "", clean_inline(str(value or ""))).strip().lower()

    @classmethod
    def _entity_index(cls, entities: list[dict[str, Any]]) -> dict[str, str]:
        """Map comparison key to the canonical entity name of this batch."""
        index = {}
        for entity in entities:
            key = cls._key(entity.get("name"))
            if key and key not in index:
                index[key] = str(entity.get("name"))
        return index

    @classmethod
    def _match_entity(cls, text: Any, index: dict[str, str]) -> str:
        """Return the longest batch entity name contained in ``text``."""
        haystack = cls._key(text)
        if not haystack:
            return ""
        best = ""
        for key, _ in index.items():
            if len(key) >= 2 and key in haystack and len(key) > len(best):
                best = key
        return index.get(best, "")

    @classmethod
    def _codes_in(cls, text: Any, index: dict[str, str], limit: int = MAX_RELATIONS_PER_UNIT) -> list[str]:
        """Entity names mentioned by one card or claim."""
        haystack = cls._key(text)
        if not haystack:
            return []
        found = [name for key, name in index.items() if len(key) >= 2 and key in haystack]
        return found[:limit]

    @classmethod
    def _link_entities(cls, cards: list[dict[str, Any]], claims: list[dict[str, Any]], index: dict[str, str]) -> None:
        """Attach mentioned entities to every card and claim so MENTIONS edges exist."""
        for card in cards:
            text = " ".join(str(card.get(field) or "") for field in ("title", "summary", "content"))
            card["entity_codes"] = cls._codes_in(text, index)
        for claim in claims:
            text = " ".join(str(claim.get(field) or "") for field in ("subject", "object", "object_value"))
            claim["entity_codes"] = cls._codes_in(text, index)

    def _clean_relations(
        self,
        raw: Any,
        index: dict[str, str],
        vocabulary: tuple | None = None,
        contexts: list[str] | None = None,
    ) -> list[dict[str, Any]]:
        """Resolve model relations onto batch entities, dropping dangling or self edges.

        A label the model invented, or wrote in Chinese, still names the relation in the
        sentence it came from; when the label is not in the vocabulary the wording of that
        sentence decides the type, so "受影响于" becomes AFFECTS instead of RELATED_TO.
        """
        relations = []
        for item in raw or []:
            if not isinstance(item, dict):
                continue
            source = self._resolve_ref(
                item.get("source") or item.get("source_entity_code") or item.get("subject"), index
            )
            target = self._resolve_ref(
                item.get("target") or item.get("target_entity_code") or item.get("object"), index
            )
            if not source or not target or self._key(source) == self._key(target):
                continue
            raw_type = item.get("relation_type") or item.get("type") or item.get("predicate")
            relation_type = self._relation_type(raw_type, vocabulary)
            if relation_type == "RELATED_TO":
                sentence = self._context_sentence(source, target, contexts)
                relation_type = self._infer_relation_type(sentence or str(raw_type or ""), vocabulary) or relation_type
            relations.append(
                {
                    "source": source,
                    "target": target,
                    "relation_type": relation_type,
                    "confidence": self._confidence(item.get("confidence"), 0.6),
                }
            )
        return relations

    @classmethod
    def _typed_cooccurrence(
        cls,
        material: list[dict[str, Any]],
        index: dict[str, str],
        vocabulary: tuple | None = None,
        contexts: list[str] | None = None,
    ) -> list[dict[str, Any]]:
        """Co-occurrence edges, typed by the wording of the sentence that mentions both ends."""
        relations = cls._cooccurrence_relations(material, index)
        for relation in relations:
            sentence = cls._context_sentence(relation.get("source"), relation.get("target"), contexts)
            inferred = cls._infer_relation_type(sentence, vocabulary)
            if inferred:
                relation["relation_type"] = inferred
        return relations

    @classmethod
    def _strict_prompt(cls, text: str, vocabulary: tuple | None = None, types: tuple | None = None) -> str:
        """Fallback prompt: the smallest request that still yields typed entities and relations.

        Used when the first attempt came back empty. It drops cards and claims — they are valuable
        but the graph is what makes an impact chain answerable — and asks for JSON only, because
        a model that is thinking out loud has no budget left for the answer.
        """
        return (
            "只输出 JSON，不要输出分析过程、解释或代码块。\n"
            '格式：{"entities":[{"name":"","type":""}],'
            '"relations":[{"source":"","target":"","relation_type":""}]}\n'
            "relation_type 只能取：" + "|".join(vocabulary or RELATION_VOCABULARY) + "\n"
            "type 只能取：" + "|".join(types or ("TERM",)) + "\n"
            "关系含义：" + cls._relation_glossary(vocabulary) + "\n"
            "材料：\n" + text
        )

    @classmethod
    def _context_sentence(cls, source: str, target: str, contexts: list[str] | None) -> str:
        """The sentence that mentions both endpoints; wording decides the relation type."""
        for block in contexts or []:
            for sentence in re.split(r"[。！？；;\n]", str(block or "")):
                if source and target and source in sentence and target in sentence:
                    return sentence
        return ""

    @classmethod
    def _infer_relation_type(cls, context: Any, vocabulary: tuple | None = None) -> str:
        """Map the wording of a sentence onto a controlled relation label."""
        text = str(context or "")
        if not text:
            return ""
        allowed = vocabulary or RELATION_VOCABULARY
        for relation_type, keywords in RELATION_HINTS:
            if relation_type not in allowed:
                continue
            for keyword in keywords:
                if keyword in text:
                    return relation_type
        return ""

    @classmethod
    def _resolve_ref(cls, value: Any, index: dict[str, str]) -> str:
        """Accept a model-supplied entity code or name and return a usable reference."""
        raw = clean_inline(str(value or "")).strip()
        if not raw:
            return ""
        return cls._match_entity(raw, index) or raw

    @staticmethod
    def _relation_type(value: Any, vocabulary: tuple | None = None) -> str:
        """Whitelist the relation type so graph labels stay stable and injection-free."""
        text = re.sub(r"[^A-Za-z0-9_]", "_", str(value or "").strip().upper())
        text = re.sub(r"_+", "_", text).strip("_")
        if not text:
            return "RELATED_TO"
        if text not in (vocabulary or RELATION_VOCABULARY):
            return "RELATED_TO"
        return text[:40]

    @staticmethod
    def _entity_type(value: Any, types: tuple | None = None) -> str:
        """Whitelist the entity type so node kinds stay inside the published schema."""
        text = re.sub(r"[^A-Za-z0-9_]", "_", str(value or "").strip().upper())
        text = re.sub(r"_+", "_", text).strip("_")
        allowed = tuple(types or ()) or ("TERM",)
        if text and text in allowed:
            return text[:32]
        return "TERM" if "TERM" in allowed else allowed[0]

    @staticmethod
    def _confidence(value: Any, default: float) -> float:
        try:
            score = float(value)
        except (TypeError, ValueError):
            return default
        return round(min(0.95, max(0.05, score)), 2)

    @classmethod
    def _claim_relations(
        cls,
        claims: list[dict[str, Any]],
        index: dict[str, str],
        vocabulary: tuple | None = None,
    ) -> list[dict[str, Any]]:
        """Turn subject/predicate/object claims into typed entity relations.

        A predicate is written in the language of the source document ("受影响于"), and the
        vocabulary is a set of English codes, so mapping the wording back is what keeps a
        claim from becoming an untyped edge that no traversal rule can follow.
        """
        relations = []
        for claim in claims:
            source = cls._match_entity(claim.get("subject"), index)
            target = cls._match_entity(claim.get("object") or claim.get("object_value"), index)
            if not source or not target or source == target:
                continue
            relation_type = cls._relation_type(claim.get("predicate") or "DESCRIBES", vocabulary)
            if relation_type == "RELATED_TO":
                wording = "%s %s" % (
                    claim.get("predicate") or "",
                    claim.get("object") or claim.get("object_value") or "",
                )
                relation_type = cls._infer_relation_type(wording, vocabulary) or relation_type
            relations.append(
                {
                    "source": source,
                    "target": target,
                    "relation_type": relation_type,
                    "confidence": cls._confidence(claim.get("confidence"), 0.6),
                }
            )
        return relations

    @classmethod
    def _cooccurrence_relations(
        cls, material: list[dict[str, Any]], index: dict[str, str], limit: int = 40
    ) -> list[dict[str, Any]]:
        """Connect entities that appear in the same standard unit.

        Co-occurrence is a weak signal, so it is only used when the model produced no
        explicit relations; confidence grows with the number of shared units.
        """
        counts: dict[tuple, int] = {}
        for item in material or []:
            haystack = cls._key(item.get("content"))
            if not haystack:
                continue
            hits = [key for key in index if len(key) >= 2 and key in haystack][:MAX_RELATIONS_PER_UNIT]
            for i in range(len(hits)):
                for j in range(i + 1, len(hits)):
                    pair = (hits[i], hits[j]) if hits[i] < hits[j] else (hits[j], hits[i])
                    counts[pair] = counts.get(pair, 0) + 1
        ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
        # A single shared unit is a very weak signal: keep only pairs seen in at least two
        # units when possible, otherwise cap the clique so the graph stays readable.
        strong = [item for item in ordered if item[1] >= 2]
        chosen = (strong or ordered)[: limit if strong else 10]
        return [
            {
                "source": index.get(pair[0], ""),
                "target": index.get(pair[1], ""),
                "relation_type": "RELATED_TO",
                "confidence": round(min(0.75, 0.4 + 0.07 * count), 2),
            }
            for pair, count in chosen
            if index.get(pair[0]) and index.get(pair[1])
        ]

    @classmethod
    def _dedupe_relations(cls, relations: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Keep the strongest edge per (source, target, type) triple."""
        best: dict[tuple, dict[str, Any]] = {}
        for relation in relations:
            key = (
                cls._key(relation.get("source")),
                cls._key(relation.get("target")),
                str(relation.get("relation_type") or ""),
            )
            current = best.get(key)
            if not key[0] or not key[1] or key[0] == key[1]:
                continue
            if current is None or float(relation.get("confidence") or 0) > float(current.get("confidence") or 0):
                best[key] = relation
        return list(best.values())[:MAX_RELATIONS]

    def _fallback(self, text: str, material: list[dict[str, Any]]) -> dict[str, Any]:
        """Generate conservative candidates when no structured LLM is available."""
        if material:
            sources = [
                {
                    "content": item["content"],
                    "heading_path": item.get("heading_path", ""),
                    "evidence_ids": [str(item["evidence_code"])] if item.get("evidence_code") else [],
                }
                for item in material
            ]
        else:
            sources = [
                {
                    "content": item.strip(),
                    "heading_path": "",
                    "evidence_ids": [],
                }
                for item in re.split(r"\n{2,}", text or "")
                if item.strip()
            ]
        sources = [item for item in sources if has_content(item["content"])][:40]

        cards = []
        claims = []
        entities = []
        seen = set()

        for source in sources:
            content = source["content"]
            title = self._title(content, source["heading_path"])
            if not has_content(title):
                continue
            knowledge_type = self._classify(content, source.get("heading_path", ""))
            cards.append(
                {
                    "knowledge_code": "K_" + uuid.uuid4().hex,
                    "type": knowledge_type,
                    "title": title[:100],
                    "summary": content[:240],
                    "content": content,
                    "confidence": 0.68,
                    "evidence_ids": source["evidence_ids"][:4],
                }
            )
            sentence = re.split(r"[。！？\n]", content)[0].strip()
            if has_content(sentence):
                # The object used to be ``sentence[40:160]``, a fixed offset meant to skip a
                # repeated title. It landed mid-number instead, producing objects such as
                # ",000；绩效工资占比: 15%" that read as corrupted and, because two fragments of
                # the same sentence differ, were then reported as conflicting with each other.
                # Only strip a prefix that genuinely repeats the title, never a blind slice.
                body = sentence
                if title and body.startswith(title):
                    body = body[len(title) :].strip(" 　:：,，-—")
                claims.append(
                    {
                        "claim_code": "CLM_" + uuid.uuid4().hex,
                        "subject": title[:40],
                        "predicate": self._infer_predicate(sentence, source.get("heading_path", "")),
                        "object": (body or sentence)[:200],
                        "conditions": [],
                        "confidence": 0.62,
                        "evidence_ids": source["evidence_ids"][:4],
                        "derivation": "FALLBACK",
                    }
                )
            self._collect_entities(content, source.get("heading_path", ""), entities, seen)

        index = self._entity_index(entities)
        self._link_entities(cards, claims, index)
        relations = self._dedupe_relations(
            self._claim_relations(claims, index) + self._cooccurrence_relations(sources, index)
        )
        return {
            "cards": cards,
            "claims": claims,
            "entities": entities,
            "relations": relations[:MAX_RELATIONS],
        }

    @staticmethod
    def _title(content: str, heading_path: str = "") -> str:
        """Derive a readable title from the heading path or the opening sentence."""
        if heading_path:
            segments = [part.strip() for part in heading_path.split("/") if part.strip()]
            if segments:
                return segments[-1][:100]
        first_line = content.strip().splitlines()[0] if content.strip() else ""
        sentence = re.split(r"[。！？；]", first_line)[0].strip()
        candidate = sentence or first_line
        # Guard against residual layout characters surviving into a title.
        return clean_inline(candidate).strip(" -—*#>|")[:100]

    @staticmethod
    def _classify(content: str, heading_path: str = "") -> str:
        haystack = "%s %s" % (heading_path, content)
        if any(keyword in haystack for keyword in ["步骤", "流程", "如何", "操作"]):
            return "PROCEDURE"
        if any(keyword in haystack for keyword in ["必须", "禁止", "应当", "不得"]):
            return "RULE"
        if any(keyword in haystack for keyword in ["故障", "问题", "原因", "解决"]):
            return "PROBLEM_SOLUTION"
        return "FACT"

    @staticmethod
    def _infer_predicate(sentence: str, heading_path: str = "") -> str:
        """Name what a sentence states about its subject.

        Every fallback claim used to be ``DESCRIBES``, which made the predicate useless for
        both reading and comparison: two claims about the same subject then differed only in
        their object, so a rewording was reported as a contradiction. Naming the property
        keeps unrelated statements apart and lets the same property be compared on purpose.
        """
        haystack = "%s %s" % (heading_path, sentence)
        for predicate, keywords in CLAUSE_PREDICATE_HINTS:
            if any(keyword in haystack for keyword in keywords):
                return predicate
        return "DESCRIBES"

    @staticmethod
    def _collect_entities(content: str, heading_path: str, entities: list, seen: set) -> None:
        """Collect terms that can act as graph nodes.

        Running Chinese text cannot be word-segmented without a dictionary, so candidates
        come from heading paths, Latin technical tokens, and phrases that end at a noun
        suffix or a relation word. Slicing every Chinese run would turn whole sentences
        into nodes and make the graph meaningless.
        """
        # (candidate, from_heading): heading segments are explicit titles and are trusted,
        # phrases captured from running text are only kept when they read like a term.
        candidates = []
        for segment in str(heading_path or "").split("/"):
            name = clean_inline(segment).strip()
            if 2 <= len(name) <= 40:
                candidates.append((name, True))
        for token in re.findall(r"[A-Za-z][A-Za-z0-9_.+-]{1,}", content or ""):
            candidates.append((token.strip("._+-"), False))
        for token in re.findall(
            r"[A-Za-z0-9]*[\u4e00-\u9fa5]{2,6}(?:服务|平台|系统|模块|资源池|调度器|队列|组件|接口|集群|库|流程|策略)",
            content or "",
        ):
            candidates.append((token, False))
        for token in re.findall(r"[\u4e00-\u9fa5]{2,8}(?=由|依赖|复用|包含|属于|需要|必须|采用|使用)", content or ""):
            candidates.append((token, False))
        for name, from_heading in candidates:
            name = clean_inline(name).strip()
            key = KnowledgeExtractor._key(name)
            if len(name) < 2 or not key or key in seen or len(entities) >= MAX_ENTITIES:
                continue
            if name[:1] in STOP_PREFIXES:
                continue
            if not from_heading and any(mark in name for mark in CONNECTOR_CHARS):
                continue
            seen.add(key)
            entities.append({"name": name[:60], "type": "TERM", "confidence": 0.52})
