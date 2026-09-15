# 研发工程师详细开发手册

## 1. 适用对象

本文面向需要在此仓库继续做需求、排障、性能优化、检索算法、索引运维的研发工程师。读完后应能回答：

- 一个上传请求会写哪些表？
- 为什么索引失败不会回滚知识数据？
- Query 的 BM25/Vector/Graph 分别在哪里实现？
- Card 从 DRAFT 变 APPROVED 如何进入正式检索？
- Outbox 卡在 DEAD 怎么处理？
- 修改 Elasticsearch Mapping 怎么安全上线？
- 新增 API/字段应该同时改哪些地方？
- 什么测试必须在提交前跑？

## 2. 环境要求

目标开发环境：

```text
JDK 8
Maven 3.8+
Python 3.10
Node 18+/20+
Docker / Docker Compose
MySQL 8
```

基础设施：
- Redis；
- Elasticsearch；
- Milvus；
- Neo4j；
- 可选 Ollama；
- 可选 BGE reranker。

注意：源码必须保持 Java 8 兼容，即使本机 JDK 更高。

## 3. 第一次启动

```bash
cp .env.example .env

# 静态/烟测
./scripts/check-v11.sh

# 基础设施
make up
./scripts/wait-ready.sh
```

分别启动：

```bash
cd backend
mvn spring-boot:run
```

Python 侧统一使用 conda 环境 `llmkb`（Python 3.10）：

```bash
# 仅首次：创建环境并安装依赖（等价于 make dev-install）
conda create -y -n llmkb python=3.10
conda activate llmkb
pip install -r ai-service/requirements.txt

cd ai-service
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 12000
```

`make dev-ai` 与此等价，并通过环境变量把 Neo4j、模型服务、检索降级一并配好。

```bash
cd frontend
npm install
npm run dev
```

如果只做 Python 算法开发，可设置：

```env
RETRIEVAL_MODE=local
ELASTICSEARCH_ENABLED=false
MILVUS_ENABLED=false
GRAPH_PROVIDER=null
LLM_PROVIDER=mock
RERANKER_ENABLED=false
```

这样可以最小依赖运行 smoke tests。

## 4. 目录说明

```text
.
├── backend/
│   ├── src/main/java/com/jh/knowledgeos/
│   │   ├── controller/      # HTTP API
│   │   ├── service/         # 业务编排、Outbox、Schema Release
│   │   ├── mapper/          # MyBatis Java contract
│   │   ├── domain/          # DB/domain object
│   │   ├── dto/             # transport DTO
│   │   ├── config/          # config/filter/startup validation
│   │   └── common/          # error/status/response
│   └── src/main/resources/
│       ├── mapper/KnowledgeMapper.xml
│       └── db/migration/    # Flyway V1..V6
│
├── ai-service/
│   ├── app/
│   │   ├── routes.py
│   │   ├── core/config.py
│   │   ├── models/schemas.py
│   │   ├── services/
│   │   │   ├── document_parser.py
│   │   │   ├── extractor.py
│   │   │   ├── embedding.py
│   │   │   ├── indexer.py
│   │   │   ├── query_engine.py
│   │   │   ├── reranker.py
│   │   │   ├── graph_provider.py
│   │   │   └── retrieval/
│   │   │       ├── hybrid.py
│   │   │       └── external.py
│   │   └── storage/index_store.py
│   └── tests/
│
├── frontend/src/
│   ├── App.vue
│   └── api/knowledge.js
│
├── monitoring/             # Prometheus/Grafana/alerts
├── deploy/                 # production compose
├── scripts/                # gates/e2e/load/fault injection
├── contracts/              # cross-service contract
└── docs/                   # developer documentation
```

## 5. Backend 分层规则

### Controller

`KnowledgeController` 是 API 入口。新增接口时先问：

1. 是否已有 DTO？
2. 是否已有 focused service？
3. 是否属于 `/index/**` 运维 API，需要 RBAC？
4. 是否需要 trace？

Controller 不应该出现复杂事务/循环/外部 Provider。

### KnowledgeService

它是兼容 façade，不是继续堆功能的地方。历史 Controller 可以继续调用它，但新逻辑应该进入 focused service。

### Mapper

Mapper 变更要求 Java Interface 和 XML 同时修改；`check-mapper-contract.py` 会检测错配。

## 6. 最重要的事务模型

系统刻意不使用“大事务包住所有步骤”。

示例：上传：

```text
parse file (slow)                     no DB tx
  ↓
insert source/document/chunks          short DB tx
insert outbox                          same tx
  ↓ commit
extract knowledge (slow AI)            no previous tx
  ↓
insert card/claim + outbox              short DB tx
```

如果你新增逻辑，看到：

```java
transactionRunner.required(() -> aiClient.post(...))
```

应当高度警惕。

## 7. 数据库模型怎么理解

### Source / Document / Evidence

```text
t_source
  ↓
t_source_version
  ↓
t_document
  ↓
t_content_unit
  ↓
t_evidence
```

原始文档变更时应该创建新的 source version，不要直接覆盖来源历史。

### Knowledge

当前核心正式对象：
- `t_knowledge_card`；
- `t_claim`；
- evidence link；
- conflict/review/assessment。

### Index operations

```text
t_index_aggregate_version
t_index_outbox
t_index_delivery_attempt

t_index_outbox_archive
t_index_delivery_attempt_archive

t_index_schema_release
t_index_schema_snapshot_audit
```

## 8. Transactional Outbox 调试步骤

### 看积压

```http
GET /api/knowledge/index/outbox/{kb}/stats
X-Ops-Token: <viewer>
```

重点看：
- PENDING；
- RETRY；
- DEAD；
- oldest lag。

### 某事件 RETRY

先看：
- `last_error`；
- delivery attempt；
- AI service log trace；
- Elasticsearch/Milvus/Neo4j availability。

不要第一反应直接 skip。

### DEAD

修复根因后：

```http
POST /api/knowledge/index/outbox/events/{eventId}/replay
```

只有明确判断“该事件已经被后续完整状态覆盖且允许放弃”时才：

```http
POST /api/knowledge/index/outbox/events/{eventId}/skip
```

## 9. Query 调试方法

### 第一步：确认 router

看 QueryTrace 中 `routerDecision/strategies`。

### 第二步：确认 Provider

期望生产：

```text
bm25/card/claim -> elasticsearch
vector          -> milvus
graph           -> neo4j
```

如果显示 local，说明发生 fallback。

### 第三步：确认 ACL/status

如果结果“应该搜到但没搜到”，先看：
- KB ID；
- permission tags；
- Card/Claim status；
- object type；
- index version；
- alias target。

不要先调 RRF 权重。

### 第四步：看 rerank

区分：
- recall 没召回；
- 召回了但 reranker 排低；
- Context Assembly 丢弃；
- LLM 没使用证据。

这四种问题的修复位置完全不同。

## 10. Elasticsearch 调试

关键配置：

```env
ELASTICSEARCH_ENABLED=true
ELASTICSEARCH_URL=http://elasticsearch:9200
ELASTICSEARCH_INDEX=llmkb_knowledge_v1
ELASTICSEARCH_READ_ALIAS=llmkb_knowledge_read
```

Provider：`external.py -> ElasticsearchProvider`。

常见问题：
- alias 不存在 -> read 会回 base index；
- mapping 冲突 -> Bulk 返回 item error；
- ACL tag 类型错 -> terms filter 无结果；
- DRAFT Card 不返回是设计，不是 bug。

## 11. Milvus 调试

Provider：`external.py -> MilvusProvider`。

检查：
- embedding dim 是否与 collection 相同；
- point payload 是否有 `knowledge_base_id/object_type/status/permission_tags`；
- read alias 当前指向哪个 collection；
- `RETRIEVAL_EXTERNAL_REQUIRED` 是否导致失败上抛。

## 12. Neo4j 调试

Provider 是 process singleton。`available()` 有 10 秒缓存，因此刚恢复 Neo4j 后最多可能短时间仍显示旧状态。

目前关系类型在 Cypher 插值前做字符校验，修改相关代码必须保留注入防护。

## 13. 蓝绿索引发布操作

### rebuild

```http
POST /api/knowledge/index/schema/{kb}/rebuild?schemaVersion=v2
X-Ops-Token: <admin>
```

过程：
1. 获取 source high-watermark；
2. count authoritative objects；
3. MySQL 每页 500 读取；
4. 每页 checksum audit；
5. AI embedding；
6. 写 Green Elasticsearch/Milvus；
7. 对比 count；
8. 再获取 end-watermark；
9. READY 或 VALIDATION_FAILED。

### activate

只能激活可激活 Release。

### rollback

本质是把 alias 切回 retained release，所以一般比重新构建快得多。

## 14. 如何新增字段

假设给 Claim 增加 `risk_level`：

1. 新增 Flyway `V7__...sql`；
2. domain 字段；
3. Mapper insert/select/update；
4. DTO/API 是否需要暴露；
5. extraction 是否产生；
6. Indexer 是否需要写入 retrieval view；
7. Elasticsearch mapping / Milvus payload 是否需要；
8. Schema Version 是否需要升级；
9. 测试与 contract；
10. 文档。

如果字段会改变 Elasticsearch mapping，不能只改 `ensure_index` 然后期待旧 index 自动兼容，应走新的 schema release。

## 15. 如何新增一种检索策略

例：增加 `procedure` recall。

建议：
1. QueryRouter 决定何时启用；
2. HybridRetriever 新 channel；
3. Provider 仍输出统一 Candidate；
4. RRF 权重显式；
5. QueryTrace 记录 recall/provider；
6. 离线评测 Recall@K/NDCG；
7. ACL/status 前置。

## 16. 如何修改索引事件

新增事件前明确：
- aggregate key 是什么？
- 是否和现有事件共享 version sequence？
- idempotency key 如何生成？
- payload 是 snapshot 还是 authoritative reference？
- 失败是否允许跳过？
- 是否影响正式知识可见性？

大对象优先存“引用”，Worker 从 MySQL 重建 payload，避免 Outbox 膨胀。

## 17. 测试分层

### 快速 Gate

```bash
./scripts/check-v11.sh
```

### Backend 单测/编译

```bash
cd backend
mvn test
```

### MySQL/Flyway 集成

```bash
mvn -Pintegration-test verify
```

### Frontend

```bash
cd frontend
npm ci
npm run build
```

### 全栈

```bash
docker compose up -d
python scripts/e2e-test.py
```

### 故障注入

```bash
./scripts/fault-injection-indexing.sh
```

## 18. 日志和 Trace

始终携带/记录 `X-Trace-Id`。排查跨服务问题时不要只 grep error message；先以 trace ID 串联 Backend 和 AI Service。

## 19. 性能注意事项

已验证热点：
- 本地 linear vector 已改为 Matrix Snapshot；
- Python BM25 在 50k+ 会成为瓶颈；
- BGE concurrency 默认 1 防止小 GPU OOM；
- Chunk batch DB size=100；
- schema rebuild page size=500。

调整这些值时必须重新跑性能回归，不要仅凭“感觉更大更快”。

## 20. 安全注意事项

- 不把 Ops token 写源码；
- 生产 RBAC token 至少 24 字符且三种角色不同；
- Milvus API Key/Elasticsearch 密码走 secret；
- 检索 ACL 前置；
- source 删除/撤回应触发索引影响处理；
- 不让前端隐藏代替后端权限。

## 21. 新人第一周建议任务

Day 1：跑 `check-v11.sh`，读 `KnowledgeController` + 本文。

Day 2：跟一次 Upload，画自己的 sequence diagram。

Day 3：跟一次 Query，打印 QueryTrace。

Day 4：手工制造一个 Outbox RETRY，理解 replay。

Day 5：用 local 模式增加一个小测试，提交时确保 Gate 全绿。
