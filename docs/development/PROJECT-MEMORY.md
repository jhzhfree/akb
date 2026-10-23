# 工程记忆

本文记录会影响后续开发、构建和验证的工程约定。新增约定时同步更新本文，避免只留在聊天记录中。

## 数据库迁移验证必须使用 Docker

- 后端数据库为 MySQL 8；Flyway 迁移由应用启动时执行。
- `DatabaseMigrationIT` 使用 Testcontainers 启动 `mysql:8.0.36`，所以本地或 CI 必须安装并启动 Docker Engine（或兼容的 Docker API），且当前用户可访问 Docker daemon。
- 只运行 `mvn test` 不会执行 `*IT` 集成测试，不能据此宣称数据库迁移已通过。
- 修改 `backend/src/main/resources/db/migration/` 后，需在 Docker 可用的环境执行：

  ```bash
  docker info
  cd backend
  mvn -Pintegration-test -Dit.test=DatabaseMigrationIT verify
  ```

- 新迁移涉及的表、列和索引应在 `DatabaseMigrationIT` 中增加断言。集成测试失败时先保留失败信息，不得跳过迁移验证并标记为已通过。
- 当前开发容器若没有 Docker daemon，允许先运行 `mvn test`、前端检查及静态迁移审查；交付记录必须明确说明 Testcontainers 未执行，并将迁移验证留给 Docker 可用的 CI/开发机。
- 运行本地迁移集成测试前可先用 `docker pull mysql:8.0.36` 预取镜像；测试需能访问 Docker daemon，并允许 Testcontainers 启动临时 MySQL。

## Java 与提交约定

- 后端代码维持 Java 8 语言兼容；开发环境可使用更高版本 JDK。
- GitHub 工程提交使用已连接的 GitHub 账号/连接方式，目标仓库 `jhzhfree/akb`、分支 `feature/octop-mcp-integration`。
- `backend/demo-seed/mysql/.knowledge_os.sql.4dryTx` 与 `deploy/seed/enterprise-kb-demo/.knowledge_data.sql.IEyso8` 是本地未跟踪演示数据，保留但不得纳入提交。

## 外部委托身份约定

- OIDC 委托身份功能默认关闭；开启后调用必须同时携带 Agent `X-API-Key` 与员工 `Authorization: Bearer <JWT>`。
- JWT 由配置的 JWKS 验签，并校验配置 issuer、audience、`exp`、`nbf` 和非空 `sub`；不得信任 `X-User-Id` 之类的自报身份头。
- 外部 IdP `(issuer, subject)` 必须由管理员显式映射至 ACTIVE `USER` Principal。Agent 和员工均需有目标知识库 grant，标签范围在 Key、Agent grant、员工 grant 之间取交集。
- 委托模式拒绝独立 Embed Token。启用前必须验证 IdP 的 issuer/JWKS/audience 配置以及 Octop/MCP 客户端是否能并行发送两种凭证。


## 知识质量闭环修复

参见 [KNOWLEDGE-QUALITY-CLOSURE.md](KNOWLEDGE-QUALITY-CLOSURE.md)：E3 幂等键、资源级重抽取授权、入库计数及阶段摘要。MySQL 迁移和 Outbox 状态集成验证需要 Docker；本地未具备 Docker 时不得报告集成测试通过。
