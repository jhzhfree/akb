# ACL Phase 4 — Engineering Closure

## 目标
修复 Phase 3 的 Code Quality 回归，并把 ACL Change 从“写入 Outbox”升级为可观察的传播状态机。

## 传播状态机
`PENDING → COMPLETED | REBUILD_REQUIRED | FAILED`

- `COMPLETED`：ACL_CHANGED 已成功传播，且目标响应不要求权威重建。
- `REBUILD_REQUIRED`：Milvus 标记 requiresReindex，或 SOURCE ACL 触发 source cascade；禁止错误标记完成。
- `FAILED`：Outbox 达到最大重试次数进入 DEAD。
- Retry 中仍保持未完成状态。

`t_acl_change` 新增 `propagation_result`、`last_error`。`completed_time` 沿用 V12 已有字段，V13 不重复新增。

## Worker Closure
IndexOutboxWorker 在 ACL_CHANGED 投递后读取 AI 响应：requiresAuthoritativeRebuild=false → COMPLETED；true → REBUILD_REQUIRED；DEAD → FAILED。使用 Outbox `idempotencyKey = changeCode` 关联 ACL Change。

## Audit Reliability
AclAuditService 不再静默吞掉持久化异常，改为 ERROR 日志。审计写失败不能导致受保护数据被放行。

## Code Quality Closure
修复 Elasticsearch/Milvus provider、App.vue、TypeScript interface、AclAdministrationPanel 和 ACL Java Javadoc 的 Phase 3 回归。`check-code-quality.py` 本轮实际通过。

## 尚未自动化的重建
本轮故意不在普通 Outbox Worker 中直接执行全量 blue/green rebuild。Source ACL cascade / Milvus reindex 涉及 schema release、checksum、high-watermark、activation barrier，应该由专用 ACL Rebuild Orchestrator 完成：green generation → authoritative rebuild → consistency validation → ACL leakage smoke → activate → ACL Change COMPLETED。

## 实际验证
PASS：ACL Phase 4 Contract、Code Quality、Frontend TypeScript Structural、Mapper（97 statements）、DB（34 tables / 32 mapper tables / 12 trace columns）、MyBatis XML、shell syntax、focused pytest 6 passed、ZIP integrity。

静态 Gate 扩展为 40 项。

未执行/未声称：Maven、vue-tsc/Vite production build、Flyway V13 live MySQL、Docker full-stack、Live ES/Milvus/Neo4j、ACL rebuild orchestrator live、four-persona leakage E2E。
