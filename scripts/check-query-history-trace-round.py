from pathlib import Path

R = Path(__file__).resolve().parents[1]
controller = (R / "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeQueryController.java").read_text()
mapper = (R / "backend/src/main/resources/mapper/TraceMapper.xml").read_text()
app = (R / "frontend/src/App.vue").read_text()
api = (R / "frontend/src/api/knowledge-impl.ts").read_text()
for token in ["/traces/{kb}", "queryHistory(kb, page, pageSize)"]:
    assert (
        token in controller
        or token in (R / "backend/src/main/java/com/jh/knowledgeos/service/KnowledgeService.java").read_text()
    )
assert 'id="listQueryTraces"' in mapper
for token in ["getQueryHistory", "getTrace"]:
    assert token in api
for token in ["QueryTracePanel", "loadQueryHistory", "openTrace"]:
    assert token in app
assert (R / "frontend/src/components/QueryTracePanel.vue").exists()
assert (R / "frontend/src/components/TraceStage.vue").exists()
assert "KnowledgeObjectDetail,\n    decision" not in app
print("query history + trace workspace contract passed")
