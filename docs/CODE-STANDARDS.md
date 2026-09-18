# 代码规范（V1.1）

本文是本仓库的强制研发规范。目标不是追求形式统一，而是让代码在 **Java 8 + Spring Boot 2.7 / Python 3.10 + FastAPI / Vue 3** 的长期演进中保持可读、可测、可定位、可回滚。

## 1. 总原则

1. **MySQL 是权威状态，检索引擎是可重建视图。** 不允许把 Elasticsearch/Milvus/Neo4j 的状态反向当成业务真相。
2. **慢调用不占数据库事务。** 文档解析、LLM、Embedding、Reranker、Elasticsearch、Milvus、Neo4j 调用必须尽量在事务外执行。
3. **状态变化必须可追踪。** 影响正式知识检索的 Card/Claim 状态变化必须生成 Outbox 事件，并携带 aggregate version。
4. **权限过滤前置。** ACL 必须在召回/Top-K 前执行，不允许先召回敏感内容再依赖 LLM 或 UI 隐藏。
5. **失败要显式。** 不吞异常，不用空 `except`，fallback 必须留下 provider/trace/metric 证据。
6. **注释解释 Why，不复述 What。** `i++` 不需要注释；“为什么必须用 high-watermark 拒绝发布”需要注释。

## 2. 文件与行宽

自动 Gate 当前约束：

| 类型 | 建议/强制最大行宽 |
|---|---:|
| Java | 160 |
| Python | 140 |
| JavaScript | 160 |
| Vue | 180 |

超过限制时优先：拆参数、拆表达式、提取常量/方法、格式化模板，而不是关闭检查。

禁止：
- Tab 缩进；
- 行尾空格；
- Python 一行多语句；
- Java 9+ API（项目目标 Java 8）；
- 大量一行 getter/setter；
- 在业务代码中出现 `Map.of` / `List.of` / `var`。

## 3. Java 规范

### 3.1 分层

`controller -> application service -> mapper / AI client / outbox`

Controller 只负责：
- transport DTO；
- HTTP 参数；
- RBAC/trace 已由 filter 处理；
- 调用服务并包装响应。

Controller 不应该：
- 写 SQL；
- 拼复杂业务 Map；
- 直接操作 Elasticsearch/Milvus；
- 持有事务。

### 3.2 Service

一个 Service 应对应一个明确职责：
- `DocumentIngestionService`：上传/解析/落库；
- `KnowledgeExtractionService`：知识抽取；
- `KnowledgeGovernanceService`：裁决/冲突/状态；
- `KnowledgeQueryService`：查询与 Trace；
- `IndexOutboxService/Worker`：最终一致索引；
- `IndexSchemaService`：蓝绿重建与发布。

当一个类超过约 300 行时必须评估拆分，但不要为了行数机械拆成无语义的小类。

### 3.3 事务边界

推荐模式：

```text
外部计算（parse/LLM）
    ↓
短 DB transaction
    ↓ commit
Outbox / 外部调用
```

不要：

```java
@Transactional
public void upload(...) {
    aiClient.parse(...);     // 可能几十秒
    mapper.insert(...);
    milvusCall(...);         // 再等待网络
}
```

### 3.4 DTO / Map

新公开 API 优先 DTO。`Map<String,Object>` 只允许：
- 对接动态 AI JSON；
- MyBatis 投影；
- 兼容历史接口。

新增稳定业务接口时不要继续扩大 Map 使用面。

### 3.5 Javadoc

所有顶级 `public class/interface/enum` 必须有类级 Javadoc，说明：
- 它负责什么；
- 它不负责什么；
- 重要一致性/事务边界（如适用）。

核心公共业务方法应说明“副作用”和“关键约束”，不要求给所有 getter/setter 写 Javadoc。

## 4. Python 规范

### 4.1 模块和公共符号

`ai-service/app` 下模块应有 module docstring。公共顶级 class/function 应有 docstring。

### 4.2 Provider 生命周期

重对象必须进程复用：
- Embedding Provider；
- Reranker；
- Graph Provider；
- Elasticsearch/Milvus Provider；
- LLM Provider。

使用 `@lru_cache(maxsize=1)` 或应用生命周期容器，禁止每请求加载 BGE/Neo4j Driver/HTTP Client。

### 4.3 async 与阻塞代码

FastAPI event loop 里禁止直接运行长时间同步代码。当前约定：
- 文档解析 -> `asyncio.to_thread`；
- BGE 推理 -> `to_thread` + semaphore；
- 同步 Neo4j -> `to_thread`。

### 4.4 Retrieval Provider

Provider 返回统一 Candidate：

```python
{
    "id": "...",
    "channel": "bm25|vector|card|claim|graph",
    "title": "...",
    "content": "...",
    "score": 0.82,
    "metadata": {
        "retrieval_provider": "elasticsearch|milvus|neo4j|local"
    }
}
```

不要让 QueryEngine 直接理解 Elasticsearch `_source` 或 Milvus point 格式。

### 4.5 异常策略

`RETRIEVAL_EXTERNAL_REQUIRED=false` 时允许 fallback，但必须返回/记录 provider；true 时必须抛出，让 Outbox/请求失败并由上层补偿。

禁止：

```python
try:
    ...
except Exception:
    pass
```

## 5. Vue / JavaScript 规范

1. API URL 集中放在 `src/api/knowledge.ts`，组件不散落 axios 调用。
2. 页面只加载当前 Tab 所需数据，避免切页 `Promise.all` 全量刷新。
3. 多面板互不依赖时用 `Promise.allSettled`，避免一个失败清空整个工作台。
4. `App.vue` 当前是 V1.1 单页实现；若继续增长，下一步应拆 `views/` 与 `components/`，而不是继续堆到一个文件。
5. UI 不承担权限安全，后端/检索层必须做真实 ACL。

## 6. SQL / Flyway / MyBatis

- Flyway migration **只追加，不修改已发布版本**。
- 命名：`V<整数>__<description>.sql`。
- 大表新索引必须考虑线上建索引成本。
- Outbox 领取必须保持可并发消费且同 aggregate 有序。
- Mapper XML statement ID 必须存在对应 Java 方法，Gate 会检查。
- SQL 里影响检索一致性的状态条件应写出注释。

## 7. Outbox 规范

事件必须包含：
- `event_id`：唯一事件；
- `idempotency_key`：业务操作幂等；
- `aggregate_key`：同对象排序维度；
- `object_version`：单调版本；
- `event_type`；
- `target_path`；
- payload 或轻量引用。

DEAD 事件默认阻塞后续同 aggregate 版本，必须显式 replay/skip。

## 8. 注释规范示例

好：

```java
// Parse outside the DB transaction because PDF parsing can be CPU-heavy and slow.
Map<String, Object> parsed = aiClient.parse(file);
```

不好：

```java
// 调用 parse
Map<String, Object> parsed = aiClient.parse(file);
```

好：

```python
# Missing/null/empty permission tags represent public knowledge.
should.append({"is_empty": {"key": "permission_tags"}})
```

## 9. 提交前最小检查

```bash
./scripts/check-v11.sh
```

至少确认：
- Python compile；
- AI smoke tests；
- dual retrieval / outbox / blue-green contracts；
- Flyway / Mapper contracts；
- Java 8 forbidden API；
- code quality；
- developer docs contract；
- YAML parse。

Maven/Docker 可用的研发机还必须执行：

```bash
cd backend
mvn test
mvn -Pintegration-test verify

cd ../frontend
npm ci
npm run build
```
