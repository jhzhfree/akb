# V1.1 工程化收口

本阶段不扩展产品域能力，目标是把 V1.1 收敛为可构建、可配置、可迁移、可观测、可测试、可一键启动的工程版本。

## 收口内容

- MySQL schema 改由 Flyway 管理：`db/migration/V1__baseline.sql`，应用启动自动校验/迁移。
- Spring Boot 配置全部支持环境变量覆盖；Actuator 提供 `/actuator/health`、`info`、`metrics`。
- Java 增加统一异常处理和 `X-Trace-Id` 请求链路标识。
- AI Service 增加请求 Trace、统一 500 响应、`/health` 与 `/ready`。
- Backend / AI / Frontend 均提供 Dockerfile；Compose 包含 MySQL、Redis、Neo4j、AI、Backend、Frontend 及健康检查。
- `.env.example` 集中定义数据库、Redis、Neo4j、Ollama、Embedding、Reranker 配置。
- `Makefile` 提供 `make check/up/down/logs/ps/clean`。
- `scripts/wait-ready.sh` 用于一键启动后的端到端就绪检查。

## 本地启动

```bash
cp .env.example .env
docker compose config
docker compose up -d --build
./scripts/wait-ready.sh
```

浏览器入口为 `http://localhost:8088`；Backend 健康检查为 `http://localhost:8080/actuator/health`；AI 健康检查为 `http://localhost:12000/health`。

## Ollama 联调

宿主机运行 Ollama 时：

```env
LLM_PROVIDER=ollama
LLM_BASE_URL=http://host.docker.internal:11434
LLM_MODEL=qwen2.5:7b
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text
```

## BGE Reranker

基础 AI 镜像默认不安装 Torch/SentenceTransformers，避免镜像膨胀。设置 `INSTALL_ML=true` 后构建会安装 `requirements-ml.txt`；再设置 `RERANKER_ENABLED=true` 即可启用 `BAAI/bge-reranker-v2-m3`。模型不可用时查询仍使用轻量 fallback。

## 发布前 Gate

1. `./scripts/check-v11.sh`
2. `mvn test && mvn package`
3. `npm install && npm run build`
4. `docker compose config`
5. `docker compose up -d --build`
6. `./scripts/wait-ready.sh`
7. 上传一份测试文档，验证 Parse → Extract → Index → Query → Citation → Trace。
8. 启用 Ollama/Neo4j 后验证 capability 与真实调用。

当前仍是 V1.1 开发候选版；只有上述 Gate 全部通过后再形成 Git tag/提交主线。
