"""Runtime retrieval-index configuration (Elasticsearch / Milvus) editable from the console.

The model centre only covers generation and embedding endpoints; the two retrieval
engines were left on environment variables, so turning an engine on or off meant
editing the deployment and restarting the AI service. This module gives them the
same treatment as the model centre:

* typed Pydantic models describe each engine (url, index/collection, read alias,
  credentials, analyzer);
* effective values live in ``<KNOWLEDGE_DATA_DIR>/retrieval_settings.json`` and
  take effect immediately, without a restart;
* once that file exists it is authoritative. Environment variables only seed the
  initial values, so console edits are never silently reverted on restart;
* secrets are masked on read, never echoed back, and stored with 0600 mode.

The file is kept separate from ``settings.json`` on purpose: the model centre
persists only its own sections and would drop anything else written there.
"""

from __future__ import annotations

import json
import os
import threading
from typing import Any, Literal

from pydantic import BaseModel, Field

from app.core.config import DATA_DIR
from app.core.config import settings as env_settings

SETTINGS_FILE = DATA_DIR / "retrieval_settings.json"
SETTINGS_FILE_MODE = 0o600
SECTIONS = ("elasticsearch", "milvus")
_MASK = "****"

# Re-entrant: ``save_retrieval_settings`` reuses ``load_retrieval_settings`` while
# already holding the lock, which would deadlock with a plain ``threading.Lock``.
_lock = threading.RLock()
_cache: RetrievalSettings | None = None


class ElasticsearchSettings(BaseModel):
    """Full-text engine. Disabled means the local BM25 index answers instead."""

    enabled: bool = True
    url: str = "http://127.0.0.1:9200"
    index: str = "llmkb_knowledge_v1"
    readAlias: str = "llmkb_knowledge_read"
    username: str = ""
    password: str = ""
    verifySsl: bool = False
    analyzer: str = "standard"


class MilvusSettings(BaseModel):
    """Dense-vector engine. Disabled means the local vector index answers instead."""

    enabled: bool = True
    url: str = "http://127.0.0.1:19530"
    collection: str = "llmkb_knowledge_v1"
    readAlias: str = "llmkb_knowledge_read"
    token: str = ""


class RetrievalSettings(BaseModel):
    """Retrieval-mode switches plus the two optional engines.

    ``mode`` decides whether external engines are consulted at all (design §12.5):
    ``local`` ignores them even when enabled, ``auto`` uses them and falls back to
    the local index when they do not answer, ``external``/``dual`` treat them as
    the intended source. ``externalRequired`` turns a degraded engine into a hard
    failure instead of a silent fallback, which is how a partially wired
    deployment gets noticed instead of quietly answering from stale local data.
    """

    mode: Literal["local", "auto", "external", "dual"] = "auto"
    externalRequired: bool = False
    elasticsearch: ElasticsearchSettings = Field(default_factory=ElasticsearchSettings)
    milvus: MilvusSettings = Field(default_factory=MilvusSettings)


# ---------------------------------------------------------------------------
# Initial values derived from the deployment environment
# ---------------------------------------------------------------------------
def _env_mode() -> str:
    mode = str(env_settings.retrieval_mode or "auto").lower()
    return mode if mode in {"local", "auto", "external", "dual"} else "auto"


def seed_from_env() -> RetrievalSettings:
    """Translate the deployment environment switches into a persisted model."""
    return RetrievalSettings(
        mode=_env_mode(),  # type: ignore[arg-type]
        externalRequired=bool(env_settings.retrieval_external_required),
        elasticsearch=ElasticsearchSettings(
            enabled=bool(env_settings.elasticsearch_enabled),
            url=env_settings.elasticsearch_url,
            index=env_settings.elasticsearch_index,
            readAlias=env_settings.elasticsearch_read_alias,
            username=env_settings.elasticsearch_username,
            password=env_settings.elasticsearch_password,
            verifySsl=bool(env_settings.elasticsearch_verify_ssl),
            analyzer=env_settings.elasticsearch_analyzer,
        ),
        milvus=MilvusSettings(
            enabled=bool(env_settings.milvus_enabled),
            url=env_settings.milvus_url,
            collection=env_settings.milvus_collection,
            readAlias=env_settings.milvus_read_alias,
            token=env_settings.milvus_token,
        ),
    )


# ---------------------------------------------------------------------------
# Read / write
# ---------------------------------------------------------------------------
def load_retrieval_settings(force: bool = False) -> RetrievalSettings:
    """Return the effective retrieval settings, seeding them from disk only once."""
    global _cache
    with _lock:
        if _cache is not None and not force:
            return _cache
        seeded = seed_from_env()
        if SETTINGS_FILE.exists():
            try:
                raw: dict[str, Any] = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
                known = {key: raw[key] for key in ("mode", "externalRequired", *SECTIONS) if key in raw}
                _cache = RetrievalSettings.model_validate({**seeded.model_dump(), **known})
            except Exception:
                # A corrupted file must never brick retrieval; fall back to env.
                _cache = seeded
        else:
            _cache = seeded
        return _cache


def save_retrieval_settings(payload: dict[str, Any]) -> RetrievalSettings:
    """Merge a partial console update into the stored configuration."""
    global _cache
    with _lock:
        merged = load_retrieval_settings().model_dump()
        payload = payload if isinstance(payload, dict) else {}
        for key in ("mode", "externalRequired"):
            if key in payload:
                merged[key] = payload[key]
        for section in SECTIONS:
            incoming = payload.get(section)
            if not isinstance(incoming, dict):
                continue
            for key, value in incoming.items():
                # A masked secret means "leave the stored value untouched".
                if isinstance(value, str) and _MASK in value:
                    continue
                if key in merged[section]:
                    merged[section][key] = value
        settings = RetrievalSettings.model_validate(merged)
        _persist(settings)
        _cache = settings
        apply_retrieval_settings(settings)
        return settings


def _persist(settings: RetrievalSettings) -> None:
    """Write atomically and keep credentials readable by the owner only."""
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = SETTINGS_FILE.with_suffix(".json.tmp")
    tmp.write_text(
        json.dumps(settings.model_dump(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    os.chmod(tmp, SETTINGS_FILE_MODE)
    tmp.replace(SETTINGS_FILE)


def apply_retrieval_settings(settings: RetrievalSettings | None = None) -> None:
    """Push the configuration into the process-wide settings and rebuild clients.

    Every retrieval path reads ``app.core.config.settings``, so writing those
    attributes is what makes a console edit take effect without a restart. The
    provider singletons additionally cache an HTTP client built from the url and
    credentials, so they are dropped whenever those change.
    """
    settings = settings or load_retrieval_settings()
    env_settings.retrieval_mode = settings.mode
    env_settings.retrieval_external_required = settings.externalRequired
    env_settings.elasticsearch_enabled = settings.elasticsearch.enabled
    env_settings.elasticsearch_url = settings.elasticsearch.url.rstrip("/")
    env_settings.elasticsearch_index = settings.elasticsearch.index.lower()
    env_settings.elasticsearch_read_alias = settings.elasticsearch.readAlias.lower()
    env_settings.elasticsearch_username = settings.elasticsearch.username
    env_settings.elasticsearch_password = settings.elasticsearch.password
    env_settings.elasticsearch_verify_ssl = settings.elasticsearch.verifySsl
    env_settings.elasticsearch_analyzer = settings.elasticsearch.analyzer
    env_settings.milvus_enabled = settings.milvus.enabled
    env_settings.milvus_url = settings.milvus.url.rstrip("/")
    env_settings.milvus_collection = settings.milvus.collection.lower()
    env_settings.milvus_read_alias = settings.milvus.readAlias.lower()
    env_settings.milvus_token = settings.milvus.token

    from app.services.retrieval.elasticsearch_provider import get_elasticsearch_provider
    from app.services.retrieval.milvus_provider import get_milvus_provider

    get_elasticsearch_provider.cache_clear()
    get_milvus_provider.cache_clear()


def reset_cache() -> None:
    """Drop the in-process copy so the next read reloads from disk."""
    global _cache
    with _lock:
        _cache = None


# ---------------------------------------------------------------------------
# Console-facing view
# ---------------------------------------------------------------------------
def mask_secret(value: str) -> str:
    """Render a credential-safe preview such as ``sk-1****7d7e``."""
    if not value:
        return ""
    if len(value) <= 8:
        return _MASK
    return value[:4] + _MASK + value[-4:]


def public_retrieval_settings(settings: RetrievalSettings | None = None) -> dict[str, Any]:
    """Return the console payload with every credential masked."""
    settings = settings or load_retrieval_settings()
    data = settings.model_dump()
    data["elasticsearch"]["password"] = mask_secret(settings.elasticsearch.password)
    data["elasticsearch"]["passwordSet"] = bool(settings.elasticsearch.password)
    data["elasticsearch"]["usernameSet"] = bool(settings.elasticsearch.username)
    data["milvus"]["token"] = mask_secret(settings.milvus.token)
    data["milvus"]["tokenSet"] = bool(settings.milvus.token)
    data["source"] = "file" if SETTINGS_FILE.exists() else "env"
    data["settingsFile"] = str(SETTINGS_FILE)
    return data
