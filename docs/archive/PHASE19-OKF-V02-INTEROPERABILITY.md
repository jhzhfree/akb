# Phase 19：OKF v0.2 互操作与安全接入报告

## 结论

当前源码已在 Phase 18 基线上完成 Open Knowledge Format v0.2 接入。OKF 被限定为知识交换与检查层，不替代 MySQL 权威数据、ACL、证据图谱或答案资格门禁。

## 实现范围

- 新增 OKF ZIP Bundle 检查器：解析 Markdown/YAML frontmatter，输出稳定 Concept ID 和规范化概念。
- 按 v0.2 派生 `unverified`、`machine-confirmed`、`human-reviewed` 信任层级，但不映射成 ACL。
- 支持 `draft`、`stable`、`deprecated` 生命周期和带时区的 `stale_after`。
- 解析 Bundle 内 Markdown 链接并报告断链；断链、未知类型、未知字段、未知版本采用 best-effort 消费。
- 保留未知 frontmatter 字段，避免消费者破坏未来扩展。
- 增加压缩包大小、展开大小、文件数、单文件大小和路径穿越保护。
- 新增 AI API `POST /ai/okf/inspect` 与后端 API `POST /api/knowledge/okf/inspect`。
- 新增 Phase 19 单元测试和发布静态门禁，并接入 `check-v11.sh`。

## 关键边界

OKF 的来源、生成者和核验者属于可移植的可信度信号。企业权限仍以现有权威 ACL 为准；OKF trust tier 不授予访问权。导入检查也不直接发布知识，后续仍需经过现有清洗、抽取、人工裁决、版本化和索引发布链路。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Python compileall | 通过 |
| OKF v0.2 运行断言 | 通过 |
| Phase 19 静态契约 | 通过 |
| 代码质量契约 | 通过 |
| Phase 18 回归契约 | 9/9 通过 |
| 全量 `check-v11.sh` | 环境阻塞 |

全量门禁在第 2/44 步因运行环境未安装 `beautifulsoup4` 而停止。失败发生在既有文档解析器导入阶段，不是 Phase 19 断言失败。工程依赖文件已声明 `beautifulsoup4==4.12.3`，并新增 `PyYAML==6.0.2`。

## 下一轮建议

Phase 20 可在此基础上实现 OKF 导入暂存区：将通过检查的概念映射为摄入候选，记录 Bundle 哈希与来源快照，经现有裁决链路后再进入权威知识对象；同时增加从权威对象导出 OKF Bundle 的能力。

---

## 附录：OKF 接入要点摘要（API / 语义边界 / 安全限制）

> 来源 `docs/PHASE19-OKF-V02-INTEROPERABILITY.md`（历史路径，已并入本文档）。

## Phase 19 — OKF v0.2 互操作接入

本阶段将 Open Knowledge Format 作为交换与检查层接入，内部 MySQL 权威模型、ACL、证据绑定和答案资格门禁保持不变。

### API

`POST /api/knowledge/okf/inspect`，multipart 字段为 `file`。服务返回 bundle 哈希、规范版本、conformance、概念、来源、链接、扩展字段、派生 trust tier 和诊断项。

### 语义边界

- `verified` 派生的 trust tier 仅是可信度提示，禁止映射成访问权限。
- 未声明 `status` 时按 `stable` 解释；未知状态保留并告警。
- 未知 concept type、未知 frontmatter 字段和断链不导致消费者拒绝整个 bundle。
- 非保留 Markdown 缺失 `type`、frontmatter 非法或非 UTF-8 会导致 conformance 失败。
- `stale_after` 必须包含 UTC offset；陈旧只标记，不自动删除知识。

### 安全限制

入口拒绝 ZIP 路径穿越、非 ZIP、超过 25 MiB 的压缩包、超过 100 MiB 的展开内容、超过 5000 个文件以及超过 2 MiB 的单文件。
