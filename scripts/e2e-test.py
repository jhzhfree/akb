#!/usr/bin/env python3
import json,os,time,urllib.request,uuid
BASE=os.getenv("E2E_BACKEND_URL","http://127.0.0.1:8080");TRACE="e2e-"+uuid.uuid4().hex[:16]
AUTH_TOKEN=os.getenv("E2E_AUTH_TOKEN","")
def auth_enabled():
 try:
  with urllib.request.urlopen(urllib.request.Request(BASE+"/api/auth/status",headers={"X-Trace-Id":TRACE}),timeout=30) as x:
   return bool((json.loads(x.read().decode()).get("data") or {}).get("enabled"))
 except Exception:return False
def login():
 """后端开启认证时先取会话 token，未配置密码则交给匿名/AUTH_ALLOW_ANONYMOUS 模式。"""
 global AUTH_TOKEN
 if not auth_enabled() or AUTH_TOKEN:return
 pwd=os.getenv("E2E_AUTH_PASSWORD","")
 if not pwd:return
 body=json.dumps({"username":os.getenv("E2E_AUTH_USERNAME","admin"),"password":pwd}).encode()
 r=urllib.request.Request(BASE+"/api/auth/login",data=body,headers={"Content-Type":"application/json","X-Trace-Id":TRACE},method="POST")
 with urllib.request.urlopen(r,timeout=30) as x:AUTH_TOKEN=(json.loads(x.read().decode()).get("data") or {}).get("token","")
login()
def req(method,path,data=None,headers=None):
 h={"X-Trace-Id":TRACE}
 if AUTH_TOKEN:h["Authorization"]="Bearer "+AUTH_TOKEN
 h.update(headers or {});body=None if data is None else json.dumps(data).encode();
 if body:h["Content-Type"]="application/json"
 r=urllib.request.Request(BASE+path,data=body,headers=h,method=method)
 with urllib.request.urlopen(r,timeout=180) as x:return x.status,dict(x.headers),json.loads(x.read().decode())
def upload():
 b="----llmkb"+uuid.uuid4().hex;parts=[]
 def add(x):parts.append(x.encode() if isinstance(x,str) else x)
 add("--"+b+"\r\nContent-Disposition: form-data; name=\"knowledgeBaseId\"\r\n\r\n1\r\n");add("--"+b+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"e2e.txt\"\r\nContent-Type: text/plain\r\n\r\n");add(b"Tomcat 8.5 supports PKCS12 certificate configuration. Production changes require review.\n");add("\r\n--"+b+"--\r\n")
 hdrs={"Content-Type":"multipart/form-data; boundary="+b,"X-Trace-Id":TRACE}
 if AUTH_TOKEN:hdrs["Authorization"]="Bearer "+AUTH_TOKEN
 r=urllib.request.Request(BASE+"/api/knowledge/documents/upload",data=b"".join(parts),headers=hdrs,method="POST")
 with urllib.request.urlopen(r,timeout=180) as x:return x.status,dict(x.headers),json.loads(x.read().decode())
status,h,u=upload();assert status==200 and u["success"],u
if os.getenv("E2E_REQUIRE_EXTERNAL_RETRIEVAL", "false").lower()=="true": time.sleep(2.0)
status,h,q=req("POST","/api/knowledge/query",{"knowledgeBaseId":1,"query":"Tomcat 是否支持 PKCS12？","topK":5});assert q["success"],q;qid=q["data"]["queryId"];assert qid and q["data"].get("trace") is not None
status,h,t=req("GET","/api/knowledge/trace/"+qid);assert t["success"] and t["data"]["queryId"]==qid,t
if os.getenv("E2E_REQUIRE_EXTERNAL_RETRIEVAL", "false").lower()=="true":
 providers=(q["data"].get("trace") or {}).get("retrievalProviders") or {}
 flat={p for values in providers.values() for p in values}
 assert "milvus" in flat, providers
 assert "elasticsearch" in flat, providers
print("E2E passed traceId=%s queryId=%s"%(TRACE,qid))
