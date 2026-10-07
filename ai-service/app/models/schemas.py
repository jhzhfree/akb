"""Pydantic transport models shared by AI-service routes and internal orchestration."""

from typing import Any

from pydantic import BaseModel, Field


class QueryRequest(BaseModel):
    """Validated query payload accepted by the AI query endpoint."""

    queryId: str | None = None
    knowledgeBaseId: int = Field(ge=1)
    query: str = Field(min_length=1, max_length=4000)
    topK: int = Field(default=10, ge=1, le=50)
    userId: str | None = None
    permissionTags: list[str] | None = None
    # 交付格式：default（模型原文）/ markdown（附引用原文章节）/ text（纯文本、不含证据）/ html（含引用原文）。
    # 未知取值退回 default：一个老调用方或一次拼写错误不该把能用的问题变成 422。
    answerFormat: str = "default"
    # 知识库自带的策略覆盖：后端按知识库场景模板下发，合并到部署级策略之上。
    # 只有白名单内的字段生效（见 app.core.query_policy.merge_override），所以一个知识库
    # 无法改写与它无关的全局行为，也无法用一个坏值把整次查询变成 500。
    policyOverride: dict[str, Any] | None = None


class ExtractRequest(BaseModel):
    """Validated knowledge-extraction request payload."""

    knowledgeBaseId: int = Field(ge=1)
    sourceId: int | None = None
    documentId: int | None = None
    text: str = ""
    chunks: list[dict[str, Any]] | None = None
    # Normalized standard units; when supplied, extraction reads these instead of raw text.
    units: list[dict[str, Any]] | None = None


class IndexDocument(BaseModel):
    """Normalized retrieval document written to local/external indexes."""

    id: str
    knowledge_base_id: int
    document_id: int | None = None
    content_unit_id: int | None = None
    object_type: str = "CONTENT_UNIT"
    title: str = ""
    content: str
    source_name: str = ""
    source_uri: str = ""
    page_no: int | None = None
    heading_path: str = ""
    permission_tags: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class Candidate(BaseModel):
    """Normalized retrieval candidate returned by recall channels."""

    id: str
    channel: str
    title: str
    content: str
    score: float
    metadata: dict[str, Any] = Field(default_factory=dict)
