"""Configurable policy for turning a parsed document into retrieval units.

Chunking decides what a citation can point at and what an answer can quote, so it
cannot stay a pair of hard-coded constants: a policy manual wants small precise
chunks, a design report wants whole sections, and a table must never be split in
half. The policy below is passed per parse request (the backend stores one per
knowledge base) and is applied by :func:`app.services.standard_document.build_units`.

Available strategies:

* ``SEMANTIC`` — accumulate blocks up to the window, never mixing tables or code
  into prose, and cut on heading changes (the historical behaviour);
* ``FIXED`` — sliding window over the cleaned text with real overlap, for sources
  with no reliable structure;
* ``SENTENCE`` — group sentences up to the window, so no chunk ends mid-sentence;
* ``STRUCTURE`` — one unit per heading path, only hard-split when a section is
  oversized, for procedures and manuals;
* ``PARENT_CHILD`` — a section-level parent unit per heading plus window-sized
  children: retrieval matches the child, the answer reads the parent.
"""

from __future__ import annotations

import json
from typing import Any, Dict, Optional

from app.core.config import settings as env_settings

STRATEGIES = ("SEMANTIC", "FIXED", "SENTENCE", "STRUCTURE", "PARENT_CHILD")

STRATEGY_LABELS = {
    "SEMANTIC": "语义分段（默认）",
    "FIXED": "固定窗口滑窗",
    "SENTENCE": "句子聚合",
    "STRUCTURE": "章节结构",
    "PARENT_CHILD": "父子分块",
}

STRATEGY_DESCRIPTIONS = {
    "SEMANTIC": "按语义块累积到窗口上限，表格与代码独立成块，遇到新章节即切分",
    "FIXED": "按字符/Token 窗口在整篇正文上滑动，带真实重叠，适合无结构长文本",
    "SENTENCE": "以句子为最小单位聚合到窗口，切分点始终落在句末",
    "STRUCTURE": "每个标题路径生成一块，仅在超长时硬切，适合手册与规程",
    "PARENT_CHILD": "先按章节生成父块，再在父块内生成窗口子块，检索命中子块、回答回看父块",
}

MEASURES = ("CHAR", "TOKEN")

MEASURE_LABELS = {"CHAR": "字符数", "TOKEN": "估算 Token"}

CHUNK_SIZE_RANGE = (200, 4000)
CHUNK_OVERLAP_RANGE = (0, 1000)
MIN_CHUNK_SIZE_RANGE = (0, 1000)

DEFAULT_POLICY: Dict[str, Any] = {
    "strategy": "SEMANTIC",
    "chunkSize": int(env_settings.chunk_size),
    "chunkOverlap": int(env_settings.chunk_overlap),
    "minChunkSize": 120,
    "measure": "CHAR",
    "keepStructure": True,
    "respectSentence": True,
    "parentChild": False,
}

POLICY_VERSION = "chunk-policy-v1"


def _clamp_int(value: Any, default: int, bounds: tuple) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    low, high = bounds
    return max(low, min(high, number))


def _clean_bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def normalize(payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Coerce a console/backend payload into a complete, bounds-checked policy."""
    raw = payload if isinstance(payload, dict) else {}
    strategy = str(raw.get("strategy") or DEFAULT_POLICY["strategy"]).strip().upper()
    if strategy not in STRATEGIES:
        strategy = str(DEFAULT_POLICY["strategy"])
    measure = str(raw.get("measure") or DEFAULT_POLICY["measure"]).strip().upper()
    if measure not in MEASURES:
        measure = str(DEFAULT_POLICY["measure"])
    size = _clamp_int(raw.get("chunkSize", raw.get("chunk_size")),
                      DEFAULT_POLICY["chunkSize"], CHUNK_SIZE_RANGE)
    overlap = _clamp_int(raw.get("chunkOverlap", raw.get("chunk_overlap")),
                         DEFAULT_POLICY["chunkOverlap"], CHUNK_OVERLAP_RANGE)
    min_size = _clamp_int(raw.get("minChunkSize", raw.get("min_chunk_size")),
                          DEFAULT_POLICY["minChunkSize"], MIN_CHUNK_SIZE_RANGE)
    # An overlap that swallows the window would duplicate the whole document.
    overlap = min(overlap, max(0, size // 2))
    min_size = min(min_size, max(0, size // 2))
    parent_child = _clean_bool(raw.get("parentChild", raw.get("parent_child")),
                               DEFAULT_POLICY["parentChild"]) or strategy == "PARENT_CHILD"
    return {
        "strategy": strategy,
        "chunkSize": size,
        "chunkOverlap": overlap,
        "minChunkSize": min_size,
        "measure": measure,
        "keepStructure": _clean_bool(raw.get("keepStructure", raw.get("keep_structure")),
                                     DEFAULT_POLICY["keepStructure"]),
        "respectSentence": _clean_bool(raw.get("respectSentence", raw.get("respect_sentence")),
                                       DEFAULT_POLICY["respectSentence"]),
        "parentChild": parent_child,
    }


def default_policy(size: Optional[int] = None, overlap: Optional[int] = None) -> Dict[str, Any]:
    """Environment-seeded policy, optionally with the legacy size/overlap overrides."""
    policy = normalize(DEFAULT_POLICY)
    if size is not None:
        policy["chunkSize"] = _clamp_int(size, policy["chunkSize"], CHUNK_SIZE_RANGE)
    if overlap is not None:
        policy["chunkOverlap"] = _clamp_int(overlap, policy["chunkOverlap"], CHUNK_OVERLAP_RANGE)
        policy["chunkOverlap"] = min(policy["chunkOverlap"], max(0, policy["chunkSize"] // 2))
    return policy


def schema() -> Dict[str, Any]:
    """Vocabulary and bounds for the console, so the UI never invents a value."""
    return {
        "version": POLICY_VERSION,
        "default": default_policy(),
        "strategies": [
            {"name": name, "label": STRATEGY_LABELS[name], "description": STRATEGY_DESCRIPTIONS[name]}
            for name in STRATEGIES
        ],
        "measures": [{"name": name, "label": MEASURE_LABELS[name]} for name in MEASURES],
        "ranges": {
            "chunkSize": {"min": CHUNK_SIZE_RANGE[0], "max": CHUNK_SIZE_RANGE[1]},
            "chunkOverlap": {"min": CHUNK_OVERLAP_RANGE[0], "max": CHUNK_OVERLAP_RANGE[1]},
            "minChunkSize": {"min": MIN_CHUNK_SIZE_RANGE[0], "max": MIN_CHUNK_SIZE_RANGE[1]},
        },
    }


def parse_json(raw: Optional[str]) -> Optional[Dict[str, Any]]:
    """Tolerant decode of the ``policy`` form field sent by the backend."""
    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None
    try:
        payload = json.loads(text)
    except (TypeError, ValueError):
        return None
    return payload if isinstance(payload, dict) else None
