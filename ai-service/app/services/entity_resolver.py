"""Entity resolution / entity normalization (Entity Resolution).

Design reference: V1.1 section 5.4. Surface forms extracted from different documents
("Tomcat", "Tomcat 8.5", "tomcat-8.5.x") must collapse onto one canonical entity before
they reach the graph; otherwise the graph fragments and multi-hop recall degrades.

Resolution runs in three deterministic layers and never invents entities:

1. alias dictionary  - explicit operator-maintained synonyms,
2. normalized match  - case / punctuation / version-suffix insensitive equality,
3. embedding cosine  - semantic similarity above a configurable threshold.

Embedding similarity is advisory: it is only consulted when no exact layer matched, and
the resulting decision carries its own score so downstream services can audit it.
"""

import logging
import re
from collections.abc import Iterable
from typing import Any

from app.core.config import settings
from app.services.embedding import cosine, get_embedding_provider

logger = logging.getLogger(__name__)

# Operator-maintained synonyms. Kept here rather than in a table so the AI service stays
# deployable without a database dependency; business-specific overrides come from the caller.
ALIAS_DICTIONARY: dict[str, str] = {
    "tomcat8.5": "tomcat",
    "tomcat 8.5": "tomcat",
    "jdk8": "jdk",
    "jdk1.8": "jdk",
    "springboot": "spring boot",
    "mysql8": "mysql",
    "es": "elasticsearch",
    "k8s": "kubernetes",
}

_VERSION_SUFFIX = re.compile(r"[\s_\-]?v?\d+(\.\d+)*[\s_\-]?[a-z]*$", re.IGNORECASE)
_SEPARATORS = re.compile(r"[\s_\-\./\\:：，,、（）()\[\]]+")


def normalize_name(name: str) -> str:
    """Collapse casing, separators and trailing version noise for exact matching."""
    text = (name or "").strip().lower()
    if not text:
        return ""
    aliased = ALIAS_DICTIONARY.get(text)
    if aliased:
        text = aliased
    text = _SEPARATORS.sub("", text)
    stripped = _VERSION_SUFFIX.sub("", text)
    return stripped or text


class EntityResolver:
    """Resolve incoming entity mentions onto canonical entities."""

    def __init__(self) -> None:
        # Stateless by design: the embedding provider is resolved lazily per call so that a
        # broken model backend degrades to exact matching instead of failing ingestion.
        self._provider = None

    async def resolve(
        self,
        entities: list[dict[str, Any]],
        canonical_entities: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        """Return merge decisions and alias records for a batch of extracted mentions.

        Args:
            entities: new mentions, each with ``code``/``name`` and optional ``type``.
            canonical_entities: already-known canonical entities of the knowledge base.

        Returns:
            ``{"merges": [...], "aliases": [...], "canonicalCount": int}`` where a merge maps a
            mention code onto the canonical entity code that should absorb it.
        """
        canonical_entities = canonical_entities or []
        if not getattr(settings, "entity_resolution_enabled", False) or not entities:
            return {
                "merges": [],
                "aliases": [],
                "canonicalCount": len(canonical_entities),
                "enabled": False,
                "reason": "disabled",
            }

        merges: list[dict[str, Any]] = []
        aliases: list[dict[str, Any]] = []
        unmatched: list[tuple[dict[str, Any], str]] = []

        for mention in entities:
            code = str(mention.get("code") or mention.get("entity_code") or "").strip()
            name = str(mention.get("name") or mention.get("canonical_name") or "").strip()
            if not code or not name:
                continue
            normalized = normalize_name(name)
            target = self._exact_target(canonical_entities, normalized, name)
            if target is None:
                already_merged = self._exact_target_from_merges(merges, entities, normalized)
                target = already_merged
            if target is not None:
                if target != code:
                    merges.append(self._merge_record(mention, target, "NORMALIZED_MATCH", 1.0))
                    aliases.append(self._alias_record(target, name, "NORMALIZED_MATCH", 1.0))
                continue
            aliases.append(self._alias_record(code, name, "SELF", 1.0))
            unmatched.append((mention, normalized))

        if unmatched and getattr(settings, "entity_resolution_embedding_enabled", False):
            anchors: list[tuple[str, str]] = []
            for canonical in canonical_entities:
                code = str(canonical.get("entityCode") or canonical.get("code") or "").strip()
                name = str(canonical.get("canonicalName") or canonical.get("name") or "").strip()
                if code and name:
                    anchors.append((code, name))
            merged_sources = {str(m.get("from")) for m in merges}
            for mention in entities:
                code = str(mention.get("code") or mention.get("entity_code") or "").strip()
                name = str(mention.get("name") or mention.get("canonical_name") or "").strip()
                if code and name and code not in merged_sources:
                    anchors.append((code, name))
            await self._embedding_pass(unmatched, anchors, merges, aliases)

        return {
            "merges": merges,
            "aliases": aliases,
            "canonicalCount": len(canonical_entities),
            "enabled": True,
            "reason": "ok",
        }

    async def _embedding_pass(
        self,
        unmatched: list[tuple[dict[str, Any], str]],
        anchors: list[tuple[str, str]],
        merges: list[dict[str, Any]],
        aliases: list[dict[str, Any]],
    ) -> None:
        """Attach remaining mentions to their closest canonical anchor by embedding cosine."""
        threshold = float(getattr(settings, "entity_resolution_similarity", 0.86))
        if not anchors:
            return
        try:
            provider = get_embedding_provider()
            mention_vectors = await provider.embed([name for _, name in unmatched])
            anchor_vectors = await provider.embed([name for _, name in anchors])
        except Exception as exc:
            logger.warning("Entity resolution embedding pass failed: %s", exc)
            return

        for index, (mention, _) in enumerate(unmatched):
            vector = mention_vectors[index] if index < len(mention_vectors) else None
            if not vector:
                continue
            best_anchor, best_score = None, 0.0
            for position, anchor_vector in enumerate(anchor_vectors):
                score = float(cosine(vector, anchor_vector))
                if score > best_score:
                    best_anchor, best_score = anchors[position][0], score
            if best_anchor is not None and best_score >= threshold and best_anchor != str(mention.get("code") or ""):
                merges.append(self._merge_record(mention, best_anchor, "EMBEDDING_MATCH", best_score))
                aliases.append(
                    self._alias_record(best_anchor, str(mention.get("name") or ""), "EMBEDDING_MATCH", best_score)
                )

    @staticmethod
    def _exact_target(canonical_entities: Iterable[dict[str, Any]], normalized: str, raw_name: str) -> str | None:
        for canonical in canonical_entities:
            code = str(canonical.get("entityCode") or canonical.get("code") or "").strip()
            name = str(canonical.get("canonicalName") or canonical.get("name") or "").strip()
            if not code:
                continue
            if name and normalize_name(name) == normalized:
                return code
            if raw_name and name.lower() == raw_name.lower():
                return code
        return None

    @staticmethod
    def _exact_target_from_merges(merges, entities, normalized) -> str | None:
        """Mentions inside the same batch may already share a canonical representative."""
        merged_codes = {str(m.get("from")) for m in merges}
        for mention in entities:
            code = str(mention.get("code") or mention.get("entity_code") or "").strip()
            if not code or code in merged_codes:
                continue
            name = str(mention.get("name") or mention.get("canonical_name") or "").strip()
            if name and normalize_name(name) == normalized:
                return code
        return None

    @staticmethod
    def _merge_record(mention: dict[str, Any], target: str, strategy: str, score: float) -> dict[str, Any]:
        return {
            "from": str(mention.get("code") or mention.get("entity_code") or ""),
            "to": target,
            "strategy": strategy,
            "score": round(float(score), 4),
            "reason": "归一到已存在的规范实体：%s" % target,
        }

    @staticmethod
    def _alias_record(target: str, alias: str, strategy: str, score: float) -> dict[str, Any]:
        return {
            "entityCode": target,
            "alias": alias,
            "aliasType": "SURFACE_FORM" if strategy == "SELF" else strategy,
            "confidence": round(float(score), 4),
        }


_resolver: EntityResolver | None = None


def get_entity_resolver() -> EntityResolver:
    """Process-wide resolver singleton."""
    global _resolver
    if _resolver is None:
        _resolver = EntityResolver()
    return _resolver
