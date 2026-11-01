"""Embedding provider abstraction with deterministic fallback and optional external model provider."""

import asyncio
import hashlib
import logging
import re
import time
from functools import lru_cache
from urllib.parse import urlparse
from typing import Any

import httpx
import numpy as np

from app.core.contracts import UpstreamRateLimitedError, UpstreamServiceError
from app.core.runtime_settings import EmbeddingSettings, load_model_settings

logger = logging.getLogger("llmkb.embedding")

# 限流和 5xx 抖动能自愈，等一下再来一次通常就成了；4xx 是配置问题，重试多少次都一样。
_RETRYABLE_STATUS = frozenset({429, 500, 502, 503, 504})
_MAX_ATTEMPTS = 3


def _retry_delay(response: httpx.Response | None, attempt: int) -> float:
    """Respect Retry-After when the vendor sends one, otherwise back off exponentially."""
    if response is not None:
        header = response.headers.get("Retry-After")
        if header:
            try:
                return min(float(header), 30.0)
            except ValueError:
                pass
    return 0.5 * (2 ** (attempt - 1))


def _raise_upstream(response: httpx.Response, provider: str, model: str, endpoint: str = "") -> None:
    """Turn a vendor response into a typed error: 429 is recoverable, everything else is a fault."""
    status = response.status_code
    # 响应体只进日志，不外传：里面可能是供应商的原始报文。
    logger.error("embedding provider=%s model=%s status=%s body=%s", provider, model, status,
                 (response.text or "")[:300])
    if status == 429:
        raise UpstreamRateLimitedError(provider, model, _retry_delay(response, 1), endpoint)
    raise UpstreamServiceError(provider, model, status, "", endpoint)


async def _post_with_retry(client: httpx.AsyncClient, url: str, payload: dict, provider: str, model: str,
                           raise_on_failure: bool = True) -> httpx.Response:
    # 供应商地址一起带出去：只报 "openai" 看不出配的是哪家，排查时会先猜错方向。
    endpoint = urlparse(url).netloc
    """Post once, retrying rate limits and transient 5xx with backoff.

    ``raise_on_failure=False`` hands the last response back so callers with a legacy
    fallback path can still take it; otherwise a vendor failure becomes a typed error
    instead of an opaque 500 further up.
    """
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            response = await client.post(url, json=payload)
        except httpx.RequestError as exc:
            if attempt == _MAX_ATTEMPTS:
                raise UpstreamServiceError(provider, model, None, "请求未到达供应商：%s" % exc,
                                           endpoint) from exc
            logger.warning("embedding provider=%s unreachable attempt=%s error=%s", provider, attempt, exc)
        else:
            if response.status_code < 400:
                return response
            if response.status_code not in _RETRYABLE_STATUS or attempt == _MAX_ATTEMPTS:
                if not raise_on_failure:
                    return response
                _raise_upstream(response, provider, model, endpoint)
            logger.warning("embedding provider=%s status=%s attempt=%s retrying", provider,
                           response.status_code, attempt)
        await asyncio.sleep(_retry_delay(None, attempt))
    raise UpstreamServiceError(provider, model, None, "重试结束仍未拿到可用响应", urlparse(url).netloc)


class EmbeddingProvider:
    """Create dense embeddings using deterministic fallback or configured external model."""

    name = "base"

    async def embed(self, texts: list[str]) -> list[list[float]]:
        raise NotImplementedError

    async def test(self) -> dict[str, Any]:
        """Embed a short probe and report dimension plus latency."""
        started = time.perf_counter()
        vectors = await self.embed(["连通性测试"])
        vector = vectors[0] if vectors else []
        return {
            "ok": bool(vector),
            "provider": self.name,
            "model": getattr(self, "model", ""),
            "dimension": len(vector),
            "latencyMs": int((time.perf_counter() - started) * 1000),
        }


class HashEmbeddingProvider(EmbeddingProvider):
    """Zero-external-model fallback used by tests and lightweight deployments."""

    name = "hash"

    def __init__(self, config: EmbeddingSettings) -> None:
        self._dim = config.dim

    async def embed(self, texts):
        return [self._one(text) for text in texts]

    def _one(self, text):
        vector = np.zeros(self._dim, dtype=np.float32)
        tokens = re.findall(r"[A-Za-z0-9_.+-]+|[一-鿿]", (text or "").lower())
        for token in tokens:
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            index = int.from_bytes(digest[:4], "little") % self._dim
            vector[index] += 1.0 if digest[4] % 2 == 0 else -1.0
        norm = float(np.linalg.norm(vector))
        if norm > 0:
            vector /= norm
        return vector.tolist()


class OllamaEmbeddingProvider(EmbeddingProvider):
    """Ollama ``/api/embed`` endpoint, with a fallback for servers that only speak the old one."""

    name = "ollama"

    def __init__(self, config: EmbeddingSettings):
        self._config = config
        self.model = config.model
        self._base_url = config.base_url.rstrip("/")

    async def embed(self, texts):
        # One connection per batch, closed with it: a pool held for the life of the process
        # keeps a socket that dies unnoticed when the network blinks, and every later index
        # run then fails for a reason that never reaches the log.
        async with httpx.AsyncClient(timeout=self._config.timeout) as client:
            # 主端点失败不急着报错：老版本 Ollama 只认下面那个端点，要留机会走兼容分支。
            response = await _post_with_retry(
                client, self._base_url + "/api/embed",
                {"model": self._config.model, "input": texts}, self.name, self.model,
                raise_on_failure=False,
            )
            if response.status_code < 400:
                return response.json().get("embeddings", [])

            # Compatibility fallback for older Ollama versions.
            output = []
            for text in texts:
                response = await _post_with_retry(
                    client, self._base_url + "/api/embeddings",
                    {"model": self._config.model, "prompt": text}, self.name, self.model,
                )
                output.append(response.json()["embedding"])
        return output


class OpenAIEmbeddingProvider(EmbeddingProvider):
    """OpenAI-style ``/embeddings`` endpoint used by OpenAI, 智谱 embedding-3, ..."""

    name = "openai"

    def __init__(self, config: EmbeddingSettings) -> None:
        self._config = config
        self.model = config.model
        self._url = _embeddings_url(config.base_url)
        headers = {"Content-Type": "application/json"}
        if config.api_key:
            headers["Authorization"] = f"Bearer {config.api_key}"
        self._headers = headers

    async def embed(self, texts):
        async with httpx.AsyncClient(timeout=self._config.timeout, headers=self._headers) as client:
            response = await _post_with_retry(
                client, self._url, {"model": self._config.model, "input": texts}, self.name, self.model,
            )
        if response.status_code >= 400:
            _raise_upstream(response, self.name, self.model)
        rows = sorted(response.json().get("data") or [], key=lambda row: row.get("index", 0))
        vectors = [row.get("embedding") or [] for row in rows]
        self._assert_dimension(vectors)
        return vectors

    def _assert_dimension(self, vectors: list[list[float]]) -> None:
        """Fail loudly instead of letting mismatched shapes corrupt similarity scores."""
        if not vectors:
            return
        actual = len(vectors[0])
        expected = self._config.dim
        if actual != expected:
            raise RuntimeError(
                f"向量维度不匹配：模型返回 {actual} 维，当前配置为 {expected} 维，请调整「向量维度」并重建索引"
            )


def _embeddings_url(base_url: str) -> str:
    """Accept both ``https://api.x/v1`` and an already-complete embeddings URL."""
    base = (base_url or "").rstrip("/")
    if base.endswith("/embeddings"):
        return base
    return base + "/embeddings"


@lru_cache(maxsize=4)
def _build_embedding_provider(config_json: str) -> EmbeddingProvider:
    """Memoise one provider per configuration so console edits take effect at once."""
    config = EmbeddingSettings.model_validate_json(config_json)
    if not config.enabled or config.provider == "hash":
        return HashEmbeddingProvider(config)
    if config.provider == "openai":
        return OpenAIEmbeddingProvider(config)
    return OllamaEmbeddingProvider(config)


def get_embedding_provider() -> EmbeddingProvider:
    """Return the process-wide embedding provider for the current configuration."""
    return _build_embedding_provider(load_model_settings().embedding.model_dump_json())


def cosine(left, right):
    """Compute cosine similarity with zero-vector and mixed-dimension protection."""
    left_vector = np.asarray(left, dtype=np.float32)
    right_vector = np.asarray(right, dtype=np.float32)
    if left_vector.shape != right_vector.shape:
        # Stored vectors came from another embedding model; treat them as unrelated.
        return 0.0
    denominator = float(np.linalg.norm(left_vector) * np.linalg.norm(right_vector))
    if denominator == 0:
        return 0.0
    return float(np.dot(left_vector, right_vector) / denominator)
