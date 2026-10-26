"""Cross-service constants for AI error codes and governed knowledge states."""

ERROR_INVALID_REQUEST = "AI-400-001"
ERROR_UNSUPPORTED_DOCUMENT = "AI-400-002"
ERROR_INTERNAL = "AI-500-001"
# 检索的一致性屏障：ES / Milvus / 图库的结果必须同属一个索引世代，混了就拒绝返回。
ERROR_GENERATION_MISMATCH = "AI-409-001"


class GenerationMismatchError(RuntimeError):
    """Raised when retrieval results span more than one index generation.

    Carries the generation ids so the caller can name the engines that disagree
    instead of reporting a bare internal error. The fix is to rebuild the index,
    and whoever runs into this has to be told that without reading the logs.
    """

    def __init__(self, generations: list[str]) -> None:
        super().__init__("CROSS_ENGINE_GENERATION_MISMATCH")
        self.generations = sorted(generations)

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
    selected_policy = (policy or "standard").strip().lower()
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
    """Allow statusless legacy objects only when permissive mode is explicitly selected."""
    from app.core.config import settings

    return (settings.retrieval_status_policy or "standard").strip().lower() == "permissive"
