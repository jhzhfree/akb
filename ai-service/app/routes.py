"""HTTP route layer for parsing, extraction, indexing, retrieval, adjudication, graph, and schema operations."""

import asyncio
import shutil
import uuid
from typing import Dict, Any
from fastapi import APIRouter, UploadFile, File, HTTPException
from app.core.config import UPLOAD_DIR
from app.models.schemas import ExtractRequest, QueryRequest
from app.services.document_parser import DocumentParser
from app.services.extractor import KnowledgeExtractor
from app.services.indexer import KnowledgeIndexer
from app.services.query_engine import QueryEngine
from app.storage.index_store import store
from app.services.graph_provider import get_graph_provider
from app.services.conflict_detector import ConflictDetector
from app.services.reranker import get_reranker
from app.core.config import settings
from app.services.adjudication_engine import AdjudicationEngine
from app.services.retrieval.external import get_elasticsearch_provider, get_milvus_provider

router = APIRouter()
parser = DocumentParser()
extractor = KnowledgeExtractor()
indexer = KnowledgeIndexer()
engine = QueryEngine()
conflict_detector = ConflictDetector()
adjudication_engine = AdjudicationEngine()


@router.post("/ai/documents/parse")
async def parse_document(file: UploadFile = File(...)):
    """Parse an uploaded document into normalized text and chunks."""
    safe = (file.filename or "upload").replace("/", "_").replace("\\", "_")
    path = UPLOAD_DIR / (uuid.uuid4().hex + "_" + safe)
    with path.open("wb") as f:
        shutil.copyfileobj(file.file, f)
    try:
        return await asyncio.to_thread(parser.parse, str(path), safe)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/ai/extract")
async def extract(r: ExtractRequest):
    """Extract structured knowledge candidates from normalized document evidence."""
    return await extractor.extract(r.text, r.chunks)


@router.post("/ai/index/chunks")
async def index_chunks(p: Dict[str, Any]):
    """Index authoritative document chunks into configured retrieval views."""
    return await indexer.index_chunks(
        int(p["knowledgeBaseId"]),
        int(p["documentId"]),
        p.get("sourceName", ""),
        p.get("sourceUri", ""),
        p.get("chunks", []),
        p.get("permissionTags", []),
        int(p.get("indexVersion") or 0),
    )


@router.post("/ai/index/knowledge")
async def index_knowledge(p: Dict[str, Any]):
    """Index knowledge cards/claims into configured retrieval views."""
    return await indexer.index_knowledge_objects(
        int(p["knowledgeBaseId"]), p.get("cards"), p.get("claims"), p.get("entities"), p.get("relations"),
        int(p.get("indexVersion") or 0)
    )


@router.post("/ai/index/object/status")
async def update_index_object_status(p: Dict[str, Any]):
    """Apply a version-aware lifecycle-status update to retrieval views."""
    return await indexer.update_object_status(
        int(p["knowledgeBaseId"]),
        str(p.get("objectType") or "CARD"),
        str(p["objectId"]),
        str(p["status"]),
        int(p.get("indexVersion") or 0),
    )


@router.get("/ai/index/external/stats/{knowledge_base_id}")
async def external_index_stats(knowledge_base_id: int):
    """Return Elasticsearch and Milvus health/count diagnostics."""
    return await indexer.external_stats(knowledge_base_id)


@router.delete("/ai/index/document/{knowledge_base_id}/{document_id}")
async def delete_document_index(knowledge_base_id: int, document_id: int):
    """Delete all retrieval objects belonging to one document."""
    return await indexer.delete_document(knowledge_base_id, document_id)


@router.post("/ai/index/rebuild/{knowledge_base_id}")
async def rebuild_external_index(knowledge_base_id: int):
    """Rebuild the external retrieval views from provided documents."""
    return await indexer.rebuild_external(knowledge_base_id)



@router.post("/ai/index/schema/rebuild/{knowledge_base_id}")
async def rebuild_index_schema(knowledge_base_id:int, p:Dict[str,Any]):
    """Reset one blue/green schema target and index the supplied batch."""
    version=str(p.get("schemaVersion") or "").strip()
    if not version:
        raise HTTPException(status_code=400,detail="schemaVersion is required")
    return await indexer.rebuild_schema(knowledge_base_id,version)


@router.post("/ai/index/schema/rebuild-batch/{knowledge_base_id}")
async def rebuild_index_schema_batch(knowledge_base_id:int, p:Dict[str,Any]):
    """Append one authoritative snapshot batch to a blue/green target."""
    version=str(p.get("schemaVersion") or "").strip()
    if not version:
        raise HTTPException(status_code=400,detail="schemaVersion is required")
    return await indexer.rebuild_schema_batch(knowledge_base_id,version,p.get("documents") or [],bool(p.get("reset")))

@router.post("/ai/index/schema/activate/{knowledge_base_id}")
async def activate_index_schema(knowledge_base_id:int, p:Dict[str,Any]):
    """Move retrieval read aliases to a validated schema target."""
    version=str(p.get("schemaVersion") or "").strip()
    if not version:
        raise HTTPException(status_code=400,detail="schemaVersion is required")
    return await indexer.activate_schema(knowledge_base_id,version)

@router.get("/ai/retrieval/capabilities")
async def retrieval_capabilities():
    """Describe configured retrieval providers and fallback mode."""
    return {
        "mode": settings.retrieval_mode,
        "externalRequired": settings.retrieval_external_required,
        "elasticsearch": await get_elasticsearch_provider().stats(),
        "milvus": await get_milvus_provider().stats(),
    }


@router.post("/ai/query")
async def query(r: QueryRequest):
    """Execute query routing, hybrid retrieval, reranking, context assembly, and answer generation."""
    return await engine.query(
        r.queryId or "", r.knowledgeBaseId, r.query, r.topK, r.permissionTags
    )


@router.post("/ai/adjudicate")
async def adjudicate(p: Dict[str, Any]):
    """Score one knowledge candidate for automatic or human-assisted adjudication."""
    return await adjudication_engine.judge(p)


@router.get("/ai/index/stats/{knowledge_base_id}")
async def stats(knowledge_base_id: int):
    """Return local retrieval-store statistics."""
    docs = store.load(knowledge_base_id)
    by_type = {}
    for d in docs:
        typ = d.get("object_type", "UNKNOWN")
        by_type[typ] = by_type.get(typ, 0) + 1
    return {"knowledgeBaseId": knowledge_base_id, "total": len(docs), "byType": by_type}


@router.post("/ai/conflicts/detect")
async def detect_conflicts(p: Dict[str, Any]):
    """Detect likely contradictions among focused claim candidates."""
    conflicts = await asyncio.to_thread(
        conflict_detector.detect, p.get("claims", []), p.get("focusCodes")
    )
    return {"conflicts": conflicts}


@router.get("/ai/graph/{knowledge_base_id}/{object_code}")
async def graph_neighborhood(knowledge_base_id: int, object_code: str, depth: int = 2, limit: int = 50):
    """Return a graph neighborhood for entity/claim exploration."""
    provider = get_graph_provider()
    return provider.neighborhood(knowledge_base_id, object_code, depth, limit)


@router.get("/ai/capabilities")
async def capabilities():
    """Return AI-service feature/provider capabilities."""
    graph = get_graph_provider()
    reranker = get_reranker()
    return {
        "graph": {"provider": graph.__class__.__name__, "available": graph.available()},
        "reranker": {"provider": reranker.name, "configured": settings.reranker_enabled},
        "retrieval": {
            "mode": settings.retrieval_mode,
            "elasticsearch": await get_elasticsearch_provider().stats(),
            "milvus": await get_milvus_provider().stats(),
        },
    }
