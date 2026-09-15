"""Release qualification contracts for Elasticsearch + Milvus migration."""
import asyncio
import json
from pathlib import Path
import sys
import httpx
ROOT=Path(__file__).resolve().parents[1]; sys.path.insert(0,str(ROOT))
from app.services.indexer import KnowledgeIndexer
from app.services.retrieval.elasticsearch_provider import ElasticsearchProvider
from app.services.retrieval.milvus_provider import MilvusProvider


def test_snapshot_normalization():
    row={"objectId":"K1","objectType":"CARD","knowledgeBaseId":1,"content":"证书部署", "status":"APPROVED","permissionTags":"ops,pmo","contentHash":"abc"}
    item=KnowledgeIndexer._normalize_snapshot_document(1,row)
    assert item["id"]=="K1" and item["permission_tags"]==["ops","pmo"]
    assert item["metadata"]["content_hash"]=="abc"


def test_elasticsearch_mapping_and_acl():
    seen=[]
    def handler(request):
        seen.append((request.method,request.url.path,request.read().decode()))
        if request.method=='HEAD': return httpx.Response(404)
        if request.method=='PUT': return httpx.Response(200,json={"acknowledged":True})
        return httpx.Response(200,json={"hits":{"hits":[]}})
    async def run():
        p=ElasticsearchProvider(); await p.client.aclose(); p.client=httpx.AsyncClient(base_url='http://es',transport=httpx.MockTransport(handler))
        await p.ensure_index('rq_index')
        p._active_read_index='rq_index'
        await p.search(1,'Tomcat 证书',10,['ops'],'CARD')
        await p.client.aclose()
    asyncio.run(run())
    mapping=json.loads(next(x[2] for x in seen if x[0]=='PUT'))
    assert mapping['mappings']['properties']['permission_tags']['type']=='keyword'
    query=json.loads(next(x[2] for x in seen if x[1].endswith('/_search')))
    raw=json.dumps(query,ensure_ascii=False)
    assert 'permission_tags' in raw and 'APPROVED' in raw and 'EFFECTIVE' in raw and 'knowledge_base_id' in raw


def test_milvus_acl_and_reset_contract():
    p=MilvusProvider()
    expr=p._filter_expr(1,['ops'],'CARD')
    assert 'knowledge_base_id == 1' in expr and 'object_type == "CARD"' in expr
    assert 'APPROVED' in expr and 'EFFECTIVE' in expr and 'permission_tags' in expr
    asyncio.run(p.client.aclose())
    source=(ROOT/'app/services/indexer.py').read_text()
    assert '/v2/vectordb/collections/drop' in source
    assert '/collections/%s' not in source


def test_compose_and_env_versions():
    project=ROOT.parent
    compose=(project/'docker-compose.yml').read_text()
    assert 'docker.elastic.co/elasticsearch/elasticsearch:9.5.3' in compose
    assert 'milvusdb/milvus:v3.0.1' in compose and '19530:19530' in compose
    for name in ['.env.example','.env.production.example']:
        text=(project/name).read_text()
        assert 'MILVUS_URL=http://milvus:19530' in text
        assert 'MILVUS_URL=http://milvus:6333' not in text


if __name__=='__main__':
    test_snapshot_normalization(); test_elasticsearch_mapping_and_acl(); test_milvus_acl_and_reset_contract(); test_compose_and_env_versions(); print('release qualification contract passed')
