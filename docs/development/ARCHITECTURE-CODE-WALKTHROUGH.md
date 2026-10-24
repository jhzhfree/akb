# 代码架构导读：从 HTTP 请求到知识检索

本文给第一次接触项目的研发工程师提供“按代码走一遍”的阅读路线。建议先看本文，再按链接的类进入源码。

## 1. 一句话理解系统

这是一个 **MySQL 权威知识状态 + Python AI 算法服务 + Elasticsearch/Milvus/Neo4j 可重建检索视图** 的 AI 原生知识库。

最重要的边界：

```text
Java Backend                  Python AI Service
----------------------        --------------------------
业务状态 / 权限 / 事务          解析 / 抽取 / Embedding
审核 / 发布 / Outbox            Retrieval / Rerank / LLM
Schema Release                Graph / Index Provider

                MySQL = authoritative
Elasticsearch / Milvus / Neo4j / local JSON = retrieval views
```

## 2. 推荐阅读顺序

### 第 1 层：入口

1. `backend/.../controller/KnowledgeController.java`
2. `frontend/src/api/knowledge.ts`
3. `ai-service/app/routes.py`

先理解“有哪些能力”，不要一开始钻 Mapper XML。

### 第 2 层：Java 应用服务

阅读顺序：

1. `DocumentIngestionService`
2. `KnowledgeExtractionService`
3. `KnowledgeGovernanceService`
4. `KnowledgeQueryService`
5. `IndexOutboxService`
6. `IndexOutboxWorker`
7. `IndexSchemaService`

它们构成 V1.1 的业务主链。

### 第 3 层：Python 查询/索引

1. `services/indexer.py`
2. `services/query_engine.py`
3. `services/retrieval/hybrid.py`
4. `services/retrieval/external.py`
5. `services/reranker.py`
6. `services/graph_provider.py`

### 第 4 层：持久化

1. `KnowledgeMapper.java`
2. `KnowledgeMapper.xml`
3. `db/migration/V1..V6`

## 3. 文档上传链路

HTTP：

```text
POST /api/knowledge/documents/upload
```

代码：

```text
KnowledgeController.upload
  ↓
KnowledgeService (compatibility facade)
  ↓
DocumentIngestionService.upload
```

`DocumentIngestionService.upload` 的关键点不是“上传文件”，而是事务边界：

```text
aiClient.parse(file)              ← DB transaction 外
    ↓
transactionRunner.required
    ├─ insert t_source
    ├─ insert t_source_version
    ├─ insert t_document
    ├─ batch insert t_content_unit
    ├─ batch insert t_evidence
    └─ enqueue DOCUMENT_CHUNKS_UPSERT
    ↓ COMMIT
KnowledgeExtractionService.extract ← 再做 AI 抽取
```

为什么 parser 在事务外？因为 PDF/DOCX 解析可能耗时较长，不能占用数据库连接/锁。

为什么 Chunk 索引不直接调 Milvus/Elasticsearch？因为外部引擎失败不能回滚已经合法提交的 MySQL 权威数据，因此写 Outbox。

## 4. 知识抽取链路

```text
KnowledgeExtractionService.extract
    ↓
aiClient.extract                 ← AI 调用，事务外
    ↓
短事务
    ├─ t_knowledge_card
    ├─ t_claim
    ├─ evidence link
    └─ Outbox INDEX_KNOWLEDGE
    ↓
conflict detection
```

Card/Claim 初始状态不等于正式可检索。正式问答只允许：

```text
APPROVED / EFFECTIVE
```

这个过滤被下推到 local/Elasticsearch/Milvus/Neo4j，不依赖 LLM 自觉忽略未审核知识。

## 5. 查询链路

Backend：

```text
POST /api/knowledge/query
  ↓
KnowledgeQueryService.query
  ↓
AiClient -> POST /ai/query
```

AI Service：

```text
routes.query
  ↓
QueryEngine
  ├─ QueryRouter
  ├─ HybridRetriever
  │    ├─ Elasticsearch: BM25/Card/Claim
  │    ├─ Milvus: Vector
  │    ├─ Neo4j: Graph
  │    └─ Local fallback
  ├─ weighted RRF
  ├─ Reranker
  ├─ Context Assembly
  └─ LLM Provider
```

`HybridRetriever` 的职责是决定“每个 channel 从哪里召回”；`QueryEngine` 的职责是“如何组合这些召回结果”。不要把 Elasticsearch JSON 拼装放进 QueryEngine。

## 6. 本地 fallback

`storage/index_store.py` 是开发/恢复 fallback，不是中大规模生产主引擎。

它内部有：
- generation；
- JSON snapshot cache；
- VectorSnapshot（float32 matrix）；
- LexicalSnapshot（预分词 BM25）。

当 generation 改变时缓存失效。这个机制让开发环境无需 Elasticsearch/Milvus 也能跑通查询，但生产规模建议外部检索引擎。

## 7. Transactional Outbox

权威状态与索引视图最终一致：

```text
业务 Transaction
   ├─ update knowledge table
   └─ insert t_index_outbox
COMMIT
      ↓
IndexOutboxWorker
      ↓
AI indexing endpoint
      ↓
Elasticsearch / Milvus / Neo4j
```

文档抽取还有独立的后处理检查点：写入 Card/Claim 的同一个 MySQL 事务会创建图谱投递、冲突检测、实体消歧和完成事件发布阶段（迁移 V68）。同步流程立即按序处理以保持原响应；进程退出或阶段失败时，`ExtractionPostStageWorker` 只重做失败阶段，并阻止后置阶段越过未完成的前置阶段。文档摘要以 `runId` 隔离旧运行。支持 `POST /api/knowledge/documents/{id}/replay-post-processing` 手动重放失败阶段，不会重新调用 `/ai/extract`。

### 7.1 为什么 aggregate version 必须存在

假设：

```text
v1: APPROVED
v2: REJECTED
```

如果 Worker 并发/网络重试导致 v2 先到、v1 后到，外部索引可能被旧状态覆盖。因此：
- DB 按 aggregate 分配单调 version；
- Worker 不跨越未完成旧版本；
- Provider 更新状态时也检查 index_version。

### 7.2 DEAD 处理

DEAD 默认阻塞后续同 aggregate：

```text
v1 DEAD
v2 PENDING  ← 不越过
```

运维只能：
- replay v1；
- 或确认可跳过后 skip v1。

## 8. Index Worker

`IndexOutboxWorker.poll` 每轮：

```text
recoverStaleLocks
update metrics
claimBatch (短事务 + SKIP LOCKED)
for event:
    deliver (无 DB lock)
    success -> SUCCEEDED
    failure -> RETRY / DEAD
```

重要：**claim 事务和 HTTP deliver 分开**。否则一个 5 秒 Milvus 调用就可能让数据库锁持有 5 秒。

## 9. 蓝绿 Schema Release

`IndexSchemaService` 管理“索引结构版本”，不要和对象 `indexVersion` 混淆。

```text
schemaVersion = 检索结构版本
indexVersion  = 单个 Card/Claim 状态版本
```

蓝绿流程：

```text
MySQL authoritative snapshot
  ↓ 500/page
page SHA256 audit
  ↓
Elasticsearch green + Milvus green
  ↓
count validation
  ↓
high-watermark validation
  ↓
READY
  ↓ ADMIN activate
read alias switch
```

重建开始/结束对比 `MAX(t_index_outbox.id)`。若发生变化，说明重建期间源数据发生修改，Release 标记 `SOURCE_CHANGED`，不允许切线上。

## 10. ElasticsearchProvider

文件：`ai-service/app/services/retrieval/external.py`

职责：
- ensure physical index；
- bulk upsert；
- ACL/status filter；
- BM25 `multi_match`；
- deterministic `_id`；
- schema target；
- alias activation；
- version-aware status update。

不要把 embedding 放在 Elasticsearch Provider；Embedding 是 Indexer 的上层能力。

## 11. MilvusProvider

职责：
- ensure collection；
- payload field indexes；
- UUIDv5 deterministic point ID；
- batch upsert；
- ACL/status filter；
- `/points/query`；
- collection alias activation。

ID 规则：

```text
UUIDv5("llmkb:<knowledge_base_id>:<object_id>")
```

因此 Outbox 重试不会产生重复 point。

## 12. Neo4jGraphProvider

Graph Provider 目前将写入拆分为：
- `_upsert_entity`；
- `_upsert_card`；
- `_upsert_claim`；
- `_upsert_relation`。

关系类型无法使用 Cypher 参数，所以在字符串插值前做白名单式字符校验。这个检查不能删除。

当前 graph search 使用 `CONTAINS`，适合 V1.1/中小规模。大规模下一步应做 Neo4j fulltext/exact alias lookup，再做 traversal。

## 13. 裁决与冲突

`KnowledgeGovernanceService`：业务编排。

`adjudication_engine.py`：解释性评分。

`conflict_detector.py`：候选冲突快速过滤。

设计思想：

```text
Rule/score -> AI Judge advisory -> Human when required
```

LLM 不具有最终治理权。高风险、冲突、证据不足对象必须进入人工路径。

## 14. Trace 与可观测性

每次 Query 建议从 `qid/traceId` 开始排查：

```text
HTTP X-Trace-Id
    ↓
Backend TraceIdFilter
    ↓
AiClient header
    ↓
FastAPI request_context
```

QueryTrace 包括：
- rewritten query；
- router decision；
- recall counts；
- retrieval provider；
- rerank；
- selected context；
- model；
- latency；
- citation。

## 15. Ops RBAC

`/api/knowledge/index/**` 由 `OpsRbacFilter` 保护。

角色：
- VIEWER：读取状态；
- OPERATOR：replay/skip；
- ADMIN：rebuild/activate/rollback。

生产不要绕过 Filter 在新 Controller 暴露同类高风险接口。

## 16. 新增功能应该放在哪里

### 新检索通道

```text
ai-service/app/services/retrieval/
```

实现统一 Candidate，再在 `HybridRetriever` 接入。

### 新知识类型

通常需要同步修改：
1. MySQL schema/domain；
2. extraction schema；
3. Java persistence；
4. Indexer normalization；
5. Elasticsearch/Milvus metadata/filter；
6. Query router；
7. governance lifecycle；
8. tests/contracts。

### 新业务 API

优先：

```text
DTO -> Controller -> focused Service -> Mapper
```

不要继续把 `KnowledgeService` façade 做大。
