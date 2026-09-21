"""Reranker abstraction with lexical fallback and optional BGE cross-encoder."""

import logging
import math
import re
from abc import ABC, abstractmethod
from functools import lru_cache
from typing import Any

from app.core.config import settings

logger = logging.getLogger(__name__)
TOKEN_RE = re.compile(r"[A-Za-z0-9_.+-]+|[\u4e00-\u9fff]")


def _tokens(text: str) -> list[str]:
    return TOKEN_RE.findall((text or "").lower())


class Reranker(ABC):
    """Abstract candidate-reranking contract."""

    name = "base"

    @abstractmethod
    def rerank(
        self,
        query: str,
        candidates: list[dict[str, Any]],
        top_k: int,
    ) -> list[dict[str, Any]]:
        """Return candidates ordered by final relevance score."""
        raise NotImplementedError


class LexicalCrossFeatureReranker(Reranker):
    """Deterministic reranker used when ML reranking is unavailable."""

    name = "lexical-cross-feature"

    def rerank(
        self,
        query: str,
        candidates: list[dict[str, Any]],
        top_k: int,
    ) -> list[dict[str, Any]]:
        """Blend token overlap, phrase match, recall score, and confidence."""
        query_tokens = _tokens(query)
        query_set = set(query_tokens)
        output = []
        for candidate in candidates:
            text = (candidate.get("title", "") + " " + candidate.get("content", "")).lower()
            text_set = set(_tokens(text))
            overlap = len(query_set.intersection(text_set)) / max(len(query_set), 1)
            phrase = 1.0 if query.lower() in text else 0.0
            original = float(candidate.get("score", 0.0) or 0.0)
            confidence = float((candidate.get("metadata") or {}).get("confidence", 0.0) or 0.0)
            score = 0.55 * overlap + 0.20 * phrase + 0.20 * min(original * 30.0, 1.0) + 0.05 * confidence
            item = dict(candidate)
            item["preRerankScore"] = original
            item["rerankScore"] = score
            item["score"] = score
            output.append(item)
        return sorted(output, key=lambda item: item["score"], reverse=True)[:top_k]


class BgeCrossEncoderReranker(Reranker):
    """BGE cross-encoder reranker loaded once per process."""

    name = "bge-cross-encoder"

    def __init__(self) -> None:
        import torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self.torch = torch
        self.tokenizer = AutoTokenizer.from_pretrained(settings.reranker_model)
        self.model = AutoModelForSequenceClassification.from_pretrained(settings.reranker_model)
        device = settings.reranker_device
        if device == "auto":
            device = "cuda" if torch.cuda.is_available() else "cpu"
        self.device = torch.device(device)
        self.model.to(self.device)
        self.model.eval()

    def rerank(
        self,
        query: str,
        candidates: list[dict[str, Any]],
        top_k: int,
    ) -> list[dict[str, Any]]:
        """Score query/document pairs in bounded batches on CPU or GPU."""
        if not candidates:
            return []

        pairs = [
            [
                query,
                (candidate.get("title", "") + "\n" + candidate.get("content", ""))[: settings.reranker_max_chars],
            ]
            for candidate in candidates
        ]
        batch_size = max(1, settings.reranker_batch_size)
        scores = []
        with self.torch.no_grad():
            for start in range(0, len(pairs), batch_size):
                batch = pairs[start : start + batch_size]
                features = self.tokenizer(
                    batch,
                    padding=True,
                    truncation=True,
                    max_length=settings.reranker_max_length,
                    return_tensors="pt",
                ).to(self.device)
                logits = self.model(**features, return_dict=True).logits.view(-1).float().cpu().tolist()
                scores.extend([1.0 / (1.0 + math.exp(-max(min(value, 40.0), -40.0))) for value in logits])

        output = []
        # strict：分数与候选必须一一对应，静默错位会把排序结果悄悄改错且不报错
        for candidate, score in zip(candidates, scores, strict=True):
            item = dict(candidate)
            item["preRerankScore"] = float(candidate.get("score", 0.0) or 0.0)
            item["rerankScore"] = float(score)
            item["score"] = float(score)
            output.append(item)
        return sorted(output, key=lambda item: item["score"], reverse=True)[:top_k]


@lru_cache(maxsize=1)
def get_reranker() -> Reranker:
    """Return the process-wide configured reranker with safe lexical fallback."""
    if not settings.reranker_enabled:
        return LexicalCrossFeatureReranker()
    if settings.reranker_provider.lower() in {"bge", "cross-encoder", "transformers"}:
        try:
            return BgeCrossEncoderReranker()
        except Exception as exc:
            logger.warning("BGE reranker unavailable; using lexical fallback: %s", exc)
            return LexicalCrossFeatureReranker()
    return LexicalCrossFeatureReranker()
