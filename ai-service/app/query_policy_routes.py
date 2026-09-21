"""Console routes of the knowledge-consumption (Q&A) query policy.

The operations console edits one policy object and every node of the consumer pipeline
(routing, rewrite, recall, fusion, rerank, answer gate) reads from it on the next query.

Several named strategies can coexist: an operator clones the running one, tunes the
clone, and switches the whole console over only when the benchmark agrees. That is what
makes a retrieval change reversible instead of a one-way edit of the live policy.
"""

from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, ValidationError

from app.core.query_policy import (
    activate_profile,
    create_profile,
    default_policy,
    delete_profile,
    list_profiles,
    policy_schema,
    public_policy,
    rename_profile,
    reset_policy,
    save_policy,
    update_profile,
)

router = APIRouter()


class ProfileCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=40)
    label: str | None = None
    base: str | None = None
    policy: dict[str, Any] | None = None


class ProfilePatch(BaseModel):
    policy: dict[str, Any] | None = None


class ProfileRename(BaseModel):
    label: str | None = None
    newName: str | None = None


@router.get("/ai/settings/query-policy")
def read_query_policy() -> dict[str, Any]:
    """Return the effective query policy plus its persistence metadata."""
    return public_policy()


@router.post("/ai/settings/query-policy")
def update_query_policy(payload: dict[str, Any]) -> dict[str, Any]:
    """Merge a console update into the stored policy and return the effective result."""
    try:
        policy = save_policy(payload)
    except ValidationError as exc:
        raise HTTPException(status_code=400, detail="查询策略配置无效：%s" % exc.errors()) from exc
    return public_policy(policy)


@router.post("/ai/settings/query-policy/reset")
def reset_query_policy() -> dict[str, Any]:
    """Restore the built-in defaults and discard console customizations."""
    return public_policy(reset_policy())


@router.get("/ai/settings/query-policy/schema")
def read_query_policy_schema() -> dict[str, Any]:
    """Return the vocabulary the console editor renders from (channels, defaults)."""
    return policy_schema()


@router.get("/ai/settings/query-policy/defaults")
def read_query_policy_defaults() -> dict[str, Any]:
    """Return the environment-seeded defaults without touching the stored policy."""
    return {"policy": default_policy().model_dump()}


@router.get("/ai/settings/query-policy/profiles")
def read_query_policy_profiles() -> dict[str, Any]:
    """List every saved strategy and which one is live."""
    return list_profiles()


@router.post("/ai/settings/query-policy/profiles")
def add_query_policy_profile(body: ProfileCreate) -> dict[str, Any]:
    """Create a strategy by cloning the active one, the defaults, or a named one."""
    try:
        return create_profile(body.name, body.label, body.base, body.policy)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ValidationError as exc:
        raise HTTPException(status_code=400, detail="查询策略配置无效：%s" % exc.errors()) from exc


@router.put("/ai/settings/query-policy/profiles/{name}")
def patch_query_policy_profile(name: str, body: ProfilePatch) -> dict[str, Any]:
    """Merge a console patch into one saved strategy."""
    try:
        return update_profile(name, body.policy or {})
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ValidationError as exc:
        raise HTTPException(status_code=400, detail="查询策略配置无效：%s" % exc.errors()) from exc


@router.post("/ai/settings/query-policy/profiles/{name}/rename")
def rename_query_policy_profile(name: str, body: ProfileRename) -> dict[str, Any]:
    """Rename a strategy or change its display label."""
    try:
        return rename_profile(name, body.label, body.newName)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/ai/settings/query-policy/profiles/{name}/activate")
def activate_query_policy_profile(name: str) -> dict[str, Any]:
    """Switch every following query to another saved strategy."""
    try:
        return activate_profile(name)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.delete("/ai/settings/query-policy/profiles/{name}")
def delete_query_policy_profile(name: str) -> dict[str, Any]:
    """Delete a saved strategy; the live one and the last survivor are protected."""
    try:
        return delete_profile(name)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
