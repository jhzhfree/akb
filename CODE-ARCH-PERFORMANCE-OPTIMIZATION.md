# V1.1 代码可读性、软件架构与性能优化说明

## 1. 本轮目标

本轮不增加业务功能，目标是让 V1.1 RC 在三个维度继续收口：

1. **代码可读性**：降低单类职责、单行压缩、动态 Map 扩散和前端请求逻辑耦合。
2. **软件架构**：拆分应用服务，缩短事务边界，区分外部计算与数据库持久化。
3. **性能**：减少 JSON 索引重复解析、模型重复初始化、Neo4j Driver 重建、冲突 O(N²) 比较、逐 Chunk 数据库往返和异步事件循环阻塞。

---

## 2. 代码可读性优化

### 2.1 KnowledgeService 从“上帝类”变为兼容门面

原结构：

```text
KnowledgeService
├── 上传/解析
├── Source/Document/ContentUnit/Evidence 持久化
├── 知识抽取
├── 卡片/断言持久化
├── 冲突检测
├── 查询
├── Query Trace
├── 人工裁决
├── 智能裁决
├── 图谱
└── 列表查询
```

优化后：

```text
KnowledgeService (Facade, 保持 Controller 契约)
├── DocumentIngestionService
│   └── Source / Document / Chunk / Evidence
├── KnowledgeExtractionService
│   └── Card / Claim / Conflict / Knowledge Index
├── KnowledgeQueryService
│   └── Query / QueryTrace
├── KnowledgeGovernanceService
│   └── Manual / Intelligent Adjudication
└── KnowledgeReadService
    └── Card / Claim / Source / Document / Graph / Trace Read
```

收益：

- 单个类的修改原因明显减少；
- 事务边界可以按业务步骤控制；
- 后续异步化、缓存、限流、消息队列可以针对单个应用服务演进；
- Controller API 不需要随内部重构而变化。

### 2.2 Java 格式与命名恢复为正常工程代码

原 `KnowledgeService.java` 只有约 18 行，但包含 12K+ 字符和 200+ 分号，大量逻辑压在单行。

本轮对核心 Java 类进行正常格式化，并增加自动 Gate：

```bash
python scripts/check-code-quality.py
```

当前约束：

- Java 单行不超过 220 字符；
- `KnowledgeService` 兼容门面不超过 150 行；
- 关键分层服务必须存在；
- Embedding / Reranker / GraphProvider 必须有进程级缓存。

### 2.3 前端请求逻辑从 App.vue 中抽离

新增：

```text
frontend/src/api/knowledge.js
```

统一：

- API 路径；
- KB ID；
- 长请求 timeout；
- API Response 解包；
- 上传/查询/裁决/图谱调用。

同时把原来切换任意页签都调用 7 个接口的：

```text
watch(active) -> refresh() -> Promise.all(7 APIs)
```

改为：

```text
active=data   -> documents
active=review -> pending + conflicts + assessments
active=cards  -> cards
active=claims -> claims
active=query  -> 不做无关刷新
active=graph  -> 按对象代码查询
```

Review 页采用 `Promise.allSettled`，可选接口失败不会拖垮整个工作台。

---

## 3. 架构优化

### 3.1 解决“远程 AI 调用包在数据库事务中”问题

原设计中：

```text
@Transactional
upload()
  ├── 调 AI Parser
  ├── DB 写入
  ├── 调 Embedding / Index
  └── 调 Extract
```

以及：

```text
@Transactional
intelligentAdjudicate()
  └── 循环调用 AI Judge
```

LLM / Embedding / Graph 调用可能耗时数秒到数十秒。如果事务覆盖整个过程，会造成：

- 数据库连接长时间被占用；
- 长事务增加锁持有时间；
- 并发上传/裁决时连接池更容易耗尽；
- 外部服务失败导致大事务整体回滚，失败域过大。

本轮新增：

```text
TransactionRunner
```

形成：

```text
外部解析 / AI 调用
        ↓
   短事务 DB 写入
        ↓
外部索引 / Graph / LLM
        ↓
   短事务状态更新
```

这比简单给大方法加 `@Transactional` 更适合 AI 工作流。

### 3.2 同步重计算从 FastAPI Event Loop 移出

以下同步重任务已改为 `asyncio.to_thread(...)`：

- 文档解析；
- 冲突检测；
- Neo4j 查询/写入；
- CrossEncoder Rerank。

并增加：

```env
RERANKER_CONCURRENCY=1
```

用 Semaphore 限制 BGE 并发，避免多请求同时占满 GPU/显存。

### 3.3 重资源 Provider 改为进程级复用

原逻辑存在重大性能问题：

```text
每次 Query
  -> get_reranker()
  -> AutoTokenizer.from_pretrained()
  -> AutoModelForSequenceClassification.from_pretrained()
```

Neo4j Provider 也会重复创建 Driver / 验证连接。

本轮改为：

```python
@lru_cache(maxsize=1)
def get_reranker(): ...

@lru_cache(maxsize=1)
def get_embedding_provider(): ...

@lru_cache(maxsize=1)
def get_graph_provider(): ...
```

同时 Ollama Embedding 复用一个 `httpx.AsyncClient` 连接池。

Neo4j connectivity 增加 10 秒 availability cache，避免每次查询都执行连接探测，同时仍允许 Neo4j 启动后自动恢复。

---

## 4. 性能优化

### 4.1 JSON IndexStore 增加内存 Snapshot Cache

原逻辑每次检索：

```text
Query
 -> open kb_x.json
 -> read full file
 -> json.loads(full file)
 -> retrieval
```

优化后：

```text
IndexStore
├── generation
├── in-memory snapshot
└── write 时 generation + 1 / cache replace
```

只有索引变化时才重新构建内存快照。

本地 5,000 条测试的方向性结果：

```text
100 次缓存 load       ≈ 1.2 ms
10 次原始 JSON parse  ≈ 1.1 s
```

该数据不是生产 SLO，但清楚说明重复磁盘读取/JSON 解析已经被移出 Query 热路径。

可运行：

```bash
python scripts/benchmark-hotspots.py
```

### 4.2 BM25 / Typed Retrieval 预分词缓存

原逻辑每次 Query 都：

```text
对所有 Doc tokenize
-> 统计 DF
-> 构建 TF Counter
-> 排序全部结果
```

本轮新增：

```text
LexicalSnapshot
├── token tuple
├── term_frequency
├── document_frequency
├── average_length
└── index generation
```

索引未变化时复用词法统计。

同时：

- BM25 / Vector / Typed / RRF 的 Top-K 从全量 sort 改为 `heapq.nlargest`；
- 权限过滤仍在候选参与召回/上下文之前执行；
- 本地 Graph fallback 明确只作为 Claim lexical fallback，不伪装成真实图遍历。

### 4.3 冲突检测从全量 N² 缩小为候选组比较

原逻辑：

```text
全部 Claims
 -> for i
    -> for j
       -> compare
```

5,000 Claim 理论组合数：

```text
12,497,500 pairs
```

优化包括两层：

1. Java 只查询与新 Claim subject 相关的候选 Claim；
2. Python 先按照 normalized `(subject, predicate)` 分桶；
3. 支持 `focusCodes`，只检测至少包含一条本次新 Claim 的 Pair；
4. A/B Code 按字典序规范化，降低反向重复冲突。

方向性测试：

```text
naive pairs        = 12,497,500
optimized compares = 4
```

### 4.4 文档 Chunk DB 写入从 3N 往返改为批处理

原流程每个 Chunk：

```text
INSERT content_unit
SELECT content_unit id
INSERT evidence
```

即：

```text
约 3 × N SQL round trips
```

优化后每 100 条一批：

```text
Batch INSERT ContentUnit
        ↓
Batch SELECT ids by unit_code
        ↓
Batch INSERT Evidence
```

1,000 Chunk 从理论约 3,000 次 Mapper 往返，下降为约 30 组批量 SQL。

批大小当前：

```java
DB_BATCH_SIZE = 100
```

后续可以按 MySQL `max_allowed_packet`、平均 Chunk 字节数和压测结果配置化。

### 4.5 裁决 SQL 去除 Correlated Conflict Subquery

原 `listAdjudicationCandidates` 对每个 Claim 分别执行：

```sql
SELECT COUNT(*) FROM conflict ...
SELECT MAX(score) FROM conflict ...
```

改为先把 A/B 两侧冲突展开一次并聚合：

```text
Conflict
 -> UNION ALL object_a / object_b
 -> GROUP BY objectCode
 -> LEFT JOIN Candidate Claim
```

避免候选数量增加时产生明显的相关子查询放大。

---

## 5. 当前查询热路径

优化后的 V1.1 Query Path：

```text
HTTP /ai/query
   ↓
QueryRouter
   ↓
IndexStore memory snapshot
   ↓
LexicalSnapshot cache
   ├── BM25
   ├── Card
   └── Claim
   ↓
EmbeddingProvider singleton
   └── Vector scan
   ↓
GraphProvider singleton
   └── thread offload
   ↓
Weighted RRF Top-K
   ↓
Reranker singleton
   ├── concurrency semaphore
   └── thread offload
   ↓
Context budget
   ↓
LLM
```

---

## 6. 本轮自动回归

新增：

```text
ai-service/tests/test_performance_regression.py
scripts/check-code-quality.py
scripts/benchmark-hotspots.py
```

`check-v11.sh` 扩展到 15 项：

```text
Python syntax
AI pipeline
Governance / reranker / graph
Intelligent adjudication
AI API contract
Performance regression
DB / Flyway contract
MyBatis contract
Performance indexes
Configuration contract
Migration structure
Java 8 API scan
Code readability / architecture contract
RC API boundary
RC delivery files
```

---

## 7. 仍然需要在后续版本继续做的性能架构升级

### P0：正式发布前

1. 在有 Maven / Docker 的环境执行：
   - `mvn verify -Pintegration-test`
   - Testcontainers MySQL
   - `docker compose up`
   - E2E
2. 前端 Production Build 完整验证。
3. 对 10K / 50K / 100K Knowledge Object 做真实压测。
4. 用 `EXPLAIN ANALYZE` 验证 MySQL 核心 SQL。

### P1：数据量 > 5 万后

V1.1 JSON IndexStore 只能作为本地 fallback，不建议继续扩容。生产架构建议：

```text
BM25 / exact search -> Elasticsearch / Elasticsearch
Vector search       -> Milvus / Milvus / pgvector / ES vector
Metadata / lifecycle-> MySQL
Graph               -> Neo4j
Cache                -> Redis
```

目标是把：

```text
Python 全量 Vector Scan O(N × D)
```

替换成 ANN Index。

### P1：Neo4j 查询

当前 Graph Search 使用属性 `CONTAINS`，规模大时难以利用普通索引。建议增加：

- Neo4j Full-text Index；
- Entity exact / alias lookup；
- 关系查询从“文本 contains”与“图遍历”分开；
- 对常用 neighborhood 加缓存。

### P1：列表 API 分页

目前 Cards / Claims / Sources 部分接口仍返回全量列表。正式大数据量环境建议：

```text
page / pageSize
cursor pagination
fields projection
```

避免前端一次加载数万 Knowledge Card。

### P1：知识发布边界

正式检索需要进一步强化：

```text
Candidate Index
      ≠
Effective Index
```

或至少在所有检索通道严格执行：

```text
APPROVED / EFFECTIVE only
```

Graph / Vector / BM25 状态同步需要成为统一 Publish/Unpublish 事务或 Outbox Event。

### P2：事件驱动知识流水线

当文档较大、模型较慢时，不应让上传 HTTP 请求同步等待完整：

```text
Upload
 ↓
Persist Source
 ↓
Outbox / Queue
 ↓
Parse Worker
 ↓
Extract Worker
 ↓
Adjudication Worker
 ↓
Index Worker
 ↓
READY
```

可选技术：Redis Stream / RabbitMQ / Kafka。

V1.1 保持同步链路是合理的 RC 选择，但 V1.2+ 应把长任务异步化。

---

## 8. 推荐的性能目标

进入真实压测阶段后建议定义：

| 指标 | V1.1 RC 建议目标 |
|---|---:|
| Backend 普通元数据 API P95 | < 300 ms |
| Hybrid Retrieval（不含 LLM）P95 | < 500 ms |
| BGE Rerank P95 | < 1.5 s（按 GPU 调整） |
| Query E2E P95 | 由 LLM 基线 + 2 s 内检索开销 |
| 文档 1000 Chunk DB 持久化 | 批量 SQL，无 3N round-trip |
| DB Connection Pool 等待 | 接近 0，禁止长 AI Transaction |
| Conflict detection | 只比较候选 bucket / new focus |
| Retrieval no-result rate | 可观测 |
| Query Trace completeness | 100% |

---

## 9. 结论

本轮最有价值的不是“代码变漂亮”，而是把几个真实生产风险移出了主链：

```text
单体 KnowledgeService
→ 分层应用服务

长事务 + AI 网络调用
→ 外部计算 + 短事务

每 Query 重载模型/Driver
→ Provider 单例

每 Query 读取 JSON / 全量分词
→ Snapshot + Lexical Cache

全 Claim O(N²) 冲突
→ DB 候选 + Bucket + Focus

Chunk 3N SQL 往返
→ Batch SQL

FastAPI event loop 执行同步重任务
→ thread offload + concurrency guard

前端页签切换 7 API
→ 按页面按需加载
```

V1.1 RC 因此更适合进入“真实环境压测 + 全栈联调 + 修复 RC 缺陷”阶段，而不是继续增加功能。
