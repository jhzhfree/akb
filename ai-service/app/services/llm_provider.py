"""LLM provider abstraction with mock and Ollama-compatible implementations."""

import json
import re
from functools import lru_cache
from typing import Any, Dict, Optional

import httpx

from app.core.config import settings


class LlmProvider:
    """Abstract text-generation provider contract."""

    name = "base"

    async def generate(self, prompt: str) -> str:
        """Generate text for a fully assembled prompt."""
        raise NotImplementedError

    async def extract_json(self, prompt: str) -> Optional[Dict[str, Any]]:
        """Generate text and extract the first JSON object from the response."""
        text = await self.generate(prompt)
        match = re.search(r"\{.*\}", text, re.S)
        return json.loads(match.group(0)) if match else None


class MockProvider(LlmProvider):
    """Deterministic LLM fallback used in tests and offline development."""

    name = "mock"

    async def generate(self, prompt: str) -> str:
        """Return a stable non-generative answer for environments without an LLM."""
        return (
            "现有知识上下文已检索完成。当前未配置真实大模型，"
            "请设置 LLM_PROVIDER=ollama 后启用生成式回答。"
        )


class OllamaProvider(LlmProvider):
    """Ollama-compatible HTTP LLM provider."""

    name = "ollama"

    def __init__(self) -> None:
        self.client = httpx.AsyncClient(timeout=180)

    async def generate(self, prompt: str) -> str:
        """Call Ollama's generate endpoint with a low-temperature configuration."""
        response = await self.client.post(
            settings.llm_base_url.rstrip("/") + "/api/generate",
            json={
                "model": settings.llm_model,
                "prompt": prompt,
                "stream": False,
                "options": {"temperature": 0.1},
            },
        )
        response.raise_for_status()
        return response.json().get("response", "")


@lru_cache(maxsize=1)
def get_llm_provider() -> LlmProvider:
    """Return the process-wide configured LLM provider."""
    if settings.llm_provider.lower() == "ollama":
        return OllamaProvider()
    return MockProvider()
