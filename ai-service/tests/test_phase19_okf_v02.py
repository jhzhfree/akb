import io
import zipfile

import pytest

from app.services.okf_bundle import OkfBundleError, OkfBundleInspector


def bundle(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, body in files.items():
            archive.writestr(name, body)
    return output.getvalue()


def test_okf_v02_normalizes_trust_lifecycle_links_and_extensions():
    content = bundle({
        "knowledge/index.md": "---\nokf_version: '0.2'\n---\n# Index\n",
        "knowledge/policy/access.md": """---
type: Policy
title: Access policy
verified:
  - agent/reviewer-2
  - human:alice
status: draft
stale_after: '2099-01-01T00:00:00+00:00'
vendor_extension: retained
---
See [control](../control.md) and [missing](missing.md).
""",
        "knowledge/control.md": "---\ntype: Control\n---\n# Control\n",
    })
    result = OkfBundleInspector().inspect(content)
    concept = next(item for item in result["concepts"] if item["conceptId"] == "policy/access")
    assert result["specVersion"] == "0.2"
    assert result["conformant"] is True
    assert concept["trustTier"] == "human-reviewed"
    assert concept["status"] == "draft"
    assert concept["stale"] is False
    assert concept["extensions"]["vendor_extension"] == "retained"
    assert [link["exists"] for link in concept["links"]] == [True, False]
    assert any(item["code"] == "BROKEN_LINK" for item in result["warnings"])


def test_missing_type_is_a_conformance_error_but_file_is_tolerated():
    result = OkfBundleInspector().inspect(bundle({"note.md": "---\ntitle: Note\n---\ntext"}))
    assert result["conformant"] is False
    assert result["errors"][0]["code"] == "TYPE_REQUIRED"


def test_archive_path_traversal_is_rejected():
    with pytest.raises(OkfBundleError, match="unsafe path"):
        OkfBundleInspector().inspect(bundle({"../escape.md": "---\ntype: Note\n---\n"}))


def test_unknown_version_and_type_are_best_effort_consumed():
    result = OkfBundleInspector().inspect(bundle({
        "index.md": "---\nokf_version: '9.9'\n---\n",
        "x.md": "---\ntype: FutureType\nfuture: true\n---\n",
    }))
    assert result["conformant"] is True
    assert result["concepts"][0]["type"] == "FutureType"
    assert any(item["code"] == "UNKNOWN_OKF_VERSION" for item in result["warnings"])
