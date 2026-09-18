"""Pydantic transport models shared by AI-service routes and internal orchestration."""

from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field

class QueryRequest(BaseModel):
    """Validated query payload accepted by the AI query endpoint."""
    queryId: Optional[str] = None
    knowledgeBaseId: int = Field(ge=1)
    query: str = Field(min_length=1, max_length=4000)
    topK: int = Field(default=10, ge=1, le=50)
    userId: Optional[str] = None
    permissionTags: Optional[List[str]] = None

class ExtractRequest(BaseModel):
    """Validated knowledge-extraction request payload."""
    knowledgeBaseId: int = Field(ge=1)
    sourceId: Optional[int] = None
    documentId: Optional[int] = None
    text: str = ""
    chunks: Optional[List[Dict[str, Any]]] = None
    # Normalized standard units; when supplied, extraction reads these instead of raw text.
    units: Optional[List[Dict[str, Any]]] = None

class IndexDocument(BaseModel):
    """Normalized retrieval document written to local/external indexes."""
    id: str
    knowledge_base_id: int
    document_id: Optional[int] = None
    content_unit_id: Optional[int] = None
    object_type: str = "CONTENT_UNIT"
    title: str = ""
    content: str
    source_name: str = ""
    source_uri: str = ""
    page_no: Optional[int] = None
    heading_path: str = ""
    permission_tags: List[str] = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)

class Candidate(BaseModel):
    """Normalized retrieval candidate returned by recall channels."""
    id: str
    channel: str
    title: str
    content: str
    score: float
    metadata: Dict[str, Any] = Field(default_factory=dict)
