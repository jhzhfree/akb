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
