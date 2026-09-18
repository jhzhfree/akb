#!/usr/bin/env python3
"""Repository-level readability and documentation contract.

This is intentionally lightweight: it does not replace Checkstyle/Ruff/ESLint,
but it prevents the readability regressions that previously accumulated in the
RC codebase and can run without downloading additional tooling.
"""

import ast
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

LINE_LIMITS = {
    ".java": 160,
    ".py": 140,
    ".js": 160,
    ".ts": 160,
    ".vue": 180,
}
SOURCE_ROOTS = [
    ROOT / "backend/src/main/java",
    ROOT / "backend/src/test/java",
    ROOT / "ai-service/app",
    ROOT / "ai-service/tests",
    ROOT / "frontend/src",
]


def source_files():
    for base in SOURCE_ROOTS:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if path.is_file() and path.suffix in LINE_LIMITS:
                yield path


def check_text_hygiene():
    long_lines = []
    tabs = []
    trailing = []
    for path in source_files():
        limit = LINE_LIMITS[path.suffix]
        if path.suffix == ".py" and "tests" in path.parts:
            # Test fixtures often contain compact JSON/mock payloads. Keep them
            # readable without forcing production-code line-width strictness.
            limit = 200
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if len(line) > limit:
                long_lines.append((path.relative_to(ROOT), number, len(line), limit))
            if "\t" in line:
                tabs.append((path.relative_to(ROOT), number))
            if line.rstrip() != line:
                trailing.append((path.relative_to(ROOT), number))
    assert not long_lines, "line length regression: %s" % long_lines[:10]
    assert not tabs, "tab indentation is forbidden: %s" % tabs[:10]
    assert not trailing, "trailing whitespace: %s" % trailing[:10]


def check_java_docs_and_architecture():
    java_root = ROOT / "backend/src/main/java"
    missing_docs = []
    for path in java_root.rglob("*.java"):
        if path.name == "package-info.java":
            continue
        text = path.read_text(encoding="utf-8")
        match = re.search(
            r"(?m)^public\s+(?:final\s+)?(?:class|interface|enum)\s+(\w+)",
            text,
        )
        if not match:
            continue
        window = text[max(0, match.start() - 800) : match.start()]
        if "/**" not in window:
            missing_docs.append((path.relative_to(ROOT), match.group(1)))
    assert not missing_docs, "public Java types require class Javadoc: %s" % missing_docs

    package_roots = [
        "common",
        "config",
        "controller",
        "domain",
        "dto",
        "mapper",
        "service",
    ]
    for package in package_roots:
        path = java_root / "com/jh/knowledgeos" / package / "package-info.java"
        assert path.exists(), "missing package documentation: %s" % path.relative_to(ROOT)

    facade = java_root / "com/jh/knowledgeos/service/KnowledgeService.java"
    facade_lines = len(facade.read_text(encoding="utf-8").splitlines())
    assert facade_lines <= 150, "KnowledgeService facade became too large: %d" % facade_lines

    for required in [
        "DocumentIngestionService.java",
        "KnowledgeExtractionService.java",
        "KnowledgeQueryService.java",
        "KnowledgeGovernanceService.java",
        "KnowledgeReadService.java",
        "IndexOutboxService.java",
        "IndexOutboxWorker.java",
        "IndexSchemaService.java",
        "TransactionRunner.java",
    ]:
        path = facade.parent / required
        assert path.exists(), "missing focused service: %s" % required


def check_python_docs_and_safety():
    app_root = ROOT / "ai-service/app"
    missing_module_docs = []
    missing_public_docs = []
    silent_exception = []

    for path in app_root.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        tree = ast.parse(text)
        if ast.get_docstring(tree) is None and path.name != "__init__.py":
            missing_module_docs.append(path.relative_to(ROOT))

        for node in tree.body:
            if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
                if node.name.startswith("_"):
                    continue
                if ast.get_docstring(node) is None:
                    missing_public_docs.append((path.relative_to(ROOT), node.name))

        # Silent broad-exception handling makes external-provider fallback
        # impossible to diagnose. Narrow exceptions or log the reason.
        for node in ast.walk(tree):
            if not isinstance(node, ast.ExceptHandler) or node.type is None:
                continue
            if isinstance(node.type, ast.Name) and node.type.id == "Exception":
                if len(node.body) == 1 and isinstance(node.body[0], ast.Pass):
                    silent_exception.append((path.relative_to(ROOT), node.lineno))

    assert not missing_module_docs, "Python modules require docstrings: %s" % missing_module_docs
    assert not missing_public_docs, "public Python symbols require docstrings: %s" % missing_public_docs
    assert not silent_exception, "silent broad exception handlers: %s" % silent_exception

    for provider_file in [
        "services/reranker.py",
        "services/graph_provider.py",
        "services/embedding.py",
        "services/llm_provider.py",
        "services/retrieval/elasticsearch_provider.py",
        "services/retrieval/milvus_provider.py",
    ]:
        text = (app_root / provider_file).read_text(encoding="utf-8")
        # Providers must be cached per process; configuration-aware providers cache
        # per configuration instead, so any bounded lru_cache satisfies the rule.
        assert re.search(r"@lru_cache\(maxsize=\d+\)", text), (
            "provider must be process cached: %s" % provider_file
        )


def check_frontend_boundaries():
    app = (ROOT / "frontend/src/App.vue").read_text(encoding="utf-8")
    assert "axios." not in app, "App.vue must call centralized API helpers, not axios directly"
    assert "Promise.allSettled" in app, "review workspace should isolate independent endpoint failures"


def main():
    check_text_hygiene()
    check_java_docs_and_architecture()
    check_python_docs_and_safety()
    check_frontend_boundaries()
    print(
        "code quality contract passed: java<=160 python<=140 js<=160 vue<=180; "
        "public docs/package docs/provider lifetime verified"
    )


if __name__ == "__main__":
    main()
