#!/usr/bin/env python3
import argparse, asyncio, json, os, sys, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; sys.path.insert(0,str(ROOT/'ai-service'))
from app.storage.index_store import store
from app.services.retrieval.hybrid import HybridRetriever
from app.services.query_engine import QueryEngine
from app.services.conflict_detector import ConflictDetector

class CountingDetector(ConflictDetector):
    def __init__(self): self.compares=0
    def compare(self,a,b): self.compares+=1; return super().compare(a,b)

def conflict(scale):
    claims=[{'claim_code':'C%d'%i,'subject':'S%d'%(i%10000),'predicate':'IS','object':'V%d'%(i%2)} for i in range(scale)]
    d=CountingDetector(); t=time.perf_counter(); out=d.detect(claims,focus_codes=['C1']); ms=(time.perf_counter()-t)*1000
    return {'scale':scale,'elapsed_ms':round(ms,2),'comparisons':d.compares,'conflicts':len(out),'naive_pairs':scale*(scale-1)//2}

async def full100(path):
    store._path=lambda kb: Path(path); store._cache.clear(); store._generation.clear()
    t=time.perf_counter(); docs=store.load(100000); load=(time.perf_counter()-t)*1000
    r=HybridRetriever(); q='如何完成Tomcat证书部署流程和知识裁决'
    t=time.perf_counter(); r._bm25(100000,q,20); warm=(time.perf_counter()-t)*1000
    e=QueryEngine(); e.retriever=r
    t=time.perf_counter(); res=await e.query('full-100k-first',100000,q,10); first=(time.perf_counter()-t)*1000
    t=time.perf_counter(); res2=await e.query('full-100k-warm',100000,q,10); warm_query=(time.perf_counter()-t)*1000
    async def one(i):
        t=time.perf_counter(); await e.query('full-100k-c%d'%i,100000,q,10); return (time.perf_counter()-t)*1000
    t=time.perf_counter(); lats=await asyncio.gather(*[one(i) for i in range(4)]); wall=(time.perf_counter()-t)*1000
    return {'cold_load_ms':round(load,2),'lexical_warm_ms':round(warm,2),'full_first_query_ms':round(first,2),'full_warm_query_ms':round(warm_query,2),'concurrent4_wall_ms':round(wall,2),'concurrent4_qps':round(4/(wall/1000),2),'concurrent4_lats_ms':[round(x,2) for x in lats],'trace':res2['trace']}

async def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--path'); ap.add_argument('--out',required=True); args=ap.parse_args()
    result={'conflict':[conflict(n) for n in (10000,50000,100000)]}
    if args.path: result['full_100k']=await full100(args.path)
    Path(args.out).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8'); print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__': asyncio.run(main())
