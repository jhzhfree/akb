import argparse, json, urllib.request
p=argparse.ArgumentParser(); p.add_argument('--base-url',default='http://127.0.0.1:12000'); p.add_argument('--kb',type=int,required=True); a=p.parse_args()
req=urllib.request.Request('%s/ai/index/rebuild/%s'%(a.base_url.rstrip('/'),a.kb),method='POST')
with urllib.request.urlopen(req,timeout=600) as r: print(json.dumps(json.load(r),ensure_ascii=False,indent=2))
