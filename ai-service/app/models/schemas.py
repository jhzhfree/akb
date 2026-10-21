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
    # 最低相关度分数：低于该分数的候选不进入返回。不给阈值即不过滤，行为与以前一致。
    # 检索接口（knowledge_search / /v1/external/search）靠它把“召回了多少”和
    # “其中有多少真的够格”分开——数量由 topK 决定，质量由它决定。
    minScore: float | None = Field(default=None, ge=0.0, le=1.0)
    # 召回原文按需返回。recall 每条都带几百字正文，20 条就是上万字，占响应体积的绝大部分，
    # 而所有已知消费方（检索测试页、溯源面板、对外检索接口）都只用它的元数据。
    # 默认只给元数据 + 原文长度；确实要看原文（排查召回质量）时显式打开。
    includeRecallContent: bool = False
    # 是否生成答案。full（默认）：走完整链路，产出 answer 与 citations；
    # none：只做召回，跳过 LLM 与答案门控（answer 为空串、门控标为 NOT_RUN），响应体积和
    # 延迟都只取决于检索本身。给第三方检索协议（NovaWork standard）这类"自己拿片段生成答案"
    # 的调用方用——它们有超时预算，不该为用不上的答案付一次模型调用。
    # 未知取值退回 full：与 answerFormat 同款容错，一个拼错的取值不该变成 422。
    answerMode: str = "full"


class ExtractRequest(BaseModel):
    """Validated knowledge-extraction request payload."""

    knowledgeBaseId: int = Field(ge=1)
    sourceId: int | None = None
    documentId: int | None = None
    text: str = ""
    chunks: list[dict[str, Any]] | None = None
    # Normalized standard units; when supplied, extraction reads these instead of raw text.
    units: list[dict[str, Any]] | None = None
    # Scenario template of this library (GENERAL / OPS / POLICY / BUSINESS / VULN), sent by
    # the console so generation follows the same template as chunking and retrieval instead
    # of every library being extracted by one generic instruction.
    scenario: str = "GENERAL"


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
