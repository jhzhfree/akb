# Knowledge Version + Evidence Traceability

## 目标
让知识详情回答四个问题：当前知识是什么、以前是什么、为什么变化、证据原文在哪里。

## V9 权威版本快照
新增 `t_knowledge_version`，对 CARD/CLAIM 保存不可变快照。版本链使用 `version_no` 与 `supersedes_version_no`，不把检索索引版本当成知识业务版本。

自动快照时点：
- AI 抽取创建：`CREATED`
- 智能裁决：`AI_ADJUDICATION`
- 人工复核：`HUMAN_REVIEW`
- 冲突检测改变 Claim 状态：`CONFLICT_DETECTED`

## Evidence 原文定位
详情 Evidence 增加：
`sourceName → documentTitle/documentCode → pageNo → headingPath → contentUnitCode → quoteText`

这些字段来自既有 Source / Document / ContentUnit / Evidence 权威链，不创建第二份原文。

## 前端
新增 `VersionDiffPanel.vue`：
- 版本时间线
- SUPERSEDES 链
- 标题/摘要/正文/状态差异
- Evidence 文档、页码、标题路径和 ContentUnit 定位
