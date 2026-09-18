# Evidence Viewer + Cross Navigation

## 目标
把问答、知识、证据、来源真正串成一条可操作的追溯链，而不是只展示引用文字。

## 导航
- Knowledge Detail → Evidence Viewer
- Query Citation → ContentUnit / Evidence Viewer
- Query Citation → Card / Claim（当检索结果提供 objectType/objectId）
- Evidence Viewer → Source → Document → Page → Heading → ContentUnit

## Evidence Viewer
展示权威 ContentUnit 原文，并对 Evidence quote 做 HTML 转义后精确高亮。
不复制 Source/Document 原文，不建立第二套事实源。

## API
- `GET /api/knowledge/evidence/{kb}/{evidenceCode}`
- `GET /api/knowledge/content-unit/{kb}/{contentUnitId}`

第二个接口用于 Query Retrieval Citation：检索层通常直接携带 contentUnitId，不要求先知道 evidenceCode。

## AI Citation Contract
Query Engine citation 补充：
- objectId
- objectType
- contentUnitId
- sourceName/sourceUri/pageNo

前端优先级：
EvidenceCode → ContentUnitId → CARD/CLAIM Object → 无定位提示。

## 安全
Evidence Detail 和 ContentUnit Detail 均带 knowledge_base_id 约束，避免跨知识库直接按 ID 读取。
正式生产还必须继续叠加用户 ACL/permissionTags 校验；本轮不把 KB 边界等同于最终 ACL。

## 后续
- Document PDF/DOCX/TXT 专用预览器
- PDF page canvas 精确跳页与 quote overlay
- Citation ↔ Evidence ↔ Knowledge ↔ Graph 双向 breadcrumb
- Query Trace score waterfall
