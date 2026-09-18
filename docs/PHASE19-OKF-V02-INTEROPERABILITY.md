# Phase 19 — OKF v0.2 互操作接入

本阶段将 Open Knowledge Format 作为交换与检查层接入，内部 MySQL 权威模型、ACL、证据绑定和答案资格门禁保持不变。

## API

`POST /api/knowledge/okf/inspect`，multipart 字段为 `file`。服务返回 bundle 哈希、规范版本、conformance、概念、来源、链接、扩展字段、派生 trust tier 和诊断项。

## 语义边界

- `verified` 派生的 trust tier 仅是可信度提示，禁止映射成访问权限。
- 未声明 `status` 时按 `stable` 解释；未知状态保留并告警。
- 未知 concept type、未知 frontmatter 字段和断链不导致消费者拒绝整个 bundle。
- 非保留 Markdown 缺失 `type`、frontmatter 非法或非 UTF-8 会导致 conformance 失败。
- `stale_after` 必须包含 UTC offset；陈旧只标记，不自动删除知识。

## 安全限制

入口拒绝 ZIP 路径穿越、非 ZIP、超过 25 MiB 的压缩包、超过 100 MiB 的展开内容、超过 5000 个文件以及超过 2 MiB 的单文件。
