"""HTTP routes for the console model-settings panel: read, save, and probe."""

from typing import Any

from fastapi import APIRouter, HTTPException

from app.core.runtime_settings import (
    SETTINGS_FILE,
    load_model_settings,
    public_model_settings,
    save_model_settings,
)
from app.services.embedding import get_embedding_provider
from app.services.llm_provider import get_llm_provider

router = APIRouter()


@router.get("/ai/settings/model")
def read_model_settings() -> dict[str, Any]:
    """Return the effective model configuration with every credential masked."""
    return public_model_settings()


@router.post("/ai/settings/model")
def update_model_settings(payload: dict[str, Any]) -> dict[str, Any]:
    """Persist a partial update; masked secrets keep the currently stored value."""
    try:
        settings = save_model_settings(payload)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"配置无效：{exc}") from exc
    return public_model_settings(settings)


@router.post("/ai/settings/test")
async def test_model_settings(payload: dict[str, Any]) -> dict[str, Any]:
    """Probe the configured generation or embedding endpoint end-to-end."""
    kind = str((payload or {}).get("kind", "llm")).lower()
    try:
        if kind == "embedding":
            result = await get_embedding_provider().test()
        else:
            result = await get_llm_provider().test()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    return {"kind": kind, **result}


@router.get("/ai/settings/env")
def model_settings_env() -> dict[str, Any]:
    """Report where the configuration comes from, for troubleshooting."""
    settings = load_model_settings()
    return {
        "settingsFile": str(SETTINGS_FILE),
        "persisted": SETTINGS_FILE.exists(),
        "llmProvider": settings.llm.provider,
        "llmModel": settings.llm.model,
        "embeddingProvider": settings.embedding.provider,
        "embeddingModel": settings.embedding.model,
        "embeddingDim": settings.embedding.dim,
    }
