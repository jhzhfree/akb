"""HTTP routes for the console retrieval-index switches: read, save, and probe."""

from typing import Any

from fastapi import APIRouter, HTTPException

from app.core.retrieval_settings import (
    SETTINGS_FILE,
    load_retrieval_settings,
    public_retrieval_settings,
    save_retrieval_settings,
)
from app.services.retrieval.elasticsearch_provider import get_elasticsearch_provider
from app.services.retrieval.milvus_provider import get_milvus_provider

router = APIRouter()


async def _provider_stats() -> dict[str, Any]:
    """Report what each engine is actually doing right now, not what is configured."""
    elasticsearch = get_elasticsearch_provider()
    milvus = get_milvus_provider()
    return {
        "elasticsearch": await elasticsearch.stats(),
        "milvus": await milvus.stats(),
    }


@router.get("/ai/settings/retrieval")
async def read_retrieval_settings() -> dict[str, Any]:
    """Return the effective retrieval configuration with every credential masked."""
    return {**public_retrieval_settings(), "providers": await _provider_stats()}


@router.post("/ai/settings/retrieval")
async def update_retrieval_settings(payload: dict[str, Any]) -> dict[str, Any]:
    """Persist a partial update; masked secrets keep the currently stored value."""
    try:
        settings = save_retrieval_settings(payload)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"配置无效：{exc}") from exc
    return {**public_retrieval_settings(settings), "providers": await _provider_stats()}


@router.post("/ai/settings/retrieval/test")
async def test_retrieval_settings() -> dict[str, Any]:
    """Probe both engines and say plainly whether each one answers.

    A probe answers three different questions — disabled, misconfigured, or
    unreachable — because "no results" has to be distinguishable from "switched
    off" when someone is debugging why recall changed.
    """
    result: dict[str, Any] = {}
    for name, provider in (("elasticsearch", get_elasticsearch_provider()), ("milvus", get_milvus_provider())):
        enabled = bool(provider.enabled)
        available = False
        detail = "已关闭：由本地索引承接"
        if enabled:
            try:
                available = await provider.available()
                detail = "已启用且可用" if available else "已启用但不可用：本次检索将回退本地索引"
            except Exception as exc:  # availability checks must never break the console
                detail = f"探测失败：{exc}"
        result[name] = {
            "enabled": enabled,
            "available": available,
            "detail": detail,
            **(await provider.stats()),
        }
    return result


@router.get("/ai/settings/retrieval/env")
def retrieval_settings_env() -> dict[str, Any]:
    """Report where the configuration comes from, for troubleshooting."""
    settings = load_retrieval_settings()
    return {
        "settingsFile": str(SETTINGS_FILE),
        "persisted": SETTINGS_FILE.exists(),
        "mode": settings.mode,
        "externalRequired": settings.externalRequired,
        "elasticsearchEnabled": settings.elasticsearch.enabled,
        "milvusEnabled": settings.milvus.enabled,
    }
