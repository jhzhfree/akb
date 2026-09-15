import asyncio
import httpx
from app.services.retrieval.external import ElasticsearchProvider, MilvusProvider


def response(code=200, payload=None, request=None):
    return httpx.Response(code, json=payload or {}, request=request)


async def test_elasticsearch():
    calls=[]
    def handler(request):
        calls.append((request.method, request.url.path))
        path=request.url.path
        if request.method=='HEAD' and '__schema2' in path:
            return response(404, request=request)
        if request.method=='PUT' and '__schema2' in path:
            return response(200, {'acknowledged':True}, request=request)
        if path=='/_bulk':
            return response(200, {'items':[{'index':{'status':201}}]}, request=request)
        if request.method=='GET' and path.startswith('/_alias/'):
            return response(404, request=request)
        if request.method=='POST' and path=='/_aliases':
            return response(200, {'acknowledged':True}, request=request)
        return response(200, request=request)
    p=ElasticsearchProvider(); await p.client.aclose(); p.client=httpx.AsyncClient(base_url='http://mock',transport=httpx.MockTransport(handler))
    docs=[{'id':'A','knowledge_base_id':1,'content':'x','title':'x'}]
    rebuilt=await p.rebuild_target(docs,'schema2')
    assert rebuilt['target'].endswith('__schema2') and rebuilt['indexed']==1
    # activation HEAD must succeed now
    def handler2(request):
        calls.append((request.method, request.url.path))
        if request.method=='HEAD': return response(200, request=request)
        if request.method=='GET' and request.url.path.startswith('/_alias/'): return response(404, request=request)
        return response(200, {'acknowledged':True}, request=request)
    await p.client.aclose(); p.client=httpx.AsyncClient(base_url='http://mock',transport=httpx.MockTransport(handler2))
    activated=await p.activate_target('schema2')
    assert activated['activated'] is True and activated['alias']==p.read_alias
    await p.client.aclose()


async def test_milvus():
    collections=[]
    aliases={}
    def handler(request):
        path=request.url.path
        body=request.read().decode()
        import json
        data=json.loads(body or '{}')
        if path.endswith('/collections/list'):
            return response(200, {'code':0,'data':collections}, request=request)
        if path.endswith('/collections/create'):
            collections.append(data['collectionName'])
            return response(200, {'code':0,'data':{}}, request=request)
        if path.endswith('/collections/drop'):
            if data['collectionName'] in collections: collections.remove(data['collectionName'])
            return response(200, {'code':0,'data':{}}, request=request)
        if path.endswith('/entities/upsert'):
            return response(200, {'code':0,'data':{'upsertCount':len(data['data'])}}, request=request)
        if path.endswith('/aliases/list'):
            return response(200, {'code':0,'data':[]}, request=request)
        if path.endswith('/aliases/create') or path.endswith('/aliases/alter'):
            aliases[data['aliasName']]=data['collectionName']
            return response(200, {'code':0,'data':{}}, request=request)
        return response(200, {'code':0,'data':{}}, request=request)
    p=MilvusProvider(); await p.client.aclose()
    p.client=httpx.AsyncClient(base_url='http://mock',transport=httpx.MockTransport(handler))
    docs=[{'id':'A','knowledge_base_id':1,'content':'x','vector':[0.1]*384}]
    rebuilt=await p.rebuild_target(docs,'schema2')
    assert rebuilt['target'].endswith('__schema2') and rebuilt['indexed']==1
    activated=await p.activate_target('schema2')
    assert activated['activated'] is True and aliases[p.read_alias].endswith('__schema2')
    await p.client.aclose()

asyncio.run(test_elasticsearch())
asyncio.run(test_milvus())
print('blue-green schema contract passed')
