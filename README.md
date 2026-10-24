# AI 原生企业知识库平台 1.1.0

GitHub 仓库：`https://github.com/jhzhfree/akb`

企业知识全生命周期平台：以 MySQL 保存权威知识状态，以 Elasticsearch、Milvus、Neo4j 提供可重建检索视图，并通过 Transactional Outbox 保证索引最终一致。

## 技术栈

- Vue 3 + Vite + Element Plus
- Spring Boot 2.7.18 + Java 8 + MyBatis + Flyway + MySQL
- Python 3.10 + FastAPI
- Elasticsearch + Milvus + Neo4j
- Redis
- Prometheus + Grafana
- 可选 Ollama / BGE Reranker

## 核心能力

- 数据源/文档/ContentUnit/Evidence 管理
- Knowledge Card / Claim 知识抽取与治理
- AI + 规则 + 人工裁决
- 冲突检测与生命周期状态
- Elasticsearch BM25 + Milvus Vector + Neo4j Graph 多路检索
- Weighted RRF + Reranker + Context Assembly
- Query Trace / Citation / Provider observability
- Transactional Outbox + Index Worker + Retry/Dead Letter/Replay
- 索引蓝绿发布、MySQL 权威快照、Checksum/High-Watermark 校验
- Prometheus/Grafana SLO
- 运维 RBAC

## 快速启动

```bash
cp .env.example .env
./scripts/check-v11.sh
make up
./scripts/wait-ready.sh
```

默认入口：

- Frontend: `http://localhost:8088`
- Backend: `http://localhost:8080`
- AI Service: `http://localhost:12000`
- Grafana: `http://localhost:3000`

## 研发文档

第一次参与开发建议按以下顺序阅读：

1. [研发工程师详细开发手册](docs/development/DEVELOPER-GUIDE.md)
2. [代码架构导读](docs/development/ARCHITECTURE-CODE-WALKTHROUGH.md)
3. [代码规范](docs/development/CODE-STANDARDS.md)
4. [调试与测试手册](docs/development/DEBUGGING-TESTING-GUIDE.md)
5. [常见改动 Playbook](docs/development/CHANGE-PLAYBOOK.md)

发版与部署：

- [开发与部署方案](docs/operations/BUILD-AND-DEPLOY.md)（改了什么重做什么、数据库迁移、制作与推送镜像、目标机拉取与部署、回滚）
- [Docker 部署手册](docs/operations/DEPLOYMENT-DOCKER.md)（Ubuntu 24.04 全新裸机、`.env` 全量说明、验收与运维）
- [裸金属部署手册](docs/operations/DEPLOYMENT-BAREMETAL.md)

发版与交付说明（版本 1.1.0）：

- [V1.1 功能发布说明](docs/release/V1.1-RELEASE-NOTES.md)（做什么、验收标准、约束、下个版本方向）
- [V1.1 发版清单与交付说明](docs/release/V1.1-RELEASE-MANIFEST.md)（功能清单与实现程度/限制、部署方式、升级包类型、交付形态）
- [V1.1 功能清单与缺口排期](docs/release/V1.1-FEATURE-SCOPE.md)（首发版本规划与工程缺口）

集成指南：

- [Octop 知识检索集成](docs/api/OCTOP-INTEGRATION.md)（MCP 地址、API Key 权限、Agent 绑定与联调检查）

架构/历史专项说明：

- `docs/design/ARCHITECTURE.md`
- `docs/design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md`
- `docs/design/DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md`
- `docs/operations/PERFORMANCE-CAPACITY-PLAN-V1.1.md`

## 设计边界

```text
Java Backend
  = 业务状态 / 事务 / 权限 / 治理 / Outbox / 发布

Python AI Service
  = 解析 / 抽取 / Embedding / Retrieval / Rerank / LLM / Graph算法

MySQL
  = authoritative state

Elasticsearch / Milvus / Neo4j / Local JSON
  = rebuildable retrieval views
```

**Knowledge cannot exist without evidence.** 召回状态白名单由 `RETRIEVAL_STATUS_POLICY` 控制：默认 `standard`，仅召回 `APPROVED`、`EFFECTIVE`；`permissive` 可显式启用并额外召回 `DRAFT`、`UNCERTAIN`；`custom` 可通过 `RETRIEVAL_ALLOWED_STATUSES` 指定知识状态。正式问答建议保持 `standard`，预览草稿时应显式配置并控制访问范围。

## 开发检查

```bash
./scripts/check-v11.sh
```

该 Gate 覆盖 Python smoke、检索/Outbox/蓝绿契约、Flyway/MyBatis、Java 8 兼容、代码可读性、研发文档、YAML/交付文件等。

Docker/Maven 环境还应执行：

```bash
cd backend
mvn test
mvn -Pintegration-test verify

cd ../frontend
npm ci
npm run build
```
