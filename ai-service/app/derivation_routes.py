"""Model backed knowledge derivation.

A rule asks the model to read the assertions a knowledge base already has and propose
conclusions nobody wrote down. Whatever comes back is *proposal*, never knowledge: the
backend stores every item as a candidate carrying its base assertions, and a human
decides. When no model is configured the endpoint still answers with a deterministic
reading of the same assertions — labelled as heuristic, because an answer that looks
modelled but is not would be worse than one that says what it is.
"""

from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.services.llm_provider import get_llm_provider

router = APIRouter(tags=["derivation"])


class DeriveRequest(BaseModel):
    """One derivation run: the rule, its prompt and the assertions it may build on."""

    knowledgeBaseId: int | None = None
    ruleCode: str | None = None
    prompt: str = ""
    limit: int = Field(default=50, ge=1, le=200)
    minConfidence: float = Field(default=0.5, ge=0.0, le=1.0)
    claims: list[dict[str, Any]] = Field(default_factory=list)


class DeriveResponse(BaseModel):
    """Candidates proposed by a run, plus which path produced them."""

    candidates: list[dict[str, Any]] = Field(default_factory=list)
    source: str = "heuristic"
    scanned: int = 0


@router.post("/ai/derive", response_model=DeriveResponse)
async def derive(request: DeriveRequest) -> DeriveResponse:
    """Propose derived knowledge candidates from existing assertions."""
    claims = [c for c in request.claims if isinstance(c, dict)][: request.limit]
    candidates = await _model_candidates(request, claims)
    source = "model"
    if not candidates:
        candidates = _heuristic_candidates(request, claims)
        source = "heuristic"
    return DeriveResponse(candidates=candidates, source=source, scanned=len(claims))


async def _model_candidates(request: DeriveRequest, claims: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Ask the model; an unconfigured or failing model simply proposes nothing."""
    if not claims:
        return []
    provider = get_llm_provider()
    if getattr(provider, "name", "mock") == "mock":
        return []
    lines = []
    for index, claim in enumerate(claims[:40], start=1):
        lines.append(
            f"{index}. {claim.get('subject', '')} / {claim.get('predicate', '')} / "
            f"{claim.get('objectValue', claim.get('object', ''))} "
            f"(code={claim.get('claimCode', '')})"
        )
    prompt = (
        "以下是知识库中已被证据支持的断言。请只输出由这些断言可以直接推导出的新结论，"
        "不要重复断言本身，不要臆造断言中没有的信息。\n"
        f"附加要求：{request.prompt or '只输出有把握的结论'}\n"
        f"最多 {min(request.limit, 20)} 条。\n"
        '输出 JSON：{"candidates":[{"title":"","content":"","confidence":0.0,'
        '"baseAssertionCodes":[""]}]}\n断言：\n' + "\n".join(lines)
    )
    try:
        result = await provider.extract_json(prompt)
    except Exception:
        return []
    if not isinstance(result, dict):
        return []
    raw = result.get("candidates")
    if not isinstance(raw, list):
        return []
    candidates = []
    known_codes = {str(c.get("claimCode", "")) for c in claims}
    for item in raw:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        try:
            confidence = max(0.0, min(1.0, float(item.get("confidence", 0.5))))
        except (TypeError, ValueError):
            confidence = 0.5
        codes = [str(code) for code in (item.get("baseAssertionCodes") or []) if str(code) in known_codes]
        candidates.append(
            {
                "title": title[:200],
                "content": str(item.get("content") or title)[:2000],
                "confidence": round(confidence, 4),
                "baseAssertionCodes": codes,
            }
        )
        if len(candidates) >= request.limit:
            break
    return candidates


def _heuristic_candidates(request: DeriveRequest, claims: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Deterministic fallback: restate pairs of assertions about the same subject."""
    candidates: list[dict[str, Any]] = []
    by_subject: dict[str, list[dict[str, Any]]] = {}
    for claim in claims:
        subject = str(claim.get("subject") or "").strip()
        if subject:
            by_subject.setdefault(subject, []).append(claim)
    for subject, group in by_subject.items():
        if len(group) < 2:
            continue
        first, second = group[0], group[1]
        confidence = (
            min(
                float(first.get("confidence") or 0.5),
                float(second.get("confidence") or 0.5),
            )
            * 0.7
        )
        if confidence < request.minConfidence:
            continue
        candidates.append(
            {
                "title": f"{subject}：{first.get('predicate', '')}与{second.get('predicate', '')}的综合结论",
                "content": (
                    f"由 {first.get('claimCode', '')} 与 {second.get('claimCode', '')} 归并："
                    f"{subject} 的{first.get('predicate', '')}为{first.get('objectValue', '')}，"
                    f"且{second.get('predicate', '')}为{second.get('objectValue', '')}。"
                ),
                "confidence": round(confidence, 4),
                "baseAssertionCodes": [
                    str(first.get("claimCode", "")),
                    str(second.get("claimCode", "")),
                ],
                "heuristic": True,
            }
        )
        if len(candidates) >= request.limit:
            break
    return candidates
