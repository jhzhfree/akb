"""Knowledge extraction orchestration that converts evidence into candidates."""

import logging
import re
import uuid
from typing import Any, Dict, Optional

from app.services.llm_provider import get_llm_provider

logger = logging.getLogger(__name__)


class KnowledgeExtractor:
    """Extract cards, claims, entities, and relations from source evidence."""

    async def extract(
        self,
        text: str,
        chunks: Optional[list] = None,
    ) -> Dict[str, Any]:
        """Prefer structured LLM extraction and degrade to deterministic rules."""
        text = (text or "").strip()
        if not text and chunks:
            text = "\n\n".join(chunk.get("content", "") for chunk in chunks)

        llm = get_llm_provider()
        if llm.name != "mock" and text:
            try:
                result = await llm.extract_json(self._prompt(text[:14000]))
                if isinstance(result, dict):
                    return self._normalize(result)
            except Exception as exc:
                # Extraction must remain usable offline. Log why fallback was
                # selected instead of silently swallowing the model failure.
                logger.warning("LLM knowledge extraction failed; using fallback: %s", exc)
        return self._fallback(text)

    @staticmethod
    def _prompt(text: str) -> str:
        return (
            "请从企业知识材料中抽取结构化知识，只输出 JSON：\n"
            '{"cards":[{"type":"FACT|PROCEDURE|RULE|PROBLEM_SOLUTION|CONCEPT",'
            '"title":"","summary":"","content":"","confidence":0.0}],'
            '"claims":[{"subject":"","predicate":"","object":"",'
            '"conditions":[],"confidence":0.0}],'
            '"entities":[{"name":"","type":"TERM","confidence":0.0}],'
            '"relations":[]}\n材料：\n'
            + text
        )

    @staticmethod
    def _normalize(result: Dict[str, Any]) -> Dict[str, Any]:
        return {
            key: result.get(key, [])
            for key in ["cards", "claims", "entities", "relations"]
        }

    def _fallback(self, text: str) -> Dict[str, Any]:
        """Generate conservative candidates when no structured LLM is available."""
        paragraphs = [
            item.strip()
            for item in re.split(r"\n{2,}", text or "")
            if item.strip()
        ][:40]
        cards = []
        claims = []
        entities = []
        seen = set()

        for paragraph in paragraphs:
            knowledge_type = self._classify(paragraph)
            cards.append(
                {
                    "knowledge_code": "K_" + uuid.uuid4().hex,
                    "type": knowledge_type,
                    "title": paragraph.splitlines()[0][:100],
                    "summary": paragraph[:240],
                    "content": paragraph,
                    "confidence": 0.68,
                }
            )
            sentence = re.split(r"[。！？\n]", paragraph)[0].strip()
            if sentence:
                claims.append(
                    {
                        "claim_code": "CLM_" + uuid.uuid4().hex,
                        "subject": sentence[:40],
                        "predicate": "DESCRIBES",
                        "object": sentence[40:160] or sentence,
                        "conditions": [],
                        "confidence": 0.62,
                    }
                )
            self._collect_entities(paragraph, entities, seen)

        return {
            "cards": cards,
            "claims": claims,
            "entities": entities,
            "relations": [],
        }

    @staticmethod
    def _classify(paragraph: str) -> str:
        if any(keyword in paragraph for keyword in ["步骤", "流程", "如何", "操作"]):
            return "PROCEDURE"
        if any(keyword in paragraph for keyword in ["必须", "禁止", "应当", "不得"]):
            return "RULE"
        if any(keyword in paragraph for keyword in ["故障", "问题", "原因", "解决"]):
            return "PROBLEM_SOLUTION"
        return "FACT"

    @staticmethod
    def _collect_entities(paragraph: str, entities: list, seen: set) -> None:
        tokens = re.findall(
            r"[A-Za-z][A-Za-z0-9_.+-]{2,}|[\u4e00-\u9fa5]{2,8}",
            paragraph,
        )
        for token in tokens:
            if token not in seen and len(entities) < 60:
                seen.add(token)
                entities.append(
                    {"name": token, "type": "TERM", "confidence": 0.52}
                )
