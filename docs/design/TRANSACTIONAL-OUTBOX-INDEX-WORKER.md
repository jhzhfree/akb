# V1.1 Transactional Outbox + Index Worker

## 1. 目标

业务事实只提交到 MySQL。Elasticsearch、Milvus、Neo4j 与本地 JSON 都是可重建 Retrieval View，不再参与业务事务同步提交。

```text
Business Transaction
  ├─ knowledge tables
  └─ t_index_outbox
        │ commit together
        ▼
Index Worker
  ├─ Elasticsearch
  ├─ Milvus
  ├─ Neo4j
  └─ local fallback
```

## 2. 表

- `t_index_aggregate_version`: 聚合对象单调版本。
- `t_index_outbox`: 待投递事件、重试、锁、死信、结果。
- `t_index_delivery_attempt`: 每次投递审计。

Flyway: `V4__transactional_outbox.sql`。

## 3. 状态机

```text
PENDING
   │ claim
   ▼
PROCESSING
   ├─ success ──────────────> SUCCEEDED
   └─ failure
         ├─ attempts left ──> RETRY ──> PROCESSING
         └─ exhausted ──────> DEAD

DEAD/RETRY -- replay --> RETRY
DEAD/RETRY/PENDING -- operator skip --> SKIPPED
```

`DEAD` 默认阻塞同 aggregate 更高版本。只有 `SUCCEEDED` 或 `SKIPPED` 会释放后续版本。

## 4. 多 Worker 并发

领取 SQL 使用 MySQL 8 `FOR UPDATE SKIP LOCKED`。同时增加前序版本条件，因此不同 Worker 可以并发处理不同 aggregate，但同一 aggregate 保持版本顺序。

## 5. 幂等

- `event_id` 唯一。
- `idempotency_key` 唯一。
- Elasticsearch 使用确定性 `_id` upsert。
- Milvus 使用 UUIDv5 确定性 point id。
- Neo4j 使用 `MERGE`。
- 本地 fallback 按 object id upsert。

人工裁决优先把 HTTP `X-Trace-Id` 纳入幂等键。文档/Card/Claim 初次索引使用稳定业务键。

## 6. 索引版本

Card/Claim 的初次写入和状态更新共享同一个 aggregate key：

```text
KB:1:CARD:K_001
KB:1:CLAIM:CLM_001
```

每次变更从 `t_index_aggregate_version` 分配新版本。Worker 不允许 v2 越过尚未完成的 v1。

Local/Elasticsearch/Neo4j 状态更新都会记录/检查 `index_version`；Milvus 依靠 Worker 的聚合顺序和幂等 point id 保证最终顺序。

## 7. 大事件控制

文档 Chunk 事件只保存 `knowledgeBaseId + documentId` 引用，不把所有正文复制进 `payload_json`。Worker 消费时从 MySQL 重新组装：

```text
t_document
  -> t_source_version
  -> t_source
  -> t_content_unit
```

这样可显著降低 Outbox 表膨胀。

## 8. 正式知识检索状态

召回状态由 `RETRIEVAL_STATUS_POLICY` 配置，并由 Local、Elasticsearch、Milvus、Neo4j 共用同一白名单：

- `permissive`（默认）：`DRAFT / UNCERTAIN / APPROVED / EFFECTIVE`；
- `standard`：仅 `APPROVED / EFFECTIVE`；
- `custom`：从 `RETRIEVAL_ALLOWED_STATUSES` 读取逗号分隔的知识状态白名单。

兼容行为也受策略约束：`permissive` 允许旧数据中缺少 status 的对象；`standard` 和 `custom` 要求对象具有白名单内的明确状态，因此标准模式下只有 `APPROVED / EFFECTIVE` 对象能进入问答召回。新写入的内容块在索引时会标记为 `EFFECTIVE`。`custom` 的值必须是已知知识状态；空值、未知状态或未知策略会报错，不会静默放宽。

## 9. 运维 API

```http
GET  /api/knowledge/index/outbox/{kb}
GET  /api/knowledge/index/outbox/{kb}/page?page=1&size=20&status=DEAD  # 控制台分页：返回 items/total/page/size/totalPages
GET  /api/knowledge/index/outbox/{kb}/stats
POST /api/knowledge/index/outbox/events/{eventId}/replay
POST /api/knowledge/index/outbox/events/{eventId}/skip
GET  /api/knowledge/index/drift/{kb}
```

AI Service:

```http
POST /ai/index/object/status
GET  /ai/index/external/stats/{knowledgeBaseId}
```

## 10. 核心监控

至少监控：

- `pending + retry + processing` 数量；
- oldest lag seconds；
- DEAD 数量；
- attempt failure rate；
- Elasticsearch/Milvus 与 MySQL count delta；
- Worker throughput；
- 每种事件平均处理时间。

建议告警：

- DEAD > 0：P1/P2，按知识重要性分级；
- oldest lag > 300s：warning；
- oldest lag > 1800s：critical；
- drift delta 持续非 0 超过 10 分钟：warning。

## 11. 生产部署

生产 compose 已拆分：

- `backend`: `INDEX_OUTBOX_ENABLED=false`；
- `index-worker`: 同一 backend 镜像，`SPRING_MAIN_WEB_APPLICATION_TYPE=none` 且 `INDEX_OUTBOX_ENABLED=true`。

因此 API 和索引消费可以独立扩容。多 Worker 依靠数据库行锁竞争任务。

## 12. 当前 V1.1 边界

已经完成：事务 Outbox、幂等键、聚合版本、多 Worker 领取、指数退避、stale lock 恢复、死信、replay/skip、状态索引同步、漂移检查、独立 Worker 部署模板。

下一阶段可进一步演进：

1. Outbox 分区/归档；
2. Micrometer/Prometheus 指标；
3. 独立 Worker 模块或独立 JVM；
4. Kafka/Debezium CDC（数据量极大时）；
5. 索引 schema version 与蓝绿重建；
6. Milvus 状态更新增加服务端 compare/version guard（若未来 API 支持条件写）。

---

## 附录：V1.1 Transactional Outbox + Index Worker 改造报告（历史）

> 来源 `V1.1_TRANSACTIONAL_OUTBOX_INDEX_WORKER_REPORT.md`（历史路径，已并入本文档；原文仅在 git 历史中保留）。
> 该报告中的检索引擎名称已被 ES 9.5.3 / Milvus 3.0.1 迁移取代。

> 历史报告：其中检索引擎名称已被后续 V1.1 Elasticsearch + Milvus 迁移取代；以 `docs/design/DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md` 为准。

## V1.1 Transactional Outbox + Index Worker 改造报告

### 结论

V1.1 的检索索引写入链路已从“业务请求同步等待 Elasticsearch + Milvus + Neo4j”改造成“业务事务提交 MySQL + Outbox，Index Worker 异步最终一致”。这解决了最核心的部分失败问题：MySQL 成功但某个检索引擎超时，不再要求业务事务和多个外部系统形成不可实现的分布式原子提交。

### 新架构

```text
Client
  ↓
Spring Boot API
  ↓
MySQL Transaction
  ├─ Source / Document / ContentUnit / Card / Claim / Review
  └─ Index Outbox Event
          ↓ COMMIT
Index Worker (1..N)
  ↓ FOR UPDATE SKIP LOCKED
  ├─ Elasticsearch
  ├─ Milvus
  ├─ Neo4j
  └─ Local fallback
          ↓
SUCCEEDED / RETRY / DEAD
```

### 本轮代码落地

- Flyway `V4__transactional_outbox.sql`。
- `IndexOutboxEvent`、`IndexOutboxStatus`。
- `IndexOutboxService`：版本分配、幂等写入、replay/skip、drift。
- `IndexOutboxWorker`：批量领取、锁、指数退避、死信、stale lock recovery。
- `t_index_delivery_attempt` 保存每次投递审计。
- 文档、Card、Claim、裁决状态变化全部接入 Outbox。
- 冲突检测把 Claim 置为 `CONFLICTED` 时也会同步产生状态事件。
- AI Service 新增对象状态更新和外部索引 count API。
- Local/Elasticsearch/Milvus/Neo4j 正式知识状态过滤。
- 生产 Compose 把 API backend 与 index-worker 分离。

### 一致性语义

MySQL 是唯一 authoritative state。检索引擎允许短暂滞后，但必须可以从 MySQL 重放/重建。

同一 Card/Claim 使用单调 `object_version`。低版本事件未成功时，高版本事件不会被领取；Dead Letter 也不会被自动越过。

### 幂等与补偿

Outbox 通过 `idempotency_key` 抑制重复事件；Elasticsearch/Milvus 使用确定性对象 ID，Neo4j 使用 MERGE，本地 fallback 使用 object id upsert。因此 Worker 重试同一事件不会产生重复对象。

失败按指数退避重试，最多默认 8 次；耗尽后进入 DEAD。运维可以 replay 或显式 skip。skip 是有风险的人工补偿操作，会释放同 aggregate 后续版本，因此必须结合业务判断。

### Outbox 表体积

文档 Chunk 事件采用引用式 payload：Outbox 不复制正文，Worker 根据 documentId 从 MySQL 重建索引请求。这样避免大文件场景中 Outbox 与 ContentUnit 双份存储导致数据库快速膨胀。

### 验证结果

当前静态/Smoke Gate 已扩展到 18 项并全部通过：原 AI Pipeline、智能裁决、API Contract、性能回归、Elasticsearch/Milvus Contract、Transactional Outbox/Index Version Contract、Flyway/Mapper/配置/可读性等均通过。

当前执行环境没有 Maven 和 Docker，因此没有声称以下项目已真实跑通：MySQL 8 Testcontainers 的 `SKIP LOCKED` 多 Worker 并发、真实 Elasticsearch/Milvus/Neo4j 故障注入、生产 compose 的独立 Worker。它们应作为部署环境 RC Gate。

### 建议真实环境故障演练

1. 正常上传文档，确认 Outbox 从 PENDING 到 SUCCEEDED。
2. 停止 Milvus，再上传文档，确认事件 RETRY 且业务数据仍提交成功。
3. 恢复 Milvus，确认自动重试成功。
4. 持续制造失败直到 DEAD，确认后续同 aggregate 版本被阻塞。
5. 修复后 replay，确认后续版本继续消费。
6. 对可丢弃旧版本执行 skip，确认显式释放。
7. 启动 2~4 个 Worker，验证同 aggregate 顺序与不同 aggregate 并行。
8. 使用 drift API 比较 MySQL/Elasticsearch/Milvus 计数。

### 下一步

V1.1 若继续做生产化，优先级建议：Prometheus 指标与 Grafana 面板、Outbox 归档清理、蓝绿索引 schema version、真实故障注入测试。若未来事件量达到持续数千/秒，再评估 Debezium/Kafka CDC；当前规模优先保留 MySQL Outbox，复杂度更低。
