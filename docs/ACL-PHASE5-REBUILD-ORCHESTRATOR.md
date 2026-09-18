# ACL Phase 5 — Rebuild Orchestrator & Activation Barrier

## 目标
将 Phase 4 的 `REBUILD_REQUIRED` 闭环为独立的 ACL 重建任务，禁止未经过资格验证的 green generation 被激活。

## 状态机

`PENDING → REBUILDING → VALIDATING → READY → ACTIVATING → COMPLETED`

失败路径进入 `FAILED`。

## V14
新增 `t_acl_rebuild_job`，持久化：
- jobCode / changeCode / knowledgeBaseId
- schemaVersion / releaseId
- state
- qualificationState / qualificationJson
- errorText / timestamps

每个 ACL Change 只允许一个 rebuild job，避免重复重建。

## Orchestrator
新增 `AclRebuildOrchestrator`：
1. 仅接受 `REBUILD_REQUIRED` ACL Change；
2. 调用既有 `IndexSchemaService.rebuild()` 生成 green release；
3. authoritative snapshot 的 count/checksum/high-watermark 校验仍由 IndexSchemaService 执行；
4. rebuild READY 后，ACL job 停在 `VALIDATING`；
5. qualification 必须显式 PASSED 才进入 READY；
6. activate() 强制检查 `state=READY && qualificationState=PASSED`；
7. 激活成功后 ACL Change 才改为 COMPLETED。

## Activation Barrier
没有 qualification 证据时不能调用 schema activation。这样把“重建成功”和“允许切流”拆成两个独立条件。

当前 qualification API 接受外部机器可验证测试结果作为 evidence。下一轮应把 Anonymous / Team-A / Team-B / Admin 四身份 leakage runner 直接接入该步骤，替代人工提交 passed=true。

## API
- `POST /api/knowledge/acl/rebuild/{changeCode}/start`
- `POST /api/knowledge/acl/rebuild/{jobCode}/qualify?passed=true|false`
- `POST /api/knowledge/acl/rebuild/{jobCode}/activate`
- `GET /api/knowledge/acl/rebuild/{kb}`

## 实际验证
通过：
- ACL Phase 5 contract
- Code Quality
- Mapper contract: 102 statements
- DB contract: 35 tables / 33 mapper tables / 12 trace columns
- MyBatis XML
- shell syntax
- focused sync pytest: 5 passed

环境限制：blue-green 两个 async pytest 因当前环境缺少 pytest-asyncio/anyio 插件无法执行，不属于业务断言失败。

未执行：Maven、vue-tsc/Vite build、Live MySQL/Flyway V14、Docker、Live ES/Milvus/Neo4j、自动四身份 leakage qualification。
