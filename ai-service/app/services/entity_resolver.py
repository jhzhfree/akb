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
# Below this a substring match is an accident: "银行" sits inside "上海银行" without naming the
# same thing, while "CVE-2026-1234" inside "安全公告：CVE-2026-1234" does.
CONTAINMENT_MIN_LENGTH = 6


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


def _compact(name: str) -> str:
    """Separator-free lower-case form, version included, used for containment matching."""
    text = (name or "").strip().lower()
    if not text:
        return ""
    aliased = ALIAS_DICTIONARY.get(text)
    if aliased:
        text = aliased
    return _SEPARATORS.sub("", text)


def match_key(name: str) -> tuple[str, str]:
    """Identity of a mention for equality: its base form plus the version it carries.

    Version travels with the name because it is the difference between "affected" and
    "patched"; two mentions only merge when their versions agree, or when one has none.
    """
    text = (name or "").strip().lower()
    if not text:
        return "", ""
    aliased = ALIAS_DICTIONARY.get(text)
    if aliased:
        text = aliased
    compact = _SEPARATORS.sub("", text)
    match = _VERSION_SUFFIX.search(text.strip())
    if not match:
        return compact, ""
    version = re.sub(r"[^a-z0-9.]", "", match.group(0).lower())
    base = _VERSION_SUFFIX.sub("", compact)
    return (base or compact), version


def _mention_code(mention: dict[str, Any]) -> str:
    return str(mention.get("code") or mention.get("entity_code") or "").strip()


def _mention_name(mention: dict[str, Any]) -> str:
    return str(mention.get("name") or mention.get("canonical_name") or "").strip()


def _canonical_code(canonical: dict[str, Any]) -> str:
    return str(canonical.get("entityCode") or canonical.get("code") or "").strip()


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
            code = _mention_code(mention)
            name = _mention_name(mention)
            if not code or not name:
                continue
            target = self._exact_target(canonical_entities, name, self_code=code)
            if target is None:
                # Mentions inside the same batch may already share a canonical representative.
                target = self._batch_target(merges, entities, name, self_code=code)
            if target is not None:
                if target != code:
                    merges.append(self._merge_record(mention, target, "NORMALIZED_MATCH", 1.0))
                    aliases.append(self._alias_record(target, name, "NORMALIZED_MATCH", 1.0))
                    continue
                # The canonical node is this very mention: keep its surface form on record so
                # the next document can match the alias instead of creating a twin node.
                aliases.append(self._alias_record(code, name, "SELF", 1.0))
                continue
            aliases.append(self._alias_record(code, name, "SELF", 1.0))
            unmatched.append((mention, name))

        # "安全公告：CVE-2026-1234" and "CVE-2026-1234" are one entity written two ways; the
        # dictionary cannot hold every document title, so a surface form that *contains* a
        # known canonical form — and is long enough not to be a substring by accident —
        # collapses onto it instead of fragmenting the graph.
        still_unmatched: list[tuple[dict[str, Any], str]] = []
        for mention, name in unmatched:
            target = self._containment_target(canonical_entities, entities, merges, name, self_code=_mention_code(mention))
            if target is None:
                still_unmatched.append((mention, name))
                continue
            merges.append(self._merge_record(mention, target, "CONTAINS_MATCH", 0.9))
            aliases.append(self._alias_record(target, name, "CONTAINS_MATCH", 0.9))
        unmatched = still_unmatched

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

    @classmethod
    def _exact_target(
        cls, canonical_entities: Iterable[dict[str, Any]], name: str, self_code: str = ""
    ) -> str | None:
        """Match a mention against canonical names and the aliases already on record.

        Version is part of identity: OpenSSL 3.0.12 and OpenSSL 3.0.0 differ by exactly the
        thing a vulnerability range is written in, so two mentions only merge when their
        versions agree — or when one of them carries no version at all.
        """
        base, version = match_key(name)
        if not base:
            return None
        for canonical in canonical_entities:
            code = _canonical_code(canonical)
            if not code or code == self_code:
                continue
            for candidate in cls._names_of(canonical):
                candidate_base, candidate_version = match_key(candidate)
                if not candidate_base or candidate_base != base:
                    continue
                if version and candidate_version and version != candidate_version:
                    continue
                return code
        return None

    @classmethod
    def _batch_target(cls, merges, entities, name, self_code: str = "") -> str | None:
        """Mentions inside the same batch may already share a canonical representative."""
        merged_codes = {str(m.get("from")) for m in merges}
        base, version = match_key(name)
        if not base:
            return None
        for mention in entities:
            code = _mention_code(mention)
            if not code or code == self_code or code in merged_codes:
                continue
            candidate_base, candidate_version = match_key(_mention_name(mention))
            if not candidate_base or candidate_base != base:
                continue
            if version and candidate_version and version != candidate_version:
                continue
            return code
        return None

    @classmethod
    def _containment_target(
        cls,
        canonical_entities: Iterable[dict[str, Any]],
        entities: Iterable[dict[str, Any]],
        merges,
        name: str,
        self_code: str = "",
    ) -> str | None:
        """Collapse a documented surface form onto the canonical form it contains."""
        whole = _compact(name)
        if not whole:
            return None
        merged_codes = {str(m.get("from")) for m in merges}
        for canonical in canonical_entities:
            code = _canonical_code(canonical)
            if not code or code == self_code:
                continue
            for candidate in cls._names_of(canonical):
                if cls._contains(whole, _compact(candidate)):
                    return code
        for mention in entities:
            code = _mention_code(mention)
            if not code or code == self_code or code in merged_codes:
                continue
            if cls._contains(whole, _compact(_mention_name(mention))):
                return code
        return None

    @staticmethod
    def _contains(left: str, right: str) -> bool:
        """One form wraps the other, and the shorter one is long enough to be deliberate."""
        shorter, longer = (left, right) if len(left) <= len(right) else (right, left)
        if not shorter or shorter == longer or len(shorter) < CONTAINMENT_MIN_LENGTH:
            return False
        return shorter in longer

    @staticmethod
    def _names_of(canonical: dict[str, Any]) -> list[str]:
        """Canonical name plus the alias dictionary rows the backend already stored."""
        names = [str(canonical.get("canonicalName") or canonical.get("name") or "")]
        raw = canonical.get("aliases")
        if isinstance(raw, list):
            names.extend(str(value or "") for value in raw)
        return [name for name in names if name.strip()]

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
