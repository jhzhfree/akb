# 上传原型 llmwiki 融合 · 决策记录合订

前端 TS 化与知识库管理 / 摄入 UI 借鉴上传原型的两轮决策。

| 轮次 | 决策 |
|---|---|
| 1 | 上传原型工程融合实现说明（React+TS→Vue 借鉴） |
| 2 | 前端全面 TS 化 + 知识库管理 + 摄入全过程 |



---

> **来源**：`docs/REFERENCE-PROTOTYPE-INTEGRATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## 上传原型工程融合实现说明

### 决策
上传工程 `llmwiki` 的价值主要在产品交互与知识工作流：项目/知识库工作台、概览、原始资料与摄入、知识页面/断言、裁决、图谱、问答、过程可观测。其实现为 React + TypeScript + FastAPI + 文件型存储。

本工程已有更适合企业部署的 Vue 3 + Element Plus、Spring Boot 2.7 / Java 8、MyBatis/MySQL、Python AI Service、Elasticsearch、Milvus、Neo4j、Transactional Outbox、蓝绿索引与治理模型。因此不直接搬运 React/FastAPI 代码，而采用“复用产品原型，保留企业后端”的融合方案。

### 原型 → 本工程映射

| 原型 | 本工程实现 |
|---|---|
| ProjectPicker / 项目工作台 | Knowledge Base ID 工作区，可继续扩展 t_knowledge_base CRUD |
| OverviewView | `OverviewPanel.vue` + `/api/knowledge/overview/{kb}` |
| IngestView / Sources | `DataCenterPanel.vue`，Source + Document + ContentUnit 流程 |
| Wiki Pages | Knowledge Card + Claim，避免再建立平行 Markdown 真相源 |
| AdjudicationView | 复用 ReviewPanel + AI Judge + Human Review |
| GraphView | 复用 Neo4j GraphProvider / GraphPanel |
| ChatView | QueryPanel + QueryRouter + ES/Milvus/Graph + RRF/Reranker |
| IngestLog | 现阶段由 QueryTrace / Outbox / Assessment 可观测；后续建议增加 ingestion run trace，而非复制文件 JSONL |
| 本地 embeddings.json | Milvus |
| search_index.json | Elasticsearch |
| 文件 wiki | MySQL authoritative state + rebuildable retrieval views |

### 新增前端信息架构

顶部工作区导航：概览、资料与摄入、知识卡片、断言、知识裁决、知识图谱、智能问答、索引运维。

交互风格参考上传原型：浅色工作台、紧凑顶部导航、指标卡、快速入口、原始资料/生成结果并列、状态 badge；仍使用 Element Plus，避免引入 Tailwind/React 双技术栈。

### 后端改造

新增轻量 `overview` read model。它只组合现有 MySQL authoritative reads，不创建新的 dashboard 冗余表，也不绕过 KnowledgeService / KnowledgeReadService 分层。

知识页面没有照搬为文件 Markdown，因为当前设计已经把 Card / Claim / Evidence 作为一等知识对象。这样可保持：Knowledge cannot exist without evidence、裁决状态、ACL、版本、Outbox、一致性重建。

### 后续建议

1. 为 `t_knowledge_base` 增加正式 CRUD 与知识库选择器，替代当前数字 ID 输入。
2. 增加 `t_ingestion_run/t_ingestion_event`，把原型中很好的“摄入过程日志”升级为数据库可审计流水，而不是 JSONL。
3. Query Trace 增加列表 API，前端形成原型式问答历史/检索解释视图。
4. Knowledge Card 增加详情页：Evidence、Claim、Graph neighborhood、Version、Review history 同屏。
5. GraphPanel 后续可借鉴原型 Canvas/D3 的交互，但数据源继续使用 Neo4j。



---

> **来源**：`docs/PROTOTYPE-TS-INGESTION-ROUND.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## 上传原型融合第二轮：TypeScript + 知识库管理 + 摄入全过程

### 决策
前端继续使用 Vue 3 + Element Plus，不引入 React 双栈；但代码格式全面迁移 TypeScript，并参考上传原型的紧凑白色工作台、ink 灰阶、indigo 焦点、知识库切换和摄入 Timeline。

### 前端
- `src/main.ts`
- `src/types.ts`
- `src/api/knowledge.ts`
- `vite.config.ts`
- 所有 Vue SFC 使用 `<script setup lang="ts">`
- `KnowledgeBaseDialog.vue`：知识库切换/新建。
- `IngestionLogPanel.vue`：运行列表 + 生命周期事件时间线。
- `DataCenterPanel.vue`：重构为上传区、原始资料、运行日志、解析文档四个区域。

### 后端
新增 Flyway V8：
- `t_knowledge_base.description`
- `t_ingestion_run`
- `t_ingestion_event`

新增：
- `KnowledgeBaseService`
- `IngestionObservabilityService`
- `KnowledgeBase`
- `IngestionRun`
- `KnowledgeBaseRequest`

摄入事件：
`SOURCE_RECEIVED → PARSE_STARTED/COMPLETED → PERSIST_STARTED/COMPLETED → INDEX_EVENT_CREATED → EXTRACTION_STARTED/COMPLETED → INGESTION_COMPLETED/FAILED`

### API
- `GET /api/knowledge/bases`
- `POST /api/knowledge/bases`
- `POST /api/knowledge/bases/{id}/archive`
- `GET /api/knowledge/ingestion/{kb}/runs`
- `GET /api/knowledge/ingestion/runs/{runId}`

### 设计边界
知识库删除采用归档，不物理删除，避免 Source/Card/Claim/Outbox/Index Release 等权威数据链被破坏。
摄入日志进入 MySQL 审计模型，不照搬原型 JSONL 文件日志。

### Hybrid UI Design System（本轮追加）
正式前端采用 **Vue 3 + TypeScript + Tailwind CSS + Element Plus**：
- Tailwind：页面布局、知识卡片、指标、Timeline、问答、Evidence、工作台视觉语言。
- Element Plus：Upload、Dialog、Form、InputNumber、Table、Message 等成熟业务组件。
- 不引入 React 双栈；参考原型的 `ink` 色板和 indigo focus 已迁移到 Tailwind theme。
- `tailwind.config.js` content 覆盖 `vue/ts`，避免生产 purge 丢失 Vue 模板类。
