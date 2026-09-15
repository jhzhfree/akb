import asyncio
import tempfile
from pathlib import Path

from app.storage.index_store import IndexStore
from app.services.retrieval.external import ElasticsearchProvider, MilvusProvider
import httpx


def test_local_status_version_guard():
    with tempfile.TemporaryDirectory() as directory:
        store = IndexStore()
        store._path = lambda kb: Path(directory) / ("kb_%s.json" % kb)
        store.save(1, [{"id":"K1","status":"DRAFT","index_version":2}])
        assert not store.update_object_status(1, "K1", "REJECTED", 1)
        assert store.load(1)[0]["status"] == "DRAFT"
        assert store.update_object_status(1, "K1", "APPROVED", 3)
        assert store.load(1)[0]["status"] == "APPROVED"
        assert store.load(1)[0]["index_version"] == 3


def test_external_status_contracts():
    seen = []
    def handler(request):
        seen.append((request.method, str(request.url), request.read().decode()))
        if "_update" in str(request.url):
            return httpx.Response(200, json={"result":"updated"})
        return httpx.Response(200, json={})

    async def run():
        os_provider = ElasticsearchProvider()
        await os_provider.client.aclose()
        os_provider.client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://os")
        q_provider = MilvusProvider()
        await q_provider.client.aclose()
        q_provider.client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://q")
        await os_provider.update_object_status(1, "K1", "APPROVED", 3)
        milvus_status = await q_provider.update_object_status(1, "K1", "APPROVED", 3)
        assert milvus_status["requiresReindex"] is True
        await os_provider.client.aclose(); await q_provider.client.aclose()
        joined = "\n".join(x[2] for x in seen)
        assert "index_version" in joined
        assert "APPROVED" in joined
    asyncio.run(run())


if __name__ == "__main__":
    test_local_status_version_guard()
    test_external_status_contracts()
    print("outbox index version contract passed")
