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

正式检索只允许：

- 无 status 的 ContentUnit；
- `APPROVED`；
- `EFFECTIVE`。

`DRAFT / UNCERTAIN / PENDING_REVIEW / CONFLICTED / REJECTED / SUPERSEDED / EXPIRED / INVALID` 不进入问答候选。该规则已下推到 Local、Elasticsearch、Milvus、Neo4j。

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
