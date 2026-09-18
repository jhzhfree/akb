"""Shared candidate conversion for external retrieval providers."""
from typing import Any, Dict

SEARCH_METADATA_FIELDS = [
    "object_type", "document_id", "content_unit_id", "source_name", "source_uri",
    "page_no", "heading_path", "status", "permission_tags", "generation_id",
    "evidence_code", "evidence_hash", "source_version", "content_hash",
]

def candidate(source: Dict[str, Any], channel: str, score: float, provider: str) -> Dict[str, Any]:
    """Convert a provider hit into the QueryEngine candidate contract."""
    metadata = {key: source.get(key) for key in SEARCH_METADATA_FIELDS}
    metadata["retrieval_provider"] = provider
    return {
        "id": str(source.get("object_id")), "channel": channel,
        "title": source.get("title", ""), "content": source.get("content", ""),
        "score": float(score or 0.0), "metadata": metadata,
    }
