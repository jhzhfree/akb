import asyncio
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app.services.retrieval.external import ElasticsearchProvider, MilvusProvider


def test_elasticsearch_query_contract():
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["body"] = request.read().decode()
        return httpx.Response(
            200,
            json={
                "hits": {
                    "hits": [
                        {
                            "_score": 2.1,
                            "_source": {
                                "object_id": "K1",
                                "title": "Tomcat",
                                "content": "PKCS12",
                                "knowledge_base_id": 1,
                            },
                        }
                    ]
                }
            },
        )

    async def run():
        p = ElasticsearchProvider()
        await p.client.aclose()
        p.client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://os")
        rows = await p.search(1, "Tomcat", 5, [])
        await p.client.aclose()
        assert rows[0]["id"] == "K1"
        assert "knowledge_base_id" in seen["body"]
        assert "APPROVED" in seen["body"]
        assert "EFFECTIVE" in seen["body"]
        assert "_search" in seen["url"]

    asyncio.run(run())


def test_milvus_query_contract():
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["body"] = request.read().decode()
        return httpx.Response(
            200,
            json={
                "code": 0,
                "data": [{"distance": 0.93, "entity": {"object_id": "K1", "title": "Tomcat", "content": "PKCS12"}}],
            },
        )

    async def run():
        p = MilvusProvider()
        await p.client.aclose()
        p.client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://milvus")
        p._active_read_collection = p.collection
        rows = await p.search(1, [0.1] * 384, 5, [])
        await p.client.aclose()
        assert rows[0]["id"] == "K1"
        assert "/v2/vectordb/entities/search" in seen["url"]
        assert "knowledge_base_id" in seen["body"]
        assert "APPROVED" in seen["body"] and "EFFECTIVE" in seen["body"]

    asyncio.run(run())


if __name__ == "__main__":
    test_elasticsearch_query_contract()
    test_milvus_query_contract()
    print("dual retrieval contract passed")
