"""供应商限流与故障必须是明确错误，不能被兜底成一个 internal server error。

之前 embedding 供应商返回 429，这里直接 raise_for_status，异常一路冒到全局兜底变成
AI-500-001。调用方只看到"内部错误"，真正的"配额打满了"要翻日志才知道。这组用例钉住：
限流单独归类、重试能自愈、配置类错误不白重试。
"""

import asyncio

import httpx
import pytest

from app.core.contracts import UpstreamRateLimitedError, UpstreamServiceError
from app.core.runtime_settings import EmbeddingSettings
from app.services.embedding import OpenAIEmbeddingProvider

DIM = 16  # EmbeddingSettings 的下限


class _FakeResponse:
    def __init__(self, status_code: int, payload=None, text: str = "", headers=None):
        self.status_code = status_code
        self._payload = payload if payload is not None else {}
        self.text = text
        self.headers = headers or {}

    def json(self):
        return self._payload


def _ok():
    return _FakeResponse(200, {"data": [{"index": 0, "embedding": [0.1] * DIM}]})


def _provider():
    return OpenAIEmbeddingProvider(
        EmbeddingSettings(enabled=True, provider="openai", base_url="https://vendor.test/v4",
                          api_key="secret", model="embedding-3", dim=DIM, timeout=5)
    )


def _install(monkeypatch, script):
    """Replay a script of responses (or exceptions) and record how many calls were made."""
    calls = []

    async def fake_post(self, url, json=None, **kwargs):
        index = min(len(calls), len(script) - 1)
        calls.append(url)
        item = script[index]
        if isinstance(item, Exception):
            raise item
        return item

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    async def skip_sleep(_seconds):
        return None

    monkeypatch.setattr(asyncio, "sleep", skip_sleep)
    return calls


def test_rate_limit_is_reported_as_rate_limit(monkeypatch):
    calls = _install(monkeypatch, [_FakeResponse(429, text="too many requests")] * 3)

    with pytest.raises(UpstreamRateLimitedError) as raised:
        asyncio.run(_provider().embed(["报销流程"]))

    assert raised.value.provider == "openai"
    assert len(calls) == 3  # 退避重试到上限后才放弃


def test_recovers_when_the_next_attempt_succeeds(monkeypatch):
    calls = _install(monkeypatch, [_FakeResponse(429, text="slow down"), _ok()])

    vectors = asyncio.run(_provider().embed(["报销流程"]))

    assert len(vectors) == 1
    assert len(calls) == 2


def test_vendor_fault_is_reported_as_unavailable(monkeypatch):
    calls = _install(monkeypatch, [_FakeResponse(503, text="upstream down")] * 3)

    with pytest.raises(UpstreamServiceError) as raised:
        asyncio.run(_provider().embed(["报销流程"]))

    assert raised.value.status == 503
    assert len(calls) == 3


def test_unreachable_vendor_has_no_status(monkeypatch):
    _install(monkeypatch, [httpx.ConnectError("connection refused")] * 3)

    with pytest.raises(UpstreamServiceError) as raised:
        asyncio.run(_provider().embed(["报销流程"]))

    assert raised.value.status is None  # 请求根本没到供应商


def test_configuration_errors_are_not_retried(monkeypatch):
    """401 是 key 配错了，重试多少次都一样——白等一轮只会拖慢失败。"""
    calls = _install(monkeypatch, [_FakeResponse(401, text="invalid api key")])

    with pytest.raises(UpstreamServiceError) as raised:
        asyncio.run(_provider().embed(["报销流程"]))

    assert raised.value.status == 401
    assert len(calls) == 1
