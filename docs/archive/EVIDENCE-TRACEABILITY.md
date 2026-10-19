# 知识版本与证据溯源 · 合订

把"知识是什么 / 以前是什么 / 为什么会变 / 证据在哪"四个问题落到产品里的两轮记录。

| 轮次 | 主题 |
|---|---|
| 1 | Knowledge Version + Evidence Traceability（不可变版本快照） |
| 2 | Evidence Viewer + Cross Navigation（问答↔知识↔证据↔来源追溯链） |



---

> **来源**：`docs/KNOWLEDGE-VERSION-EVIDENCE-TRACEABILITY.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Knowledge Version + Evidence Traceability

### 目标
让知识详情回答四个问题：当前知识是什么、以前是什么、为什么变化、证据原文在哪里。

### V9 权威版本快照
新增 `t_knowledge_version`，对 CARD/CLAIM 保存不可变快照。版本链使用 `version_no` 与 `supersedes_version_no`，不把检索索引版本当成知识业务版本。

自动快照时点：
- AI 抽取创建：`CREATED`
- 智能裁决：`AI_ADJUDICATION`
- 人工复核：`HUMAN_REVIEW`
- 冲突检测改变 Claim 状态：`CONFLICT_DETECTED`

### Evidence 原文定位
详情 Evidence 增加：
`sourceName → documentTitle/documentCode → pageNo → headingPath → contentUnitCode → quoteText`

这些字段来自既有 Source / Document / ContentUnit / Evidence 权威链，不创建第二份原文。

### 前端
新增 `VersionDiffPanel.vue`：
- 版本时间线
- SUPERSEDES 链
- 标题/摘要/正文/状态差异
- Evidence 文档、页码、标题路径和 ContentUnit 定位



---

> **来源**：`docs/EVIDENCE-VIEWER-CROSS-NAVIGATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Evidence Viewer + Cross Navigation

### 目标
把问答、知识、证据、来源真正串成一条可操作的追溯链，而不是只展示引用文字。

### 导航
- Knowledge Detail → Evidence Viewer
- Query Citation → ContentUnit / Evidence Viewer
- Query Citation → Card / Claim（当检索结果提供 objectType/objectId）
- Evidence Viewer → Source → Document → Page → Heading → ContentUnit

### Evidence Viewer
展示权威 ContentUnit 原文，并对 Evidence quote 做 HTML 转义后精确高亮。
不复制 Source/Document 原文，不建立第二套事实源。

### API
- `GET /api/knowledge/evidence/{kb}/{evidenceCode}`
- `GET /api/knowledge/content-unit/{kb}/{contentUnitId}`

第二个接口用于 Query Retrieval Citation：检索层通常直接携带 contentUnitId，不要求先知道 evidenceCode。

### AI Citation Contract
Query Engine citation 补充：
- objectId
- objectType
- contentUnitId
- sourceName/sourceUri/pageNo

前端优先级：
EvidenceCode → ContentUnitId → CARD/CLAIM Object → 无定位提示。

### 安全
Evidence Detail 和 ContentUnit Detail 均带 knowledge_base_id 约束，避免跨知识库直接按 ID 读取。
正式生产还必须继续叠加用户 ACL/permissionTags 校验；本轮不把 KB 边界等同于最终 ACL。

### 后续
- Document PDF/DOCX/TXT 专用预览器
- PDF page canvas 精确跳页与 quote overlay
- Citation ↔ Evidence ↔ Knowledge ↔ Graph 双向 breadcrumb
- Query Trace score waterfall
