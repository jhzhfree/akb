#!/usr/bin/env python3
"""Report maintainability hotspots without requiring third-party analyzers."""

from __future__ import annotations

import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DECISION_NODES = (ast.If, ast.For, ast.AsyncFor, ast.While, ast.Try, ast.BoolOp, ast.IfExp, ast.Match)


def python_hotspots():
    rows = []
    for path in (ROOT / "ai-service/app").rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                complexity = 1 + sum(isinstance(child, DECISION_NODES) for child in ast.walk(node))
                lines = getattr(node, "end_lineno", node.lineno) - node.lineno + 1
                if complexity >= 8 or lines >= 60:
                    rows.append((complexity, lines, str(path.relative_to(ROOT)), node.name))
    return sorted(rows, reverse=True)


def large_files():
    rows = []
    for suffix, base in [
        (".java", ROOT / "backend/src/main/java"),
        (".py", ROOT / "ai-service/app"),
        (".vue", ROOT / "frontend/src"),
    ]:
        for path in base.rglob(f"*{suffix}"):
            lines = len(path.read_text(encoding="utf-8").splitlines())
            if lines >= 250:
                rows.append((lines, str(path.relative_to(ROOT))))
    return sorted(rows, reverse=True)


print("Maintainability hotspot report")
print("\nLarge production files (>=250 lines):")
for lines, path in large_files():
    print(f"  {lines:4d}  {path}")
print("\nPython functions requiring review (complexity>=8 or lines>=60):")
for complexity, lines, path, name in python_hotspots():
    print(f"  cc~{complexity:2d} lines={lines:3d}  {path}::{name}")
