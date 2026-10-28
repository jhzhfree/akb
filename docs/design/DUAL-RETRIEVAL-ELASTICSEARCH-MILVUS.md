# Elasticsearch + Milvus 双检索架构

## V1.1 正式选型

- MySQL：权威业务状态。
- Elasticsearch：全文检索、BM25、精确匹配、ACL/状态过滤。
- Milvus：Dense Vector ANN、标量过滤。
- Neo4j：知识图谱。
- QueryEngine：多路召回、Weighted RRF、Rerank、Context Assembly。

## 版本基线

本轮代码/Compose 基线使用 Elasticsearch **9.5.3**、Milvus **3.0.1**、Neo4j **5.26**，三者均以 `docker-compose.yml` 的 `image:` 为准（`MySQL 8.0.36`、`Redis 7-alpine`）。不要引用"官方发布信息"来判断版本——以编排文件为唯一事实来源。升级版本前必须重新运行 Provider Contract 和真实容器验收。

## Provider 边界

`retrieval/elasticsearch_provider.py` 与 `retrieval/milvus_provider.py` 独立实现。`external.py` 只保留兼容导出，禁止重新堆积供应商逻辑。

## 启用开关与凭据

两个 Provider 都可独立关闭（默认均为**开启**）。全部开关与凭据从环境变量读取（均以 `ai-service/app/core/config.py` 为准）：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `ELASTICSEARCH_ENABLED` | `true` | 关闭后降级为本地 JSON IndexStore 兜底 |
| `ELASTICSEARCH_USERNAME` / `ELASTICSEARCH_PASSWORD` | 空 | 无认证集群留空 |
| `ELASTICSEARCH_VERIFY_SSL` | `false` | 自签证书场景保持关闭，公网应开启并配 CA |
| `ELASTICSEARCH_ANALYZER` | `standard` | 中文生产环境建议安装并验证选定中文 analyzer 后再切换 |
| `MILVUS_ENABLED` | `true` | 同上 |
| `MILVUS_READ_ALIAS` | `llmkb_knowledge_read` | 读别名**基名**，实际别名按库派生（见下） |
| `MILVUS_TOKEN` | 空 | Milvus REST v2 鉴权 Token |

## Elasticsearch

默认索引 `llmkb_knowledge_v1`，读别名 `llmkb_knowledge_read`。

## Milvus

默认 Collection `llmkb_knowledge_v1`，REST v2 地址 `http://milvus:19530`，Token 使用 `MILVUS_TOKEN`。动态字段承载检索元数据；`knowledge_base_id/object_type/status/permission_tags` 在 ANN 前组成 scalar filter。

**Tag 过滤的两个实现细节**（漏了会在授权与重建上出错）：

1. **公开资料用哨兵值表达**。写索引时若 `permission_tags` 为空，会落成 `["__public__"]`（`milvus_provider.py:210`）；查询侧再过滤掉该哨兵（`:261`），并把 `"__public__"` 追加进 `json_contains_any` 的取值列表（`:373`）。因此 **`permission_tags` 是 dynamic JSON 数组，必须用 `json_contains_any` 匹配**，不能按标量相等比较。
2. **读别名按知识库派生**。为避免多个库的蓝绿别名互相覆盖（一个 alias 只能指向一个 collection），实际别名是 `"{MILVUS_READ_ALIAS}__kb{knowledgeBaseId}"`（`milvus_provider.py:297-304`），物理 collection 为 `"{collection}__kb{id}__{schemaVer}"`。**没有 `knowledgeBaseId` 上下文时才会退回裸 `MILVUS_READ_ALIAS`**。做重建、查别名、回滚时务必带上库 ID，否则会指错 target。

## 一致性

MySQL -> Transactional Outbox -> Index Worker -> Elasticsearch/Milvus/Neo4j。对象 `indexVersion` 负责事件顺序；`schemaVersion` 负责蓝绿物理索引/Collection。MySQL authoritative snapshot + checksum + high-watermark 决定 Release 是否可激活。

## 蓝绿

Elasticsearch 使用物理 index + alias。Milvus 使用物理 collection + alias。重建 Green、校验数量/水位后才 activate；旧版本保留用于 rollback。

## Milvus 状态更新边界

本版不依赖 Milvus 标量字段的“脚本式 compare-and-set”。同 aggregate 的 Outbox 串行顺序仍是 stale write guard。状态变更需要完整对象重新索引时，Provider 返回 `requiresReindex=true`，由上层 delivery/reconciliation 处理。

## 生产验收

静态 Mock Contract 不能替代：真实 ES mapping/analyzer、Milvus REST v2/alias、ACL filter、10万级 ANN、故障恢复、蓝绿 rollback。
