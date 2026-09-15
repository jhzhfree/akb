#!/usr/bin/env python3
"""Aggregate mandatory Production Readiness evidence into a deterministic decision."""
import argparse,json
from pathlib import Path
ap=argparse.ArgumentParser()
ap.add_argument("--benchmark",default="performance/results/release-benchmark.json")
ap.add_argument("--quality",default="performance/results/retrieval-quality.json")
ap.add_argument("--acl",default="performance/results/acl-qualification.json")
ap.add_argument("--consistency",default="performance/results/consistency-qualification.json")
ap.add_argument("--blue-green",default="performance/results/blue-green-qualification.json")
ap.add_argument("--fault",default="performance/results/fault-injection-qualification.json")
ap.add_argument("--build",default="performance/results/build-qualification.json")
ap.add_argument("--es-p95",type=float,default=300); ap.add_argument("--milvus-p95",type=float,default=200)
ap.add_argument("--recall",type=float,default=.80); ap.add_argument("--ndcg",type=float,default=.75)
a=ap.parse_args(); reasons=[]; evidence={}
def load(name,path):
    try: value=json.loads(Path(path).read_text()); evidence[name]=value; return value
    except Exception: reasons.append(name+" evidence missing"); evidence[name]={}; return {}
b=load("benchmark",a.benchmark); q=load("quality",a.quality)
for name,path in [("acl",a.acl),("consistency",a.consistency),("blueGreen",a.blue_green),("faultInjection",a.fault),("build",a.build)]:
    item=load(name,path)
    if item and item.get("qualified") is not True: reasons.append(name+" qualification failed")
if b:
    if b.get("qualified") is not True: reasons.append("benchmark qualification failed")
    if b.get("elasticsearch",{}).get("p95Ms",10**9)>a.es_p95: reasons.append("Elasticsearch P95 exceeds SLO")
    if b.get("milvus",{}).get("p95Ms",10**9)>a.milvus_p95: reasons.append("Milvus P95 exceeds SLO")
if q:
    if q.get("recallAtK",0)<a.recall: reasons.append("Recall@K below threshold")
    if q.get("ndcgAtK",0)<a.ndcg: reasons.append("NDCG@K below threshold")
result={"decision":"GO" if not reasons else "NO-GO","reasons":reasons,"evidence":evidence}
print(json.dumps(result,ensure_ascii=False,indent=2)); raise SystemExit(0 if not reasons else 2)
