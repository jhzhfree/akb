"""Cross-service constants for AI error codes and governed knowledge states."""

ERROR_INVALID_REQUEST = "AI-400-001"
ERROR_UNSUPPORTED_DOCUMENT = "AI-400-002"
ERROR_INTERNAL = "AI-500-001"
# 检索的一致性屏障：ES / Milvus / 图库的结果必须同属一个索引世代，混了就拒绝返回。
ERROR_GENERATION_MISMATCH = "AI-409-001"
# 外部模型供应商的限流与故障：这不是本服务的内部错误，报 500 只会让人去翻日志。
ERROR_UPSTREAM_RATE_LIMITED = "AI-429-001"
ERROR_UPSTREAM_UNAVAILABLE = "AI-502-001"


class UpstreamRateLimitedError(RuntimeError):
    """模型供应商返回限流（429）。

    配额或速率打满是可恢复的外部状态，调用方该知道"稍后再试"或者"配额不够了"，
    而不是收到一个 internal server error。
    """

    def __init__(self, provider: str, model: str = "", retry_after: float | None = None,
                 endpoint: str = "") -> None:
        super().__init__("UPSTREAM_RATE_LIMITED")
        self.provider = provider
        self.model = model
        self.retry_after = retry_after
        self.endpoint = endpoint


class UpstreamServiceError(RuntimeError):
    """模型供应商不可用：5xx、连不上，或者 4xx 配置类错误。

    status 为 None 表示请求根本没到供应商（超时 / 网络错误）。
    """

    def __init__(self, provider: str, model: str = "", status: int | None = None, detail: str = "",
                 endpoint: str = "") -> None:
        super().__init__("UPSTREAM_SERVICE_ERROR")
        self.provider = provider
        self.model = model
        self.status = status
        self.detail = detail
        self.endpoint = endpoint


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
