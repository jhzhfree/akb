"""HTTP route layer for parsing, extraction, indexing, retrieval, adjudication, graph, and schema operations."""

import asyncio
import json
import logging
import shutil
import uuid
from typing import Any

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from app.core import chunk_policy
from app.core.config import UPLOAD_DIR, settings
from app.models.schemas import ExtractRequest, QueryRequest
from app.services.adjudication_engine import AdjudicationEngine
from app.services.conflict_detector import ConflictDetector
from app.services.document_parser import DocumentParser
from app.services.entity_resolver import get_entity_resolver
from app.services.extractor import KnowledgeExtractor
from app.services.graph_provider import get_graph_provider
from app.services.indexer import KnowledgeIndexer
from app.services.okf_bundle import OkfBundleError, OkfBundleInspector
from app.services.query_engine import QueryEngine
from app.services.reranker import get_reranker
from app.services.retrieval.external import get_elasticsearch_provider, get_milvus_provider
from app.storage.index_store import store

router = APIRouter()
parser = DocumentParser()
extractor = KnowledgeExtractor()
indexer = KnowledgeIndexer()
engine = QueryEngine()
conflict_detector = ConflictDetector()
adjudication_engine = AdjudicationEngine()
okf_inspector = OkfBundleInspector()
entity_resolver = get_entity_resolver()


@router.post("/ai/documents/parse")
async def parse_document(
    file: UploadFile = File(...),
    policy: str | None = Form(None),
    strategy: str | None = Form(None),
    chunk_size: int | None = Form(None),
    chunk_overlap: int | None = Form(None),
):
    """Parse an uploaded document into normalized text and chunks.

    The backend owns the chunking policy of each knowledge base and forwards it here,
    so a re-upload can be re-cut without restarting the AI service.
    """
    safe = (file.filename or "upload").replace("/", "_").replace("\\", "_")
    path = UPLOAD_DIR / (uuid.uuid4().hex + "_" + safe)
    with path.open("wb") as f:
        shutil.copyfileobj(file.file, f)
    payload: dict[str, Any] = chunk_policy.parse_json(policy) or {}
    if strategy:
        payload["strategy"] = strategy
    if chunk_size:
        payload["chunkSize"] = chunk_size
    if chunk_overlap:
        payload["chunkOverlap"] = chunk_overlap
    active = chunk_policy.normalize(payload) if payload else None
    try:
        return await asyncio.to_thread(parser.parse, str(path), safe, active)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e


@router.get("/ai/settings/chunk-policy")
def read_chunk_policy() -> dict[str, Any]:
    """Vocabulary, bounds and defaults of the chunking policy the parser accepts."""
    return chunk_policy.schema()


@router.post("/ai/okf/inspect")
async def inspect_okf_bundle(file: UploadFile = File(...)):
    """Safely validate and normalize an Open Knowledge Format bundle."""
    try:
        content = await file.read(OkfBundleInspector.MAX_ARCHIVE_BYTES + 1)
        return await asyncio.to_thread(okf_inspector.inspect, content, file.filename or "bundle.zip")
    except OkfBundleError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/ai/extract")
async def extract(r: ExtractRequest):
    """Extract structured knowledge candidates from normalized document evidence."""
    return await extractor.extract(r.text, r.chunks, r.units, r.knowledgeBaseId, r.scenario)


@router.post("/ai/standard/index")
async def index_standard_document(p: dict[str, Any]):
    """Store normalized standard-document units in Elasticsearch.

    This is the ingest-time contract: raw sources are normalized first, and the
    normalized units—not the raw file text—become the basis for card generation.
    """
    return await indexer.index_standard_document(
        int(p["knowledgeBaseId"]),
        int(p["documentId"]),
        p.get("units", []),
        source_name=p.get("sourceName", ""),
        source_uri=p.get("sourceUri", ""),
        permission_tags=p.get("permissionTags", []),
        index_version=int(p.get("indexVersion") or 0),
        standard=p.get("standard"),
        source_id=p.get("sourceId"),
    )


@router.get("/ai/standard/{kb}/{document_id}")
async def read_standard_document(kb: int, document_id: int):
    """Read the normalized units of one document back from Elasticsearch."""
    return await indexer.read_standard_units(kb, document_id)


@router.post("/ai/standard/search")
async def search_standard_document(p: dict[str, Any]):
    """Full-text search over normalized standard-document units."""
    return await indexer.search_standard_units(
        int(p["knowledgeBaseId"]),
        p.get("query", ""),
        int(p.get("topK") or 20),
        p.get("permissionTags", []),
        p.get("documentId"),
    )


@router.post("/ai/entity-resolve")
async def entity_resolve(p: dict[str, Any]):
    """Resolve extracted entity mentions onto canonical entities (design V1.1 §5.4)."""
    return await entity_resolver.resolve(
        p.get("entities", []),
        p.get("canonicalEntities", []),
    )


@router.post("/ai/index/chunks")
async def index_chunks(p: dict[str, Any]):
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
async def index_knowledge(p: dict[str, Any]):
    """Index knowledge cards/claims into configured retrieval views."""
    return await indexer.index_knowledge_objects(
        int(p["knowledgeBaseId"]),
        p.get("cards"),
        p.get("claims"),
        p.get("entities"),
        p.get("relations"),
        int(p.get("indexVersion") or 0),
    )


@router.post("/ai/index/object/acl")
async def update_index_object_acl(p: dict[str, Any]):
    """Propagate authoritative ACL changes; signal rebuild when a provider cannot safely patch."""
    return await indexer.update_object_acl(
        int(p["knowledgeBaseId"]),
        str(p.get("objectType") or "CARD"),
        str(p["objectId"]),
        p.get("permissionTags") or [],
        int(p.get("indexVersion") or 0),
        bool(p.get("sourceCascade")),
    )


@router.post("/ai/index/object/status")
async def update_index_object_status(p: dict[str, Any]):
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


@router.delete("/ai/index/knowledge-base/{knowledge_base_id}")
async def purge_knowledge_base(knowledge_base_id: int):
    """Delete one knowledge base from every retrieval view after a console purge."""
    return await indexer.purge_knowledge_base(knowledge_base_id)


@router.post("/ai/index/rebuild/{knowledge_base_id}")
async def rebuild_external_index(knowledge_base_id: int):
    """Rebuild the external retrieval views from provided documents."""
    return await indexer.rebuild_external(knowledge_base_id)


@router.post("/ai/index/schema/rebuild/{knowledge_base_id}")
async def rebuild_index_schema(knowledge_base_id: int, p: dict[str, Any]):
    """Reset one blue/green schema target and index the supplied batch."""
    version = str(p.get("schemaVersion") or "").strip()
    if not version:
        raise HTTPException(status_code=400, detail="schemaVersion is required")
    return await indexer.rebuild_schema(knowledge_base_id, version)


@router.post("/ai/index/schema/rebuild-batch/{knowledge_base_id}")
async def rebuild_index_schema_batch(knowledge_base_id: int, p: dict[str, Any]):
    """Append one authoritative snapshot batch to a blue/green target."""
    version = str(p.get("schemaVersion") or "").strip()
    if not version:
        raise HTTPException(status_code=400, detail="schemaVersion is required")
    return await indexer.rebuild_schema_batch(
        knowledge_base_id, version, p.get("documents") or [], bool(p.get("reset"))
    )


@router.post("/ai/index/schema/graph-rebuild/{knowledge_base_id}")
async def rebuild_authoritative_graph(knowledge_base_id: int, payload: dict[str, Any]):
    """Rebuild authoritative Entity/Relation graph for one green generation."""
    generation = str(payload.get("generationId") or "").strip()
    if not generation:
        raise HTTPException(status_code=400, detail="generationId is required")
    return await indexer.rebuild_authoritative_graph(
        knowledge_base_id, generation, payload.get("entitiesAndRelations") or [], payload.get("evidenceBindings") or []
    )


@router.post("/ai/index/schema/activate/{knowledge_base_id}")
async def activate_index_schema(knowledge_base_id: int, p: dict[str, Any]):
    """Move retrieval read aliases to a validated schema target."""
    version = str(p.get("schemaVersion") or "").strip()
    if not version:
        raise HTTPException(status_code=400, detail="schemaVersion is required")
    return await indexer.activate_schema(knowledge_base_id, version)


@router.post("/ai/index/schema/acl-qualify/{knowledge_base_id}")
async def qualify_index_schema_acl(knowledge_base_id: int, payload: dict[str, Any]):
    """Machine-verifiable ACL leakage qualification against one green schema."""
    version = str(payload.get("schemaVersion") or "").strip()
    if not version:
        raise HTTPException(status_code=400, detail="schemaVersion is required")
    return await indexer.qualify_schema_acl(
        knowledge_base_id,
        version,
        str(payload.get("expectedManifestHash") or ""),
        int(payload.get("expectedManifestCount") or 0),
        str(payload.get("expectedGraphManifestHash") or ""),
        int(payload.get("expectedGraphEntityCount") or 0),
        int(payload.get("expectedGraphRelationCount") or 0),
    )


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
    return await engine.query(r.queryId or "", r.knowledgeBaseId, r.query, r.topK, r.permissionTags,
                              generate=r.answerMode != "none",
                              answer_format=r.answerFormat, policy_override=r.policyOverride,
                              min_score=r.minScore, include_recall_content=r.includeRecallContent)


@router.post("/ai/query/stream")
async def query_stream(r: QueryRequest):
    """The same pipeline as ``/ai/query``, pushed as server-sent events as it runs.

    The engine is unchanged: it is handed a `progress` callback and awaits it after every stage
    and for every answer chunk, so the events below are the real pipeline being observed rather
    than a replay of a finished answer. One task fills a queue, the response drains it, and a
    sentinel marks the end — which also lets the task be cancelled if the client disconnects.
    """
    queue: asyncio.Queue = asyncio.Queue()

    async def progress(event):
        await queue.put(event)

    async def runner():
        try:
            result = await engine.query(
                r.queryId or "", r.knowledgeBaseId, r.query, r.topK, r.permissionTags,
                generate=r.answerMode != "none",
                answer_format=r.answerFormat, policy_override=r.policyOverride, progress=progress,
                min_score=r.minScore, include_recall_content=r.includeRecallContent,
            )
            await queue.put({"type": "result", "result": result})
        except Exception as exc:  # noqa: BLE001 - the failure has to reach the client as an event
            logging.getLogger(__name__).exception("query stream failed")
            await queue.put({"type": "error", "message": str(exc)})
        finally:
            await queue.put(None)

    task = asyncio.ensure_future(runner())

    async def event_gen():
        try:
            # A first frame is written immediately so a client (and any proxy in front of it)
            # knows the stream is open before the first stage has finished.
            yield "event: open\ndata: %s\n\n" % json.dumps({"knowledgeBaseId": r.knowledgeBaseId}, ensure_ascii=False)
            while True:
                event = await queue.get()
                if event is None:
                    break
                name = event.get("type", "message")
                if name == "stage":
                    payload = event.get("stage")
                elif name == "delta":
                    payload = event.get("text")
                else:
                    payload = {k: v for k, v in event.items() if k != "type"}
                yield "event: %s\ndata: %s\n\n" % (name, json.dumps(payload, ensure_ascii=False))
        finally:
            if not task.done():
                task.cancel()

    return StreamingResponse(
        event_gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/ai/retrieve")
async def retrieve(r: QueryRequest):
    """Run recall, fusion and reranking without generation: the grading path for evaluation runs."""
    return await engine.query(r.queryId or "", r.knowledgeBaseId, r.query, r.topK, r.permissionTags, generate=False,
                              policy_override=r.policyOverride, min_score=r.minScore,
                              include_recall_content=r.includeRecallContent)


@router.post("/ai/adjudicate")
async def adjudicate(p: dict[str, Any]):
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
async def detect_conflicts(p: dict[str, Any]):
    """Detect likely contradictions among focused claim candidates."""
    conflicts = await asyncio.to_thread(conflict_detector.detect, p.get("claims", []), p.get("focusCodes"))
    return {"conflicts": conflicts}


@router.get("/ai/graph/{knowledge_base_id}/{object_code}")
async def graph_neighborhood(
    knowledge_base_id: int, object_code: str, depth: int = 2, limit: int = 50, permissionTags: str = ""
):
    """Return a graph neighborhood for entity/claim exploration."""
    provider = get_graph_provider()
    return provider.neighborhood(
        knowledge_base_id, object_code, depth, limit, [x.strip() for x in permissionTags.split(",") if x.strip()]
    )


@router.get("/ai/capabilities")
async def capabilities():
    """Return AI-service feature/provider capabilities."""
    graph = get_graph_provider()
    reranker = get_reranker()
    return {
        "graph": {"provider": graph.__class__.__name__, "available": graph.available()},
        "reranker": {"provider": reranker.name, "configured": settings.reranker_enabled},
        "entityResolution": {
            "enabled": settings.entity_resolution_enabled,
            "embeddingAssisted": settings.entity_resolution_embedding_enabled,
            "similarityThreshold": settings.entity_resolution_similarity,
        },
        "queryPipeline": {
            "rewriteEnabled": settings.query_rewrite_enabled,
            "qualitySignalsEnabled": settings.fusion_quality_signals_enabled,
        },
        "retrieval": {
            "mode": settings.retrieval_mode,
            "elasticsearch": await get_elasticsearch_provider().stats(),
            "milvus": await get_milvus_provider().stats(),
        },
    }


@router.get("/ai/graph/{knowledge_base_id}/{entity_code}/multihop")
async def graph_multihop(
    knowledge_base_id: int,
    entity_code: str,
    depth: int = 3,
    limit: int = 20,
    tokenBudget: int = 2500,
    permissionTags: str = "",
):
    """Generation-safe multi-hop GraphRAG paths with ACL and evidence binding."""
    provider = get_graph_provider()
    if not hasattr(provider, "generation_multihop"):
        return {"provider": provider.__class__.__name__, "available": False, "paths": []}
    tags = [x.strip() for x in permissionTags.split(",") if x.strip()]
    return await asyncio.to_thread(
        provider.generation_multihop, knowledge_base_id, entity_code, depth, limit, tags, tokenBudget
    )


@router.get("/ai/graph/{knowledge_base_id}/{entity_code}/facet-multihop")
async def graph_facet_multihop(
    knowledge_base_id: int,
    entity_code: str,
    depth: int = 3,
    limit: int = 20,
    token_budget: int = 2500,
    permission_tags: str = "",
):
    """Evidence-scoped GraphRAG: expose only facets visible to the caller persona."""
    provider = get_graph_provider()
    if not hasattr(provider, "facet_multihop"):
        return {"provider": "none", "paths": [], "pathCount": 0, "available": False}
    tags = [x.strip() for x in permission_tags.split(",") if x.strip()]
    return await asyncio.to_thread(
        provider.facet_multihop, knowledge_base_id, entity_code, depth, limit, tags, token_budget
    )


@router.get("/ai/graph/{knowledge_base_id}/{entity_code}/citation-safe-multihop")
async def graph_citation_safe_multihop(
    knowledge_base_id: int,
    entity_code: str,
    depth: int = 3,
    limit: int = 20,
    token_budget: int = 2500,
    permission_tags: str = "",
):
    """Generation-pinned GraphRAG where every returned path has persona-visible evidence."""
    provider = get_graph_provider()
    if not hasattr(provider, "citation_safe_multihop"):
        return {"provider": "none", "paths": [], "citationPolicy": "UNAVAILABLE"}
    tags = [x.strip() for x in permission_tags.split(",") if x.strip()]
    return await asyncio.to_thread(
        provider.citation_safe_multihop, knowledge_base_id, entity_code, depth, limit, tags, token_budget
    )
