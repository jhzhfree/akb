# V1.1 集成测试、数据库一致性与 API 契约收口

## 1. 本轮目标

本轮不扩展产品功能，重点解决 Release Candidate 前的三类工程风险：

1. Java 与 Python 接口字段漂移；
2. Flyway / MyBatis / MySQL 表结构不一致；
3. Query Trace 无法按阶段分析和跨服务回放。

## 2. API Contract

共享契约：`contracts/ai-api-v1.1.json`

核心内部接口：

- `POST /ai/query`
- `POST /ai/extract`
- `POST /ai/adjudicate`
- `GET /ai/capabilities`

Python `test_api_contract.py` 会校验契约要求字段、请求校验和 `X-Trace-Id` 透传。
Java `ApiContractTest` 校验契约版本、Trace Header、核心 endpoint 和状态/错误码稳定性。

## 3. 统一错误码

Backend API Response 保持 `success/message/data` 兼容，同时新增：

- `code`
- `traceId`

关键错误码：

- `KB-400-001` INVALID_ARGUMENT
- `KB-400-002` VALIDATION_FAILED
- `KB-502-001` AI_SERVICE_ERROR
- `KB-500-002` DATABASE_ERROR
- `KB-500-001` INTERNAL_ERROR

AI Service 内部异常使用 `AI-*` 错误码，并返回同一个 Trace ID。

## 4. Query Trace V2

Flyway `V2__query_trace_contract.sql` 将原先单一 `trace_json` 扩展成结构字段：

- trace_id
- rewritten_queries_json
- router_decision_json
- retrieval_json
- fusion_json
- rerank_json
- selected_context_json
- citations_json
- model_name
- latency_ms
- token_usage_json
- error_code

同时保留完整 `trace_json` 作为原始回放快照。

AI Query Engine 现在输出各阶段时延：

- retrievalLatencyMs
- fusionLatencyMs
- rerankLatencyMs
- llmLatencyMs
- totalLatencyMs

以及 recallCounts / candidateCount / citationCount。

## 5. 数据库一致性

静态 Gate：

```bash
python scripts/check-db-contract.py
python scripts/check-mapper-contract.py
```

检查内容：

- MyBatis 引用表是否全部存在于 Flyway Migration；
- 必需核心表是否存在；
- Query Trace V2 字段是否完整；
- Mapper Java 方法与 XML statement 是否双向一致；
- Migration 命名和版本顺序是否合法。

V1 baseline 已去除 `CREATE DATABASE/USE`，数据库生命周期交给部署环境管理，Flyway 只负责 schema migration。

## 6. Testcontainers 集成测试

`DatabaseMigrationIT` 使用 MySQL 8.0.36 Testcontainers：

- 在真实 MySQL 上执行 V1 + V2 Flyway；
- 校验核心表与 Query Trace 字段；
- 校验 `flyway_schema_history`；
- 使用真实 `KnowledgeMapper.insertTrace/getTrace` 做结构化 Trace 写读测试。

执行：

```bash
cd backend
mvn -B -Pintegration-test verify
```

需要 Docker。

## 7. Trace 传播

Backend `RestTemplate` interceptor 会自动把 MDC 中的 `X-Trace-Id` 传给 AI Service。

链路：

```text
HTTP Request
  -> TraceIdFilter
  -> MDC traceId
  -> KnowledgeService
  -> RestTemplate Interceptor
  -> AI Service X-Trace-Id
  -> AI request log
  -> Query Trace DB
  -> HTTP Response X-Trace-Id
```

## 8. 自动 Gate

```bash
./scripts/check-v11.sh
```

当前包含 10 项：

1. Python syntax
2. AI pipeline
3. Governance/Reranker/Graph fallback
4. Intelligent adjudication
5. AI API contract
6. Database/Flyway contract
7. MyBatis mapper contract
8. Flyway ordering/XML structure
9. Java 8 forbidden API scan
10. RC engineering files

## 9. 当前环境限制

当前执行环境没有 Maven 与 Docker，因此不能在本会话中真实执行：

- `mvn test`
- `mvn -Pintegration-test verify`
- Testcontainers MySQL
- Docker Compose full-stack

前端 `npm install` 在当前网络环境再次超时，因此生产 Vite build 仍是发布前 Gate。

这些项目已经写入 `docs/release/RC-CHECKLIST.md`，不得在正式 V1.1 Release Candidate 发布前跳过。
