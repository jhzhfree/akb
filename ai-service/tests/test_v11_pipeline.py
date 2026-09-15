import asyncio
from pathlib import Path

from app.services.document_parser import DocumentParser
from app.services.extractor import KnowledgeExtractor
from app.services.indexer import KnowledgeIndexer
from app.services.query_engine import QueryEngine


def test_pipeline(tmp_path: Path):
    sample = tmp_path / "sample.md"
    sample.write_text(
        "# 知识裁决\n\n候选知识发布前必须执行重复检测、冲突检测、证据评分和知识裁决。"
        "低置信度、高风险或强冲突知识不得自动发布，必须进入人工审核。\n\n"
        "# 查询策略\n\n知识查询应根据问题类型选择 BM25、向量、知识卡片、断言和图谱检索。",
        encoding="utf-8",
    )

    async def run():
        parsed = DocumentParser().parse(str(sample), sample.name)
        assert parsed["chunks"]
        chunks = []
        for i, chunk in enumerate(parsed["chunks"], 1):
            item = dict(chunk)
            item["content_unit_id"] = 900000 + i
            chunks.append(item)

        await KnowledgeIndexer().index_chunks(
            9999, 9999, sample.name, "test://sample.md", chunks, []
        )
        knowledge = await KnowledgeExtractor().extract(parsed["text"], chunks)
        assert knowledge["cards"]
        assert knowledge["claims"]
        await KnowledgeIndexer().index_knowledge_objects(
            9999, knowledge["cards"], knowledge["claims"]
        )

        result = await QueryEngine().query(
            "Q_TEST", 9999, "为什么知识发布前需要裁决？", 5, []
        )
        assert result["candidates"]
        assert result["citations"]
        assert "bm25" in result["strategies"] or "vector" in result["strategies"]

    asyncio.run(run())
