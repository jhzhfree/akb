"""FastAPI application bootstrap, trace propagation, readiness, and lifecycle wiring."""

import logging
import os
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.core.config import settings
from app.core.contracts import (
    ERROR_GENERATION_MISMATCH,
    ERROR_UPSTREAM_RATE_LIMITED,
    ERROR_UPSTREAM_UNAVAILABLE,
    GenerationMismatchError,
    UpstreamRateLimitedError,
    UpstreamServiceError,
)
from app.core.retrieval_settings import apply_retrieval_settings
from app.core.runtime_settings import load_model_settings
from app.derivation_routes import router as derivation_router
from app.query_policy_routes import router as query_policy_router
from app.retrieval_settings_routes import router as retrieval_settings_router
from app.routes import router
from app.schema_routes import router as schema_router
from app.settings_routes import router as settings_router

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


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Apply the persisted retrieval switches before the first request.

    Environment variables only seed the console; once the console has been used
    its file is authoritative, so a restart must not silently re-enable an engine
    that an operator turned off.
    """
    apply_retrieval_settings()
    yield


app = FastAPI(title="AI 原生企业知识库平台 - AI Service", version="1.1.0", lifespan=lifespan)
app.include_router(router)
app.include_router(settings_router)
app.include_router(retrieval_settings_router)
app.include_router(query_policy_router)
app.include_router(derivation_router)
app.include_router(schema_router)


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
    except GenerationMismatchError as exc:
        # 世代不一致不是内部故障，是状态问题，重建索引就能修。报 500 只能让人去翻日志，
        # 所以单独回应，并把各引擎的世代号带出去——一眼能看出是哪一路没跟上。
        logger.warning(
            "trace_id=%s index generation mismatch path=%s generations=%s",
            trace_id,
            request.url.path,
            exc.generations,
        )
        return JSONResponse(
            status_code=409,
            content={
                "errorCode": ERROR_GENERATION_MISMATCH,
                "message": "检索索引世代不一致，请重建该知识库的索引",
                "generationIds": exc.generations,
                "traceId": trace_id,
            },
            headers={"X-Trace-Id": trace_id},
        )
    except UpstreamRateLimitedError as exc:
        # 配额或速率打满是外部状态，重试或加配额就能恢复。报 500 会把"去查配额"这条
        # 唯一有用的信息埋掉，调用方只能来翻日志，所以单独回应并把重试间隔带出去。
        logger.warning(
            "trace_id=%s upstream rate limited path=%s provider=%s model=%s retry_after=%s",
            trace_id, request.url.path, exc.provider, exc.model, exc.retry_after,
        )
        content = {
            "errorCode": ERROR_UPSTREAM_RATE_LIMITED,
            "message": "模型供应商限流（%s 返回 429），请稍后重试或检查配额"
                       % (exc.endpoint or exc.provider),
            "provider": exc.provider,
            "traceId": trace_id,
        }
        headers = {"X-Trace-Id": trace_id}
        if exc.retry_after:
            content["retryAfterSeconds"] = exc.retry_after
            headers["Retry-After"] = str(int(exc.retry_after) + 1)
        return JSONResponse(status_code=429, content=content, headers=headers)
    except UpstreamServiceError as exc:
        logger.error(
            "trace_id=%s upstream unavailable path=%s provider=%s model=%s status=%s detail=%s",
            trace_id, request.url.path, exc.provider, exc.model, exc.status, exc.detail,
        )
        return JSONResponse(
            status_code=502,
            content={
                "errorCode": ERROR_UPSTREAM_UNAVAILABLE,
                "message": "模型供应商不可用（%s %s），请稍后重试或检查供应商配置"
                           % (exc.endpoint or exc.provider, exc.status or "网络不可达"),
                "provider": exc.provider,
                "traceId": trace_id,
            },
            headers={"X-Trace-Id": trace_id},
        )
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
