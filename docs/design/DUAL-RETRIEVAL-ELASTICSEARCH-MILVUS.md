# Elasticsearch + Milvus 双检索架构

## V1.1 正式选型

- MySQL：权威业务状态。
- Elasticsearch：全文检索、BM25、精确匹配、ACL/状态过滤。
- Milvus：Dense Vector ANN、标量过滤。
- Neo4j：知识图谱。
- QueryEngine：多路召回、Weighted RRF、Rerank、Context Assembly。

## 版本基线

本轮代码/Compose 基线使用 Elasticsearch 9.5.3、Milvus 3.0.1。版本来自 2026-09 官方发布信息。升级版本前必须重新运行 Provider Contract 和真实容器验收。

## Provider 边界

`retrieval/elasticsearch_provider.py` 与 `retrieval/milvus_provider.py` 独立实现。`external.py` 只保留兼容导出，禁止重新堆积供应商逻辑。

## Elasticsearch

默认索引 `llmkb_knowledge_v1`，读别名 `llmkb_knowledge_read`。`ELASTICSEARCH_ANALYZER` 可配置；默认 `standard`，中文生产环境建议安装并验证选定中文 analyzer 后再切换。

## Milvus

默认 Collection `llmkb_knowledge_v1`，REST v2 地址 `http://milvus:19530`，Token 使用 `MILVUS_TOKEN`。动态字段承载检索元数据；`knowledge_base_id/object_type/status/permission_tags` 在 ANN 前组成 scalar filter。

## 一致性

MySQL -> Transactional Outbox -> Index Worker -> Elasticsearch/Milvus/Neo4j。对象 `indexVersion` 负责事件顺序；`schemaVersion` 负责蓝绿物理索引/Collection。MySQL authoritative snapshot + checksum + high-watermark 决定 Release 是否可激活。

## 蓝绿

Elasticsearch 使用物理 index + alias。Milvus 使用物理 collection + alias。重建 Green、校验数量/水位后才 activate；旧版本保留用于 rollback。

## Milvus 状态更新边界

本版不依赖 Milvus 标量字段的“脚本式 compare-and-set”。同 aggregate 的 Outbox 串行顺序仍是 stale write guard。状态变更需要完整对象重新索引时，Provider 返回 `requiresReindex=true`，由上层 delivery/reconciliation 处理。

## 生产验收

静态 Mock Contract 不能替代：真实 ES mapping/analyzer、Milvus REST v2/alias、ACL filter、10万级 ANN、故障恢复、蓝绿 rollback。
