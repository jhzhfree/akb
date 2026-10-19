# P2 架构收敛报告 · 合订

Mapper / Controller / Service 三层物理拆分的收官记录。两篇合订。

| 轮次 | 范围 |
|---|---|
| 1 | P2.6–P2.12 |
| 2 | P2.13–P2.15（`KnowledgeMapper` 终拆 + 中间件治理） |



---

> **来源**：`docs/P2.6-P2.12-REFACTOR-REPORT.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## P2.6–P2.12 架构收敛报告

### P2.6 Mapper 物理拆分
- 新增 `CardClaimMapper`：Card / Claim / Evidence binding / 生命周期统计。
- 新增 `TraceMapper`：Query Trace / Answer Qualification / Claim Assessment。
- `EvidenceMapper` 接管 Evidence 写入。
- 对应 MyBatis XML 已从 `KnowledgeMapper.xml` 物理迁移，避免同一 statement 重复注册。

### P2.7 Controller 领域拆分
- 新增 `KnowledgeSettingsController`。
- 新增 `KnowledgeLifecycleController`。
- 新增 `EntityGovernanceController`。
- URL 保持 `/api/knowledge/**` 兼容，不要求前端改路由。

### P2.8 Service Orchestration
- 新增 `IngestionOrchestrator`，隔离上传/重试编排入口。
- 新增 `KnowledgeGenerationOrchestrator`，隔离知识抽取/生成入口。
- `KnowledgeService` 继续作为兼容 façade，但不再直接依赖两个重型实现类。

### P2.9 Frontend API / Types 领域入口
- 原 API 实现迁移为 `api/knowledge-impl.ts`。
- 新增 `api/knowledge/base.ts`、`retrieval.ts`、`governance.ts`、`operations.ts`。
- `api/knowledge.ts` 保留兼容 barrel，现有页面无需一次性迁移。
- 原类型实现迁移为 `types-legacy.ts`，新增 `types/common.ts`、`knowledge.ts`、`retrieval.ts`、`governance.ts`，为渐进式领域迁移建立边界。

### P2.10 Frontend 大组件收敛
- `ExternalServiceLab.vue` 的 JSON/响应体格式化工具抽离到 `components/external-service/lab-utils.ts`。
- 后续 REST/MCP/SSE/Bus 子面板可以在此边界继续拆分，避免一次性重写带来的 UI 回归。

### P2.11 API Contract 强类型化
- 新增 `api/contracts.ts`。
- `retrieveKnowledge`、`answerKnowledge`、`retryDocument`、`runAdjudication` 改为明确 request/result contract，减少核心链路 `Record<string, unknown>`。

### P2.12 Test + CI Gate
- 新增 `scripts/check-p2-architecture.sh`，防止核心 statement 回流 God Mapper。
- CI 前端改为 `npm ci + format:check + lint + typecheck + build`。
- 保留后端 Checkstyle、单测、集成测试、AI smoke、Compose/E2E gate。

### 本地验证说明
本轮已执行 Mapper statement 唯一性/归属静态检查与 P2 architecture gate。当前执行容器没有 Maven；前端依赖安装在容器中超时，因此完整 Maven/TypeScript 构建应由 CI 或具备依赖缓存的开发环境最终确认。



---

> **来源**：`docs/P2.13-P2.15-ARCHITECTURE-CLOSURE.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## P2.13–P2.15 架构收敛报告

### P2.13 KnowledgeMapper 最终物理拆分

原 `KnowledgeMapper` 仍包含 132 个 statement contract、743 行 Java 与 637 行 XML。本轮将剩余 statement 按领域物理迁移到独立 Mapper namespace：

- `AclPersistenceMapper`
- `KnowledgeBaseMapper`
- `IngestionMapper`
- `KnowledgeObjectMapper`
- `GraphReadMapper`
- `PurgeMapper`
- `EvaluationMapper`
- `ContentUnitMapper`
- `TaskMapper`
- `AlertMapper`
- `DerivationMapper`
- `SchemaMapper`
- `KnowledgeCoreMapper`（仅保留少量跨域统计兼容项）

`KnowledgeMapper` 现在只作为兼容 composition interface，8 行；`KnowledgeMapper.xml` 只保留空 namespace 壳，5 行。现有 Service 无需 flag-day 修改构造器，后续新代码应直接注入 focused mapper。

### P2.14 KnowledgeController 最终领域拆分

原 565 行 `KnowledgeController` 的 61 个 endpoint 已拆分为：

- `KnowledgeIngestionController`：知识库、摄入、批量上传、源文件、OKF
- `KnowledgeReadController`：overview、document、lineage、content-unit、evidence
- `KnowledgeQueryController`：retrieve、answer、query、trace、answer qualification
- `KnowledgeGovernanceController`：extract、card/claim/assertion、adjudication、conflict
- `KnowledgeGraphController`：graph、object detail、capabilities
- `KnowledgeIndexOperationsController`：outbox、ACL rebuild、index schema

原 `KnowledgeController` 降为 10 行兼容标记类，不再注册 Spring MVC endpoint。URL 前缀和 endpoint path 保持 `/api/knowledge/**` 不变。

### P2.15 ExternalServiceLab 页面瘦身

`ExternalServiceLab.vue` 从 1291 行降到 477 行。REST/MCP/SSE/Event Bus 联调状态与行为迁入 `external-service/useExternalServiceLab.ts`，页面组件只保留 UI 模板和 composable binding；原有 API、事件、交互语义保持不变。

### 静态门禁

本轮执行并通过：

1. focused mapper XML statement ID 全局重复检查；
2. `scripts/check-p2-architecture.sh`；
3. legacy `KnowledgeMapper` / `KnowledgeController` / `ExternalServiceLab.vue` 行数收敛检查；
4. 最终 ZIP 解压完整性检查将在打包后执行。

按用户要求，本轮不进行 Maven / npm 完整编译测试；完整编译与业务回归集中放到下一阶段执行。
