#!/usr/bin/env python3
"""Guard the second-round maintainability refactor against structural regression."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

app = ROOT / "frontend/src/App.vue"
components = ROOT / "frontend/src/components"
required = {
    "DataCenterPanel.vue",
    "ReviewPanel.vue",
    "QueryPanel.vue",
    "KnowledgeListPanel.vue",
    "GraphPanel.vue",
}
missing = [name for name in required if not (components / name).exists()]
assert not missing, "missing frontend responsibility components: " + ", ".join(missing)
assert len(app.read_text(encoding="utf-8").splitlines()) <= 250, "App.vue must remain a thin orchestration component"

mapper = (ROOT / "backend/src/main/java/com/jh/knowledgeos/mapper/KnowledgeMapper.java").read_text(encoding="utf-8")
xml = (ROOT / "backend/src/main/resources/mapper/KnowledgeMapper.xml").read_text(encoding="utf-8")
assert "List<AuthoritativeIndexSnapshotRow> listAuthoritativeIndexSnapshot" in mapper
assert 'resultType="com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow"' in xml

projection = ROOT / "backend/src/main/java/com/jh/knowledgeos/projection/AuthoritativeIndexSnapshotRow.java"
assert projection.exists()
assert "toPayload()" in projection.read_text(encoding="utf-8")
assert (ROOT / "scripts/analyze-maintainability.py").exists()

print("maintainability refactor contract passed: thin App.vue, typed snapshot projection, hotspot analyzer")
