#!/usr/bin/env python3
"""Validate authoritative count, release checksum and high-watermark evidence."""
import argparse,json,urllib.request
from pathlib import Path

def get(url):
    with urllib.request.urlopen(url,timeout=10) as r: return json.loads(r.read().decode())
def data(x): return x.get('data',x) if isinstance(x,dict) else x
ap=argparse.ArgumentParser(); ap.add_argument('--backend',default='http://127.0.0.1:8080'); ap.add_argument('--kb',type=int,required=True); ap.add_argument('--output',default='performance/results/consistency-qualification.json')
a=ap.parse_args(); base=a.backend.rstrip('/')+'/api/knowledge'
drift=data(get('%s/index/drift/%d'%(base,a.kb))); releases=data(get('%s/index/schema/%d'%(base,a.kb)))
active=next((r for r in releases if r.get('state')=='ACTIVE'),None) if isinstance(releases,list) else None
reasons=[]
if not active: reasons.append('ACTIVE schema release missing')
if drift.get('elasticsearchDelta') not in (0,'0'): reasons.append('Elasticsearch count drift')
if drift.get('milvusDelta') not in (0,'0'): reasons.append('Milvus count drift')
if active:
    if active.get('integrityState')!='VERIFIED': reasons.append('release integrityState is not VERIFIED')
    if not active.get('snapshotChecksum'): reasons.append('snapshot checksum missing')
    if active.get('sourceHighWatermark')!=active.get('sourceEndWatermark'): reasons.append('source high-watermark changed during snapshot')
    expected=active.get('expectedCount')
    for key in ('snapshotCount','elasticsearchCount','milvusCount'):
        if active.get(key)!=expected: reasons.append('%s differs from expectedCount'%key)
result={'qualified':not reasons,'reasons':reasons,'drift':drift,'activeRelease':active}
Path(a.output).write_text(json.dumps(result,ensure_ascii=False,indent=2)); print(json.dumps(result,ensure_ascii=False,indent=2))
raise SystemExit(0 if not reasons else 2)
