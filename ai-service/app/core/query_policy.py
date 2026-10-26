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

import datetime
import json
import logging
import os
import re
import threading
import urllib.parse
from typing import Any

from pydantic import BaseModel, Field, field_validator, model_validator

from app.core.config import BASE_DIR, DATA_DIR
from app.core.config import settings as env_settings

POLICY_FILE = DATA_DIR / "query_policy.json"
# 出厂默认随代码发布、进版本库；运行时的那份在 DATA_DIR 里（通常是个持久卷，已经存在的
# 卷不会被新镜像覆盖）。所以只在镜像里放文件是不够的，首次启动要从这里播种一次。
DEFAULT_POLICY_FILE = BASE_DIR / "config" / "query_policy.defaults.json"
POLICY_FILE_MODE = 0o600
POLICY_VERSION = "query-policy-v1"

logger = logging.getLogger(__name__)

_lock = threading.RLock()
_cache: QueryPolicy | None = None

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

# The two channels that carry verbatim document chunks carry the most citation-ready
# evidence: an answer quoting a chunk can point at evidenceCode + evidenceHash, while a
# card or graph path often cannot. They therefore start level with structured objects
# instead of below them — see docs/design/STABILITY-OPTIMIZATION-PLAN.md section B2.
DEFAULT_CHANNEL_WEIGHTS = {
    "bm25": 1.15,
    "vector": 1.15,
    "card": 1.10,
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
DEFAULT_ROUTE_RULES: list[dict[str, Any]] = [
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


def _clean_channels(values: Any) -> list[str]:
    """Normalize a channel list and reject names the retriever cannot serve."""
    cleaned: list[str] = []
    for item in values or []:
        name = str(item).strip().lower()
        if name and name not in cleaned:
            cleaned.append(name)
    unknown = [name for name in cleaned if name not in CHANNELS]
    if unknown:
        raise ValueError("未知召回通道：%s（可选：%s）" % (",".join(unknown), ",".join(CHANNELS)))
    return cleaned


def _clean_keywords(values: Any) -> list[str]:
    """Normalize trigger keywords: trimmed, de-duplicated, order preserving."""
    cleaned: list[str] = []
    for item in values or []:
        text = str(item).strip()
        if text and text not in cleaned:
            cleaned.append(text)
    return cleaned


class RouteRule(BaseModel):
    """One question-type routing rule edited from the operations console."""

    questionType: str
    enabled: bool = True
    keywords: list[str] = Field(default_factory=list)
    strategies: list[str] = Field(default_factory=list)
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
    def _keywords(cls, value: list[str]) -> list[str]:
        """Keep keyword arrays canonical (trimmed and unique)."""
        return _clean_keywords(value)

    @field_validator("strategies")
    @classmethod
    def _channels(cls, value: list[str]) -> list[str]:
        """A rule must point at real channels, otherwise recall silently returns nothing."""
        cleaned = _clean_channels(value)
        if not cleaned:
            raise ValueError("至少选择一个召回通道")
        return cleaned


class RoutePolicy(BaseModel):
    """Question-understanding routing behaviour."""

    enabled: bool = True
    rules: list[RouteRule] = Field(
        default_factory=lambda: [RouteRule.model_validate(item) for item in DEFAULT_ROUTE_RULES]
    )
    defaultStrategies: list[str] = Field(default_factory=lambda: ["card", "vector", "bm25", "claim"])

    @field_validator("defaultStrategies")
    @classmethod
    def _default_channels(cls, value: list[str]) -> list[str]:
        """Fallback strategies must also name real channels."""
        cleaned = _clean_channels(value)
        if not cleaned:
            raise ValueError("默认召回通道不能为空")
        return cleaned

    @model_validator(mode="after")
    def _unique_types(self) -> RoutePolicy:
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
    synonyms: dict[str, list[str]] = Field(default_factory=lambda: dict(DEFAULT_SYNONYMS))


class ChannelPolicy(BaseModel):
    """Per-channel recall switch, fusion weight, and budget multiplier."""

    enabled: bool = True
    weight: float = Field(default=1.0, ge=0.0, le=5.0)
    scale: float = Field(default=1.0, ge=0.1, le=5.0)


class RecallPolicy(BaseModel):
    """Multi-channel recall configuration."""

    channels: dict[str, ChannelPolicy] = Field(
        default_factory=lambda: {
            name: ChannelPolicy(weight=DEFAULT_CHANNEL_WEIGHTS.get(name, 1.0)) for name in CHANNELS
        }
    )
    recallMultiplier: int = Field(default=3, ge=1, le=10)
    minRecallCandidates: int = Field(default=20, ge=1, le=200)

    @field_validator("channels")
    @classmethod
    def _known_channels(cls, value: dict[str, ChannelPolicy]) -> dict[str, ChannelPolicy]:
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


class ChunkBoostPolicy(BaseModel):
    """Extra ranking weight given to verbatim document chunks.

    A chunk is the only evidence shape that always carries a quotable, hashable source,
    so lifting it lifts citation faithfulness as a side effect (plan section B3).
    """

    enabled: bool = True
    base: float = Field(default=1.25, ge=1.0, le=3.0)
    headingHit: float = Field(default=0.15, ge=0.0, le=1.0)
    coverage: float = Field(default=0.15, ge=0.0, le=1.0)


class FusionPolicy(BaseModel):
    """Weighted RRF fusion, quality-signal boost, and verbatim-chunk boost."""

    rrfK: int = Field(default=60, ge=1, le=1000)
    qualitySignalsEnabled: bool = True
    qualityWeights: QualityWeights = Field(default_factory=QualityWeights)
    chunkBoost: ChunkBoostPolicy = Field(default_factory=ChunkBoostPolicy)


class RerankPolicy(BaseModel):
    """Rerank node behaviour; zero selects a bounded automatic depth."""

    enabled: bool = True
    topN: int = Field(default=0, ge=0, le=200)


class AnswerPolicy(BaseModel):
    """Context assembly, guardrail thresholds, and the refusal sentence."""

    contextMaxChars: int = Field(default=8000, ge=500, le=60000)
    # Chunks are what an answer can quote verbatim; without a floor, high-scoring cards
    # can fill the whole context and leave every sentence without an exact citation.
    minChunkEvidence: int = Field(default=3, ge=0, le=20)
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


def _deep_merge(base: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
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


# What a knowledge base may override, and nothing else. The list is deliberately short: a
# library decides how it wants to be asked (channels, weights, fusion, rerank, evidence
# floor), while routing rules, rewrite behaviour and the refusal sentence stay platform
# policy — one library cannot quietly change what "no answer" means for everybody else.
OVERRIDABLE_FIELDS = (
    ("route", "defaultStrategies"),
    ("route", "rules"),
    ("route", "enabled"),
    ("recall", "channels"),
    ("recall", "recallMultiplier"),
    ("recall", "minRecallCandidates"),
    ("fusion", "rrfK"),
    ("fusion", "qualitySignalsEnabled"),
    ("fusion", "chunkBoost", "enabled"),
    ("rerank", "enabled"),
    ("rerank", "topN"),
    ("answer", "contextMaxChars"),
    ("answer", "minChunkEvidence"),
)


def merge_override(policy: QueryPolicy, override: dict[str, Any] | None) -> QueryPolicy:
    """Merge a knowledge base's scenario settings onto the platform policy.

    Unknown keys are dropped rather than merged, and an override that fails validation falls
    back to the platform policy: a bad value in one library's profile must degrade that
    library to the defaults, never turn a question into a 500.
    """
    if not isinstance(override, dict) or not override:
        return policy
    allowed: dict[str, Any] = {}
    for path in OVERRIDABLE_FIELDS:
        value = _dig(override, path)
        if value is None:
            continue
        _put(allowed, path, value)
    if not allowed:
        return policy
    try:
        return QueryPolicy.model_validate(_deep_merge(policy.model_dump(), allowed))
    except Exception as exc:  # noqa: BLE001 - a bad profile degrades, it does not fail
        logger.warning("kb policy override rejected, falling back to platform policy: %s", exc)
        return policy


def _dig(source: dict[str, Any], path: tuple[str, ...]) -> Any:
    """Read a nested value without assuming the levels exist."""
    current: Any = source
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def _put(target: dict[str, Any], path: tuple[str, ...], value: Any) -> None:
    """Write a nested value, creating the levels on the way down."""
    current = target
    for key in path[:-1]:
        nxt = current.get(key)
        if not isinstance(nxt, dict):
            nxt = {}
            current[key] = nxt
        current = nxt
    current[path[-1]] = value


DEFAULT_PROFILE = "default"


def _now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _summary_of(policy: QueryPolicy) -> dict[str, Any]:
    """Console fingerprint of a profile: enough to compare two strategies at a glance."""
    return {
        "channels": [name for name, channel in policy.recall.channels.items() if channel.enabled],
        "routingEnabled": policy.route.enabled,
        "rerankEnabled": policy.rerank.enabled,
        "rrfK": policy.fusion.rrfK,
        "recallMultiplier": policy.recall.recallMultiplier,
    }


def _profile_name(raw: Any) -> str:
    """Console-supplied profile ids are constrained: they end up in a file name space."""
    name = str(raw or "").strip()
    if not name:
        raise ValueError("策略名称不能为空")
    if len(name) > 40:
        raise ValueError("策略名称不能超过 40 个字符")
    if not re.fullmatch(r"[A-Za-z0-9_\-\u4e00-\u9fff]+", name):
        raise ValueError("策略名称只能包含中英文、数字、下划线与短横线")
    return name


def _coerce_name(raw: str) -> str:
    """Normalize a profile id that arrives through a URL path segment.

    A legal id never contains '%', so a percent-encoded value can only come from a
    caller that encoded the segment twice (backend pre-encoding plus the HTTP client
    encoding it again). Decoding once keeps those callers working instead of failing
    with "策略不存在：%E8%BF%90...".
    """
    text = str(raw or "")
    if "%" in text:
        text = urllib.parse.unquote(text, encoding="utf-8", errors="replace")
    return _profile_name(text)


def _seed_defaults() -> None:
    """Copy the shipped defaults into the data directory on first start.

    The data directory is normally a persistent volume, and a volume that already
    exists is never refreshed from a newer image. A deployment that only ships the
    file inside the image therefore keeps running on whatever happened to be there
    at first start — which is how a production box ends up showing a single default
    strategy. Seeding here is what gets the shipped defaults to actually arrive.
    """
    if POLICY_FILE.exists() or not DEFAULT_POLICY_FILE.exists():
        return
    try:
        raw = json.loads(DEFAULT_POLICY_FILE.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("shipped query policy defaults unreadable: %s", exc)
        return
    if not (isinstance(raw, dict) and isinstance(raw.get("profiles"), dict) and raw["profiles"]):
        return
    try:
        _write_store(raw)
        logger.info("seeded query policy with %d shipped profiles", len(raw["profiles"]))
    except Exception as exc:
        logger.warning("could not seed query policy defaults: %s", exc)


def _read_store() -> dict[str, Any]:
    """Read the persisted policy store, promoting a legacy single-policy file on the fly."""
    _seed_defaults()
    store: dict[str, Any] = {"active": DEFAULT_PROFILE, "profiles": {}}
    if POLICY_FILE.exists():
        try:
            raw = json.loads(POLICY_FILE.read_text(encoding="utf-8"))
        except Exception as exc:
            logger.warning("query policy unreadable, falling back to defaults: %s", exc)
            raw = {}
        if isinstance(raw, dict):
            profiles = raw.get("profiles")
            if isinstance(profiles, dict) and profiles:
                for name, entry in profiles.items():
                    body = entry if isinstance(entry, dict) else {}
                    payload = body.get("policy")
                    store["profiles"][str(name)] = {
                        "label": str(body.get("label") or name),
                        "updatedAt": str(body.get("updatedAt") or ""),
                        "policy": payload if isinstance(payload, dict) else {},
                    }
                store["active"] = str(raw.get("active") or DEFAULT_PROFILE)
            else:
                # Legacy layout: one anonymous policy becomes the default profile.
                store["profiles"][DEFAULT_PROFILE] = {
                    "label": "默认策略",
                    "updatedAt": "",
                    "policy": raw,
                }
    if store["active"] not in store["profiles"]:
        store["active"] = next(iter(store["profiles"]), DEFAULT_PROFILE)
    if not store["profiles"]:
        store["profiles"][DEFAULT_PROFILE] = {"label": "默认策略", "updatedAt": "", "policy": {}}
    return store


def _validate(payload: dict[str, Any]) -> QueryPolicy:
    """Validate a stored payload against the defaults so partial files still load."""
    return QueryPolicy.model_validate(_deep_merge(default_policy().model_dump(), payload or {}))


def _write_store(store: dict[str, Any]) -> None:
    """Write the whole store atomically so a crash cannot leave a half-written file."""
    POLICY_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = POLICY_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(store, ensure_ascii=False, indent=2), encoding="utf-8")
    os.chmod(tmp, POLICY_FILE_MODE)
    tmp.replace(POLICY_FILE)


def load_policy(force: bool = False) -> QueryPolicy:
    """Return the effective policy: the active profile's policy."""
    global _cache
    with _lock:
        if _cache is not None and not force:
            return _cache
        store = _read_store()
        entry = store["profiles"].get(store["active"]) or {}
        _cache = _validate(entry.get("policy") if isinstance(entry, dict) else {})
        return _cache


def save_policy(payload: dict[str, Any]) -> QueryPolicy:
    """Merge a console update into the active profile and refresh the cache."""
    global _cache
    with _lock:
        store = _read_store()
        name = store["active"]
        entry = store["profiles"].get(name) or {"label": name, "updatedAt": "", "policy": {}}
        merged = _deep_merge(_validate(entry.get("policy") or {}).model_dump(), payload or {})
        policy = QueryPolicy.model_validate(merged)
        store["profiles"][name] = {
            "label": entry.get("label") or name,
            "updatedAt": _now(),
            "policy": policy.model_dump(),
        }
        # Keep the active policy mirrored at the top level for backward-compatible
        # readers while named profiles remain the authoritative persistence model.
        store.update(policy.model_dump())
        _write_store(store)
        _cache = policy
        return policy


def reset_policy() -> QueryPolicy:
    """Restore the built-in defaults for the active profile, keeping other profiles."""
    global _cache
    with _lock:
        store = _read_store()
        name = store["active"]
        entry = store["profiles"].get(name) or {}
        policy = default_policy()
        store["profiles"][name] = {
            "label": entry.get("label") or name,
            "updatedAt": _now(),
            "policy": policy.model_dump(),
        }
        _write_store(store)
        _cache = policy
        return policy


def list_profiles() -> dict[str, Any]:
    """Every saved strategy with the fingerprint the console compares them by."""
    with _lock:
        store = _read_store()
        items: list[dict[str, Any]] = []
        for name, entry in store["profiles"].items():
            policy = _validate(entry.get("policy") or {})
            items.append(
                {
                    "name": name,
                    "label": entry.get("label") or name,
                    "updatedAt": entry.get("updatedAt") or "",
                    "active": name == store["active"],
                    "summary": _summary_of(policy),
                }
            )
        items.sort(key=lambda item: (not item["active"], item["name"]))
        return {"active": store["active"], "profiles": items}


def create_profile(
    name: str, label: str | None = None, base: str | None = None, policy: dict[str, Any] | None = None
) -> dict[str, Any]:
    """Create a strategy by cloning another one (or the defaults), without activating it."""
    with _lock:
        clean = _profile_name(name)
        store = _read_store()
        if clean in store["profiles"]:
            raise ValueError("策略已存在：%s" % clean)
        source = policy if isinstance(policy, dict) and policy else None
        if source is None:
            donor = base or store["active"]
            source = (store["profiles"].get(donor) or {}).get("policy") or {}
        store["profiles"][clean] = {
            "label": str(label or clean),
            "updatedAt": _now(),
            "policy": _validate(source).model_dump(),
        }
        _write_store(store)
        return list_profiles()


def update_profile(name: str, payload: dict[str, Any]) -> dict[str, Any]:
    """Merge a console patch into one profile; only the active one invalidates the cache."""
    global _cache
    with _lock:
        store = _read_store()
        name = _coerce_name(name)
        if name not in store["profiles"]:
            raise ValueError("策略不存在：%s" % name)
        entry = store["profiles"][name] or {}
        merged = _deep_merge(_validate(entry.get("policy") or {}).model_dump(), payload or {})
        policy = QueryPolicy.model_validate(merged)
        store["profiles"][name] = {
            "label": entry.get("label") or name,
            "updatedAt": _now(),
            "policy": policy.model_dump(),
        }
        _write_store(store)
        if name == store["active"]:
            _cache = policy
        return list_profiles()


def rename_profile(name: str, label: str | None = None, new_name: str | None = None) -> dict[str, Any]:
    """Rename a profile or change its display label, keeping it the active one if it was."""
    with _lock:
        store = _read_store()
        name = _coerce_name(name)
        if name not in store["profiles"]:
            raise ValueError("策略不存在：%s" % name)
        entry = store["profiles"].pop(name)
        target = _coerce_name(new_name) if new_name else name
        if target in store["profiles"]:
            store["profiles"][name] = entry
            raise ValueError("策略已存在：%s" % target)
        entry["label"] = str(label or entry.get("label") or target)
        entry["updatedAt"] = _now()
        store["profiles"][target] = entry
        if store["active"] == name:
            store["active"] = target
        _write_store(store)
        reset_cache()
        return list_profiles()


def delete_profile(name: str) -> dict[str, Any]:
    """Delete a profile; the active one and the last survivor are protected."""
    global _cache
    with _lock:
        store = _read_store()
        name = _coerce_name(name)
        if name not in store["profiles"]:
            raise ValueError("策略不存在：%s" % name)
        if len(store["profiles"]) <= 1:
            raise ValueError("至少需要保留一个检索策略")
        if store["active"] == name:
            raise ValueError("不能删除正在使用的策略，请先切换到其它策略")
        del store["profiles"][name]
        _write_store(store)
        _cache = None
        return list_profiles()


def activate_profile(name: str) -> dict[str, Any]:
    """Switch every following query to another saved strategy."""
    global _cache
    with _lock:
        store = _read_store()
        name = _coerce_name(name)
        if name not in store["profiles"]:
            raise ValueError("策略不存在：%s" % name)
        store["active"] = name
        _write_store(store)
        _cache = None
        return list_profiles()


def reset_cache() -> None:
    """Drop the in-process copy so the next read reloads from disk."""
    global _cache
    with _lock:
        _cache = None


def public_policy(policy: QueryPolicy | None = None) -> dict[str, Any]:
    """Return the console payload with persistence metadata attached."""
    data = (policy or load_policy()).model_dump()
    data["source"] = "file" if POLICY_FILE.exists() else "default"
    data["policyFile"] = str(POLICY_FILE)
    data["policyVersionDefault"] = POLICY_VERSION
    profiles = list_profiles()
    data["activeProfile"] = profiles["active"]
    data["profiles"] = profiles["profiles"]
    return data


def policy_schema() -> dict[str, Any]:
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


def channel_weights(policy: QueryPolicy) -> dict[str, float]:
    """Return the fusion weight of every configured channel."""
    return {name: float(channel.weight) for name, channel in policy.recall.channels.items()}


def recall_sizes(policy: QueryPolicy, top_k: int) -> dict[str, int]:
    """Return the per-channel Top-K budget derived from the global Top-K."""
    base = max(int(policy.recall.recallMultiplier), 1) * max(int(top_k), 1)
    return {
        name: max(int(policy.recall.minRecallCandidates), int(round(base * float(channel.scale))))
        for name, channel in policy.recall.channels.items()
    }


def effective_strategies(policy: QueryPolicy, strategies: list[str]) -> list[str]:
    """Drop channels the policy disabled, then fall back so recall never receives an empty list."""
    enabled = [name for name in strategies if policy.recall.channels[name].enabled]
    if enabled:
        return enabled
    fallback = [name for name in policy.route.defaultStrategies if policy.recall.channels[name].enabled]
    return fallback or [name for name in CHANNELS if policy.recall.channels[name].enabled]


def quality_weights(policy: QueryPolicy) -> dict[str, float]:
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


def chunk_boost_weights(policy: QueryPolicy) -> dict[str, float]:
    """Return the verbatim-chunk boost settings, or ``{}`` when disabled."""
    if not policy.fusion.chunkBoost.enabled:
        return {}
    boost = policy.fusion.chunkBoost
    return {"base": float(boost.base), "headingHit": float(boost.headingHit), "coverage": float(boost.coverage)}


def policy_summary(policy: QueryPolicy) -> dict[str, Any]:
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
        "chunkBoostEnabled": policy.fusion.chunkBoost.enabled,
        "rerankEnabled": policy.rerank.enabled,
        "rerankTopN": policy.rerank.topN,
        "contextMaxChars": policy.answer.contextMaxChars,
        "minChunkEvidence": policy.answer.minChunkEvidence,
        "answerPolicyVersion": policy.answer.policyVersion,
        "answerEvidenceGateEnabled": policy.answer.evidenceGateEnabled,
    }
