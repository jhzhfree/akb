"""FastAPI application bootstrap, trace propagation, readiness, and lifecycle wiring."""

import logging
import os
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.core.config import settings
from app.core.runtime_settings import load_model_settings
from app.routes import router
from app.settings_routes import router as settings_router
from app.query_policy_routes import router as query_policy_router

logging.basicConfig(
    level=getattr(logging, os.getenv("LOG_LEVEL", "INFO").upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)


class TraceAdapter(logging.LoggerAdapter):
    """Logging adapter that injects request trace identifiers into log records."""

    def process(self, msg, kwargs):
        """Attach the current trace ID without overwriting explicit caller fields."""
        extra = kwargs.setdefault("extra", {})
        extra.setdefault("trace_id", self.extra.get("trace_id", "-"))
        return msg, kwargs


app = FastAPI(title="AI 原生企业知识库平台 - AI Service", version="1.1.0")
app.include_router(router)
app.include_router(settings_router)
app.include_router(query_policy_router)


@app.middleware("http")
async def request_context(request: Request, call_next):
    """Attach trace context to each request and emit end-to-end latency headers."""
    trace_id = request.headers.get("X-Trace-Id") or uuid.uuid4().hex
    started = time.time()
    request.state.trace_id = trace_id
    logger = TraceAdapter(
        logging.getLogger("llmkb.request"),
        {"trace_id": trace_id},
    )
    try:
        response = await call_next(request)
    except Exception:
        logger.exception(
            "trace_id=%s unhandled request error path=%s",
            trace_id,
            request.url.path,
        )
        return JSONResponse(
            status_code=500,
            content={
                "errorCode": "AI-500-001",
                "message": "internal server error",
                "traceId": trace_id,
            },
            headers={"X-Trace-Id": trace_id},
        )

    response.headers["X-Trace-Id"] = trace_id
    logger.info(
        "trace_id=%s method=%s path=%s status=%s latency_ms=%d",
        trace_id,
        request.method,
        request.url.path,
        response.status_code,
        int((time.time() - started) * 1000),
    )
    return response


@app.get("/health")
def health():
    """Return liveness state without checking optional external dependencies."""
    return {"status": "UP", "version": "1.1.0"}


@app.get("/ready")
def ready():
    """Return readiness state and critical provider diagnostics."""
    model_settings = load_model_settings()
    return {
        "status": "READY",
        "llmProvider": settings.llm_provider,
        "llmModel": model_settings.llm.model,
        "embeddingProvider": settings.embedding_provider,
        "graphProvider": settings.graph_provider,
        "rerankerConfigured": settings.reranker_enabled,
        "retrievalMode": settings.retrieval_mode,
        "elasticsearchConfigured": settings.elasticsearch_enabled,
        "milvusConfigured": settings.milvus_enabled,
    }
