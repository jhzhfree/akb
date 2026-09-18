"""Runtime model configuration persisted as JSON and editable from the console.

The model centre mirrors the configuration flow used by llmwiki:

* typed Pydantic models describe each model endpoint (provider, base url,
  credential, model name, sampling parameters, timeout, vector dimension);
* effective values live in ``<KNOWLEDGE_DATA_DIR>/settings.json`` and take
  effect immediately, without restarting the AI service;
* once that file exists it is authoritative. Environment variables only seed the
  initial values, so console edits are never silently reverted on restart;
* secrets are masked on read, never echoed back, and stored with 0600 mode.
"""

from __future__ import annotations

import json
import os
import threading
from pathlib import Path
from typing import Any, Dict, Literal, Optional

from pydantic import BaseModel, Field

from app.core.config import DATA_DIR, settings as env_settings

SETTINGS_FILE = DATA_DIR / "settings.json"
SETTINGS_FILE_MODE = 0o600
SECTIONS = ("llm", "embedding")
_MASK = "****"

# Re-entrant: ``save_model_settings`` reuses ``load_model_settings`` while already
# holding the lock, which would deadlock with a plain ``threading.Lock``.
_lock = threading.RLock()
_cache: Optional["ModelSettings"] = None


class LlmSettings(BaseModel):
    """Chat / generation endpoint. ``openai`` means any OpenAI-compatible gateway."""

    provider: Literal["mock", "ollama", "openai"] = "mock"
    base_url: str = "http://127.0.0.1:11434"
    api_key: str = ""
    model: str = "qwen2.5:7b"
    temperature: float = Field(default=0.2, ge=0.0, le=2.0)
    max_tokens: int = Field(default=4096, ge=1, le=131072)
    timeout: float = Field(default=180.0, gt=0.0, le=600.0)


class EmbeddingSettings(BaseModel):
    """Embedding endpoint. ``hash`` keeps the zero-dependency deterministic vector."""

    enabled: bool = True
    provider: Literal["hash", "ollama", "openai"] = "hash"
    base_url: str = "http://127.0.0.1:11434"
    api_key: str = ""
    model: str = "nomic-embed-text"
    dim: int = Field(default=384, ge=16, le=4096)
    timeout: float = Field(default=60.0, gt=0.0, le=600.0)


class ModelSettings(BaseModel):
    """Top-level model configuration exposed to the console."""

    llm: LlmSettings = Field(default_factory=LlmSettings)
    embedding: EmbeddingSettings = Field(default_factory=EmbeddingSettings)


# ---------------------------------------------------------------------------
# Initial values derived from the deployment environment
# ---------------------------------------------------------------------------
def _env_llm_provider() -> str:
    provider = str(env_settings.llm_provider).lower()
    return provider if provider in {"mock", "ollama", "openai"} else "mock"


def _env_embedding_provider() -> str:
    provider = str(env_settings.embedding_provider).lower()
    return provider if provider in {"hash", "ollama", "openai"} else "hash"


def seed_from_env() -> ModelSettings:
    """Translate the legacy environment switches into a persisted model."""
    return ModelSettings(
        llm=LlmSettings(
            provider=_env_llm_provider(),  # type: ignore[arg-type]
            base_url=env_settings.llm_base_url,
            api_key=os.getenv("LLM_API_KEY", ""),
            model=env_settings.llm_model,
        ),
        embedding=EmbeddingSettings(
            provider=_env_embedding_provider(),  # type: ignore[arg-type]
            base_url=env_settings.llm_base_url,
            api_key=os.getenv("EMBEDDING_API_KEY", ""),
            model=env_settings.embedding_model,
            dim=env_settings.embedding_dim,
        ),
    )


# ---------------------------------------------------------------------------
# Read / write
# ---------------------------------------------------------------------------
def load_model_settings(force: bool = False) -> ModelSettings:
    """Return the effective model settings, seeding them from disk only once."""
    global _cache
    with _lock:
        if _cache is not None and not force:
            return _cache
        seeded = seed_from_env()
        if SETTINGS_FILE.exists():
            try:
                raw: Dict[str, Any] = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
                known = {key: raw[key] for key in SECTIONS if isinstance(raw.get(key), dict)}
                _cache = ModelSettings.model_validate({**seeded.model_dump(), **known})
            except Exception:
                # A corrupted file must never brick the service; fall back to env.
                _cache = seeded
        else:
            _cache = seeded
        return _cache


def save_model_settings(payload: Dict[str, Any]) -> ModelSettings:
    """Merge a partial console update into the stored configuration."""
    global _cache
    with _lock:
        current = load_model_settings()
        merged = current.model_dump()
        for section in SECTIONS:
            incoming = payload.get(section) if isinstance(payload, dict) else None
            if not isinstance(incoming, dict):
                continue
            for key, value in incoming.items():
                if key.endswith("_set"):
                    continue
                # A masked secret means "leave the stored value untouched".
                if isinstance(value, str) and _MASK in value:
                    continue
                if key in merged[section]:
                    merged[section][key] = value
        settings = ModelSettings.model_validate(merged)
        _persist(settings)
        _cache = settings
        return settings


def _persist(settings: ModelSettings) -> None:
    """Write atomically and keep credentials readable by the owner only."""
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = SETTINGS_FILE.with_suffix(".json.tmp")
    tmp.write_text(
        json.dumps(settings.model_dump(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    os.chmod(tmp, SETTINGS_FILE_MODE)
    tmp.replace(SETTINGS_FILE)


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


def public_model_settings(settings: Optional[ModelSettings] = None) -> Dict[str, Any]:
    """Return the console payload with every credential masked."""
    settings = settings or load_model_settings()
    data = settings.model_dump()
    for section in SECTIONS:
        section_settings = getattr(settings, section)
        data[section]["api_key_set"] = bool(section_settings.api_key)
        data[section]["api_key"] = mask_secret(section_settings.api_key)
    data["source"] = "file" if SETTINGS_FILE.exists() else "env"
    data["settingsFile"] = str(SETTINGS_FILE)
    return data
