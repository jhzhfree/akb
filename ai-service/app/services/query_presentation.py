"""Ranking quality and presentation score helpers."""
from datetime import datetime


def _clamp01(value, default):
    """Coerce a numeric signal into [0,1] and fall back when the index lacks the field."""
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return default


def quality_signals(candidate):
    """Read the evidence/authority/freshness/confidence signals carried by an indexed object.

    Missing signals fall back to neutral values so an incomplete index cannot distort ranking.
    """
    metadata = candidate.get("metadata") or {}
    return {
        "authority": _clamp01(metadata.get("authority_score"), 0.5),
        "freshness": _freshness(metadata.get("updated_at")),
        "confidence": _clamp01(metadata.get("confidence"), 0.5),
        "evidence": _clamp01(metadata.get("evidence_count"), 0.0),
    }


def _freshness(updated_at):
    """Decay freshness from the indexed update time; unknown timestamps get a neutral score."""
    if not updated_at:
        return 0.6
    try:
        if isinstance(updated_at, (int, float)):
            moment = datetime.fromtimestamp(float(updated_at))
        else:
            moment = datetime.fromisoformat(str(updated_at).replace("Z", "+00:00"))
        if moment.tzinfo is not None:
            moment = moment.replace(tzinfo=None)
        age_days = max(0.0, (datetime.utcnow() - moment).total_seconds() / 86400.0)
    except (TypeError, ValueError):
        return 0.6
    # 365-day half-life keeps long-lived enterprise knowledge from collapsing to zero.
    return round(max(0.2, 0.5 ** (age_days / 365.0)), 4)


CHUNK_CHANNELS = {"bm25", "vector"}
STRUCTURED_OBJECT_TYPES = {"CARD", "KNOWLEDGE_CARD", "CLAIM", "ASSERTION", "GRAPH"}


def is_chunk_evidence(candidate: dict) -> bool:
    """True when the candidate is a verbatim document chunk rather than a structured object."""
    if candidate.get("channel") not in CHUNK_CHANNELS:
        return False
    object_type = str((candidate.get("metadata") or {}).get("object_type") or "").upper()
    return object_type not in STRUCTURED_OBJECT_TYPES


def _terms(text: str) -> set[str]:
    """Cheap Chinese bigram + latin token view, matching the answer gate's notion of a term."""
    import re

    normalized = re.sub(r"\s+", "", str(text or "").lower())
    # Strip Chinese first, otherwise "OpenSSL3.0.12" and "OpenSSL" become different tokens
    # and a chunk that answers the question exactly looks unrelated to it.
    stripped = re.sub(r"[\u4e00-\u9fff]+", " ", normalized)
    latin = set()
    for run in re.findall(r"[a-z0-9_.+-]{2,}", stripped):
        for part in re.findall(r"[a-z]{2,}|\d[\d._]{1,}", run):
            latin.add(part)
    han = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
    grams = {han[i : i + 2] for i in range(max(0, len(han) - 1))}
    return latin.union(grams)


def chunk_boost(candidate: dict, query: str, weights: dict[str, float]) -> tuple[float, dict | None]:
    """Return ``(boost, signals)`` for verbatim chunks.

    Chunks are the evidence an answer can quote with a stable code and hash, so they get a
    stage of their own after the quality boost: a base lift plus what the chunk actually
    answers of the question (heading hit and term coverage).
    """
    if not weights or not is_chunk_evidence(candidate):
        return 1.0, None
    query_terms = _terms(query)
    if not query_terms:
        return round(float(weights.get("base", 1.0)), 4), {"headingHit": 0.0, "coverage": 0.0}
    metadata = candidate.get("metadata") or {}
    heading = "%s %s" % (metadata.get("heading_path") or "", candidate.get("title") or "")
    heading_terms = _terms(heading)
    heading_hit = 1.0 if query_terms.intersection(heading_terms) else 0.0
    coverage = len(query_terms.intersection(_terms(candidate.get("content") or ""))) / float(len(query_terms))
    boost = float(weights.get("base", 1.0))
    boost += float(weights.get("headingHit", 0.0)) * heading_hit
    boost += float(weights.get("coverage", 0.0)) * min(1.0, coverage)
    return round(boost, 4), {"headingHit": heading_hit, "coverage": round(min(1.0, coverage), 4)}


CHANNEL_LABELS = {
    "bm25": "BM25 全文",
    "vector": "向量",
    "card": "知识卡",
    "claim": "断言",
    "graph": "图谱",
}


def build_score_breakdown(candidate, channel_weights, rrf_k, rerank_depth, top_k):
    """Explain one candidate's final score (design §12.4).

    The retrieval test page must answer three questions per row: which channel hit it,
    how each stage moved its score, and why it was dropped. Everything needed is already
    computed upstream — this only projects it into a stable, UI-facing shape.
    """
    metadata = candidate.get("metadata") or {}
    channel_scores = candidate.get("channelScores") or {}
    channel_ranks = candidate.get("channelRanks") or {}
    matched = metadata.get("matched_channels") or list(channel_ranks.keys())
    weights = channel_weights or {}

    channels = []
    reasons = []
    for channel in matched:
        label = CHANNEL_LABELS.get(channel, channel)
        weight = float(weights.get(channel, 1.0))
        rank = channel_ranks.get(channel)
        raw = channel_scores.get(channel)
        contribution = round(weight / (rrf_k + rank), 6) if rank and rrf_k else None
        channels.append(
            {
                "channel": channel,
                "label": label,
                "rank": rank,
                "rawScore": raw,
                "weight": weight,
                "rrfContribution": contribution,
            }
        )
        reasons.append(
            "%s命中第 %s 名（原始分 %s，通道权重 %.2f）"
            % (label, rank if rank else "-", ("%.4f" % raw) if raw is not None else "-", weight)
        )

    chunk_boost_value = candidate.get("chunkBoost")
    if chunk_boost_value:
        signals = candidate.get("chunkSignals") or {}
        reasons.append(
            "原文段落加成 ×%s（标题命中 %s，问题词覆盖 %s）"
            % (
                "%.2f" % float(chunk_boost_value),
                "是" if signals.get("headingHit") else "否",
                "%.0f%%" % (float(signals.get("coverage") or 0.0) * 100),
            )
        )

    filtered = []
    if rerank_depth and candidate.get("rerankScore") is None:
        filtered.append("RERANK_DEPTH_CUTOFF")
    rerank_rank = candidate.get("rerankRank")
    if rerank_rank and top_k and rerank_rank > top_k:
        filtered.append("TOPK_CUTOFF")

    return {
        "rrfScore": candidate.get("rrfScore"),
        "rrfK": rrf_k,
        "channelWeights": dict(weights),
        "channels": channels,
        "qualityBoost": candidate.get("qualityBoost"),
        "qualitySignals": candidate.get("quality"),
        "chunkBoost": candidate.get("chunkBoost"),
        "chunkSignals": candidate.get("chunkSignals"),
        "preRerankScore": candidate.get("preRerankScore"),
        "rerankScore": candidate.get("rerankScore"),
        "finalScore": candidate.get("score"),
        "fusionRank": candidate.get("fusionRank"),
        "rerankRank": candidate.get("rerankRank"),
        "rankDelta": candidate.get("rankDelta"),
        "whyMatched": reasons or ["融合后进入候选（无通道明细）"],
        "filteredBy": filtered,
    }


