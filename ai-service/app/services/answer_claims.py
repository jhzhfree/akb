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
# An insufficiency statement says what the corpus is missing, not what it contains, so it can
# never carry a [证据N] marker. Counting it as a claim made the gate block the very answers that
# were being honest about having no evidence — the prompt tells the model to say "建议摄入…" and
# the gate then blocked that sentence for having no citation. Such sentences are skipped instead.
_INSUFFICIENT_RE = re.compile(
    r"^(现有知识不足以|暂(无|未)(法)?(回答|给出|检索到|收录)|无法(回答|给出)|"
    r"知识库(中|里)?(暂)?(未|没有)|建议(摄入|补充|导入|新增|上传)|缺少|"
    r"未(收录|检索到|找到|命中))"
)


def is_insufficient_statement(text):
    """True when a sentence only declares missing knowledge instead of stating a fact."""
    stripped = normalize_sentence(text)
    return stripped.startswith(_UNVERIFIED_PREFIX) or bool(_INSUFFICIENT_RE.match(stripped))


# Quoted material is part of the sentence: "该资产风险已缓解；需持续检查…"[证据4] carries one
# citation that belongs to the whole sentence. Splitting on the semicolon inside the quotes
# produced a head sentence with no marker at all, which the citation gate then blocked as
# missingCitation — the quote, not the model, had swallowed the citation.
_QUOTED_RE = re.compile(r"[\"'“”「」‘’]([^\"'“”「」‘’]*)[\"'“”「」‘’]")
_SENTINEL = {"；": "\x00", ";": "\x00", "。": "\x01"}


def normalize_sentence(text):
    """Strip presentation markup so gates compare prose, not formatting."""
    return _MARKDOWN_RE.sub("", (text or "").strip()).strip(" \t-*#")


def protect_quotes(text):
    """Hide sentence-ending punctuation that sits inside quotes, so splitting keeps one claim."""
    def hide(match):
        inner = match.group(0)
        for raw, sentinel in _SENTINEL.items():
            inner = inner.replace(raw, sentinel)
        return inner

    return _QUOTED_RE.sub(hide, text or "")


def restore_quotes(text):
    """Undo :func:`protect_quotes` after splitting."""
    for raw, sentinel in _SENTINEL.items():
        text = text.replace(sentinel, raw)
    return text


def strip_citations(text):
    """Remove every [证据n] marker so the remaining sentence is ready for scoring."""
    return _CITATION_RE.sub("", text or "").strip()


def split_claims(answer, min_length=3):
    """Split a model answer into the factual sentences a gate should score."""
    normalized = re.sub(r"([。！？!?；;])(\[证据\d+\])", r"\2\1", protect_quotes(answer))
    claims = []
    for raw in _SENTENCE_RE.split(normalized):
        stripped = raw.strip()
        if _HEADING_RE.match(stripped) or _TABLE_RULE_RE.match(stripped):
            continue
        text = normalize_sentence(restore_quotes(raw))
        if not text or is_insufficient_statement(text):
            continue
        if _META_CLAIM_RE.search(text):
            continue
        if len(strip_citations(text)) < min_length:
            continue
        claims.append(text)
    return claims
