"""Embedding provider abstraction with deterministic fallback and optional external model provider."""

import hashlib
import re
from functools import lru_cache
from typing import List

import httpx
import numpy as np

from app.core.config import settings


class EmbeddingProvider:
    """Create dense embeddings using deterministic fallback or configured external model."""
    async def embed(self, texts: List[str]) -> List[List[float]]:
        raise NotImplementedError


class HashEmbeddingProvider(EmbeddingProvider):
    """Zero-external-model fallback used by tests and lightweight deployments."""

    async def embed(self, texts):
        return [self._one(text) for text in texts]

    def _one(self, text):
        vector = np.zeros(settings.embedding_dim, dtype=np.float32)
        tokens = re.findall(r"[A-Za-z0-9_.+-]+|[一-鿿]", (text or "").lower())
        for token in tokens:
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            index = int.from_bytes(digest[:4], "little") % settings.embedding_dim
            vector[index] += 1.0 if digest[4] % 2 == 0 else -1.0
        norm = float(np.linalg.norm(vector))
        if norm > 0:
            vector /= norm
        return vector.tolist()


class OllamaEmbeddingProvider(EmbeddingProvider):
    """Reusable async client instead of creating a new TCP pool per query."""

    def __init__(self):
        self._base_url = settings.llm_base_url.rstrip("/")
        self._client = httpx.AsyncClient(timeout=120)

    async def embed(self, texts):
        response = await self._client.post(
            self._base_url + "/api/embed",
            json={"model": settings.embedding_model, "input": texts},
        )
        if response.status_code < 400:
            return response.json().get("embeddings", [])

        # Compatibility fallback for older Ollama versions.
        output = []
        for text in texts:
            response = await self._client.post(
                self._base_url + "/api/embeddings",
                json={"model": settings.embedding_model, "prompt": text},
            )
            response.raise_for_status()
            output.append(response.json()["embedding"])
        return output


@lru_cache(maxsize=1)
def get_embedding_provider():
    """Return the process-wide embedding provider."""
    if settings.embedding_provider.lower() == "ollama":
        return OllamaEmbeddingProvider()
    return HashEmbeddingProvider()


def cosine(left, right):
    """Compute cosine similarity with zero-vector protection."""
    left_vector = np.asarray(left, dtype=np.float32)
    right_vector = np.asarray(right, dtype=np.float32)
    denominator = float(np.linalg.norm(left_vector) * np.linalg.norm(right_vector))
    if denominator == 0:
        return 0.0
    return float(np.dot(left_vector, right_vector) / denominator)
