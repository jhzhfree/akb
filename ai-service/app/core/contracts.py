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

# States admitted into recall. DRAFT and UNCERTAIN are the states a freshly generated
# object actually carries: a card is created as DRAFT and a claim whose statement could not
# be tied to a chunk deterministically is UNCERTAIN. Excluding them leaves nothing to recall
# until a human reviews every object — a library looks empty right after ingestion.
# Rejected/superseded/expired/invalid stay out: they are the deliberate exclusions.
RETRIEVABLE_STATUSES = {"DRAFT", "UNCERTAIN", "APPROVED", "EFFECTIVE"}
