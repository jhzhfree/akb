import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
pkg = json.loads((root / "frontend/package.json").read_text())
for dep in ["tailwindcss", "postcss", "autoprefixer"]:
    assert dep in pkg["devDependencies"], dep
cfg = (root / "frontend/tailwind.config.js").read_text()
assert "./src/**/*.{vue,ts}" in cfg
css = (root / "frontend/src/tailwind.css").read_text()
for token in ["@tailwind base", "kb-card", "kb-btn-primary", "kb-metric"]:
    assert token in css, token
main = (root / "frontend/src/main.ts").read_text()
assert "./tailwind.css" in main and "element-plus" in main
for name in ["OverviewPanel.vue", "QueryPanel.vue", "DataCenterPanel.vue"]:
    text = (root / "frontend/src/components" / name).read_text()
    assert "kb-" in text, name
print("hybrid Vue + TypeScript + Tailwind + Element Plus UI contract passed")
