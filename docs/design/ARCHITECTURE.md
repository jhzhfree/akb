# V1.1 软件架构

## 1. 分层原则

```text
Frontend
  ↓ REST/OpenAPI
Backend Application Layer
  ↓
Domain / Governance State
  ↓
MySQL (authoritative state)

Backend
  ↓ AI Contract
AI Service
  ├─ Parser
  ├─ Extractor
  ├─ Adjudication Engine
  ├─ Hybrid Retriever
  ├─ Reranker
  ├─ Embedding Provider
  └─ Graph Provider
```

核心边界：

> Java 管理业务状态、治理流程和权威数据；Python 管理模型推理、检索和算法；MySQL 管理权威事实；检索索引是可重建视图。

## 2. Backend Application Services

```text
KnowledgeController
       ↓
KnowledgeService (compatibility facade)
       ├─ DocumentIngestionService
       ├─ KnowledgeExtractionService
       ├─ KnowledgeQueryService
       ├─ KnowledgeGovernanceService
       └─ KnowledgeReadService
```

事务原则：

```text
禁止把 LLM / Embedding / Neo4j / Parser 网络或重计算调用包在长数据库事务里。
```

采用：

```text
External Compute
→ Short DB Transaction
→ External Compute
→ Short DB Transaction
```

## 3. AI Retrieval Layer

```text
Query
 ↓
Router
 ↓
Multi Recall
 ├─ BM25
 ├─ Vector
 ├─ Card
 ├─ Claim
 └─ Graph
 ↓
Weighted RRF
 ↓
Reranker
 ↓
Context Assembly
 ↓
LLM
```

Provider 使用进程级复用：

- LlmProvider
- EmbeddingProvider
- RerankProvider
- GraphProvider

V1.1 JSON IndexStore 是本地 fallback。生产规模扩大后应替换为：

- VectorStoreProvider
- FullTextSearchProvider

候选实现：Milvus / Milvus / pgvector / Elasticsearch / Elasticsearch。

## 4. Knowledge Governance Boundary

```text
Source
 → Evidence
 → Claim / KnowledgeCard
 → Adjudication
 → APPROVED / EFFECTIVE
 → Retrieval
```

目标状态：候选知识与正式有效知识拥有清晰 Publish Boundary。

所有检索通道必须满足：

1. Knowledge Base boundary；
2. ACL / permission boundary；
3. lifecycle status boundary；
4. evidence traceability。

## 5. 性能原则

- 禁止每请求加载 BGE 模型；
- 禁止每请求创建 Neo4j Driver；
- 禁止每请求完整解析本地 JSON 索引；
- 禁止大型列表全量排序后才取 Top-K；
- 禁止 O(N²) 全知识冲突比较；
- 禁止每 Chunk 多次 DB round-trip；
- 同步 CPU/阻塞 I/O 不直接运行在 FastAPI event loop；
- 大列表 API 必须向分页演进；
- 生产向量检索必须向 ANN 索引演进。

详细实现与后续路线见 `docs/design/CODE-ARCH-PERFORMANCE-OPTIMIZATION.md`。

## Transactional Outbox / Index Worker (V1.1)

检索视图不参与业务事务同步多写。业务表与 `t_index_outbox` 在同一个 MySQL 事务提交，独立 Worker 使用 `FOR UPDATE SKIP LOCKED` 异步投递 Elasticsearch/Milvus/Neo4j。详见 `docs/design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md`。
