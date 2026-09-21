"""Shared sentence splitting used by the answer gates.

Both the citation gate and the entailment gate must judge the same sentences:
presentation markup and meta narration ("未发现冲突信息") are not factual
claims, so they are normalized away once here instead of per gate.
"""

import re

_CITATION_RE = re.compile(r"\[证据(\d+)\]")
_SENTENCE_RE = re.compile(r"(?<=[。！？!?；;])|\n+")
# Markdown decoration: bold, code, quote/list markers and numbered list prefixes.
_MARKDOWN_RE = re.compile(r"\*\*|`|^[\s>*#-]+|^\d+[.、)]\s*")
# Markdown headings are navigation, not factual claims: a gate must not demand citations for them.
_HEADING_RE = re.compile(r"^#{1,6}\s+\S")
# Table rule rows (| --- | :---: |) carry structure only and can never hide a claim.
_TABLE_RULE_RE = re.compile(r"^[\s|:-]*-[\s|:-]*$")
# Model narration about the evidence itself carries no factual content and can
# never be grounded, so it must not inflate the claim population of a gate.
_META_CLAIM_RE = re.compile(r"(未发现|没有发现|未提及冲突|不存在冲突|无冲突|未见冲突|冲突信息|无法从上述|以上内容均)")
_UNVERIFIED_PREFIX = "现有知识不足以"


def normalize_sentence(text):
    """Strip presentation markup so gates compare prose, not formatting."""
    return _MARKDOWN_RE.sub("", (text or "").strip()).strip(" \t-*#")


def strip_citations(text):
    """Remove every [证据n] marker so the remaining sentence is ready for scoring."""
    return _CITATION_RE.sub("", text or "").strip()


def split_claims(answer, min_length=3):
    """Split a model answer into the factual sentences a gate should score."""
    normalized = re.sub(r"([。！？!?；;])(\[证据\d+\])", r"\2\1", answer or "")
    claims = []
    for raw in _SENTENCE_RE.split(normalized):
        stripped = raw.strip()
        if _HEADING_RE.match(stripped) or _TABLE_RULE_RE.match(stripped):
            continue
        text = normalize_sentence(raw)
        if not text or text.startswith(_UNVERIFIED_PREFIX):
            continue
        if _META_CLAIM_RE.search(text):
            continue
        if len(strip_citations(text)) < min_length:
            continue
        claims.append(text)
    return claims
