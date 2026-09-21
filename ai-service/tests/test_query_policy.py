"""Tests for the console-editable query policy that drives knowledge consumption.

The policy is the only place where operations staff can influence what an answer
finally contains, so these tests pin the contract: routing rules, channel switches,
recall budgets, fusion weights, and validation failures.
"""

import json

import pytest
from app.core import query_policy as qp
from app.services.query_engine import QueryRewriter, QueryRouter
from app.services.retrieval.hybrid import HybridRetriever


@pytest.fixture(autouse=True)
def isolated_policy(tmp_path, monkeypatch):
    """Redirect the policy file into a temp directory so tests never touch deployments."""
    monkeypatch.setattr(qp, "POLICY_FILE", tmp_path / "query_policy.json")
    qp.reset_cache()
    yield
    qp.reset_cache()


def test_saved_policy_is_reloaded():
    """A console save must survive a reload so every worker sees the same rules."""
    qp.save_policy({"route": {"enabled": False}, "answer": {"contextMaxChars": 1234}})
    policy = qp.load_policy(force=True)
    assert policy.route.enabled is False
    assert policy.answer.contextMaxChars == 1234
    assert json.loads(qp.POLICY_FILE.read_text(encoding="utf-8"))["answer"]["contextMaxChars"] == 1234


def test_disabled_routing_falls_back_to_default_channels():
    """Stopping the router must still answer through the default channel set."""
    qp.save_policy({"route": {"enabled": False}})
    policy = qp.load_policy()
    decision = QueryRouter().route(
        "版本冲突怎么解决", enabled=policy.route.enabled, defaultStrategies=policy.route.defaultStrategies
    )
    assert decision["matchedRule"] == "policy_disabled"
    assert decision["strategies"] == qp.load_policy().route.defaultStrategies


def test_custom_rule_routes_by_longest_keyword():
    """An added keyword intent must win over a built-in rule with a shorter match."""
    qp.save_policy(
        {
            "route": {
                "rules": [
                    {
                        "questionType": "RELEASE",
                        "keywords": ["发布流程"],
                        "strategies": ["card"],
                        "priority": 95,
                        "reason": "release-specific routing",
                    }
                ]
            }
        }
    )
    policy = qp.load_policy()
    # Deep merge keeps the built-in rules and appends the custom one.
    assert any(rule.questionType == "RELEASE" for rule in policy.route.rules)
    matched = QueryRouter().route("请说明 Spring Boot 发布流程", rules=policy.route.rules)
    # "哪个版本" exists in the CONFLICT rule, but "发布流程" is longer and wins.
    assert matched["questionType"] == "RELEASE"
    assert matched["strategies"] == ["card"]


def test_disabled_channel_is_dropped_from_recall():
    """A switched-off channel must never appear in the strategies sent to the retriever."""
    qp.save_policy({"recall": {"channels": {"vector": {"enabled": False}}}})
    policy = qp.load_policy()
    strategies = qp.effective_strategies(policy, ["card", "vector", "bm25"])
    assert strategies == ["card", "bm25"]


def test_recall_sizes_use_multiplier_and_minimum():
    """Recall budgets combine the Top-K multiplier with the per-channel minimum."""
    qp.save_policy({"recall": {"recallMultiplier": 2, "minRecallCandidates": 50}})
    policy = qp.load_policy()
    sizes = qp.recall_sizes(policy, 10)
    assert sizes["vector"] == 50
    qp.save_policy({"recall": {"minRecallCandidates": 5}})
    assert qp.recall_sizes(qp.load_policy(), 10)["vector"] == 20


def test_recall_sizes_scale_per_channel():
    """The scale multiplier lets one channel recall wider without touching others."""
    qp.save_policy({"recall": {"channels": {"graph": {"scale": 2.0}}, "minRecallCandidates": 1}})
    sizes = qp.recall_sizes(qp.load_policy(), 10)
    assert sizes["graph"] == 60
    assert sizes["vector"] == 30


def test_rewrite_policy_controls_variants():
    """A disabled rewrite must return the normalized question only."""
    import asyncio

    async def run():
        qp.save_policy({"rewrite": {"enabled": False, "maxVariants": 1}})
        policy = qp.load_policy()
        variants = await QueryRewriter().rewrite("报错了怎么办  ", policy=policy.rewrite)
        assert variants == ["报错了怎么办"]
        qp.save_policy({"rewrite": {"enabled": True, "maxVariants": 2}})
        variants = await QueryRewriter().rewrite("是啥配置", policy=qp.load_policy().rewrite)
        assert len(variants) <= 2

    asyncio.run(run())


def test_invalid_policy_is_rejected():
    """Unknown channels and out-of-range values must fail before reaching the pipeline."""
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        qp.save_policy({"recall": {"channels": {"bm25x": {"enabled": True}}}})
    with pytest.raises(ValidationError):
        qp.save_policy({"fusion": {"rrfK": 0}})
    # The rejected payload must not have clobbered the stored policy.
    assert qp.load_policy().fusion.rrfK > 0


def test_reset_policy_restores_defaults():
    """Resetting must discard every console customization."""
    qp.save_policy({"fusion": {"rrfK": 7}, "answer": {"contextMaxChars": 600}})
    policy = qp.reset_policy()
    assert policy.fusion.rrfK == qp.default_policy().fusion.rrfK
    assert qp.load_policy().answer.contextMaxChars == qp.default_policy().answer.contextMaxChars


def test_hybrid_size_for_int_and_mapping():
    """The retriever accepts both a shared budget and per-channel budgets."""
    assert HybridRetriever.size_for(12, "vector") == 12
    assert HybridRetriever.size_for({"vector": 40}, "vector") == 40
    assert HybridRetriever.size_for({"card": 40}, "vector") == 20
    assert HybridRetriever.size_for(None, "vector") == 20


def test_policy_summary_exposes_effective_channels():
    """Traces must record which channels were actually enabled."""
    qp.save_policy({"recall": {"channels": {"graph": {"enabled": False}}}})
    summary = qp.policy_summary(qp.load_policy())
    assert "graph" not in summary["channels"]
    assert summary["rerankEnabled"] is True
