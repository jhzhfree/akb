# V1.1 Release Candidate Checklist

## Automated local gate

```bash
./scripts/check-v11.sh
```

The gate validates Python syntax, AI pipeline, governance, adjudication, API contract,
Flyway/Mapper consistency, migration ordering, Java 8 compatibility and RC file completeness.

## Backend unit tests

```bash
cd backend
mvn -B test
```

## MySQL/Flyway integration test

Requires Docker because Testcontainers starts MySQL 8.0.36.

```bash
cd backend
mvn -B -Pintegration-test verify
```

This test verifies that V1/V2 Flyway migrations execute on real MySQL and that the
query trace columns required by the V1.1 API contract exist.

## Frontend build

```bash
cd frontend
npm install
npm run build
```

## Compose validation and full-stack boot

```bash
cp .env.example .env
docker compose config --quiet
docker compose up -d --build
./scripts/wait-ready.sh
```

## Mandatory end-to-end RC scenario

1. Upload a real PDF/DOCX/Markdown document.
2. Verify Source -> SourceVersion -> Document -> ContentUnit -> Evidence records.
3. Verify Claim/Card extraction and evidence links.
4. Run intelligent adjudication and verify assessment record.
5. Query the knowledge base and verify citation/source/page.
6. Fetch `/api/knowledge/trace/{queryId}` and verify rewrite/router/retrieval/fusion/rerank/context/model/latency fields.
7. Verify the same `X-Trace-Id` appears at HTTP response, backend logs and AI service logs.
8. Run a conflict case and verify it cannot auto-pass human-review policy.

## RC exit criteria

- `./scripts/check-v11.sh` passes.
- `mvn test` passes on Java 8.
- `mvn -Pintegration-test verify` passes with Docker/Testcontainers.
- Frontend production build passes.
- Docker Compose full stack becomes healthy.
- Real Ollama/BGE/Neo4j integration has been exercised at least once.
- No P0/P1 contract, migration, traceability or adjudication defects remain.

---

## 附录：RC 收口（原 `docs/release/RC-CHECKLIST.md`）

> 来源 `RC-FINAL.md`（历史路径，已并入本文档；原文仅在 git 历史中保留）。

## V1.1 Release Candidate Finalization

本轮只做 RC 收口，不扩展产品范围。

### 已落地
- 核心写接口 DTO 化与 Bean Validation；Query 返回类型化门面。
- Springdoc OpenAPI：`/v3/api-docs`、`/swagger-ui.html`。
- `KnowledgeProperties` 配置校验、AI 超时、可选启动 fail-fast。
- `StartupDiagnostics` 数据库连接和 AI capabilities 诊断。
- Flyway V3 性能索引与索引契约检查。
- 黑盒 E2E：上传→抽取/索引→查询→QueryTrace。
- 生产 Compose 模板仅公开前端端口。

### RC 发布前必须实际执行
1. `mvn -B clean verify -Pintegration-test`。
2. `npm ci && npm run build`。
3. `docker compose up -d --build`。
4. `scripts/diagnose.sh`。
5. `python scripts/e2e-test.py`。
6. 真实 Ollama/BGE/Neo4j 模式分别验证一次并核对 QueryTrace。
