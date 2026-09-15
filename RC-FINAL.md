# V1.1 Release Candidate Finalization

本轮只做 RC 收口，不扩展产品范围。

## 已落地
- 核心写接口 DTO 化与 Bean Validation；Query 返回类型化门面。
- Springdoc OpenAPI：`/v3/api-docs`、`/swagger-ui.html`。
- `KnowledgeProperties` 配置校验、AI 超时、可选启动 fail-fast。
- `StartupDiagnostics` 数据库连接和 AI capabilities 诊断。
- Flyway V3 性能索引与索引契约检查。
- 黑盒 E2E：上传→抽取/索引→查询→QueryTrace。
- 生产 Compose 模板仅公开前端端口。

## RC 发布前必须实际执行
1. `mvn -B clean verify -Pintegration-test`。
2. `npm ci && npm run build`。
3. `docker compose up -d --build`。
4. `scripts/diagnose.sh`。
5. `python scripts/e2e-test.py`。
6. 真实 Ollama/BGE/Neo4j 模式分别验证一次并核对 QueryTrace。
