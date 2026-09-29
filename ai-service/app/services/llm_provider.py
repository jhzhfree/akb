"""LLM provider abstraction with mock, Ollama, and OpenAI-compatible implementations."""

import json
import logging
import re
import time
from functools import lru_cache
from typing import Any

import httpx

from app.core.runtime_settings import LlmSettings, load_model_settings

logger = logging.getLogger(__name__)

# Guard against providers that reject very large ``max_tokens`` values outright.
_MAX_OUTPUT_TOKENS = 8192


class LlmProvider:
    """Abstract text-generation provider contract."""

    name = "base"

    async def generate(self, prompt: str, max_tokens: int | None = None) -> str:
        """Generate text for a fully assembled prompt."""
        raise NotImplementedError

    async def extract_json(self, prompt: str, max_tokens: int | None = None) -> dict[str, Any] | None:
        """Generate text and extract the first JSON object from the response.

        ``max_tokens`` is overridable because a reasoning model spends its budget on thinking
        before it writes anything: with the interactive budget the visible answer can come back
        empty even though the model did the work, and the caller cannot tell that from a refusal.
        """
        text = await self.generate(prompt, max_tokens=max_tokens)
        match = re.search(r"\{.*\}", text, re.S)
        return json.loads(match.group(0)) if match else None

    async def test(self) -> dict[str, Any]:
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

    async def generate(self, prompt: str, max_tokens: int | None = None) -> str:
        """Return a stable non-generative answer for environments without an LLM."""
        return "现有知识上下文已检索完成。当前未配置真实大模型，请在「模型设置」中配置 LLM 后启用生成式回答。"


class OllamaProvider(LlmProvider):
    """Ollama-compatible HTTP LLM provider."""

    name = "ollama"

    def __init__(self, config: LlmSettings) -> None:
        self._config = config

    async def generate(self, prompt: str, max_tokens: int | None = None) -> str:
        """Call Ollama's generate endpoint with the configured sampling temperature."""
        options: dict[str, Any] = {"temperature": self._config.temperature}
        if max_tokens:
            options["num_predict"] = int(max_tokens)
        async with httpx.AsyncClient(timeout=self._config.timeout) as client:
            response = await client.post(
                self._config.base_url.rstrip("/") + "/api/generate",
                json={
                    "model": self._config.model,
                    "prompt": prompt,
                    "stream": False,
                    "options": options,
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
        self._headers = headers

    async def generate(self, prompt: str, max_tokens: int | None = None) -> str:
        """Call the chat-completions endpoint and return the assistant message.

        A reasoning model answers in two parts: the thinking, and the answer. When the budget runs
        out during thinking the answer arrives empty, which looks exactly like a model that refused
        to answer. Empty content is therefore reported instead of being swallowed, so callers can
        retry with a bigger budget rather than silently degrading to rule-based extraction.
        """
        budget = int(max_tokens or min(self._config.max_tokens, _MAX_OUTPUT_TOKENS))
        # A connection is opened per call and closed with it. A provider kept alive for the life
        # of the process holds a socket that dies silently when the network blinks, and every
        # later question then fails for a reason nobody can see; opening one per call costs a
        # handshake and buys back that failure mode.
        async with httpx.AsyncClient(timeout=self._config.timeout, headers=self._headers) as client:
            response = await client.post(
                self._url,
                json={
                    "model": self._config.model,
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": self._config.temperature,
                    "max_tokens": min(budget, _MAX_OUTPUT_TOKENS),
                    "stream": False,
                },
            )
        response.raise_for_status()
        choices = response.json().get("choices") or []
        if not choices:
            raise RuntimeError("模型未返回 choices，请检查模型名称与端点是否匹配")
        message = choices[0].get("message") or {}
        content = message.get("content") or ""
        if not content and message.get("reasoning_content"):
            logger.warning(
                "LLM returned empty content with reasoning only (finish_reason=%s); "
                "the output budget was likely consumed by thinking",
                choices[0].get("finish_reason"),
            )
        return content


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
