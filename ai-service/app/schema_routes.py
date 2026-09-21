"""Console routes of the knowledge schema.

Publishing a schema is a platform decision; this endpoint is how the extraction side
hears about it. The vocabulary is persisted per knowledge base so a restart keeps it,
and read back by extraction on the next call.
"""

from typing import Any, Dict, Optional

from fastapi import APIRouter

from app.core.knowledge_schema import apply_schema, load_schema

router = APIRouter(tags=["schema"])


@router.post("/ai/schema/apply")
async def schema_apply(payload: Dict[str, Any]) -> Dict[str, Any]:
    """Apply the published vocabulary of one knowledge base to extraction."""
    schema = apply_schema(payload or {})
    return {
        "applied": True,
        "knowledgeBaseId": schema.get("knowledgeBaseId"),
        "version": schema.get("version"),
        "relationTypes": schema.get("relationTypes", []),
        "entityTypes": schema.get("entityTypes", []),
        "knowledgeTypes": schema.get("knowledgeTypes", []),
    }


@router.get("/ai/schema/{knowledge_base_id}")
async def schema_read(knowledge_base_id: int) -> Dict[str, Any]:
    """Read the vocabulary currently in force, for diagnostics and tests."""
    schema = load_schema(knowledge_base_id)
    if not schema:
        return {"applied": False, "knowledgeBaseId": knowledge_base_id}
    return {"applied": True, **schema}
