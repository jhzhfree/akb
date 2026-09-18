"""Runtime policy of the knowledge-consumption pipeline (question -> answer).

Every node that can change what the user finally sees is described here as typed
configuration instead of hard-coded constants:

* **route**: which recall channels one question type may use;
* **rewrite**: how many recall variants and how many recall rounds are allowed;
* **recall**: per-channel enablement, fusion weight and recall budget;
* **fusion**: how multiple ranked lists become one ranked list (weighted RRF +
  quality signals);
* **rerank**: whether a cross-encoder reorders the fused list and how deep it goes;
* **answer**: how much evidence enters the prompt and when an answer is refused.

The policy lives in ``<KNOWLEDGE_DATA_DIR>/query_policy.json`` once the console saves
it; before that the deployment environment seeds the defaults. Changes take effect on
the next query, so operations staff tune answer quality without redeploying, and a
corrupted or partial file can never disable querying.
"""

from __future__ import annotations

import json
import logging
import os
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, field_validator, model_validator

from app.core.config import DATA_DIR, settings as env_settings

POLICY_FILE = DATA_DIR / "query_policy.json"
POLICY_FILE_MODE = 0o600
POLICY_VERSION = "query-policy-v1"

logger = logging.getLogger(__name__)

_lock = threading.RLock()
_cache: Optional["QueryPolicy"] = None

CHANNELS = ("bm25", "vector", "card", "claim", "graph")

CHANNEL_LABELS = {
    "bm25": "BM25 全文检索",
    "vector": "向量语义检索",
    "card": "知识卡片",
    "claim": "断言/主张",
    "graph": "知识图谱遍历",
}

CHANNEL_DESCRIPTIONS = {
    "bm25": "精确术语与错误码命中，Elasticsearch 优先、本地词法回退。",
    "vector": "语义相似Recall，Milvus 优先、本地向量回退。",
    "card": "结构化知识卡片，适合 SOP、定义与操作步骤。",
    "claim": "版本化断言，适合冲突、条件与版本差异问题。",
    "graph": "图谱邻域遍历，适合关系与多跳问题。",
}

DEFAULT_CHANNEL_WEIGHTS = {
    "bm25": 1.0,
    "vector": 1.0,
    "card": 1.15,
    "claim": 1.1,
    "graph": 1.05,
}

DEFAULT_SYNONYMS = {
    "报错": ["错误", "异常", "故障"],
    "怎么": ["如何", "步骤"],
    "是啥": ["是什么", "定义"],
    "配置": ["配置项", "参数"],
    "版本": ["版本号", " Version"],
}

NO_ANSWER_TEXT = "现有知识库中未检索到足够证据，暂时无法确认。"

# Built-in routing table (design V1.1 section 8.1). Order still matters: when two rules
# match with the same longest keyword, the higher priority wins, and equal priority keeps
# the earlier declaration.
DEFAULT_ROUTE_RULES: List[Dict[str, Any]] = [
    {
        "questionType": "EXACT",
        "keywords": ["错误码", "配置项", "接口", "类名", "参数名", "型号"],
        "strategies": ["bm25", "vector", "card"],
        "priority": 80,
        "reason": "专有名词/错误码：以 BM25 精确术语为主，向量兜底语义变体。",
    },
    {
        "questionType": "MULTIHOP",
        "keywords": ["多跳", "通过什么", "路径", "追根溯源", "最终导致", "间接"],
        "strategies": ["graph", "card", "vector"],
        "priority": 70,
        "reason": "多跳问题：走图谱遍历/邻居扩展，卡片与向量补充。",
    },
    {
        "questionType": "CONFLICT",
        "keywords": ["冲突", "不一致", "矛盾", "哪个版本", "版本差异", "失效了吗"],
        "strategies": ["claim", "card", "bm25"],
        "priority": 90,
        "reason": "冲突/版本问题：优先断言与版本化卡片，不做全局检索。",
    },
    {
        "questionType": "RELATION",
        "keywords": ["关系", "依赖", "关联", "影响", "上游", "下游", "组成"],
        "strategies": ["graph", "vector", "card"],
        "priority": 60,
        "reason": "关系问题：实体+图谱为主，向量补充召回。",
    },
    {
        "questionType": "CONDITION",
        "keywords": ["是否", "支持", "条件", "规则", "必须", "前提"],
        "strategies": ["claim", "card", "bm25", "vector"],
        "priority": 50,
        "reason": "条件/规则问题：断言+条件为主，图谱增强。",
    },
    {
        "questionType": "PROCEDURE",
        "keywords": ["如何", "步骤", "流程", "操作", "怎么", "SOP"],
        "strategies": ["card", "bm25", "vector"],
        "priority": 40,
        "reason": "SOP/操作问题：优先结构化知识卡片。",
    },
    {
        "questionType": "GLOBAL",
        "keywords": ["总结", "汇总", "整体", "全局", "趋势", "概览"],
        "strategies": ["vector", "card", "claim", "bm25"],
        "priority": 30,
        "reason": "全局总结：社区摘要属于阶段 4 能力，当前退化为向量+卡片宽召回。",
    },
    {
        "questionType": "FACT",
        "keywords": [],
        "strategies": ["card", "vector", "bm25", "claim"],
        "priority": 10,
        "reason": "事实/定义：结构化卡片+向量+BM25，断言增强。",
    },
]


def _clean_channels(values: Any) -> List[str]:
    """Normalize a channel list and reject names the retriever cannot serve."""
    cleaned: List[str] = []
    for item in values or []:
        name = str(item).strip().lower()
        if name and name not in cleaned:
            cleaned.append(name)
    unknown = [name for name in cleaned if name not in CHANNELS]
    if unknown:
        raise ValueError("未知召回通道：%s（可选：%s）" % (",".join(unknown), ",".join(CHANNELS)))
    return cleaned


def _clean_keywords(values: Any) -> List[str]:
    """Normalize trigger keywords: trimmed, de-duplicated, order preserving."""
    cleaned: List[str] = []
    for item in values or []:
        text = str(item).strip()
        if text and text not in cleaned:
            cleaned.append(text)
    return cleaned


class RouteRule(BaseModel):
    """One question-type routing rule edited from the operations console."""

    questionType: str
    enabled: bool = True
    keywords: List[str] = Field(default_factory=list)
    strategies: List[str] = Field(default_factory=list)
    priority: int = Field(default=50, ge=0, le=999)
    reason: str = ""

    @field_validator("questionType")
    @classmethod
    def _require_type(cls, value: str) -> str:
        """Reject anonymous rules so traces always carry a meaningful type."""
        text = str(value or "").strip()
        if not text:
            raise ValueError("questionType 不能为空")
        return text

    @field_validator("keywords")
    @classmethod
    def _keywords(cls, value: List[str]) -> List[str]:
        """Keep keyword arrays canonical (trimmed and unique)."""
        return _clean_keywords(value)

    @field_validator("strategies")
    @classmethod
    def _channels(cls, value: List[str]) -> List[str]:
        """A rule must point at real channels, otherwise recall silently returns nothing."""
        cleaned = _clean_channels(value)
        if not cleaned:
            raise ValueError("至少选择一个召回通道")
        return cleaned


class RoutePolicy(BaseModel):
    """Question-understanding routing behaviour."""

    enabled: bool = True
    rules: List[RouteRule] = Field(
        default_factory=lambda: [RouteRule.model_validate(item) for item in DEFAULT_ROUTE_RULES]
    )
    defaultStrategies: List[str] = Field(default_factory=lambda: ["card", "vector", "bm25", "claim"])

    @field_validator("defaultStrategies")
    @classmethod
    def _default_channels(cls, value: List[str]) -> List[str]:
        """Fallback strategies must also name real channels."""
        cleaned = _clean_channels(value)
        if not cleaned:
            raise ValueError("默认召回通道不能为空")
        return cleaned

    @model_validator(mode="after")
    def _unique_types(self) -> "RoutePolicy":
        """Two rules fighting over the same question type would make routing unexplainable."""
        seen = set()
        for rule in self.rules:
            key = rule.questionType.strip().lower()
            if key in seen:
                raise ValueError("questionType 重复：%s" % rule.questionType)
            seen.add(key)
        return self


class RewritePolicy(BaseModel):
    """Query-rewrite behaviour feeding the multi-round recall loop."""

    enabled: bool = True
    llmEnabled: bool = False
    maxVariants: int = Field(default=4, ge=1, le=8)
    maxRecallRounds: int = Field(default=2, ge=1, le=5)
    synonyms: Dict[str, List[str]] = Field(default_factory=lambda: dict(DEFAULT_SYNONYMS))


class ChannelPolicy(BaseModel):
    """Per-channel recall switch, fusion weight, and budget multiplier."""

    enabled: bool = True
    weight: float = Field(default=1.0, ge=0.0, le=5.0)
    scale: float = Field(default=1.0, ge=0.1, le=5.0)


class RecallPolicy(BaseModel):
    """Multi-channel recall configuration."""

    channels: Dict[str, ChannelPolicy] = Field(
        default_factory=lambda: {
            name: ChannelPolicy(weight=DEFAULT_CHANNEL_WEIGHTS.get(name, 1.0)) for name in CHANNELS
        }
    )
    recallMultiplier: int = Field(default=3, ge=1, le=10)
    minRecallCandidates: int = Field(default=20, ge=1, le=200)

    @field_validator("channels")
    @classmethod
    def _known_channels(cls, value: Dict[str, ChannelPolicy]) -> Dict[str, ChannelPolicy]:
        """Only declared channels exist; typos must fail loudly instead of being ignored."""
        unknown = [name for name in (value or {}) if name not in CHANNELS]
        if unknown:
            raise ValueError("未知召回通道：%s（可选：%s）" % (",".join(unknown), ",".join(CHANNELS)))
        for name in CHANNELS:
            value.setdefault(name, ChannelPolicy(weight=DEFAULT_CHANNEL_WEIGHTS.get(name, 1.0)))
        return value


class QualityWeights(BaseModel):
    """Weights of the evidence/authority/freshness/confidence quality boost."""

    authority: float = Field(default=0.15, ge=0.0, le=1.0)
    freshness: float = Field(default=0.10, ge=0.0, le=1.0)
    confidence: float = Field(default=0.15, ge=0.0, le=1.0)
    evidence: float = Field(default=0.10, ge=0.0, le=1.0)


class FusionPolicy(BaseModel):
    """Weighted RRF fusion and quality-signal boost."""

    rrfK: int = Field(default=60, ge=1, le=1000)
    qualitySignalsEnabled: bool = True
    qualityWeights: QualityWeights = Field(default_factory=QualityWeights)


class RerankPolicy(BaseModel):
    """Rerank node behaviour; ``topN = 0`` reranks every fused candidate."""

    enabled: bool = True
    topN: int = Field(default=0, ge=0, le=200)


class AnswerPolicy(BaseModel):
    """Context assembly, guardrail thresholds, and the refusal sentence."""

    contextMaxChars: int = Field(default=8000, ge=500, le=60000)
    evidenceGateEnabled: bool = True
    faithfulnessMin: float = Field(default=0.03, ge=0.0, le=1.0)
    entailmentMin: float = Field(default=0.08, ge=0.0, le=1.0)
    policyVersion: str = "answer-policy-v1"
    noAnswerText: str = NO_ANSWER_TEXT


class QueryPolicy(BaseModel):
    """Complete policy applied to every knowledge-consumption request."""

    version: str = POLICY_VERSION
    route: RoutePolicy = Field(default_factory=RoutePolicy)
    rewrite: RewritePolicy = Field(
        default_factory=lambda: RewritePolicy(
            enabled=bool(env_settings.query_rewrite_enabled),
            llmEnabled=bool(env_settings.query_rewrite_llm_enabled),
        )
    )
    recall: RecallPolicy = Field(default_factory=RecallPolicy)
    fusion: FusionPolicy = Field(
        default_factory=lambda: FusionPolicy(
            rrfK=env_settings.rrf_k,
            qualitySignalsEnabled=bool(env_settings.fusion_quality_signals_enabled),
            qualityWeights=QualityWeights(
                authority=float(env_settings.fusion_weight_authority),
                freshness=float(env_settings.fusion_weight_freshness),
                confidence=float(env_settings.fusion_weight_confidence),
                evidence=float(env_settings.fusion_weight_evidence),
            ),
        )
    )
    rerank: RerankPolicy = Field(default_factory=RerankPolicy)
    answer: AnswerPolicy = Field(
        default_factory=lambda: AnswerPolicy(
            contextMaxChars=env_settings.context_max_chars,
            evidenceGateEnabled=bool(env_settings.answer_evidence_gate_enabled),
            faithfulnessMin=float(env_settings.answer_faithfulness_min),
            entailmentMin=float(env_settings.answer_entailment_min),
            policyVersion=env_settings.answer_policy_version,
        )
    )


def _deep_merge(base: Dict[str, Any], patch: Dict[str, Any]) -> Dict[str, Any]:
    """Recursively merge a console patch into a policy dump; lists replace wholesale."""
    merged = dict(base)
    for key, value in (patch or {}).items():
        current = merged.get(key)
        if isinstance(current, dict) and isinstance(value, dict):
            merged[key] = _deep_merge(current, value)
        else:
            merged[key] = value
    return merged


def default_policy() -> QueryPolicy:
    """Return the environment-seeded default policy (never touches the cache)."""
    return QueryPolicy()


def load_policy(force: bool = False) -> QueryPolicy:
    """Return the effective policy, reading the persisted file on first use."""
    global _cache
    with _lock:
        if _cache is not None and not force:
            return _cache
        seeded = default_policy()
        if POLICY_FILE.exists():
            try:
                raw = json.loads(POLICY_FILE.read_text(encoding="utf-8"))
                payload = raw if isinstance(raw, dict) else {}
                _cache = QueryPolicy.model_validate(_deep_merge(seeded.model_dump(), payload))
            except Exception as exc:
                # A corrupted policy must not take question answering down.
                logger.warning("query policy unreadable, falling back to defaults: %s", exc)
                _cache = seeded
        else:
            _cache = seeded
        return _cache


def save_policy(payload: Dict[str, Any]) -> QueryPolicy:
    """Merge a console update into the stored policy and refresh the cache."""
    global _cache
    with _lock:
        merged = _deep_merge(load_policy().model_dump(), payload or {})
        policy = QueryPolicy.model_validate(merged)
        _persist(policy)
        _cache = policy
        return policy


def reset_policy() -> QueryPolicy:
    """Restore the built-in defaults and discard every console customization."""
    global _cache
    with _lock:
        policy = default_policy()
        _persist(policy)
        _cache = policy
        return policy


def _persist(policy: QueryPolicy) -> None:
    """Write the policy atomically so a crash cannot leave a half-written file."""
    POLICY_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = POLICY_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(policy.model_dump(), ensure_ascii=False, indent=2), encoding="utf-8")
    os.chmod(tmp, POLICY_FILE_MODE)
    tmp.replace(POLICY_FILE)


def reset_cache() -> None:
    """Drop the in-process copy so the next read reloads from disk."""
    global _cache
    with _lock:
        _cache = None


def public_policy(policy: Optional[QueryPolicy] = None) -> Dict[str, Any]:
    """Return the console payload with persistence metadata attached."""
    data = (policy or load_policy()).model_dump()
    data["source"] = "file" if POLICY_FILE.exists() else "default"
    data["policyFile"] = str(POLICY_FILE)
    data["policyVersionDefault"] = POLICY_VERSION
    return data


def policy_schema() -> Dict[str, Any]:
    """Return the vocabulary the console editor renders from."""
    return {
        "channels": [
            {"name": name, "label": CHANNEL_LABELS[name], "description": CHANNEL_DESCRIPTIONS[name]}
            for name in CHANNELS
        ],
        "defaults": default_policy().model_dump(),
        "policyFile": str(POLICY_FILE),
        "policyVersionDefault": POLICY_VERSION,
    }


def channel_weights(policy: QueryPolicy) -> Dict[str, float]:
    """Return the fusion weight of every configured channel."""
    return {name: float(channel.weight) for name, channel in policy.recall.channels.items()}


def recall_sizes(policy: QueryPolicy, top_k: int) -> Dict[str, int]:
    """Return the per-channel Top-K budget derived from the global Top-K."""
    base = max(int(policy.recall.recallMultiplier), 1) * max(int(top_k), 1)
    return {
        name: max(int(policy.recall.minRecallCandidates), int(round(base * float(channel.scale))))
        for name, channel in policy.recall.channels.items()
    }


def effective_strategies(policy: QueryPolicy, strategies: List[str]) -> List[str]:
    """Drop channels the policy disabled, then fall back so recall never receives an empty list."""
    enabled = [name for name in strategies if policy.recall.channels[name].enabled]
    if enabled:
        return enabled
    fallback = [name for name in policy.route.defaultStrategies if policy.recall.channels[name].enabled]
    return fallback or [name for name in CHANNELS if policy.recall.channels[name].enabled]


def quality_weights(policy: QueryPolicy) -> Dict[str, float]:
    """Return the quality-signal weights of the final score, or ``{}`` when disabled."""
    if not policy.fusion.qualitySignalsEnabled:
        return {}
    weights = policy.fusion.qualityWeights
    return {
        "authority": float(weights.authority),
        "freshness": float(weights.freshness),
        "confidence": float(weights.confidence),
        "evidence": float(weights.evidence),
    }


def policy_summary(policy: QueryPolicy) -> Dict[str, Any]:
    """Return the compact policy fingerprint recorded in every query trace."""
    return {
        "version": policy.version,
        "routingEnabled": policy.route.enabled,
        "routeRuleCount": len(policy.route.rules),
        "rewriteEnabled": policy.rewrite.enabled,
        "llmRewriteEnabled": policy.rewrite.llmEnabled,
        "channels": [name for name, channel in policy.recall.channels.items() if channel.enabled],
        "recallMultiplier": policy.recall.recallMultiplier,
        "minRecallCandidates": policy.recall.minRecallCandidates,
        "rrfK": policy.fusion.rrfK,
        "qualitySignalsEnabled": policy.fusion.qualitySignalsEnabled,
        "rerankEnabled": policy.rerank.enabled,
        "rerankTopN": policy.rerank.topN,
        "contextMaxChars": policy.answer.contextMaxChars,
        "answerPolicyVersion": policy.answer.policyVersion,
        "answerEvidenceGateEnabled": policy.answer.evidenceGateEnabled,
    }
