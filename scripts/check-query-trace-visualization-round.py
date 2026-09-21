from pathlib import Path

R = Path(__file__).resolve().parents[1]
panel = (R / "frontend/src/components/QueryTracePanel.vue").read_text()
pipe = (R / "frontend/src/components/RetrievalPipeline.vue").read_text()
app = (R / "frontend/src/App.vue").read_text()
engine = (R / "ai-service/app/services/query_engine.py").read_text()
types = (R / "frontend/src/types.ts").read_text()
for token in ["RetrievalPipeline", "recallCounts", "selectedCount", "open-candidate"]:
    assert token in panel
for token in ["RECALL → RRF → RERANK → CONTEXT", "rerankScore", "open-candidate"]:
    assert token in pipe
assert '@open-candidate="openCitation"' in app
for token in ["objectType", "contentUnitId", "sourceName", "pageNo"]:
    assert token in engine
assert "interface TraceCandidate" in types
print("query trace visualization + candidate navigation contract passed")
