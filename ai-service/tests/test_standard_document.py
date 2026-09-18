"""Ingest-time normalization: every source format must converge on clean units.

Layout-only content (markdown markers, HTML tags, table alignment rows, front
matter) is what used to leak into card titles and claim subjects. These tests pin
the contract that normalization removes it before anything else reads the source.
"""

import tempfile
from pathlib import Path

import pytest

from app.services.document_parser import DocumentParser
from app.services.extractor import KnowledgeExtractor
from app.services.standard_document import (
    BLOCK_HEADING,
    BLOCK_PARAGRAPH,
    BLOCK_TABLE,
    from_html,
    from_markdown,
    from_text,
)


MARKDOWN = """---
title: 差旅报销标准
tags: [finance]
---

# 差旅报销标准

## 适用范围

本**标准**适用于所有`国内`出差员工。

| 项目 | 标准 |
| --- | --- |
| 市内交通 | 80 元/天 |
| 住宿 | 400 元/天 |

---

- 提交发票原件
- 3 个工作日内审批
"""

MARKDOWN_NOISE = ("#", "**", "|", "---", "```", "title:", "tags:")


def test_markdown_normalizer_drops_format_only_content():
    blocks = from_markdown(MARKDOWN)
    headings = [item for item in blocks if item["block_type"] == BLOCK_HEADING]
    paragraphs = [item for item in blocks if item["block_type"] == BLOCK_PARAGRAPH]
    tables = [item for item in blocks if item["block_type"] == BLOCK_TABLE]

    assert [item["text"] for item in headings][:2] == ["差旅报销标准", "适用范围"]
    assert headings[0]["level"] == 1
    assert headings[1]["level"] == 2
    assert headings[1]["heading_path"] == "差旅报销标准 / 适用范围"
    # Emphasis and inline-code markers are gone, the words remain.
    assert paragraphs[0]["text"] == "本标准适用于所有国内出差员工。"
    # Tables become prose rows: no pipe or alignment separator survives.
    table_text = tables[0]["text"]
    assert "|" not in table_text
    assert "项目: 市内交通；标准: 80 元/天" in table_text
    for block in blocks:
        assert "---" not in block["text"]
        assert "**" not in block["text"]


def test_html_normalizer_uses_tag_semantics_and_drops_scripts():
    html = """<html><body>
        <script>var tracking = 1;</script>
        <style>.x { color: red; }</style>
        <h1>报销流程</h1>
        <p>员工需在 <b>3 个工作日</b> 内提交材料。</p>
        <ul><li>发票原件</li><li>审批单</li></ul>
        <table><tr><th>项目</th><th>标准</th></tr>
        <tr><td>市内交通</td><td>80 元/天</td></tr></table>
    </body></html>"""
    blocks = from_html(html)
    texts = [item["text"] for item in blocks]
    assert not any("script" in text or "color: red" in text for text in texts)
    assert "报销流程" in texts
    assert "员工需在 3 个工作日 内提交材料。" in texts
    assert "发票原件" in texts
    assert "项目: 市内交通；标准: 80 元/天" in texts
    for text in texts:
        assert "<" not in text and ">" not in text


def test_text_normalizer_drops_separator_only_blocks():
    blocks = from_text("第一段内容。\n\n---\n\n第二段内容。\n\n***\n")
    assert [item["text"] for item in blocks] == ["第一段内容。", "第二段内容。"]


def test_parser_produces_standard_model_and_clean_chunks(tmp_path):
    path = tmp_path / "policy.md"
    path.write_text(MARKDOWN, encoding="utf-8")
    result = DocumentParser().parse(str(path), "policy.md")

    standard = result["standard"]
    assert standard["source_format"] == "MARKDOWN"
    assert standard["title"] == "差旅报销标准"
    assert standard["language"] == "zh"
    assert standard["stats"]["units"] >= 2
    assert standard["stats"]["tables"] == 1
    assert result["chunks"][0]["unit_id"] == "U1"
    assert result["chunks"][0]["content"] == standard["units"][0]["content"]

    for chunk in result["chunks"]:
        for marker in MARKDOWN_NOISE:
            assert marker not in chunk["content"], marker
    for marker in MARKDOWN_NOISE:
        assert marker not in result["text"], marker


def test_extractor_cards_and_claims_carry_no_layout_markers():
    units = [{"unit_id": "U1", "heading_path": "差旅报销标准 / 适用范围",
              "content": "本标准适用于所有国内出差员工。"}]
    result = KnowledgeExtractor()._fallback("", units)

    assert result["cards"], "expected at least one card from clean units"
    for card in result["cards"]:
        assert card["title"].strip() == "适用范围"
        assert not any(char in card["title"] for char in "#*|")
        assert "|" not in card["content"]
    for claim in result["claims"]:
        assert claim["subject"].strip() == "适用范围"
        assert not any(char in claim["subject"] for char in "#*|")


def test_extractor_ignores_units_without_meaning():
    units = [
        {"unit_id": "U1", "heading_path": "", "content": "| --- | --- |"},
        {"unit_id": "U2", "heading_path": "标题", "content": "有效内容说明。"},
    ]
    result = KnowledgeExtractor()._fallback("", units)
    assert len(result["cards"]) == 1
    assert result["cards"][0]["title"] == "标题"
