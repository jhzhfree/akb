"""LLM provider abstraction with mock, Ollama, and OpenAI-compatible implementations."""

import json
import re
import time
from functools import lru_cache
from typing import Any, Dict, Optional

import httpx

from app.core.runtime_settings import LlmSettings, load_model_settings

# Guard against providers that reject very large ``max_tokens`` values outright.
_MAX_OUTPUT_TOKENS = 8192


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

    async def test(self) -> Dict[str, Any]:
        """Probe connectivity with a tiny request and report latency."""
        started = time.perf_counter()
        sample = await self.generate("请只回复两个字：可用")
        return {
            "ok": True,
            "provider": self.name,
            "model": getattr(self, "model", ""),
            "latencyMs": int((time.perf_counter() - started) * 1000),
            "sample": (sample or "").strip()[:120],
        }


class MockProvider(LlmProvider):
    """Deterministic LLM fallback used in tests and offline development."""

    name = "mock"

    async def generate(self, prompt: str) -> str:
        """Return a stable non-generative answer for environments without an LLM."""
        return (
            "现有知识上下文已检索完成。当前未配置真实大模型，"
            "请在「模型设置」中配置 LLM 后启用生成式回答。"
        )


class OllamaProvider(LlmProvider):
    """Ollama-compatible HTTP LLM provider."""

    name = "ollama"

    def __init__(self, config: LlmSettings) -> None:
        self._config = config
        self._client = httpx.AsyncClient(timeout=config.timeout)

    async def generate(self, prompt: str) -> str:
        """Call Ollama's generate endpoint with the configured sampling temperature."""
        response = await self._client.post(
            self._config.base_url.rstrip("/") + "/api/generate",
            json={
                "model": self._config.model,
                "prompt": prompt,
                "stream": False,
                "options": {"temperature": self._config.temperature},
            },
        )
        response.raise_for_status()
        return response.json().get("response", "")


class OpenAICompatibleProvider(LlmProvider):
    """OpenAI-style ``/chat/completions`` endpoint used by DeepSeek, 智谱, Kimi, OpenAI ..."""

    name = "openai"

    def __init__(self, config: LlmSettings) -> None:
        self._config = config
        self.model = config.model
        self._url = _completion_url(config.base_url)
        headers = {"Content-Type": "application/json"}
        if config.api_key:
            headers["Authorization"] = f"Bearer {config.api_key}"
        self._client = httpx.AsyncClient(timeout=config.timeout, headers=headers)

    async def generate(self, prompt: str) -> str:
        """Call the chat-completions endpoint and return the assistant message."""
        response = await self._client.post(
            self._url,
            json={
                "model": self._config.model,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": self._config.temperature,
                "max_tokens": min(self._config.max_tokens, _MAX_OUTPUT_TOKENS),
                "stream": False,
            },
        )
        response.raise_for_status()
        choices = response.json().get("choices") or []
        if not choices:
            raise RuntimeError("模型未返回 choices，请检查模型名称与端点是否匹配")
        message = choices[0].get("message") or {}
        return message.get("content", "") or ""


def _completion_url(base_url: str) -> str:
    """Accept both ``https://api.deepseek.com`` and an already-complete completions URL."""
    base = (base_url or "").rstrip("/")
    if base.endswith("/chat/completions"):
        return base
    return base + "/chat/completions"


@lru_cache(maxsize=4)
def _build_llm_provider(config_json: str) -> LlmProvider:
    """Memoise one provider per configuration so console edits take effect at once."""
    config = LlmSettings.model_validate_json(config_json)
    if config.provider == "openai":
        return OpenAICompatibleProvider(config)
    if config.provider == "ollama":
        return OllamaProvider(config)
    return MockProvider()


def get_llm_provider() -> LlmProvider:
    """Return the provider matching the current model configuration."""
    return _build_llm_provider(load_model_settings().llm.model_dump_json())
