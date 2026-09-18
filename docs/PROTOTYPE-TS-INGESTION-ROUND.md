# 上传原型融合第二轮：TypeScript + 知识库管理 + 摄入全过程

## 决策
前端继续使用 Vue 3 + Element Plus，不引入 React 双栈；但代码格式全面迁移 TypeScript，并参考上传原型的紧凑白色工作台、ink 灰阶、indigo 焦点、知识库切换和摄入 Timeline。

## 前端
- `src/main.ts`
- `src/types.ts`
- `src/api/knowledge.ts`
- `vite.config.ts`
- 所有 Vue SFC 使用 `<script setup lang="ts">`
- `KnowledgeBaseDialog.vue`：知识库切换/新建。
- `IngestionLogPanel.vue`：运行列表 + 生命周期事件时间线。
- `DataCenterPanel.vue`：重构为上传区、原始资料、运行日志、解析文档四个区域。

## 后端
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

## API
- `GET /api/knowledge/bases`
- `POST /api/knowledge/bases`
- `POST /api/knowledge/bases/{id}/archive`
- `GET /api/knowledge/ingestion/{kb}/runs`
- `GET /api/knowledge/ingestion/runs/{runId}`

## 设计边界
知识库删除采用归档，不物理删除，避免 Source/Card/Claim/Outbox/Index Release 等权威数据链被破坏。
摄入日志进入 MySQL 审计模型，不照搬原型 JSONL 文件日志。

## Hybrid UI Design System（本轮追加）
正式前端采用 **Vue 3 + TypeScript + Tailwind CSS + Element Plus**：
- Tailwind：页面布局、知识卡片、指标、Timeline、问答、Evidence、工作台视觉语言。
- Element Plus：Upload、Dialog、Form、InputNumber、Table、Message 等成熟业务组件。
- 不引入 React 双栈；参考原型的 `ink` 色板和 indigo focus 已迁移到 Tailwind theme。
- `tailwind.config.js` content 覆盖 `vue/ts`，避免生产 purge 丢失 Vue 模板类。
