"""Console routes of the knowledge-consumption (Q&A) query policy.

The operations console edits one policy object and every node of the consumer pipeline
(routing, rewrite, recall, fusion, rerank, answer gate) reads from it on the next query.
"""

from typing import Any, Dict

from fastapi import APIRouter, HTTPException
from pydantic import ValidationError

from app.core.query_policy import default_policy, load_policy, policy_schema, public_policy, reset_policy, save_policy

router = APIRouter()


@router.get("/ai/settings/query-policy")
def read_query_policy() -> Dict[str, Any]:
    """Return the effective query policy plus its persistence metadata."""
    return public_policy()


@router.post("/ai/settings/query-policy")
def update_query_policy(payload: Dict[str, Any]) -> Dict[str, Any]:
    """Merge a console update into the stored policy and return the effective result."""
    try:
        policy = save_policy(payload)
    except ValidationError as exc:
        raise HTTPException(status_code=400, detail="查询策略配置无效：%s" % exc.errors())
    return public_policy(policy)


@router.post("/ai/settings/query-policy/reset")
def reset_query_policy() -> Dict[str, Any]:
    """Restore the built-in defaults and discard console customizations."""
    return public_policy(reset_policy())


@router.get("/ai/settings/query-policy/schema")
def read_query_policy_schema() -> Dict[str, Any]:
    """Return the vocabulary the console editor renders from (channels, defaults)."""
    return policy_schema()


@router.get("/ai/settings/query-policy/defaults")
def read_query_policy_defaults() -> Dict[str, Any]:
    """Return the environment-seeded defaults without touching the stored policy."""
    return {"policy": default_policy().model_dump()}
