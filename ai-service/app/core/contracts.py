"""Cross-service constants for AI error codes and governed knowledge states."""

ERROR_INVALID_REQUEST = "AI-400-001"
ERROR_UNSUPPORTED_DOCUMENT = "AI-400-002"
ERROR_INTERNAL = "AI-500-001"

KNOWLEDGE_STATUSES = {
    "DRAFT",
    "UNCERTAIN",
    "PENDING_REVIEW",
    "APPROVED",
    "EFFECTIVE",
    "CONFLICTED",
    "REJECTED",
    "SUPERSEDED",
    "EXPIRED",
    "INVALID",
}

DEFAULT_RETRIEVABLE_STATUSES = {"DRAFT", "UNCERTAIN", "APPROVED", "EFFECTIVE"}
STANDARD_RETRIEVABLE_STATUSES = {"APPROVED", "EFFECTIVE"}


def resolve_retrievable_statuses(policy: str, allowed_statuses: str = "") -> set[str]:
    """Resolve the configured recall policy to a validated knowledge-state whitelist."""
    selected_policy = (policy or "permissive").strip().lower()
    if selected_policy == "permissive":
        return set(DEFAULT_RETRIEVABLE_STATUSES)
    if selected_policy == "standard":
        return set(STANDARD_RETRIEVABLE_STATUSES)
    if selected_policy == "custom":
        statuses = {item.strip().upper() for item in allowed_statuses.split(",") if item.strip()}
        if not statuses:
            raise ValueError("RETRIEVAL_ALLOWED_STATUSES is required when policy is custom")
        unknown = statuses - KNOWLEDGE_STATUSES
        if unknown:
            raise ValueError("Unknown retrieval statuses: %s" % ", ".join(sorted(unknown)))
        return statuses
    raise ValueError("RETRIEVAL_STATUS_POLICY must be permissive, standard, or custom")


def retrievable_statuses() -> set[str]:
    """Read the whitelist from runtime settings so all retrieval channels stay aligned."""
    from app.core.config import settings

    return resolve_retrievable_statuses(
        settings.retrieval_status_policy,
        settings.retrieval_allowed_statuses,
    )


def statusless_objects_retrievable() -> bool:
    """Keep legacy statusless records only under the backward-compatible default."""
    from app.core.config import settings

    return (settings.retrieval_status_policy or "permissive").strip().lower() == "permissive"
