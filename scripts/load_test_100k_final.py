#!/usr/bin/env python3
import asyncio,json,math,statistics,sys,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'ai-service'))
from app.storage.index_store import store
from app.services.retrieval.hybrid import HybridRetriever
from app.services.query_engine import QueryEngine

def pct(a,p):
 a=sorted(a); k=(len(a)-1)*p; f=int(math.floor(k)); c=int(math.ceil(k)); return a[f] if f==c else a[f]*(c-k)+a[c]*(k-f)
async def main():
 path=Path('/mnt/data/v11-loadtest/index-100k.json'); store._path=lambda kb:path; store._cache.clear(); store._generation.clear(); q='如何完成Tomcat证书部署流程和知识裁决'
 t=time.perf_counter(); store.load(100000); cold=(time.perf_counter()-t)*1000
 r=HybridRetriever(); t=time.perf_counter(); r._bm25(100000,q,30); lex=(time.perf_counter()-t)*1000
 t=time.perf_counter(); await r._vector(100000,q,30); vec_first=(time.perf_counter()-t)*1000
 e=QueryEngine(); e.retriever=r
 samples=[]
 for i in range(5):
  t=time.perf_counter(); await e.query('w%d'%i,100000,q,10); samples.append((time.perf_counter()-t)*1000)
 async def one(i):
  t=time.perf_counter(); await e.query('c%d'%i,100000,q,10); return (time.perf_counter()-t)*1000
 t=time.perf_counter(); lats=await asyncio.gather(*[one(i) for i in range(4)]); wall=(time.perf_counter()-t)*1000
 out={'cold_load_ms':round(cold,2),'lexical_build_ms':round(lex,2),'vector_snapshot_build_and_first_query_ms':round(vec_first,2),'warm_query_ms':{'samples':[round(x,2) for x in samples],'p50':round(pct(samples,.5),2),'p95':round(pct(samples,.95),2)},'concurrency4':{'wall_ms':round(wall,2),'qps':round(4/(wall/1000),2),'latencies_ms':[round(x,2) for x in lats],'p50':round(pct(lats,.5),2),'p95':round(pct(lats,.95),2)}}
 Path('/mnt/data/v11-loadtest/result-100k-final.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)); print(json.dumps(out,ensure_ascii=False,indent=2))
asyncio.run(main())
