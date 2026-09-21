#!/usr/bin/env python3
"""Static release gate for Phase 19 OKF v0.2 interoperability."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path, tokens):
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise SystemExit("FAIL {} missing {}".format(path, ", ".join(missing)))
    print("PASS", path)


require(
    "ai-service/app/services/okf_bundle.py",
    [
        'consumerVersion": "0.2"',
        "TYPE_REQUIRED",
        "UNKNOWN_OKF_VERSION",
        "human-reviewed",
        "machine-confirmed",
        "BROKEN_LINK",
        "unsafe path",
        "MAX_EXPANDED_BYTES",
        '"extensions"',
    ],
)
require("ai-service/app/routes.py", ["/ai/okf/inspect", "asyncio.to_thread(okf_inspector.inspect"])
require("backend/src/main/java/com/jh/knowledgeos/service/AiClient.java", ["inspectOkf", "INSPECT_OKF"])
require(
    "backend/src/main/java/com/jh/knowledgeos/controller/KnowledgeController.java",
    [
        '"/okf/inspect"',
        "aiClient.inspectOkf(file)",
    ],
)
require(
    "ai-service/tests/test_phase19_okf_v02.py",
    [
        "test_archive_path_traversal_is_rejected",
        "test_unknown_version_and_type_are_best_effort_consumed",
    ],
)
print("Phase 19 OKF v0.2 interoperability contract passed")
