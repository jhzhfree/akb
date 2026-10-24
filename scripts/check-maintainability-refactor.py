#!/usr/bin/env python3
"""Guard the second-round maintainability refactor against structural regression."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

app = ROOT / "frontend/src/App.vue"
components = ROOT / "frontend/src/components"
required = {
    "WorkbenchPage.vue",
    "GeneratePage.vue",
    "SearchPage.vue",
}
missing = [name for name in required if not (components / name).exists()]
assert not missing, "missing frontend responsibility components: " + ", ".join(missing)
app_source = app.read_text(encoding="utf-8")
for component in ("WorkbenchPage", "GeneratePage", "SearchPage"):
    assert component in app_source, "App.vue must route to the current page component: " + component

mapper = (ROOT / "backend/src/main/java/com/jh/knowledgeos/mapper/IndexMapper.java").read_text(encoding="utf-8")
xml = (ROOT / "backend/src/main/resources/mapper/IndexMapper.xml").read_text(encoding="utf-8")
assert "List<AuthoritativeIndexSnapshotRow> listAuthoritativeIndexSnapshot" in mapper
assert 'resultType="com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow"' in xml

projection = ROOT / "backend/src/main/java/com/jh/knowledgeos/projection/AuthoritativeIndexSnapshotRow.java"
assert projection.exists()
assert "toPayload()" in projection.read_text(encoding="utf-8")
assert (ROOT / "scripts/analyze-maintainability.py").exists()

print("maintainability refactor contract passed: page-level composition, typed snapshot projection, hotspot analyzer")
