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
from typing import Any, Dict, List, Optional

MAX_ENTITIES = 60
MAX_RELATIONS = 60
MAX_RELATIONS_PER_UNIT = 8
# Function words that must never start an entity: they glue clauses together, so a
# phrase captured from running text would otherwise begin with them.
STOP_PREFIXES = ("由", "与", "和", "或", "对", "从", "把", "被", "其", "该", "并",
                 "但", "而", "则", "即", "如", "是", "的", "了", "也", "还", "以",
                 "所", "就", "都", "又", "再", "让", "使", "给")
# Characters that glue clauses together. A phrase captured from running text that contains
# one of them is a clause, not a term, so it must not become a graph node.
CONNECTOR_CHARS = ("只", "能", "按", "于", "并", "且", "将", "使", "让", "被", "把",
                   "对", "从", "由", "与", "和", "是", "的", "了", "也", "还", "又",
                   "再", "就", "都", "很", "更", "最", "不", "无", "会", "可", "应",
                   "需", "优先", "必须", "因此", "但是")
RELATION_VOCABULARY = (
    "RELATED_TO", "PART_OF", "DEPENDS_ON", "USES", "CAUSES",
    "BELONGS_TO", "LOCATED_IN", "DESCRIBES", "CONTAINS", "MANAGES",
)

from app.services.llm_provider import get_llm_provider
from app.services.standard_document import clean_inline, has_content

logger = logging.getLogger(__name__)


class KnowledgeExtractor:
    """Extract cards, claims, entities, and relations from source evidence."""

    async def extract(
        self,
        text: str,
        chunks: Optional[list] = None,
        units: Optional[list] = None,
    ) -> Dict[str, Any]:
        """Prefer structured LLM extraction and degrade to deterministic rules."""
        text = (text or "").strip()
        material = self._material(units, chunks or [])
        if not text:
            text = "\n\n".join(item["content"] for item in material)
        if not text and chunks:
            text = "\n\n".join(chunk.get("content", "") for chunk in chunks)

        llm = get_llm_provider()
        if llm.name != "mock" and text:
            try:
                result = await llm.extract_json(self._prompt(text[:14000], material))
                if isinstance(result, dict):
                    return self._normalize(result, material)
            except Exception as exc:
                # Extraction must remain usable offline. Log why fallback was
                # selected instead of silently swallowing the model failure.
                logger.warning("LLM knowledge extraction failed; using fallback: %s", exc)
        return self._fallback(text, material)

    @staticmethod
    def _material(units: Optional[list], chunks: list) -> List[Dict[str, Any]]:
        """Join standard units with their persisted evidence codes.

        Units and chunks are produced from the same normalized blocks, so
        ``unit_id`` is the joining key that keeps provenance intact while the
        extractor works from the cleaned unit text.
        """
        if not units:
            return [{
                "unit_id": str(chunk.get("unit_id") or ""),
                "evidence_code": chunk.get("evidence_code"),
                "content": chunk.get("content", ""),
                "heading_path": chunk.get("heading_path", ""),
                "block_type": chunk.get("block_type", ""),
            } for chunk in chunks]
        by_unit = {str(chunk.get("unit_id")): chunk for chunk in chunks if chunk.get("unit_id")}
        material = []
        for unit in units:
            chunk = by_unit.get(str(unit.get("unit_id")), {})
            content = unit.get("content", "")
            if not has_content(content):
                continue
            material.append({
                "unit_id": str(unit.get("unit_id") or ""),
                "evidence_code": chunk.get("evidence_code"),
                "content": content,
                "heading_path": unit.get("heading_path", ""),
                "block_type": unit.get("block_type", ""),
            })
        return material

    @staticmethod
    def _prompt(text: str, material: List[Dict[str, Any]]) -> str:
        catalog = [
            {
                "evidence_id": item.get("evidence_code"),
                "heading_path": item.get("heading_path", ""),
                "content": (item.get("content") or "")[:1200],
            }
            for item in material if item.get("evidence_code")
        ]
        return (
            "请从企业知识材料中抽取结构化知识，只输出 JSON：\n"
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
            "entities 数组里出现过的实体名称，relation_type 只能取 "
            + "|".join(RELATION_VOCABULARY)
            + "，不要输出自环，最多 30 条有把握的关系。\n"
            "挂载要求：cards/claims 的 entity_codes 填写该条知识涉及的实体名称，"
            "没有则留空数组。\n"
            "标题要求：title 不得包含 #、*、-、| 等排版符号，不得照抄表格分隔行；"
            "材料已去除排版标记，请直接产出可读标题。\n材料：\n"
            + text
        )

    def _normalize(self, result: Dict[str, Any], material: List[Dict[str, Any]]) -> Dict[str, Any]:
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
            entity["type"] = str(entity.get("type") or "TERM").upper()[:32]
            entities.append(entity)
        index = self._entity_index(entities)
        relations = self._clean_relations(result.get("relations", []), index)
        self._link_entities(cards, claims, index)
        if not relations:
            relations = self._cooccurrence_relations(material, index)
        relations = self._dedupe_relations(
            self._claim_relations(claims, index) + relations)
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
    def _entity_index(cls, entities: List[Dict[str, Any]]) -> Dict[str, str]:
        """Map comparison key to the canonical entity name of this batch."""
        index = {}
        for entity in entities:
            key = cls._key(entity.get("name"))
            if key and key not in index:
                index[key] = str(entity.get("name"))
        return index

    @classmethod
    def _match_entity(cls, text: Any, index: Dict[str, str]) -> str:
        """Return the longest batch entity name contained in ``text``."""
        haystack = cls._key(text)
        if not haystack:
            return ""
        best = ""
        for key, name in index.items():
            if len(key) >= 2 and key in haystack and len(key) > len(best):
                best = key
        return index.get(best, "")

    @classmethod
    def _codes_in(cls, text: Any, index: Dict[str, str], limit: int = MAX_RELATIONS_PER_UNIT) -> List[str]:
        """Entity names mentioned by one card or claim."""
        haystack = cls._key(text)
        if not haystack:
            return []
        found = [name for key, name in index.items() if len(key) >= 2 and key in haystack]
        return found[:limit]

    @classmethod
    def _link_entities(cls, cards: List[Dict[str, Any]], claims: List[Dict[str, Any]],
                       index: Dict[str, str]) -> None:
        """Attach mentioned entities to every card and claim so MENTIONS edges exist."""
        for card in cards:
            text = " ".join(str(card.get(field) or "") for field in ("title", "summary", "content"))
            card["entity_codes"] = cls._codes_in(text, index)
        for claim in claims:
            text = " ".join(str(claim.get(field) or "")
                            for field in ("subject", "object", "object_value"))
            claim["entity_codes"] = cls._codes_in(text, index)

    def _clean_relations(self, raw: Any, index: Dict[str, str]) -> List[Dict[str, Any]]:
        """Resolve model relations onto batch entities, dropping dangling or self edges."""
        relations = []
        for item in raw or []:
            if not isinstance(item, dict):
                continue
            source = self._resolve_ref(
                item.get("source") or item.get("source_entity_code") or item.get("subject"), index)
            target = self._resolve_ref(
                item.get("target") or item.get("target_entity_code") or item.get("object"), index)
            if not source or not target or self._key(source) == self._key(target):
                continue
            relations.append({
                "source": source,
                "target": target,
                "relation_type": self._relation_type(
                    item.get("relation_type") or item.get("type") or item.get("predicate")),
                "confidence": self._confidence(item.get("confidence"), 0.6),
            })
        return relations

    @classmethod
    def _resolve_ref(cls, value: Any, index: Dict[str, str]) -> str:
        """Accept a model-supplied entity code or name and return a usable reference."""
        raw = clean_inline(str(value or "")).strip()
        if not raw:
            return ""
        return cls._match_entity(raw, index) or raw

    @staticmethod
    def _relation_type(value: Any) -> str:
        """Whitelist the relation type so graph labels stay stable and injection-free."""
        text = re.sub(r"[^A-Za-z0-9_]", "_", str(value or "").strip().upper())
        text = re.sub(r"_+", "_", text).strip("_")
        if not text:
            return "RELATED_TO"
        if text not in RELATION_VOCABULARY:
            return "RELATED_TO"
        return text[:40]

    @staticmethod
    def _confidence(value: Any, default: float) -> float:
        try:
            score = float(value)
        except (TypeError, ValueError):
            return default
        return round(min(0.95, max(0.05, score)), 2)

    @classmethod
    def _claim_relations(cls, claims: List[Dict[str, Any]], index: Dict[str, str]) -> List[Dict[str, Any]]:
        """Turn subject/predicate/object claims into typed entity relations."""
        relations = []
        for claim in claims:
            source = cls._match_entity(claim.get("subject"), index)
            target = cls._match_entity(
                claim.get("object") or claim.get("object_value"), index)
            if not source or not target or source == target:
                continue
            relations.append({
                "source": source,
                "target": target,
                "relation_type": cls._relation_type(claim.get("predicate") or "DESCRIBES"),
                "confidence": cls._confidence(claim.get("confidence"), 0.6),
            })
        return relations

    @classmethod
    def _cooccurrence_relations(cls, material: List[Dict[str, Any]], index: Dict[str, str],
                                limit: int = 40) -> List[Dict[str, Any]]:
        """Connect entities that appear in the same standard unit.

        Co-occurrence is a weak signal, so it is only used when the model produced no
        explicit relations; confidence grows with the number of shared units.
        """
        counts: Dict[tuple, int] = {}
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
        chosen = (strong or ordered)[:limit if strong else 10]
        return [{
            "source": index.get(pair[0], ""),
            "target": index.get(pair[1], ""),
            "relation_type": "RELATED_TO",
            "confidence": round(min(0.75, 0.4 + 0.07 * count), 2),
        } for pair, count in chosen if index.get(pair[0]) and index.get(pair[1])]

    @classmethod
    def _dedupe_relations(cls, relations: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Keep the strongest edge per (source, target, type) triple."""
        best: Dict[tuple, Dict[str, Any]] = {}
        for relation in relations:
            key = (cls._key(relation.get("source")), cls._key(relation.get("target")),
                   str(relation.get("relation_type") or ""))
            current = best.get(key)
            if not key[0] or not key[1] or key[0] == key[1]:
                continue
            if current is None or float(relation.get("confidence") or 0) > float(current.get("confidence") or 0):
                best[key] = relation
        return list(best.values())[:MAX_RELATIONS]

    def _fallback(self, text: str, material: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Generate conservative candidates when no structured LLM is available."""
        if material:
            sources = [{
                "content": item["content"],
                "heading_path": item.get("heading_path", ""),
                "evidence_ids": [str(item["evidence_code"])] if item.get("evidence_code") else [],
            } for item in material]
        else:
            sources = [{
                "content": item.strip(),
                "heading_path": "",
                "evidence_ids": [],
            } for item in re.split(r"\n{2,}", text or "") if item.strip()]
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
            cards.append({
                "knowledge_code": "K_" + uuid.uuid4().hex,
                "type": knowledge_type,
                "title": title[:100],
                "summary": content[:240],
                "content": content,
                "confidence": 0.68,
                "evidence_ids": source["evidence_ids"][:4],
            })
            sentence = re.split(r"[。！？\n]", content)[0].strip()
            if has_content(sentence):
                claims.append({
                    "claim_code": "CLM_" + uuid.uuid4().hex,
                    "subject": title[:40],
                    "predicate": "DESCRIBES",
                    "object": sentence[40:160] or sentence[:160],
                    "conditions": [],
                    "confidence": 0.62,
                    "evidence_ids": source["evidence_ids"][:4],
                })
            self._collect_entities(
                content, source.get("heading_path", ""), entities, seen)

        index = self._entity_index(entities)
        self._link_entities(cards, claims, index)
        relations = self._dedupe_relations(
            self._claim_relations(claims, index)
            + self._cooccurrence_relations(sources, index))
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
                content or ""):
            candidates.append((token, False))
        for token in re.findall(
                r"[\u4e00-\u9fa5]{2,8}(?=由|依赖|复用|包含|属于|需要|必须|采用|使用)",
                content or ""):
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
