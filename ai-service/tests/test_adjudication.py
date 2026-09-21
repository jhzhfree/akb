import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from app.services.adjudication_engine import AdjudicationEngine


async def run():
    engine = AdjudicationEngine()
    trusted = await engine.judge(
        {
            "confidence": 0.95,
            "evidenceCount": 3,
            "sourceCount": 2,
            "authorityScore": 0.95,
            "freshnessScore": 0.9,
            "evidenceSupportScore": 0.95,
            "objectContent": "受多来源证据支持的知识",
            "conflictCount": 0,
        }
    )
    conflicted = await engine.judge(
        {
            "confidence": 0.9,
            "evidenceCount": 2,
            "sourceCount": 1,
            "authorityScore": 0.9,
            "freshnessScore": 0.9,
            "objectContent": "存在冲突的知识",
            "conflictCount": 1,
            "maxConflictScore": 0.92,
        }
    )
    unsupported = await engine.judge(
        {
            "confidence": 0.8,
            "evidenceCount": 0,
            "sourceCount": 0,
            "authorityScore": 0.5,
            "freshnessScore": 0.8,
            "objectContent": "无证据知识",
            "conflictCount": 0,
        }
    )
    assert trusted["decision"] == "APPROVED" and not trusted["requiresHumanReview"]
    assert conflicted["decision"] == "CONFLICTED" and conflicted["requiresHumanReview"]
    assert unsupported["requiresHumanReview"]
    print(
        "adjudication smoke passed",
        trusted["knowledgeConfidence"],
        conflicted["knowledgeConfidence"],
        unsupported["knowledgeConfidence"],
    )


if __name__ == "__main__":
    asyncio.run(run())
