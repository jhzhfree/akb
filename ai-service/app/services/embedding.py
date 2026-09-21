"""Embedding provider abstraction with deterministic fallback and optional external model provider."""

import hashlib
import re
import time
from functools import lru_cache
from typing import Any

import httpx
import numpy as np

from app.core.runtime_settings import EmbeddingSettings, load_model_settings


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
    """Reusable async client instead of creating a new TCP pool per query."""

    name = "ollama"

    def __init__(self, config: EmbeddingSettings):
        self._config = config
        self.model = config.model
        self._base_url = config.base_url.rstrip("/")
        self._client = httpx.AsyncClient(timeout=config.timeout)

    async def embed(self, texts):
        response = await self._client.post(
            self._base_url + "/api/embed",
            json={"model": self._config.model, "input": texts},
        )
        if response.status_code < 400:
            return response.json().get("embeddings", [])

        # Compatibility fallback for older Ollama versions.
        output = []
        for text in texts:
            response = await self._client.post(
                self._base_url + "/api/embeddings",
                json={"model": self._config.model, "prompt": text},
            )
            response.raise_for_status()
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
        self._client = httpx.AsyncClient(timeout=config.timeout, headers=headers)

    async def embed(self, texts):
        response = await self._client.post(
            self._url,
            json={"model": self._config.model, "input": texts},
        )
        response.raise_for_status()
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
